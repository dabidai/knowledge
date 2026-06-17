package com.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.entity.Conversation;
import com.knowledge.entity.ConversationMessage;
import com.knowledge.repository.ConversationMessageRepository;
import com.knowledge.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository convRepo;
    private final ConversationMessageRepository msgRepo;
    private final ObjectMapper objectMapper;

    /** 列出用户的所有对话 */
    public List<Conversation> listConversations(Long userId) {
        return convRepo.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /** 获取对话的全部消息 */
    public List<ConversationMessage> getMessages(Long conversationId, Long userId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("对话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该对话");
        }
        return msgRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** 创建新对话 */
    @Transactional
    public Conversation createConversation(Long userId, String firstQuestion) {
        String title = firstQuestion;
        if (title.length() > 50) title = title.substring(0, 50) + "...";
        Conversation conv = Conversation.builder()
                .title(title)
                .userId(userId)
                .build();
        return convRepo.save(conv);
    }

    /** 添加一条消息到对话 */
    @Transactional
    public ConversationMessage addMessage(Long conversationId, String role,
                                          String content, List<?> sources) {
        String sourcesJson = null;
        if (sources != null && !sources.isEmpty()) {
            try {
                sourcesJson = objectMapper.writeValueAsString(sources);
            } catch (JsonProcessingException e) {
                log.warn("序列化 sources 失败", e);
            }
        }

        ConversationMessage msg = ConversationMessage.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .sources(sourcesJson)
                .build();
        ConversationMessage saved = msgRepo.save(msg);

        // 更新对话最后活跃时间
        convRepo.findById(conversationId).ifPresent(conv -> {
            conv.setUpdatedAt(saved.getCreatedAt());
            convRepo.save(conv);
        });

        return saved;
    }

    /** 重命名对话 */
    @Transactional
    public void renameConversation(Long conversationId, Long userId, String newTitle) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("对话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权修改该对话");
        }
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String title = newTitle.trim();
        if (title.length() > 100) title = title.substring(0, 100);
        conv.setTitle(title);
        convRepo.save(conv);
    }

    /** 删除对话及其所有消息 */
    @Transactional
    public void deleteConversation(Long conversationId, Long userId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("对话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权删除该对话");
        }
        msgRepo.deleteByConversationId(conversationId);
        convRepo.delete(conv);
    }
}
