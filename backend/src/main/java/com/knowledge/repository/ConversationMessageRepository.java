package com.knowledge.repository;

import com.knowledge.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {
    /** 按对话 ID 查询所有消息，按时间正序 */
    List<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /** 删除对话下所有消息 */
    void deleteByConversationId(Long conversationId);
}
