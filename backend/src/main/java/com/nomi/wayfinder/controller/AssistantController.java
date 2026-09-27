package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.assistant.AssistantService;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.assistant.AssistantService.MessageResponse;
import com.nomi.wayfinder.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/messages")
    public AssistantReply send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendMessageRequest request) {
        return assistantService.handle(CurrentUser.id(jwt), new AssistantRequest(
                request.message().trim(), request.latitude(), request.longitude(), request.routeId()));
    }

    @GetMapping("/messages")
    public List<MessageResponse> history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") @Positive @Max(200) int limit
    ) {
        return assistantService.history(CurrentUser.id(jwt), limit);
    }

    // latitude/longitude = the user's current GPS position
    public record SendMessageRequest(
            @NotBlank @Size(max = 1000) String message,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            Long routeId
    ) {
    }
}
