package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.admin.PlaceReviewService;
import com.nomi.wayfinder.admin.PlaceReviewService.ReportReason;
import com.nomi.wayfinder.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

// Signed-in users: "Bu mekan kapanmış olabilir" reports and suggestions for the app (both reach the owner's admin area)
@RestController
@RequestMapping("/api/v1")
public class UserReportController {

    public record ReportRequest(@NotNull ReportReason reason) {
    }

    public record FeedbackRequest(@NotBlank @Size(max = 1000) String message) {
    }

    private final PlaceReviewService service;

    public UserReportController(PlaceReviewService service) {
        this.service = service;
    }

    @PostMapping("/places/{id}/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                       @Valid @RequestBody ReportRequest request) {
        service.report(CurrentUser.id(jwt), id, request.reason());
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FeedbackRequest request) {
        service.feedback(CurrentUser.id(jwt), request.message());
    }
}
