package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.entity.AssistantConversation;
import com.nomi.wayfinder.entity.AssistantMessage;
import com.nomi.wayfinder.entity.MessageRole;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.repository.AssistantConversationRepository;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.RouteService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * The user's assistant chats ("Sohbetler"): list, create, read, rename and delete them, and the message history.
 * Sending a message (and the AI behind it) is AssistantService.
 */
@Service
public class AssistantConversationService {

    static final int PREVIEW_LENGTH = 90;
    static final int MAX_TITLE_LENGTH = 120;

    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;
    private final RouteService routeService;

    public AssistantConversationService(AssistantConversationRepository conversationRepository,
                                        AssistantMessageRepository messageRepository, RouteService routeService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.routeService = routeService;
    }

    // Latest messages of the user across all chats (the app's old single chat screen)
    @Transactional(readOnly = true)
    public List<MessageResponse> history(Long userId, int limit) {
        return toResponses(messageRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit)));
    }

    // Not read-only: reading a chat's route may expire it (past day)
    @Transactional
    public List<ConversationSummary> conversations(Long userId, int limit) {
        List<AssistantConversation> conversations =
                conversationRepository.findByUserIdOrderByLastMessageAtDescIdDesc(userId, PageRequest.of(0, limit));
        if (conversations.isEmpty()) {
            return List.of();
        }

        Map<Long, String> previews = new HashMap<>();
        messageRepository.findLatestByConversationIds(conversations.stream().map(AssistantConversation::getId).toList())
                .forEach(m -> previews.put(m.getConversationId(), shorten(m.getContent(), PREVIEW_LENGTH)));

        Map<Long, String> routeTitles = new HashMap<>();
        conversations.stream()
                .map(AssistantConversation::getRouteId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(routeId -> routeService.findRouteEntity(userId, routeId)
                        .ifPresent(r -> routeTitles.put(routeId, r.getTitle())));

        return conversations.stream()
                .map(c -> summary(c, routeTitles.get(c.getRouteId()), previews.get(c.getId())))
                .toList();
    }

    @Transactional
    public ConversationSummary createConversation(Long userId) {
        return summary(conversationRepository.save(new AssistantConversation(userId)), null, null);
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> conversationMessages(Long userId, Long conversationId, int limit) {
        AssistantConversation conversation = owned(conversationRepository, userId, conversationId);
        return toResponses(messageRepository.findByConversationIdOrderByCreatedAtDescIdDesc(
                conversation.getId(), PageRequest.of(0, limit)));
    }

    @Transactional
    public ConversationSummary renameConversation(Long userId, Long conversationId, String title) {
        AssistantConversation conversation = owned(conversationRepository, userId, conversationId);
        conversation.setTitle(shorten(title, MAX_TITLE_LENGTH));
        String routeTitle = conversation.getRouteId() == null ? null
                : routeService.findRouteEntity(userId, conversation.getRouteId()).map(Route::getTitle).orElse(null);
        String preview = messageRepository.findLatestByConversationIds(List.of(conversation.getId())).stream()
                .findFirst().map(m -> shorten(m.getContent(), PREVIEW_LENGTH)).orElse(null);
        return summary(conversation, routeTitle, preview);
    }

    // Deletes the chat and its messages; the route it planned stays in "Rotalarım"
    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        conversationRepository.delete(owned(conversationRepository, userId, conversationId));
    }

    // The user's chat; filtering by user id means other users' chats look like they do not exist
    static AssistantConversation owned(AssistantConversationRepository conversations, Long userId, Long conversationId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found with id: " + conversationId));
    }

    // One line of at most max characters, cut at a word boundary when there is one in the second half
    static String shorten(String text, int max) {
        String flat = text.strip().replaceAll("\\s+", " ");
        if (flat.length() <= max) {
            return flat;
        }
        // max - 1 characters leave room for "…"; the cut is at a word boundary when the next character is a space
        String cut = flat.substring(0, max - 1);
        int space = cut.lastIndexOf(' ');
        if (flat.charAt(max - 1) != ' ' && space >= max / 2) {
            cut = cut.substring(0, space);
        }
        return cut.replaceAll("[\\s,.;:!?-]+$", "") + "…";
    }

    private static ConversationSummary summary(AssistantConversation c, String routeTitle, String preview) {
        return new ConversationSummary(c.getId(), c.getTitle(), c.getRouteId(), routeTitle, c.getLastMessageAt(), preview);
    }

    private static List<MessageResponse> toResponses(List<AssistantMessage> newestFirst) {
        List<AssistantMessage> messages = new ArrayList<>(newestFirst);
        Collections.reverse(messages);
        return messages.stream()
                .map(m -> new MessageResponse(m.getId(), m.getRole(), m.getContent(), m.getRouteId(),
                        m.getConversationId(), m.getCreatedAt()))
                .toList();
    }

    public record MessageResponse(Long id, MessageRole role, String content, Long routeId, Long conversationId,
                                  Instant createdAt) {
    }

    // One row of "Sohbetler". title is null until the first message; preview = the newest message, shortened
    public record ConversationSummary(Long id, String title, Long routeId, String routeTitle, Instant lastMessageAt,
                                      String preview) {
    }
}
