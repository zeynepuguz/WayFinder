package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.admin.PlaceReviewService;
import com.nomi.wayfinder.admin.PlaceReviewService.Action;
import com.nomi.wayfinder.admin.PlaceReviewService.Counts;
import com.nomi.wayfinder.admin.PlaceReviewService.Feedback;
import com.nomi.wayfinder.admin.PlaceReviewService.ReviewedPlace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The owner's admin area (SecurityConfig: /api/v1/admin/** needs ROLE_ADMIN)
@RestController
@RequestMapping("/api/v1/admin/review")
public class ReviewAdminController {

    public record ReviewRequest(@NotNull Action action) {
    }

    private final PlaceReviewService service;

    public ReviewAdminController(PlaceReviewService service) {
        this.service = service;
    }

    @GetMapping("/counts")
    public Counts counts() {
        return service.counts();
    }

    // Kaldır / Şüpheli / Normale döndür (also "geri al" for a removed place)
    @PostMapping("/places/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        service.review(id, request.action());
    }

    // Silinen mekanlar of a city (null = all)
    @GetMapping("/removed")
    public List<ReviewedPlace> removed(@RequestParam(required = false) String city) {
        return service.reviewed("REMOVED", city);
    }

    @GetMapping("/suspects")
    public List<ReviewedPlace> suspects(@RequestParam(required = false) String city) {
        return service.reviewed("SUSPECT", city);
    }

    @GetMapping("/reports")
    public List<ReviewedPlace> reports() {
        return service.openReports();
    }

    @PostMapping("/reports/{placeId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable long placeId) {
        service.dismissReports(placeId);
    }

    @GetMapping("/feedback")
    public List<Feedback> feedback() {
        return service.feedbackList();
    }

    @PostMapping("/feedback/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable long id) {
        service.markFeedbackRead(id);
    }
}
