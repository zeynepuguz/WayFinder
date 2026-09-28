package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.AssistantMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    List<AssistantMessage> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    List<AssistantMessage> findByConversationIdOrderByCreatedAtDescIdDesc(Long conversationId, Pageable pageable);

    // The newest message of each chat (list previews)
    @Query(value = """
            SELECT DISTINCT ON (conversation_id) *
            FROM assistant_messages
            WHERE conversation_id IN (:conversationIds)
            ORDER BY conversation_id, created_at DESC, id DESC
            """, nativeQuery = true)
    List<AssistantMessage> findLatestByConversationIds(@Param("conversationIds") Collection<Long> conversationIds);
}
