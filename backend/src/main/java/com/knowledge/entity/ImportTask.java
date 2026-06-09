package com.knowledge.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 导入任务实体 —— 追踪每个压缩包的导入进度 */
@Entity
@Table(name = "import_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务批次号 (UUID) */
    @Column(name = "batch_id", nullable = false, unique = true, length = 64)
    private String batchId;

    /** 原始压缩包文件名 */
    @Column(name = "archive_name", length = 500)
    private String archiveName;

    /** 导入目标: public / 具体部门名 */
    @Column(name = "target_dept", length = 100)
    private String targetDept;

    /** 状态: pending / metadata_parsed / parsing / embedding / complete / failed */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";

    /** 文件总数 */
    @Column(name = "total_files")
    @Builder.Default
    private Integer totalFiles = 0;

    /** 已处理文件数 */
    @Column(name = "processed_files")
    @Builder.Default
    private Integer processedFiles = 0;

    /** 错误信息 (JSON) */
    @Column(name = "errors", columnDefinition = "TEXT")
    private String errors;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
