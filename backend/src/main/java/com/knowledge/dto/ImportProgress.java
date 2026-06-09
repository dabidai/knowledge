package com.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 导入进度响应 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportProgress {
    private String batchId;
    private String status;
    private int totalFiles;
    private int processedFiles;
    private int percent;
    private String errors;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
