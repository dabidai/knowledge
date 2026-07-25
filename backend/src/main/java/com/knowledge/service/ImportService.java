package com.knowledge.service;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    /** 异步导入执行器 —— 避免大导入阻塞 HTTP 请求线程 */
    @Autowired
    @Qualifier("importExecutor")
    private ThreadPoolTaskExecutor importExecutor;
    /** 事务模板 —— 用于在异步线程中包装数据操作 */
    private final TransactionTemplate transactionTemplate;
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
    public String importArchive(MultipartFile file, String targetDept) throws Exception {
        // 先存为临时文件，复用 doImport 逻辑
        Path tempDir = Path.of(workDir, "uploads");
        Files.createDirectories(tempDir);
        Path temp = tempDir.resolve(UUID.randomUUID().toString().replace("-", ""));
        Files.copy(file.getInputStream(), temp, StandardCopyOption.REPLACE_EXISTING);

        String fileName = file.getOriginalFilename();
        return submitAsyncImport(batchId -> doImport(temp, targetDept, fileName, batchId), targetDept, fileName,
                () -> { try { Files.deleteIfExists(temp); } catch (Exception ignored) {} });
    }

    /**
     * 导入压缩包（服务器本地路径）
     * @param pathStr    服务器上的压缩包绝对路径
     * @param targetDept 目标部门 或 "public"
     */
    public String importFromPath(String pathStr, String targetDept) throws Exception {
        Path path = Path.of(pathStr).toAbsolutePath().normalize();

        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在或不是普通文件");
        }

        String fileName = path.getFileName().toString();
        return submitAsyncImport(batchId -> doImport(path, targetDept, fileName, batchId), targetDept, fileName, null);
    }

    /**
     * 导入服务器目录（扫描文件夹下所有文档）
     * @param dirPathStr 服务器上的目录绝对路径
     * @param targetDept 目标部门 或 "public"
     */
    public String importFromDir(String dirPathStr, String targetDept) throws Exception {
        Path dirPath = Path.of(dirPathStr).toAbsolutePath().normalize();

        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("路径不存在或不是目录");
        }

        return submitAsyncImport(batchId -> doImportDir(dirPath, targetDept, batchId), targetDept,
                "目录: " + dirPath.getFileName().toString(), null);
    }

    /**
     * 目录导入核心逻辑 —— 异步执行。
     * 扫描目录 → CSV 元数据 → 文档解析 → Embedding → ES 索引 → 交叉引用检测
     */
    @SuppressWarnings("unused")
    private String doImportDir(Path dirPath, String targetDept, String batchId) throws Exception {
        boolean isPublic = "public".equals(targetDept);

        ImportMetrics metrics = ImportMetrics.builder()
                .batchId(batchId)
                .taskStartTimeMs(System.currentTimeMillis())
                .build();

        graphBuildService.ensureConstraints();

        // 从 submitAsyncImport 已创建的 task 开始
        ImportTask task = taskRepo.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + batchId));
        task.setArchiveName("目录: " + dirPath.getFileName());
        task.setTotalFiles(0);

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
                        || name.endsWith(".pdf") || name.endsWith(".ofd") || name.endsWith(".wps")) {
                    docFiles.add(p);
                }
            }
        }

        if (docFiles.isEmpty() && csvFiles.isEmpty()) {
            throw new IllegalArgumentException("目录中未找到支持的文档或CSV文件: " + dirPath);
        }

        // 更新任务：记录总文件数
        task.setStatus("metadata_parsed");
        task.setTotalFiles(Math.max(csvFiles.size(), 1) + docFiles.size());
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

        for (int i = 0; i < docFiles.size(); i++) {
            Path docPath = docFiles.get(i);
            try {
                String plainText = processDocument(docPath, targetDept, isPublic, batchId, metrics);
                String fileName = docPath.getFileName().toString();
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
                String content = d.getMinioPath() != null
                        ? minioService.getMarkdown(d.getMinioPath())
                        : "";
                if (content == null) content = "";
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
        metrics.setTotalFiles(docFiles.size());
        metrics.logSummary();
        return batchId;
    }

    /** 核心导入逻辑 —— 解压、解析元数据、解析文档、建图 */
    private String doImport(Path archivePath, String targetDept, String archiveName, String batchId) throws Exception {
        boolean isPublic = "public".equals(targetDept);

        ImportMetrics metrics = ImportMetrics.builder()
                .batchId(batchId)
                .taskStartTimeMs(System.currentTimeMillis())
                .build();

        // 1. 确保 Neo4j 约束就绪
        graphBuildService.ensureConstraints();

        // 2. 使用 submitAsyncImport 已创建的导入任务
        ImportTask task = taskRepo.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + batchId));
        task.setArchiveName(archiveName);

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
                        || name.endsWith(".pdf") || name.endsWith(".ofd") || name.endsWith(".wps")) {
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

        for (int i = 0; i < docFiles.size(); i++) {
            Path docPath = docFiles.get(i);
            try {
                String plainText = processDocument(docPath, targetDept, isPublic, batchId, metrics);
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
                String content = d.getMinioPath() != null
                        ? minioService.getMarkdown(d.getMinioPath())
                        : "";
                if (content == null) content = "";
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
        metrics.setTotalFiles(docFiles.size());
        metrics.logSummary();
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

        ImportMetrics metrics = ImportMetrics.builder()
                .batchId(batchId)
                .taskStartTimeMs(System.currentTimeMillis())
                .build();

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
            metrics.setTotalFiles(0);
            metrics.logSummary();
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
                processDocument(docPath, targetDept, isPublic, batchId, metrics);
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
        metrics.setTotalFiles(task.getProcessedFiles());
        metrics.logSummary();
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
                                  String batchId, ImportMetrics metrics) throws IOException {
        String fileName = docPath.getFileName().toString();
        log.debug("处理文档: {}", fileName);
        long docStart = System.currentTimeMillis();

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
        long parseEnd = System.currentTimeMillis();
        metrics.addParseTime(parseEnd - docStart);

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
        // 预取懒加载的 Item 字段
        String itemTitle = null;
        String itemCategory = null;
        try {
            itemTitle = transactionTemplate.execute(s ->
                    doc.getItem() != null ? doc.getItem().getTitle() : null);
            itemCategory = transactionTemplate.execute(s ->
                    doc.getItem() != null ? doc.getItem().getCategory() : null);
        } catch (Exception e) {
            // LazyInitializationException 等，保持 null
        }
        long minioEnd = System.currentTimeMillis();
        metrics.addMinioTime(minioEnd - parseEnd);
        metrics.addTextSize(plainText.length());

        // 5. 文本分块 → 过滤垃圾 → 批量 Embedding → 流式 ES 索引
        List<String> chunks = splitText(plainText);
        chunks = chunks.stream().filter(c -> !isGarbage(c)).collect(java.util.stream.Collectors.toList());

        // 单文件 chunk 上限：防止 OFD 垃圾坐标文本产生海量分块
        final int MAX_CHUNKS_PER_DOC = 100;
        if (chunks.size() > MAX_CHUNKS_PER_DOC) {
            chunks = chunks.subList(0, MAX_CHUNKS_PER_DOC);
        }
        metrics.addChunks(chunks.size());

        boolean embedSuccess = true;
        if (chunks.isEmpty()) {
            metrics.addEmptyDoc();
        } else {
            long embedStart = System.currentTimeMillis();
            List<float[]> vectors = aiClient.embedBatch(chunks);
            long embedEnd = System.currentTimeMillis();
            embedSuccess = !vectors.isEmpty();
            metrics.addEmbedCall(embedEnd - embedStart, !embedSuccess);

            if (embedSuccess) {
                // 流式写 ES：每 200 条发一批，避免单文档 chunk 过多时内存堆积
                final int ES_BATCH_SIZE = 200;
                long esStart = System.currentTimeMillis();
                List<ElasticsearchService.DocIndex> esBatch = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    float[] vector = vectors.get(i);

                    esBatch.add(ElasticsearchService.DocIndex.builder()
                            .docId(doc.getFileId())
                            .fileName(doc.getFileName())
                            .deptName(deptName)
                            .isPublic(isPublic)
                            .itemTitle(itemTitle)
                            .itemCategory(itemCategory)
                            .content(chunks.get(i))
                            .contentVector(vector)
                            .minioPath(doc.getMinioPath())
                            .chunkIndex(i)
                            .build());

                    if (esBatch.size() >= ES_BATCH_SIZE) {
                        esService.bulkIndex(esBatch);
                        esBatch.clear();
                    }
                }
                if (!esBatch.isEmpty()) {
                    esService.bulkIndex(esBatch);
                }
                long esEnd = System.currentTimeMillis();
                metrics.addEsIndexTime(esEnd - esStart);
            }
        }

        // 只在 Embedding 成功后保存（失败时不入库，续传时会重新处理）
        if (embedSuccess) {
            docRepo.save(doc);
        }
        long docEnd = System.currentTimeMillis();
        log.info("【性能埋点】文档处理完成: {}, 耗时={}ms, 分块={}, 文本大小={}KB",
                fileName, docEnd - docStart, chunks.size(), String.format("%.1f", plainText.length() / 1024.0));

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
        } else if (isGzip(header)) {
            // .tar.gz / .tgz：先解压 gzip，再检查是否为 tar
            var gzStream = new org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(fullStream);
            byte[] tarHeader = new byte[512];
            int tarRead = 0;
            while (tarRead < tarHeader.length) {
                int n = gzStream.read(tarHeader, tarRead, tarHeader.length - tarRead);
                if (n < 0) break;
                tarRead += n;
            }
            var decompressedFull = new java.io.SequenceInputStream(
                    new java.io.ByteArrayInputStream(tarHeader, 0, tarRead), gzStream);
            if (isTar(tarHeader)) {
                extractTar(decompressedFull, targetDir);
            } else {
                // .gz 但不是 tar（单文件压缩），视为单个文件处理
                log.warn(".gz 文件不是 tar 归档，尝试按文档读取");
                throw new IllegalArgumentException("不支持的压缩格式: .gz 不是 tar 归档，请解压后导入目录");
            }
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

    /** gzip 格式检测（.tar.gz / .tgz） */
    private static boolean isGzip(byte[] header) {
        return header.length >= 2 && (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B;
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

    /** 判断文本块是否为垃圾（OFD 坐标指令、OCR 碎片等），避免送往 Embedding 产生垃圾向量 */
    private boolean isGarbage(String text) {
        if (text == null || text.isEmpty()) return true;

        String[] lines = text.split("\n");
        int coordLines = 0;
        int totalChinese = 0;
        int totalAlphaNum = 0;

        // SVG/OFD 路径指令模式: M/L/Q/C 后跟数字坐标
        java.util.regex.Pattern coordPtn = java.util.regex.Pattern.compile(
                "[MLQC]\\s+[\\d.]+[\\s,]+[\\d.]+"
        );

        for (String line : lines) {
            if (coordPtn.matcher(line).find()) {
                coordLines++;
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                    totalChinese++;
                } else if (Character.isLetterOrDigit(c)) {
                    totalAlphaNum++;
                }
            }
        }

        int totalChars = text.length();
        if (totalChars == 0) return true;

        // 规则1: 坐标指令行占比 > 50%
        if (lines.length > 0 && (double) coordLines / lines.length > 0.5) {
            return true;
        }

        // 规则2: 非汉字符号占比 > 80%
        int nonChinese = totalChars - totalChinese;
        if ((double) nonChinese / totalChars > 0.8) {
            return true;
        }

        // 规则3: 有效汉字 < 20 个
        if (totalChinese < 20) {
            return true;
        }

        return false;
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

    // ──── 异步导入编排 ────

    /** 异步提交导入任务：创建初始记录 → 后台执行 → 立即返回 batchId */
    private String submitAsyncImport(ImportTaskRunnable importLogic, String targetDept,
                                     String archiveName, Runnable cleanup) {
        String batchId = UUID.randomUUID().toString().replace("-", "");

        ImportTask task = ImportTask.builder()
                .batchId(batchId)
                .archiveName(archiveName)
                .targetDept(targetDept)
                .status("pending")
                .build();
        taskRepo.save(task);

        importExecutor.submit(() -> {
            try {
                importLogic.run(batchId);
            } catch (Exception e) {
                log.error("异步导入失败: batchId={}", batchId, e);
                markTaskFailed(batchId, e.getMessage());
            } finally {
                if (cleanup != null) {
                    try { cleanup.run(); } catch (Exception ignored) {}
                }
            }
        });

        return batchId;
    }

    /** 标记导入任务失败 */
    private void markTaskFailed(String batchId, String error) {
        try {
            ImportTask t = taskRepo.findByBatchId(batchId).orElse(null);
            if (t != null) {
                t.setStatus("failed");
                t.setErrors(error);
                taskRepo.save(t);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 启动时检测上次 JVM 崩溃遗留的孤儿任务（状态为 parsing/metadata_parsed），
     * 自动标记为 failed，避免前台一直显示"导入中"。
     */
    public void markOrphanedTasksAsFailed() {
        try {
            List<ImportTask> orphans = taskRepo.findByStatus("parsing");
            orphans.addAll(taskRepo.findByStatus("metadata_parsed"));
            for (ImportTask t : orphans) {
                t.setStatus("failed");
                t.setErrors("进程异常终止（JVM 崩溃或关机），导入中断");
                taskRepo.save(t);
                log.warn("孤儿任务已标记为失败: batchId={}, archiveName={}", t.getBatchId(), t.getArchiveName());
            }
            if (!orphans.isEmpty()) {
                log.info("共 {} 个孤儿任务已标记为失败，可通过 POST /tasks/{{batchId}}/retry 续传", orphans.size());
            }
        } catch (Exception e) {
            log.error("标记孤儿任务失败", e);
        }
    }

    /** 异步导入执行体 —— 接收 batchId 在后台运行 */
    @FunctionalInterface
    private interface ImportTaskRunnable {
        void run(String batchId) throws Exception;
    }

    // ──── 基线性能指标采集 ────

    /** 导入性能指标采集器 —— 收集并汇总导入全流程耗时/吞吐量数据 */
    @Data
    @Builder
    public static class ImportMetrics {
        private String batchId;
        private long taskStartTimeMs;
        private int totalFiles;
        private long totalTextSizeBytes;
        private int totalChunks;
        private int embedCallCount;
        private int embedFailCount;
        private long totalEmbedTimeMs;
        private long totalEsIndexTimeMs;
        private long totalParseTimeMs;
        private long totalMinioTimeMs;
        private int emptyDocCount;

        public void addParseTime(long ms) { this.totalParseTimeMs += ms; }
        public void addTextSize(long bytes) { this.totalTextSizeBytes += bytes; }
        public void addMinioTime(long ms) { this.totalMinioTimeMs += ms; }
        public void addChunks(int count) { this.totalChunks += count; }
        public void addEmptyDoc() { this.emptyDocCount++; }
        public void addEmbedCall(long timeMs, boolean failed) {
            this.embedCallCount++;
            this.totalEmbedTimeMs += timeMs;
            if (failed) this.embedFailCount++;
        }
        public void addEsIndexTime(long ms) { this.totalEsIndexTimeMs += ms; }

        /** 输出格式化的性能指标汇总报告 */
        public void logSummary() {
            long totalTime = System.currentTimeMillis() - taskStartTimeMs;
            double totalTimeSec = totalTime / 1000.0;
            double totalTimeMin = totalTimeSec / 60.0;
            double totalTimeHour = totalTimeMin / 60.0;

            double textSizeMB = totalTextSizeBytes / 1024.0 / 1024.0;
            double docsPerHour = totalTimeHour > 0 ? totalFiles / totalTimeHour : 0;
            double chunksPerSec = totalTimeSec > 0 ? totalChunks / totalTimeSec : 0;
            double avgEmbedRt = embedCallCount > 0 ? (double) totalEmbedTimeMs / embedCallCount : 0;
            double embedQps = totalTimeSec > 0 && embedCallCount > 0 ? embedCallCount / totalTimeSec : 0;
            double avgEsWrite = totalChunks > 0 ? (double) totalEsIndexTimeMs / totalChunks : 0;

            log.info("");
            log.info("========== 基线性能指标汇总 ==========");
            log.info("批次: {}", batchId);
            log.info("文档数: {}", totalFiles);
            log.info("文本总量: {} MB ({} bytes)", String.format("%.2f", textSizeMB), totalTextSizeBytes);
            log.info("分块总数: {}", totalChunks);
            log.info("");
            log.info("【耗时】");
            log.info("总耗时: {} ms ({} min, {} h)", totalTime,
                    String.format("%.2f", totalTimeMin), String.format("%.2f", totalTimeHour));
            log.info("解析耗时: {} ms", totalParseTimeMs);
            log.info("Embedding 总耗时: {} ms (含网络+推理)", totalEmbedTimeMs);
            log.info("ES 索引总耗时: {} ms", totalEsIndexTimeMs);
            log.info("MinIO 上传总耗时: {} ms", totalMinioTimeMs);
            log.info("");
            log.info("【吞吐量】");
            log.info("文档处理速度: {} 文档/小时", String.format("%.2f", docsPerHour));
            log.info("Chunk 处理速度: {} chunk/s", String.format("%.2f", chunksPerSec));
            log.info("");
            log.info("【Embedding】");
            log.info("调用总次数: {}", embedCallCount);
            log.info("失败次数: {}", embedFailCount);
            log.info("空文档（无有效内容可嵌入）: {}", emptyDocCount);
            log.info("平均 RT: {} ms", String.format("%.1f", avgEmbedRt));
            log.info("QPS: {}", String.format("%.2f", embedQps));
            log.info("");
            log.info("【ES 写入】");
            log.info("平均单条写入: {} ms", String.format("%.1f", avgEsWrite));
            log.info("");
            log.info("======================================");
        }
    }
}
