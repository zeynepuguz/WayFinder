package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.AssistantMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    List<AssistantMessage> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
