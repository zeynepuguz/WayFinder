package com.nomi.wayfinder.billing;

import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.entity.AccessPass;
import com.nomi.wayfinder.entity.AccessPlan;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.repository.AccessPassRepository;
import com.nomi.wayfinder.entity.User;
import com.nomi.wayfinder.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final Instant NOW = LocalDateTime.of(2026, 9, 27, 12, 0).atZone(ISTANBUL).toInstant();
    private static final Map<AccessPlan, Integer> PRICES = Map.of(
            AccessPlan.DAILY, 25, AccessPlan.WEEKLY, 79, AccessPlan.MONTHLY, 199, AccessPlan.YEARLY, 1499);

    private final List<AccessPass> saved = new ArrayList<>();
    private AccessPassRepository repository;
    private GooglePlayClient googlePlay;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        repository = mock(AccessPassRepository.class);
        googlePlay = mock(GooglePlayClient.class);
        users = mock(UserRepository.class);
        when(googlePlay.isConfigured()).thenReturn(true);

        // Tiny in-memory repository
        when(repository.save(any())).thenAnswer(inv -> {
            AccessPass pass = inv.getArgument(0);
            ReflectionTestUtils.setField(pass, "id", (long) saved.size() + 1);
            saved.add(pass);
            return pass;
        });
        when(repository.findUnexpired(anyLong(), any())).thenAnswer(inv -> saved.stream()
                .filter(p -> p.getUserId().equals(inv.getArgument(0)))
                .filter(p -> p.getExpiresAt().isAfter(inv.getArgument(1)))
                .sorted(Comparator.comparing(AccessPass::getExpiresAt).reversed())
                .toList());
        when(repository.findByPurchaseToken(anyString())).thenAnswer(inv -> saved.stream()
                .filter(p -> p.getPurchaseToken().equals(inv.getArgument(0))).findFirst());
    }

    private BillingService service(boolean devMode) {
        NomiProperties properties = new NomiProperties("Europe/Istanbul", null, null, null, null,
                new NomiProperties.Billing(devMode, "com.nomi.app", "", PRICES, List.of(" Owner@Example.com ")), null);
        return new BillingService(repository, googlePlay, users, properties, Clock.fixed(NOW, ISTANBUL));
    }

    private void googleSays(int state, String owner) {
        when(googlePlay.getProductPurchase(anyString(), anyString()))
                .thenReturn(new GooglePlayClient.ProductPurchase(state, 0, 0, "GPA.1", "0", owner, "TR"));
    }

    @Test
    void verifiedPurchaseGrantsAccessForThePlanDuration() {
        googleSays(0, "7");

        BillingService.AccessStatus status = service(false).verifyGooglePlay(7L, "nomi_pass_weekly", "token-1");

        assertThat(status.active()).isTrue();
        assertThat(status.plan()).isEqualTo(AccessPlan.WEEKLY);
        assertThat(status.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(saved.getFirst().getPriceTry()).isEqualTo(79);
        verify(googlePlay).acknowledge("nomi_pass_weekly", "token-1");
    }

    @Test
    void buyingAgainStacksOnTopOfRemainingTime() {
        googleSays(0, null);
        BillingService billing = service(false);

        billing.verifyGooglePlay(7L, "nomi_pass_daily", "token-1");
        BillingService.AccessStatus status = billing.verifyGooglePlay(7L, "nomi_pass_weekly", "token-2");

        assertThat(status.plan()).isEqualTo(AccessPlan.DAILY);
        assertThat(status.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(8)));
    }

    @Test
    void sameTokenTwiceDoesNotGrantTwice() {
        googleSays(0, null);
        BillingService billing = service(false);

        billing.verifyGooglePlay(7L, "nomi_pass_daily", "token-1");
        billing.verifyGooglePlay(7L, "nomi_pass_daily", "token-1");

        assertThat(saved).hasSize(1);
        verify(googlePlay, times(1)).getProductPurchase(anyString(), anyString());
    }

    @Test
    void tokenOfAnotherAccountIsRejected() {
        googleSays(0, null);
        BillingService billing = service(false);
        billing.verifyGooglePlay(7L, "nomi_pass_daily", "token-1");

        assertThatThrownBy(() -> billing.verifyGooglePlay(8L, "nomi_pass_daily", "token-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void purchaseMadeForAnotherUserIdIsRejected() {
        googleSays(0, "99");

        assertThatThrownBy(() -> service(false).verifyGooglePlay(7L, "nomi_pass_daily", "token-1"))
                .isInstanceOf(BusinessException.class);
        assertThat(saved).isEmpty();
    }

    @Test
    void pendingOrCanceledPaymentsGiveNoAccess() {
        googleSays(2, null);
        assertThatThrownBy(() -> service(false).verifyGooglePlay(7L, "nomi_pass_daily", "t1"))
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.ACCEPTED);

        googleSays(1, null);
        assertThatThrownBy(() -> service(false).verifyGooglePlay(7L, "nomi_pass_daily", "t2"))
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(saved).isEmpty();
    }

    @Test
    void devPurchaseOnlyWorksInDevMode() {
        assertThatThrownBy(() -> service(false).devPurchase(7L, AccessPlan.MONTHLY))
                .isInstanceOf(BusinessException.class);

        BillingService.AccessStatus status = service(true).devPurchase(7L, AccessPlan.MONTHLY);
        assertThat(status.expiresAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 27, 12, 0).atZone(ISTANBUL).toInstant());
    }

    @Test
    void freeAccessAccountsNeverNeedAPass() {
        User owner = new User();
        owner.setEmail("owner@example.com");
        User other = new User();
        other.setEmail("someone@example.com");
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(users.findById(2L)).thenReturn(Optional.of(other));

        assertThat(service(false).hasAccess(1L)).isTrue();
        assertThat(service(false).status(1L)).isEqualTo(BillingService.AccessStatus.FREE);
        assertThat(service(false).hasAccess(2L)).isFalse();
        assertThat(service(false).status(2L).active()).isFalse();
    }

    @Test
    void refundedPurchaseLosesItsAccess() {
        googleSays(0, null);
        service(false).verifyGooglePlay(7L, "nomi_pass_weekly", "token-r");
        when(repository.hasAccessAt(eq(7L), any())).thenAnswer(inv -> saved.stream()
                .anyMatch(p -> p.getStatus() == AccessPass.Status.ACTIVE && p.getExpiresAt().isAfter(inv.getArgument(1))));
        assertThat(service(false).hasAccess(7L)).isTrue();

        assertThat(service(false).revokePurchase("token-r")).isTrue();
        assertThat(service(false).revokePurchase("token-r")).isFalse();
        assertThat(service(false).revokePurchase("unknown")).isFalse();
        assertThat(service(false).hasAccess(7L)).isFalse();
    }
}
