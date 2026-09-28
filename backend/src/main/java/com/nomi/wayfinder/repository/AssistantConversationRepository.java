package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.AssistantConversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {

    // Filtering by user id means other users' chats look like they do not exist
    Optional<AssistantConversation> findByIdAndUserId(Long id, Long userId);

    List<AssistantConversation> findByUserIdOrderByLastMessageAtDescIdDesc(Long userId, Pageable pageable);
}
