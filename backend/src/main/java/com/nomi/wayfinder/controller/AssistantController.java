package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.assistant.AssistantConversationService;
import com.nomi.wayfinder.assistant.AssistantConversationService.ConversationSummary;
import com.nomi.wayfinder.assistant.AssistantConversationService.MessageResponse;
import com.nomi.wayfinder.assistant.AssistantService;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The assistant chat. Paid like the rest of /assistant (AccessInterceptor); only sending a message may call the AI
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantConversationService conversationService;

    public AssistantController(AssistantService assistantService, AssistantConversationService conversationService) {
        this.assistantService = assistantService;
        this.conversationService = conversationService;
    }

    // Without conversationId a new chat is started (never the latest one continued); the reply says which chat
    @PostMapping("/messages")
    public AssistantReply send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendMessageRequest request) {
        return assistantService.handle(CurrentUser.id(jwt), new AssistantRequest(
                request.message().trim(), request.latitude(), request.longitude(), request.routeId(),
                request.conversationId()));
    }

    // The newest messages across all chats (older app versions)
    @GetMapping("/messages")
    public List<MessageResponse> history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") @Positive @Max(200) int limit
    ) {
        return conversationService.history(CurrentUser.id(jwt), limit);
    }

    // "Sohbetler": newest first
    @GetMapping("/conversations")
    public List<ConversationSummary> conversations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") @Positive @Max(50) int limit
    ) {
        return conversationService.conversations(CurrentUser.id(jwt), limit);
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSummary createConversation(@AuthenticationPrincipal Jwt jwt) {
        return conversationService.createConversation(CurrentUser.id(jwt));
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageResponse> conversationMessages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestParam(defaultValue = "200") @Positive @Max(500) int limit
    ) {
        return conversationService.conversationMessages(CurrentUser.id(jwt), id, limit);
    }

    @PatchMapping("/conversations/{id}")
    public ConversationSummary renameConversation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody RenameConversationRequest request
    ) {
        return conversationService.renameConversation(CurrentUser.id(jwt), id, request.title());
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        conversationService.deleteConversation(CurrentUser.id(jwt), id);
    }

    // latitude/longitude = the user's current GPS position; conversationId = the chat to continue (null = new chat)
    public record SendMessageRequest(
            @NotBlank @Size(max = 1000) String message,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            Long routeId,
            Long conversationId
    ) {
    }

    public record RenameConversationRequest(@NotBlank @Size(max = 120) String title) {
    }
}
