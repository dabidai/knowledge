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

    /** 上传压缩包并导入 */
    @PostMapping("/upload")
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
    public ApiResponse<List<ImportTask>> tasks() {
        List<ImportTask> tasks = taskRepo.findAllByOrderByCreatedAtDesc();
        return ApiResponse.ok(tasks);
    }
}
