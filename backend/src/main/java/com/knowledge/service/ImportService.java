package com.knowledge.service;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * 文档导入编排服务
 * 统筹 CSV 解析 → 文件解析 → MinIO 存储 → Embedding → ES 索引 全流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final CsvImportService csvImportService;
    private final DocumentParser documentParser;
    private final MinioService minioService;
    private final ElasticsearchService esService;
    private final AIClient aiClient;
    private final GraphBuildService graphBuildService;
    private final ImportTaskRepository taskRepo;
    private final DocumentRepository docRepo;
    private final ItemRepository itemRepo;

    @Value("${document.temp-dir}")
    private String tempDir;

    @Value("${document.chunk-size}")
    private int chunkSize;

    @Value("${document.chunk-overlap}")
    private int chunkOverlap;

    /**
     * 导入压缩包（zip/rar/7z）
     * @param file      上传的压缩包
     * @param targetDept 目标部门 或 "public"
     */
    @Transactional
    public String importArchive(MultipartFile file, String targetDept) throws Exception {
        String batchId = UUID.randomUUID().toString().replace("-", "");
        boolean isPublic = "public".equals(targetDept);

        // 1. 确保 Neo4j 约束就绪
        graphBuildService.ensureConstraints();

        // 2. 创建导入任务
        ImportTask task = ImportTask.builder()
                .batchId(batchId)
                .archiveName(file.getOriginalFilename())
                .targetDept(targetDept)
                .status("pending")
                .build();
        taskRepo.save(task);

        // 2. 解压至临时目录
        Path extractDir = Path.of(tempDir, batchId);
        Files.createDirectories(extractDir);
        extractArchive(file.getInputStream(), extractDir);
        task.setStatus("metadata_parsed");

        // 3. 分类文件
        Map<String, Path> csvFiles = new HashMap<>();
        List<Path> docFiles = new ArrayList<>();
        try (var stream = Files.walk(extractDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString().toLowerCase();
                if (name.startsWith("item") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("item", p);
                } else if (name.startsWith("file_index") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("file_index", p);
                } else if (name.startsWith("item_with_opinions") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("opinions", p);
                } else if (name.startsWith("user") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("user", p);
                } else if (name.endsWith(".doc") || name.endsWith(".docx")
                        || name.endsWith(".pdf") || name.endsWith(".ofd")) {
                    docFiles.add(p);
                }
            }
        }

        // 4. 解析元数据 CSV（先于文档解析）
        if (csvFiles.containsKey("item")) {
            csvImportService.importItemCsv(csvFiles.get("item"), targetDept, isPublic, batchId);
        }
        if (csvFiles.containsKey("file_index")) {
            csvImportService.importFileIndexCsv(csvFiles.get("file_index"), targetDept, isPublic, batchId);
        }
        if (csvFiles.containsKey("opinions")) {
            csvImportService.importOpinionsCsv(csvFiles.get("opinions"), batchId);
        }
        if (csvFiles.containsKey("user")) {
            csvImportService.importUserCsv(csvFiles.get("user"));
        }

        // 5. 解析实际文档
        task.setStatus("parsing");
        task.setTotalFiles(docFiles.size());
        taskRepo.save(task);

        // 收集 (fileId → 解析文本) 映射用于后续交叉引用检测
        Map<String, String> docTextMap = new LinkedHashMap<>();

        for (int i = 0; i < docFiles.size(); i++) {
            Path docPath = docFiles.get(i);
            try {
                String plainText = processDocument(docPath, targetDept, isPublic, batchId);
                // 从 DB 获取刚保存的文档 fileId
                String fileName = docPath.getFileName().toString();
                Document matched = docRepo.findAll().stream()
                        .filter(d -> "matched".equals(d.getStatus())
                                && d.getFileName() != null
                                && (d.getFileName().contains(extractBaseName(fileName))
                                    || fileName.contains(extractBaseName(d.getFileName()))))
                        .findFirst().orElse(null);
                if (matched != null && plainText != null && !plainText.isEmpty()) {
                    docTextMap.put(matched.getFileId(), plainText);
                }
                task.setProcessedFiles(i + 1);
                taskRepo.save(task);
            } catch (Exception e) {
                log.error("文档解析失败: {}", docPath.getFileName(), e);
                appendError(task, docPath.getFileName() + ": " + e.getMessage());
            }
        }

        // 6. 交叉引用检测 —— 基于文档内容和分类编号
        task.setStatus("complete");
        task.setCompletedAt(java.time.LocalDateTime.now());
        taskRepo.save(task);

        try {
            // 构建用于引用检测的 DocRef 列表（合并文本内容 + 事项分类编号）
            List<GraphBuildService.DocRef> docRefs = new ArrayList<>();
            for (Document d : docRepo.findAll()) {
                if (d.getTextLength() == null || d.getTextLength() == 0) continue;
                String content = docTextMap.getOrDefault(d.getFileId(), "");
                String categoryNo = d.getItem() != null ? d.getItem().getCategoryNo() : null;
                docRefs.add(new GraphBuildService.DocRef(d.getFileId(), content, categoryNo));
            }
            if (!docRefs.isEmpty()) {
                graphBuildService.detectCrossReferences(docRefs);
            }
        } catch (Exception e) {
            log.warn("交叉引用检测失败: {}", e.getMessage());
        }

        // 7. 清理临时文件
        deleteRecursively(extractDir);

        log.info("导入完成: batchId={}, 文件数={}", batchId, docFiles.size());
        return batchId;
    }

    /** 处理单个文档：解析 → 存储 → Embedding → 索引，返回解析后的纯文本 */
    private String processDocument(Path docPath, String deptName, boolean isPublic,
                                  String batchId) throws IOException {
        String fileName = docPath.getFileName().toString();
        log.debug("处理文档: {}", fileName);

        // 1. 解析文本
        String plainText = documentParser.parse(docPath);
        String markdown = documentParser.toMarkdown(plainText);

        // 2. 在 document 表中匹配（按文件名）
        Optional<Document> matchOpt = docRepo.findAll().stream()
                .filter(d -> "expected".equals(d.getStatus()))
                .filter(d -> fileName.contains(extractBaseName(d.getFileName())) ||
                             d.getFileName().contains(extractBaseName(fileName)))
                .findFirst();

        Document doc;
        if (matchOpt.isPresent()) {
            doc = matchOpt.get();
            doc.setStatus("matched");
            // 更新 Neo4j 文档节点状态
            graphBuildService.createDocument(doc.getFileId(), fileName, "matched");
        } else {
            // 未匹配到 CSV 记录，创建 orphan
            String newFileId = UUID.randomUUID().toString().replace("-", "");
            doc = Document.builder()
                    .fileId(newFileId)
                    .fileName(fileName)
                    .status("orphan")
                    .deptName(deptName)
                    .isPublic(isPublic)
                    .importBatch(batchId)
                    .build();
            graphBuildService.createDocument(newFileId, fileName, "orphan");
        }

        // 3. 上传原始文件 → MinIO
        String objectPath = deptName + "/" + fileName;
        try (InputStream is = Files.newInputStream(docPath)) {
            String path = minioService.uploadDocument(objectPath, is,
                    Files.size(docPath), detectMimeType(fileName));
            doc.setMinioPath(path);
        }

        // 4. 上传 Markdown → MinIO
        String mdPath = minioService.uploadMarkdown(doc.getFileId(), markdown);
        doc.setMinioMdPath(mdPath);
        doc.setTextLength((long) plainText.length());
        doc.setParsedAt(java.time.LocalDateTime.now());
        docRepo.save(doc);

        // 5. 文本分块 → Embedding → ES 索引
        List<String> chunks = splitText(plainText);
        List<ElasticsearchService.DocIndex> indexDocs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] vector = aiClient.embed(chunk);

            indexDocs.add(ElasticsearchService.DocIndex.builder()
                    .docId(doc.getFileId())
                    .fileName(doc.getFileName())
                    .deptName(deptName)
                    .isPublic(isPublic)
                    .itemTitle(doc.getItem() != null ? doc.getItem().getTitle() : null)
                    .itemCategory(doc.getItem() != null ? doc.getItem().getCategory() : null)
                    .content(chunk)
                    .contentVector(vector)
                    .minioPath(doc.getMinioPath())
                    .chunkIndex(i)
                    .build());
        }
        esService.bulkIndex(indexDocs);

        return plainText;
    }

    /** 解压归档文件（支持 zip） */
    private void extractArchive(InputStream inputStream, Path targetDir) throws Exception {
        try (var archive = new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(inputStream)) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!archive.canReadEntryData(entry)) continue;
                Path outPath = targetDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetDir)) continue; // 防Zip Slip
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(archive, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 文本分块（简单实现：按字符数，保证句子完整） */
    private List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            // 尽量在句号处断开
            if (end < text.length()) {
                int lastPeriod = text.lastIndexOf("。", end);
                if (lastPeriod > start + chunkSize / 2) {
                    end = lastPeriod + 1;
                }
            }
            chunks.add(text.substring(start, end));
            start = end - chunkOverlap;
            if (start <= 0) start = chunkSize;
        }
        return chunks;
    }

    /** 从路径中提取文件名（不含扩展名） */
    private String extractBaseName(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String detectMimeType(String fileName) {
        String ext = fileName.toLowerCase();
        if (ext.endsWith(".pdf")) return "application/pdf";
        if (ext.endsWith(".doc")) return "application/msword";
        if (ext.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (ext.endsWith(".ofd")) return "application/vnd.ofd";
        return "application/octet-stream";
    }

    private void appendError(ImportTask task, String error) {
        String existing = task.getErrors();
        task.setErrors(existing != null ? existing + "\n" + error : error);
    }

    private void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
