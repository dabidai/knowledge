package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.entity.Conversation;
import com.knowledge.entity.ConversationMessage;
import com.knowledge.entity.User;
import com.knowledge.service.ConversationService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 对话历史控制器 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /** 列出当前用户的所有对话 */
    @GetMapping
    public ApiResponse<List<ConvItem>> list(@AuthenticationPrincipal User user) {
        List<Conversation> convs = conversationService.listConversations(user.getId());
        List<ConvItem> items = convs.stream()
                .map(c -> ConvItem.builder()
                        .id(c.getId())
                        .title(c.getTitle())
                        .updatedAt(c.getUpdatedAt())
                        .createdAt(c.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
        return ApiResponse.ok(items);
    }

    /** 获取某个对话的全部消息 */
    @GetMapping("/{id}/messages")
    public ApiResponse<List<MsgItem>> getMessages(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        List<ConversationMessage> msgs = conversationService.getMessages(id, user.getId());
        List<MsgItem> items = msgs.stream()
                .map(m -> MsgItem.builder()
                        .id(m.getId())
                        .role(m.getRole())
                        .content(m.getContent())
                        .sources(m.getSources())
                        .createdAt(m.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
        return ApiResponse.ok(items);
    }

    /** 创建新对话 */
    @PostMapping
    public ApiResponse<ConvItem> create(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User user) {
        String title = body.getOrDefault("title", "新对话");
        Conversation conv = conversationService.createConversation(user.getId(), title);
        return ApiResponse.ok(ConvItem.builder()
                .id(conv.getId())
                .title(conv.getTitle())
                .updatedAt(conv.getUpdatedAt())
                .createdAt(conv.getCreatedAt())
                .build());
    }

    /** 删除对话 */
    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        conversationService.deleteConversation(id, user.getId());
        return ApiResponse.ok("已删除");
    }

    // ──── DTO ────

    @Data
    @Builder
    public static class ConvItem {
        private Long id;
        private String title;
        private java.time.LocalDateTime updatedAt;
        private java.time.LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class MsgItem {
        private Long id;
        private String role;
        private String content;
        private String sources;
        private java.time.LocalDateTime createdAt;
    }
}
