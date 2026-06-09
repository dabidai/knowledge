package com.knowledge.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 事项元数据实体 —— 来自 item.csv */
@Entity
@Table(name = "items", indexes = {
    @Index(name = "idx_items_dept", columnList = "dept_name"),
    @Index(name = "idx_items_category", columnList = "category"),
    @Index(name = "idx_items_year", columnList = "year")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Item {

    /** 事项ID，来自 CSV 的原始 ID */
    @Id
    @Column(length = 64)
    private String itemId;

    /** 事项标题 */
    @Column(nullable = false, length = 500)
    private String title;

    /** 事项发起时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 事项分类，如 "通知" */
    @Column(length = 50)
    private String category;

    /** 分类编号，如 "2021通1234" */
    @Column(name = "category_no", length = 100)
    private String categoryNo;

    /** 年度 */
    @Column(length = 10)
    private String year;

    /** 字号 */
    @Column(name = "ref_no", length = 100)
    private String refNo;

    /** 发文单位 */
    @Column(length = 200)
    private String issuer;

    /** 事项类型: 收文 / 发文 */
    @Column(name = "item_type", length = 20)
    private String itemType;

    /** 所属部门 */
    @Column(name = "dept_name", length = 100)
    private String deptName;

    /** 是否公共区文档 */
    @Column(name = "is_public")
    @Builder.Default
    private Boolean isPublic = false;

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
