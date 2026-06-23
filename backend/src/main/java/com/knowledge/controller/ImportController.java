package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.ImportProgress;
import com.knowledge.entity.ImportTask;
import com.knowledge.repository.ImportTaskRepository;
import com.knowledge.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** 文档导入控制器 */
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;
    private final ImportTaskRepository taskRepo;

    @Value("${document.import-root-dir}")
    private String importRootDir;

    /** 浏览服务器目录，返回子目录列表（前端文件夹选择器用） */
    @GetMapping("/browse-dir")
    public ApiResponse<List<Map<String, Object>>> browseDir(
            @RequestParam(defaultValue = "") String path) {
        try {
            Path root = Path.of(importRootDir).toRealPath().normalize();
            Path dir = path.isEmpty() ? root : root.resolve(path).normalize();

            // 安全检查：不离开根目录
            if (!dir.startsWith(root)) {
                return ApiResponse.error(403, "不允许访问根目录以外的路径");
            }
            if (!Files.isDirectory(dir)) {
                return ApiResponse.error(400, "不是有效目录");
            }

            List<Map<String, Object>> entries = new ArrayList<>();

            // 添加父目录（如果不在根目录）
            if (!dir.equals(root)) {
                Map<String, Object> parent = new LinkedHashMap<>();
                parent.put("name", "..");
                Path parentRel = root.relativize(dir.getParent());
                parent.put("path", parentRel.toString().replace('\\', '/'));
                parent.put("isDir", true);
                entries.add(parent);
            }

            try (Stream<Path> stream = Files.list(dir)) {
                stream.sorted((a, b) -> {
                    // 目录优先，然后按名称排序
                    boolean aDir = Files.isDirectory(a);
                    boolean bDir = Files.isDirectory(b);
                    if (aDir && !bDir) return -1;
                    if (!aDir && bDir) return 1;
                    return a.getFileName().toString().compareToIgnoreCase(
                            b.getFileName().toString());
                }).forEach(p -> {
                    String name = p.getFileName().toString();
                    // 只列目录和文档文件
                    if (name.startsWith(".")) return;
                    boolean isDir = Files.isDirectory(p);
                    if (!isDir) {
                        String lower = name.toLowerCase();
                        if (!lower.endsWith(".pdf") && !lower.endsWith(".doc")
                                && !lower.endsWith(".docx") && !lower.endsWith(".ofd")
                                && !lower.endsWith(".wps") && !lower.endsWith(".txt")
                                && !lower.endsWith(".zip") && !lower.endsWith(".7z")
                                && !lower.endsWith(".tar") && !lower.endsWith(".gz")
                                && !lower.endsWith(".csv") && !lower.endsWith(".xlsx")) {
                            return;
                        }
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", name);
                    Path rel = root.relativize(p);
                    entry.put("path", rel.toString().replace('\\', '/'));
                    entry.put("isDir", isDir);
                    if (!isDir) {
                        try {
                            entry.put("size", Files.size(p));
                        } catch (Exception ignored) {}
                    }
                    entries.add(entry);
                });
            }

            Map<String, Object> rootInfo = new LinkedHashMap<>();
            rootInfo.put("name", root.toString());
            rootInfo.put("path", "");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("root", rootInfo);
            result.put("current", dir.toString());
            result.put("entries", entries);

            return ApiResponse.ok((List<Map<String, Object>>) (Object) result);
        } catch (Exception e) {
            return ApiResponse.error(500, "读取目录失败: " + e.getMessage());
        }
    }

    /** 上传压缩包并导入，导入成功后清除所有相关缓存 */
    @PostMapping("/upload")
    @org.springframework.cache.annotation.Caching(evict = {
        @org.springframework.cache.annotation.CacheEvict(value = "search", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "browseTree", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "graphOverview", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "importTasks", allEntries = true)
    })
    public ResponseEntity<ApiResponse<Map<String, String>>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "target", defaultValue = "public") String target) {
        try {
            String batchId = importService.importArchive(file, target);
            return ResponseEntity.ok(ApiResponse.ok("导入任务已创建", Map.of("batchId", batchId)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "导入失败: " + e.getMessage()));
        }
    }

    /** 从服务器本地路径导入压缩包（跳过上传，适合大文件） */
    @PostMapping("/from-path")
    @org.springframework.cache.annotation.Caching(evict = {
        @org.springframework.cache.annotation.CacheEvict(value = "search", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "browseTree", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "graphOverview", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "importTasks", allEntries = true)
    })
    public ResponseEntity<ApiResponse<Map<String, String>>> importFromPath(
            @RequestBody Map<String, String> body) {
        String path = body.get("path");
        String target = body.getOrDefault("target", "public");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "路径不能为空"));
        }
        try {
            String batchId = importService.importFromPath(path, target);
            return ResponseEntity.ok(ApiResponse.ok("导入任务已创建", Map.of("batchId", batchId)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "导入失败: " + e.getMessage()));
        }
    }

    /** 从服务器目录扫描导入所有文档 */
    @PostMapping("/from-dir")
    @org.springframework.cache.annotation.Caching(evict = {
        @org.springframework.cache.annotation.CacheEvict(value = "search", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "browseTree", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "graphOverview", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "importTasks", allEntries = true)
    })
    public ResponseEntity<ApiResponse<Map<String, String>>> importFromDir(
            @RequestBody Map<String, String> body) {
        String path = body.get("path");
        String target = body.getOrDefault("target", "public");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "路径不能为空"));
        }
        try {
            String batchId = importService.importFromDir(path, target);
            return ResponseEntity.ok(ApiResponse.ok("导入任务已创建", Map.of("batchId", batchId)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "导入失败: " + e.getMessage()));
        }
    }

    /** 查询导入进度 */
    @GetMapping("/progress/{batchId}")
    public ApiResponse<ImportProgress> progress(@PathVariable String batchId) {
        ImportTask task = taskRepo.findByBatchId(batchId).orElse(null);
        if (task == null) {
            return ApiResponse.error(404, "任务不存在");
        }

        int percent = task.getTotalFiles() > 0
                ? task.getProcessedFiles() * 100 / task.getTotalFiles()
                : 0;

        ImportProgress progress = ImportProgress.builder()
                .batchId(task.getBatchId())
                .status(task.getStatus())
                .totalFiles(task.getTotalFiles())
                .processedFiles(task.getProcessedFiles())
                .percent(percent)
                .errors(task.getErrors())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .build();

        return ApiResponse.ok(progress);
    }

    /** 查询导入历史列表 */
    @GetMapping("/tasks")
    @org.springframework.cache.annotation.Cacheable("importTasks")
    public ApiResponse<List<ImportTask>> tasks() {
        List<ImportTask> tasks = taskRepo.findAllByOrderByCreatedAtDesc();
        return ApiResponse.ok(tasks);
    }

    /** 删除导入任务（仅允许删除失败或已完成的任务） */
    @DeleteMapping("/tasks/{batchId}")
    @org.springframework.cache.annotation.CacheEvict(value = "importTasks", allEntries = true)
    public ApiResponse<String> deleteTask(@PathVariable String batchId) {
        ImportTask task = taskRepo.findByBatchId(batchId).orElse(null);
        if (task == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        if ("pending".equals(task.getStatus()) || "parsing".equals(task.getStatus())) {
            return ApiResponse.error(400, "不能删除正在进行中的任务");
        }
        taskRepo.delete(task);
        return ApiResponse.ok("任务已删除");
    }

    /** 重试失败任务 —— 从中断处断点续传，无需重新上传 */
    @PostMapping("/tasks/{batchId}/retry")
    @org.springframework.cache.annotation.CacheEvict(value = "importTasks", allEntries = true)
    public ApiResponse<String> retryTask(@PathVariable String batchId) {
        ImportTask task = taskRepo.findByBatchId(batchId).orElse(null);
        if (task == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        if (!"failed".equals(task.getStatus()) && !"parsing".equals(task.getStatus())
                && !"metadata_parsed".equals(task.getStatus())) {
            return ApiResponse.error(400, "当前状态不支持重试: " + task.getStatus());
        }
        try {
            importService.resumeImport(batchId);
            return ApiResponse.ok("导入已从中断处恢复");
        } catch (Exception e) {
            return ApiResponse.error(500, "续传失败: " + e.getMessage());
        }
    }
}
