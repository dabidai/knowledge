package com.knowledge.repository;

import com.knowledge.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    /** 按用户查询所有对话，按更新时间倒序 */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
