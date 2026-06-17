package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.ImportProgress;
import com.knowledge.entity.ImportTask;
import com.knowledge.repository.ImportTaskRepository;
import com.knowledge.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 文档导入控制器 */
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;
    private final ImportTaskRepository taskRepo;

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
    // 临时关闭缓存排查 500 问题，确认无误后可恢复
    // @org.springframework.cache.annotation.Cacheable("importTasks")
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
