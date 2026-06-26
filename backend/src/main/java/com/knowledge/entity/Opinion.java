package com.knowledge.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 签阅意见实体 —— 来自 item_with_opinions.csv */
@Entity
@Table(name = "opinions", indexes = {
    @Index(name = "idx_opinions_item", columnList = "item_id"),
    @Index(name = "idx_opinions_signer", columnList = "signer")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Opinion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属事项 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    /** 签阅人姓名 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String signer;

    /** 签阅时间 */
    @Column(name = "sign_time")
    private LocalDateTime signTime;

    /** 签阅意见 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 导入时间 */
    @Column(name = "import_time")
    private LocalDateTime importTime;

    @PrePersist
    void prePersist() {
        if (this.importTime == null) {
            this.importTime = LocalDateTime.now();
        }
    }
}
