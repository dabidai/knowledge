package com.knowledge.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 文件实体 —— 来自 file_index.csv + 实际文件 */
@Entity
@Table(name = "documents", indexes = {
    @Index(name = "idx_documents_item", columnList = "item_id"),
    @Index(name = "idx_documents_status", columnList = "status"),
    @Index(name = "idx_documents_dept", columnList = "dept_name")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    /** 文件ID，来自 CSV 的原始 ID */
    @Id
    @Column(length = 64)
    private String fileId;

    /** 文件名 / 路径，如 "2021/09/01/xxx.PDF" */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** 所属事项 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    /** 状态: expected / matched / missing / orphan */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "expected";

    /** 所属部门 */
    @Column(name = "dept_name", length = 100)
    private String deptName;

    /** 是否公共区 */
    @Column(name = "is_public")
    @Builder.Default
    private Boolean isPublic = false;

    /** MinIO 中的文件路径 */
    @Column(name = "minio_path", length = 500)
    private String minioPath;

    /** MinIO 中 Markdown 路径 */
    @Column(name = "minio_md_path", length = 500)
    private String minioMdPath;

    /** 解析后的文本长度 (字符数) */
    @Column(name = "text_length")
    private Long textLength;

    /** 解析时间 */
    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    /** 导入时间 */
    @Column(name = "import_time")
    private LocalDateTime importTime;

    /** 导入批次 */
    @Column(name = "import_batch", length = 64)
    private String importBatch;

    @PrePersist
    void prePersist() {
        if (this.importTime == null) {
            this.importTime = LocalDateTime.now();
        }
    }
}
