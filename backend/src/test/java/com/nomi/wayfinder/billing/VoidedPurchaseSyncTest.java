package com.nomi.wayfinder.billing;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoidedPurchaseSyncTest {

    private static final Instant NOW = Instant.parse("2026-09-27T09:00:00Z");

    private final GooglePlayClient googlePlay = mock(GooglePlayClient.class);
    private final BillingService billing = mock(BillingService.class);
    private final VoidedPurchaseSync sync = new VoidedPurchaseSync(googlePlay, billing, Clock.fixed(NOW, ZoneOffset.UTC));

    private static GooglePlayClient.VoidedPurchase voided(String token) {
        return new GooglePlayClient.VoidedPurchase(token, "GPA." + token, "0", 1);
    }

    @Test
    void revokesEveryVoidedPurchaseAcrossPages() {
        when(googlePlay.getVoidedPurchases(anyLong(), isNull())).thenReturn(new GooglePlayClient.VoidedPurchasesPage(
                List.of(voided("a"), voided("b")), new GooglePlayClient.TokenPagination("page-2")));
        when(googlePlay.getVoidedPurchases(anyLong(), eq("page-2"))).thenReturn(new GooglePlayClient.VoidedPurchasesPage(
                List.of(voided("c")), null));
        when(billing.revokePurchase(anyString())).thenReturn(true);
        when(billing.revokePurchase("b")).thenReturn(false);

        assertThat(sync.sync()).isEqualTo(2);
        verify(billing).revokePurchase("c");
        // Only asks for the window Google keeps (last 30 days)
        verify(googlePlay).getVoidedPurchases(longThat(t -> t > NOW.minusSeconds(30L * 24 * 3600).toEpochMilli()), isNull());
    }

    @Test
    void doesNothingUntilGooglePlayIsConfigured() {
        when(googlePlay.isConfigured()).thenReturn(false);

        sync.run();

        verify(googlePlay, never()).getVoidedPurchases(anyLong(), any());
    }
}
