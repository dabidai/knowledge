package com.knowledge.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 对话消息实体 —— 一条消息属于一个对话 */
@Entity
@Table(name = "conversation_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属对话 ID */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 角色: user / assistant */
    @Column(nullable = false, length = 20)
    private String role;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 来源文档信息 (JSON) */
    @Column(columnDefinition = "TEXT")
    private String sources;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
