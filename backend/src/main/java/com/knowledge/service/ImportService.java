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
 * 文档导入编排服务 —— 统筹 CSV 解析 → 文件解析 → MinIO 存储 → Embedding → ES 索引全流程。
 *
 * <p>支持断点续传：导入过程中每个文档处理完后更新进度，如果中途失败，
 * 可通过 resumeImport 从中断处继续，已处理的文档自动跳过。
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
    /** Neo4j 图谱构建服务 —— 导入过程中同步建图 */
    private final GraphBuildService graphBuildService;
    private final ImportTaskRepository taskRepo;
    private final DocumentRepository docRepo;

    /** 导入工作目录（持久化，用于断点续传） */
    @Value("${document.work-dir}")
    private String workDir;

    /** 文本分块大小（字符数） */
    @Value("${document.chunk-size}")
    private int chunkSize;

    /** 分块重叠（字符数） */
    @Value("${document.chunk-overlap}")
    private int chunkOverlap;

    // ──── 解压安全限制 ────
    /** 单条目最大解压后大小（500MB） */
    private static final long MAX_ENTRY_SIZE = 500 * 1024 * 1024;
    /** 解压总大小上限（10GB） */
    private static final long MAX_TOTAL_SIZE = 10L * 1024 * 1024 * 1024;
    /** 解压条目数量上限 */
    private static final int MAX_ENTRY_COUNT = 10_000;
    /** 压缩比告警阈值（100:1） */
    private static final int COMPRESSION_RATIO_WARN = 100;

    /**
     * 导入压缩包（浏览器 HTTP 上传）
     * @param file      上传的压缩包
     * @param targetDept 目标部门 或 "public"
     */
    @Transactional
    public String importArchive(MultipartFile file, String targetDept) throws Exception {
        // 先存为临时文件，复用 doImport 逻辑
        Path tempDir = Path.of(workDir, "uploads");
        Files.createDirectories(tempDir);
        Path temp = tempDir.resolve(UUID.randomUUID().toString().replace("-", ""));
        Files.copy(file.getInputStream(), temp, StandardCopyOption.REPLACE_EXISTING);
        try {
            return doImport(temp, targetDept, file.getOriginalFilename());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 导入压缩包（服务器本地路径）
     * @param pathStr    服务器上的压缩包绝对路径
     * @param targetDept 目标部门 或 "public"
     */
    @Transactional
    public String importFromPath(String pathStr, String targetDept) throws Exception {
        Path path = Path.of(pathStr).toAbsolutePath().normalize();

        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在或不是普通文件");
        }

        return doImport(path, targetDept, path.getFileName().toString());
    }

    /**
     * 导入服务器目录（扫描文件夹下所有文档）
     * @param dirPathStr 服务器上的目录绝对路径
     * @param targetDept 目标部门 或 "public"
     */
    @Transactional
    public String importFromDir(String dirPathStr, String targetDept) throws Exception {
        Path dirPath = Path.of(dirPathStr).toAbsolutePath().normalize();

        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("路径不存在或不是目录");
        }

        String batchId = UUID.randomUUID().toString().replace("-", "");
        boolean isPublic = "public".equals(targetDept);

        graphBuildService.ensureConstraints();

        // 扫描目录下所有文档文件
        List<Path> docFiles = new ArrayList<>();
        Map<String, Path> csvFiles = new HashMap<>();
        try (var stream = Files.walk(dirPath, 16)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString().toLowerCase();
                // 注意：item_with_opinions 必须在 item 之前匹配，避免前缀冲突
                if (name.startsWith("item_with_opinions") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.putIfAbsent("opinions", p);
                } else if (name.startsWith("item") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.putIfAbsent("item", p);
                } else if (name.startsWith("file_index") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.putIfAbsent("file_index", p);
                } else if (name.startsWith("user") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.putIfAbsent("user", p);
                } else if (name.endsWith(".doc") || name.endsWith(".docx")
                        || name.endsWith(".pdf") || name.endsWith(".ofd") || name.endsWith(".wps") || name.endsWith(".txt")) {
                    docFiles.add(p);
                }
            }
        }

        if (docFiles.isEmpty() && csvFiles.isEmpty()) {
            throw new IllegalArgumentException("目录中未找到支持的文档或CSV文件: " + dirPath);
        }

        // 创建导入任务
        ImportTask task = ImportTask.builder()
                .batchId(batchId)
                .archiveName("目录: " + dirPath.getFileName())
                .targetDept(targetDept)
                .status("metadata_parsed")
                .totalFiles(Math.max(csvFiles.size(), 1) + docFiles.size())
                .build();
        taskRepo.save(task);

        // 解析元数据 —— user.csv 必须先于 item.csv，确保 Department 节点
        // 在 Neo4j 中已存在，否则 linkDeptOwnsItem 的 MATCH 会静默失败
        if (csvFiles.containsKey("user")) {
            csvImportService.importUserCsv(csvFiles.get("user"));
        }
        if (csvFiles.containsKey("item")) {
            csvImportService.importItemCsv(csvFiles.get("item"), targetDept, isPublic, batchId);
        }
        if (csvFiles.containsKey("file_index")) {
            csvImportService.importFileIndexCsv(csvFiles.get("file_index"), targetDept, isPublic, batchId);
        }
        if (csvFiles.containsKey("opinions")) {
            csvImportService.importOpinionsCsv(csvFiles.get("opinions"), batchId);
        }

        // 解析文档 —— CSV 已处理完，进度从 csvCount 起算
        task.setStatus("parsing");
        task.setProcessedFiles(csvFiles.size());
        taskRepo.save(task);

        Map<String, String> docTextMap = new LinkedHashMap<>();
        for (int i = 0; i < docFiles.size(); i++) {
            Path docPath = docFiles.get(i);
            try {
                String plainText = processDocument(docPath, targetDept, isPublic, batchId);
                String fileName = docPath.getFileName().toString();
                Document matched = docRepo.findByStatus("matched").stream()
                        .filter(d -> d.getFileName() != null
                                && (d.getFileName().contains(extractBaseName(fileName))
                                    || fileName.contains(extractBaseName(d.getFileName()))))
                        .findFirst().orElse(null);
                if (matched != null && plainText != null && !plainText.isEmpty()) {
                    docTextMap.put(matched.getFileId(), plainText);
                }
                task.setProcessedFiles(csvFiles.size() + i + 1);
                taskRepo.save(task);
            } catch (Exception e) {
                log.error("文档解析失败: {}", docPath.getFileName(), e);
                appendError(task, docPath.getFileName() + ": " + e.getMessage());
            }
        }

        // 交叉引用检测
        task.setStatus("complete");
        task.setCompletedAt(java.time.LocalDateTime.now());
        taskRepo.save(task);

        try {
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

        log.info("目录导入完成: batchId={}, 文件数={}", batchId, docFiles.size());
        return batchId;
    }

    /** 核心导入逻辑 —— 解压、解析元数据、解析文档、建图 */
    private String doImport(Path archivePath, String targetDept, String archiveName) throws Exception {
        String batchId = UUID.randomUUID().toString().replace("-", "");
        boolean isPublic = "public".equals(targetDept);

        // 1. 确保 Neo4j 约束就绪
        graphBuildService.ensureConstraints();

        // 2. 创建导入任务
        ImportTask task = ImportTask.builder()
                .batchId(batchId)
                .archiveName(archiveName)
                .targetDept(targetDept)
                .status("pending")
                .build();
        taskRepo.save(task);

        // 3. 解压至工作目录（持久化，支持断点续传）
        Path extractDir = Path.of(workDir, batchId);
        Files.createDirectories(extractDir);
        try (InputStream is = Files.newInputStream(archivePath)) {
            extractArchive(is, extractDir);
        }
        // 3. 分类文件
        Map<String, Path> csvFiles = new HashMap<>();
        List<Path> docFiles = new ArrayList<>();
        try (var stream = Files.walk(extractDir, 16)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString().toLowerCase();
                if (name.startsWith("item_with_opinions") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("opinions", p);
                } else if (name.startsWith("item") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("item", p);
                } else if (name.startsWith("file_index") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("file_index", p);
                } else if (name.startsWith("item_with_opinions") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("opinions", p);
                } else if (name.startsWith("user") && (name.endsWith(".csv") || name.endsWith(".xlsx"))) {
                    csvFiles.put("user", p);
                } else if (name.endsWith(".doc") || name.endsWith(".docx")
                        || name.endsWith(".pdf") || name.endsWith(".ofd") || name.endsWith(".wps") || name.endsWith(".txt")) {
                    docFiles.add(p);
                }
            }
        }

        // 4. 解析元数据 CSV（先于文档解析）
        // 总文件数 = CSV 元数据 + 文档，让进度列不再显示 0/0
        int csvCount = csvFiles.size();
        task.setStatus("metadata_parsed");
        task.setTotalFiles(csvCount + docFiles.size());
        taskRepo.save(task);

        if (csvFiles.containsKey("user")) {
            csvImportService.importUserCsv(csvFiles.get("user"));
        }
        if (csvFiles.containsKey("item")) {
            csvImportService.importItemCsv(csvFiles.get("item"), targetDept, isPublic, batchId);
        }
        if (csvFiles.containsKey("file_index")) {
            csvImportService.importFileIndexCsv(csvFiles.get("file_index"), targetDept, isPublic, batchId);
        }
        if (csvFiles.containsKey("opinions")) {
            csvImportService.importOpinionsCsv(csvFiles.get("opinions"), batchId);
        }

        // 5. 解析实际文档 —— CSV 已处理完，进度从 csvCount 起算
        task.setStatus("parsing");
        task.setProcessedFiles(csvCount);
        taskRepo.save(task);

        // 收集 (fileId → 解析文本) 映射用于后续交叉引用检测
        Map<String, String> docTextMap = new LinkedHashMap<>();

        for (int i = 0; i < docFiles.size(); i++) {
            Path docPath = docFiles.get(i);
            try {
                String plainText = processDocument(docPath, targetDept, isPublic, batchId);
                // 从 DB 获取刚保存的文档 fileId
                String fileName = docPath.getFileName().toString();
                Document matched = docRepo.findByStatus("matched").stream()
                        .filter(d -> d.getFileName() != null
                                && (d.getFileName().contains(extractBaseName(fileName))
                                    || fileName.contains(extractBaseName(d.getFileName()))))
                        .findFirst().orElse(null);
                if (matched != null && plainText != null && !plainText.isEmpty()) {
                    docTextMap.put(matched.getFileId(), plainText);
                }
                task.setProcessedFiles(csvCount + i + 1);
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

        // 7. 保留工作目录以便后续断点续传（不删除）
        log.info("导入完成: batchId={}, 文件数={}, 工作目录保留", batchId, docFiles.size());
        return batchId;
    }

    /**
     * 断点续传 —— 从中断处恢复导入。
     * 重新扫描工作目录，跳过已处理的文档，只处理剩余的。
     *
     * @param batchId 导入批次号
     */
    @Transactional
    public void resumeImport(String batchId) throws Exception {
        ImportTask task = taskRepo.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + batchId));

        if (!"failed".equals(task.getStatus()) && !"parsing".equals(task.getStatus())
                && !"metadata_parsed".equals(task.getStatus())) {
            throw new IllegalStateException("当前状态不支持续传: " + task.getStatus());
        }

        Path extractDir = Path.of(workDir, batchId);
        if (!Files.exists(extractDir)) {
            throw new IllegalStateException("工作目录不存在，无法续传: " + extractDir);
        }

        boolean isPublic = "public".equals(task.getTargetDept());
        String targetDept = task.getTargetDept();

        // 收集已处理文档的文件名集合
        Set<String> processedNames = new HashSet<>();
        for (Document d : docRepo.findByImportBatch(batchId)) {
            if ("matched".equals(d.getStatus()) || "orphan".equals(d.getStatus())) {
                if (d.getMinioPath() != null) {
                    processedNames.add(d.getFileName());
                    // 同时存储提取的基本名，用于模糊匹配
                    processedNames.add(extractBaseName(d.getFileName()));
                }
            }
        }

        // 重新扫描文档文件
        List<Path> remainingFiles = new ArrayList<>();
        try (var stream = Files.walk(extractDir, 16)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString().toLowerCase();
                if (name.endsWith(".doc") || name.endsWith(".docx")
                        || name.endsWith(".pdf") || name.endsWith(".ofd") || name.endsWith(".wps") || name.endsWith(".txt")) {
                    String baseName = extractBaseName(name);
                    // 跳过已处理的文件
                    if (!processedNames.contains(p.getFileName().toString())
                            && !processedNames.contains(baseName)
                            && !processedNames.contains(name)) {
                        remainingFiles.add(p);
                    }
                }
            }
        }

        if (remainingFiles.isEmpty()) {
            task.setStatus("complete");
            task.setCompletedAt(java.time.LocalDateTime.now());
            taskRepo.save(task);
            log.info("断点续传 —— 无剩余文件: batchId={}", batchId);
            return;
        }

        log.info("断点续传 —— batchId={}, 剩余 {} 个文件 (已跳过 {} 个)",
                batchId, remainingFiles.size(), task.getTotalFiles() - remainingFiles.size());

        // 处理剩余文件
        task.setStatus("parsing");
        task.setTotalFiles(task.getProcessedFiles() + remainingFiles.size());
        taskRepo.save(task);

        for (int i = 0; i < remainingFiles.size(); i++) {
            Path docPath = remainingFiles.get(i);
            try {
                processDocument(docPath, targetDept, isPublic, batchId);
                task.setProcessedFiles(task.getProcessedFiles() + 1);
                taskRepo.save(task);
            } catch (Exception e) {
                log.error("文档解析失败: {}", docPath.getFileName(), e);
                appendError(task, docPath.getFileName() + ": " + e.getMessage());
            }
        }

        // 完成（保留历史错误记录以供审计）
        task.setStatus("complete");
        task.setCompletedAt(java.time.LocalDateTime.now());
        taskRepo.save(task);

        log.info("断点续传完成: batchId={}, 最终处理 {} 个文件", batchId, task.getProcessedFiles());
    }

    /**
     * 清理导入工作目录。
     *
     * @param batchId 导入批次号
     */
    public void cleanupWorkDir(String batchId) {
        Path dir = Path.of(workDir, batchId);
        deleteRecursively(dir);
        log.info("工作目录已清理: {}", batchId);
    }

    /** 处理单个文档：解析 → 存储 → Embedding → 索引，返回解析后的纯文本 */
    private String processDocument(Path docPath, String deptName, boolean isPublic,
                                  String batchId) throws IOException {
        String fileName = docPath.getFileName().toString();
        log.debug("处理文档: {}", fileName);

        // 1. 解析文本 —— PDF 走双轨解析（原生文字 + OCR 降级），其他格式走普通解析
        String plainText;
        PdfParser.PdfParseResult pdfMeta = null;
        if (fileName.toLowerCase().endsWith(".pdf")) {
            pdfMeta = documentParser.parsePdfWithMeta(docPath);
            plainText = pdfMeta.text();
        } else {
            plainText = documentParser.parse(docPath);
        }
        String markdown = documentParser.toMarkdown(plainText);

        // 2. 在 document 表中匹配（按文件名）
        Optional<Document> matchOpt = docRepo.findByStatus("expected").stream()
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
            // 未匹配到 CSV 记录 → 尝试查找已存在的文档（同文件名覆盖）
            Optional<Document> existingOpt = docRepo.findAll().stream()
                    .filter(d -> extractBaseName(fileName).equals(extractBaseName(d.getFileName())))
                    .findFirst();

            if (existingOpt.isPresent()) {
                doc = existingOpt.get();
                log.info("覆盖已有文档: {} (fileId={})", fileName, doc.getFileId());
                doc.setDeptName(deptName);
                doc.setIsPublic(isPublic);
                doc.setImportBatch(batchId);
                // 删除旧的 ES 索引条目（稍后重新索引）
                esService.deleteByDocId(doc.getFileId());
            } else {
                // 未匹配到任何记录，创建 orphan
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
        if (pdfMeta != null) {
            doc.setTotalPages(pdfMeta.totalPages());
            doc.setOcrPages(pdfMeta.ocrPages());
            doc.setQualityGrade(pdfMeta.qualityGrade());
        }
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

    /** 解压归档文件（支持 zip / 7z / tar / tar.gz / rar） */
    private void extractArchive(InputStream inputStream, Path targetDir) throws Exception {
        // 读取前 512 字节检测格式（TAR 需要 512 字节块）
        byte[] header = new byte[512];
        int totalRead = 0;
        while (totalRead < header.length) {
            int n = inputStream.read(header, totalRead, header.length - totalRead);
            if (n < 0) break;
            totalRead += n;
        }
        if (totalRead < 4) throw new IllegalArgumentException("无法识别压缩格式");

        // 封装：把 header + 剩余流拼回去给解析器
        java.io.SequenceInputStream fullStream = new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(header, 0, totalRead), inputStream);

        if (is7z(header)) {
            extract7z(fullStream, targetDir);
        } else if (isRar(header)) {
            extractRar(fullStream, targetDir);
        } else if (isTar(header)) {
            extractTar(fullStream, targetDir);
        } else {
            extractZip(fullStream, targetDir);
        }
    }

    private static boolean is7z(byte[] header) {
        return header[0] == 0x37 && header[1] == 0x7A
            && header[2] == (byte)0xBC && header[3] == (byte)0xAF;
    }

    private static boolean isRar(byte[] header) {
        return header[0] == 0x52 && header[1] == 0x61
            && header[2] == 0x72 && header[3] == 0x21;
    }

    private static boolean isTar(byte[] header) {
        // POSIX/GNU TAR: 偏移 257 处有 "ustar" 魔数
        if (header.length >= 263) {
            return header[257] == 'u' && header[258] == 's'
                && header[259] == 't' && header[260] == 'a'
                && header[261] == 'r';
        }
        return false;
    }

    private void extractZip(InputStream inputStream, Path targetDir) throws Exception {
        try (var archive = new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(inputStream)) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            int entryCount = 0;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("压缩包条目数超过上限: " + MAX_ENTRY_COUNT);
                }
                if (!archive.canReadEntryData(entry)) continue;
                if (entry.getSize() > MAX_ENTRY_SIZE) {
                    log.warn("Zip 条目过大（已跳过）: {} ({} 字节)", entry.getName(), entry.getSize());
                    continue;
                }
                writeEntry(targetDir, archive, entry.getName(), entry.isDirectory());
            }
        }
    }

    private void extract7z(InputStream inputStream, Path targetDir) throws Exception {
        // SevenZFile 需要 SeekableByteChannel，先写临时文件
        Path temp = Files.createTempFile("kb-extract", ".7z");
        try {
            Files.copy(inputStream, temp, StandardCopyOption.REPLACE_EXISTING);
            try (var szFile = new org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder()
                    .setFile(temp.toFile())
                    .get()) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
                int entryCount = 0;
                while ((entry = szFile.getNextEntry()) != null) {
                    if (++entryCount > MAX_ENTRY_COUNT) {
                        throw new IllegalArgumentException("压缩包条目数超过上限: " + MAX_ENTRY_COUNT);
                    }
                    if (entry.isDirectory()) {
                        writeEntry(targetDir, null, entry.getName(), true);
                    } else {
                        Path outPath = targetDir.resolve(entry.getName()).normalize();
                        if (!outPath.startsWith(targetDir)) continue;
                        if (entry.getSize() > MAX_ENTRY_SIZE) {
                            log.warn("7z 条目过大（已跳过）: {} ({} 字节)", entry.getName(), entry.getSize());
                            continue;
                        }
                        Files.createDirectories(outPath.getParent());
                        // 读取到 byte[] 再写
                        byte[] content = new byte[(int) entry.getSize()];
                        szFile.read(content);
                        Files.write(outPath, content);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void extractTar(InputStream inputStream, Path targetDir) throws Exception {
        // TarArchiveInputStream 自动处理 .tar / .tar.gz / .tar.bz2
        try (var archive = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(inputStream)) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            int entryCount = 0;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("压缩包条目数超过上限: " + MAX_ENTRY_COUNT);
                }
                if (!archive.canReadEntryData(entry)) continue;
                if (entry.getSize() > MAX_ENTRY_SIZE) {
                    log.warn("TAR 条目过大（已跳过）: {} ({} 字节)", entry.getName(), entry.getSize());
                    continue;
                }
                writeEntry(targetDir, archive, entry.getName(), entry.isDirectory());
            }
        }
    }

    private void extractRar(InputStream inputStream, Path targetDir) throws Exception {
        // RAR 是专有格式，Java 生态无成熟的 RAR5 免费库
        throw new IllegalArgumentException("不支持 RAR 格式，请用 WinRAR 或 7-Zip 解压后重新打包为 ZIP 上传");
    }

    private void writeEntry(Path targetDir, InputStream source, String name, boolean isDir) throws Exception {
        Path outPath = targetDir.resolve(name).normalize();
        if (!outPath.startsWith(targetDir)) {
            log.warn("Zip Slip 攻击已拦截: 条目名={}, 解析路径={}", name, outPath);
            return;
        }
        if (isDir) {
            Files.createDirectories(outPath);
        } else {
            Files.createDirectories(outPath.getParent());
            Files.copy(source, outPath, StandardCopyOption.REPLACE_EXISTING);
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
            if (end >= text.length()) break; // 已到文本末尾，防止死循环
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
        if (ext.endsWith(".txt")) return "text/plain";
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
