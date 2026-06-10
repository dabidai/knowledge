package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.ImportProgress;
import com.knowledge.entity.ImportTask;
import com.knowledge.repository.ImportTaskRepository;
import com.knowledge.service.ImportService;
import lombok.RequiredArgsConstructor;
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
    public ApiResponse<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "target", defaultValue = "public") String target) {
        try {
            String batchId = importService.importArchive(file, target);
            return ApiResponse.ok("导入任务已创建", Map.of("batchId", batchId));
        } catch (Exception e) {
            return ApiResponse.error(500, "导入失败: " + e.getMessage());
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

    /** 重试失败任务 —— 重置状态以便重新导入 */
    @PostMapping("/tasks/{batchId}/retry")
    @org.springframework.cache.annotation.CacheEvict(value = "importTasks", allEntries = true)
    public ApiResponse<String> retryTask(@PathVariable String batchId) {
        ImportTask task = taskRepo.findByBatchId(batchId).orElse(null);
        if (task == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        if (!"failed".equals(task.getStatus())) {
            return ApiResponse.error(400, "只能重试失败的任务，当前状态: " + task.getStatus());
        }
        task.setStatus("pending");
        task.setErrors(null);
        task.setProcessedFiles(0);
        task.setCompletedAt(null);
        taskRepo.save(task);
        return ApiResponse.ok("任务已重置为待处理，请重新上传文件导入");
    }
}
