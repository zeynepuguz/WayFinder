package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.billing.BillingService;
import com.nomi.wayfinder.billing.BillingService.AccessStatus;
import com.nomi.wayfinder.billing.BillingService.PlanResponse;
import com.nomi.wayfinder.entity.AccessPlan;
import com.nomi.wayfinder.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    // Public: the paywall is shown before login too
    @GetMapping("/plans")
    public PlansResponse plans() {
        return new PlansResponse(billingService.plans(), billingService.devMode());
    }

    @GetMapping("/me")
    public AccessStatus me(@AuthenticationPrincipal Jwt jwt) {
        return billingService.status(CurrentUser.id(jwt));
    }

    @PostMapping("/google-play/verify")
    public AccessStatus verifyGooglePlay(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GooglePlayPurchase request) {
        return billingService.verifyGooglePlay(CurrentUser.id(jwt), request.productId(), request.purchaseToken());
    }

    @PostMapping("/dev/purchase")
    public AccessStatus devPurchase(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DevPurchase request) {
        return billingService.devPurchase(CurrentUser.id(jwt), request.plan());
    }

    public record PlansResponse(List<PlanResponse> plans, boolean devMode) {
    }

    public record GooglePlayPurchase(
            @NotBlank @Size(max = 100) String productId,
            @NotBlank @Size(max = 512) String purchaseToken
    ) {
    }

    public record DevPurchase(@NotNull AccessPlan plan) {
    }
}
