package com.nomi.wayfinder.billing;

import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.entity.AccessPass;
import com.nomi.wayfinder.entity.AccessPlan;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.repository.AccessPassRepository;
import com.nomi.wayfinder.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final AccessPassRepository passRepository;
    private final GooglePlayClient googlePlay;
    private final NomiProperties.Billing properties;
    private final UserRepository userRepository;
    private final Clock clock;

    public BillingService(
            AccessPassRepository passRepository,
            GooglePlayClient googlePlay,
            UserRepository userRepository,
            NomiProperties properties,
            Clock clock
    ) {
        this.passRepository = passRepository;
        this.userRepository = userRepository;
        this.googlePlay = googlePlay;
        this.properties = properties.billing();
        this.clock = clock;
    }

    public List<PlanResponse> plans() {
        return Arrays.stream(AccessPlan.values())
                .map(plan -> new PlanResponse(
                        plan,
                        plan.getLabel(),
                        plan.getProductId(),
                        price(plan),
                        days(plan)))
                .toList();
    }

    public boolean devMode() {
        return properties.devMode();
    }

    @Transactional(readOnly = true)
    public boolean hasAccess(Long userId) {
        return isFreeAccess(userId) || passRepository.hasAccessAt(userId, clock.instant());
    }

    @Transactional(readOnly = true)
    public AccessStatus status(Long userId) {
        if (isFreeAccess(userId)) {
            return AccessStatus.FREE;
        }
        Instant now = clock.instant();
        List<AccessPass> passes = passRepository.findUnexpired(userId, now);

        Optional<AccessPass> current = passes.stream()
                .filter(p -> !p.getStartsAt().isAfter(now))
                .findFirst();

        return new AccessStatus(
                current.isPresent(),
                current.map(AccessPass::getPlan).orElse(null),
                // With stacked passes, access lasts until the last one ends
                passes.isEmpty() ? null : passes.getFirst().getExpiresAt(),
                false
        );
    }

    // Accounts listed in FREE_ACCESS_EMAILS (e.g. the owner) never need a pass
    private boolean isFreeAccess(Long userId) {
        if (properties.freeAccessEmails() == null || properties.freeAccessEmails().isEmpty()) {
            return false;
        }
        return userRepository.findById(userId).map(user -> properties.isFreeAccess(user.getEmail())).orElse(false);
    }

    /**
     * Called by the app after Google Play reports a purchase. Idempotent: sending the same
     * token again (app retry, reinstall + restore) returns the current status without a new pass.
     */
    @Transactional
    public AccessStatus verifyGooglePlay(Long userId, String productId, String purchaseToken) {
        AccessPlan plan;
        try {
            plan = AccessPlan.fromProductId(productId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Unknown product");
        }

        Optional<AccessPass> existing = passRepository.findByPurchaseToken(purchaseToken);
        if (existing.isPresent()) {
            if (!existing.get().getUserId().equals(userId)) {
                throw new BusinessException(HttpStatus.CONFLICT, "Purchase belongs to another account");
            }
            return status(userId);
        }

        if (!googlePlay.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "Purchase verification is not configured");
        }

        GooglePlayClient.ProductPurchase purchase;
        try {
            purchase = googlePlay.getProductPurchase(productId, purchaseToken);
        } catch (HttpClientErrorException e) {
            log.warn("Google Play rejected purchase token for user {}: {}", userId, e.getStatusCode());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Purchase could not be verified");
        }

        if (purchase == null || purchase.purchaseState() == null) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "Empty response from Google Play");
        }
        if (purchase.purchaseState() == 2) {
            throw new BusinessException(HttpStatus.ACCEPTED, "Payment is pending");
        }
        if (purchase.purchaseState() != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Purchase was canceled");
        }
        // The app sends the user id as obfuscatedAccountId when it starts the purchase
        String owner = purchase.obfuscatedExternalAccountId();
        if (owner != null && !owner.equals(String.valueOf(userId))) {
            throw new BusinessException(HttpStatus.CONFLICT, "Purchase belongs to another account");
        }

        grant(userId, plan, AccessPass.Provider.GOOGLE_PLAY, productId, purchaseToken, purchase.orderId());

        if (purchase.acknowledgementState() != null && purchase.acknowledgementState() == 0) {
            try {
                googlePlay.acknowledge(productId, purchaseToken);
            } catch (Exception e) {
                // The app also consumes the purchase; log and keep the granted access
                log.warn("Acknowledge failed for order {}: {}", purchase.orderId(), e.getMessage());
            }
        }

        log.info("Granted {} pass to user {} (order {})", plan, userId, purchase.orderId());
        return status(userId);
    }

    // Local testing only: pretends a purchase happened
    @Transactional
    public AccessStatus devPurchase(Long userId, AccessPlan plan) {
        if (!properties.devMode()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "Not found");
        }
        grant(userId, plan, AccessPass.Provider.DEV, plan.getProductId(), "dev-" + UUID.randomUUID(), null);
        return status(userId);
    }

    private void grant(Long userId, AccessPlan plan, AccessPass.Provider provider,
                       String productId, String token, String orderId) {
        Instant now = clock.instant();

        // Stack on top of time the user already paid for
        Instant startsAt = passRepository.findUnexpired(userId, now).stream()
                .map(AccessPass::getExpiresAt)
                .findFirst()
                .filter(end -> end.isAfter(now))
                .orElse(now);

        AccessPass pass = new AccessPass();
        pass.setUserId(userId);
        pass.setPlan(plan);
        pass.setProvider(provider);
        pass.setProductId(productId);
        pass.setPurchaseToken(token);
        pass.setOrderId(orderId);
        pass.setPriceTry(price(plan));
        pass.setStartsAt(startsAt);
        // Calendar based in the city's time zone: "1 month" from 31 Jan ends 28/29 Feb
        pass.setExpiresAt(ZonedDateTime.ofInstant(startsAt, clock.getZone()).plus(plan.getDuration()).toInstant());
        passRepository.save(pass);
    }

    private int price(AccessPlan plan) {
        Integer price = properties.prices() == null ? null : properties.prices().get(plan);
        if (price == null) {
            throw new IllegalStateException("No price configured for " + plan);
        }
        return price;
    }

    private static int days(AccessPlan plan) {
        return switch (plan) {
            case DAILY -> 1;
            case WEEKLY -> 7;
            case MONTHLY -> 30;
            case YEARLY -> 365;
        };
    }

    public record PlanResponse(AccessPlan plan, String label, String productId, int priceTry, int days) {
    }

    /**
     * @param active    the user can use paid features right now
     * @param plan      plan of the pass running now
     * @param expiresAt when paid access ends (including stacked passes)
     * @param free      unlimited access granted by FREE_ACCESS_EMAILS, not by a purchase
     */
    public record AccessStatus(boolean active, AccessPlan plan, Instant expiresAt, boolean free) {

        public static final AccessStatus NONE = new AccessStatus(false, null, null, false);
        public static final AccessStatus FREE = new AccessStatus(true, null, null, true);
    }
}
