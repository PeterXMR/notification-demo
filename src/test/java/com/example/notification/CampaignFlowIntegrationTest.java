package com.example.notification;

import com.example.notification.api.dto.CampaignStatusResponse;
import com.example.notification.config.NotificationProperties;
import com.example.notification.domain.CampaignRecipient;
import com.example.notification.domain.CampaignStatus;
import com.example.notification.domain.RecipientStatus;
import com.example.notification.mail.DeliveryLog;
import com.example.notification.repository.CampaignRecipientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end behaviour of the async pipeline: immediate acceptance, observable
 * PROCESSING, retries on transient failures, permanent failures isolated per recipient.
 */
class CampaignFlowIntegrationTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private NotificationProperties properties;

    @Autowired
    private DeliveryLog deliveryLog;

    @Test
    void acceptsCampaignImmediatelyAndReportsProcessing() {
        // slow@ guarantees the campaign is still PROCESSING when we query right after POST
        UUID campaignId = createCampaign(List.of("john@example.com", "jane@example.com", "slow@example.com"));

        CampaignStatusResponse status = getStatus(campaignId);

        assertThat(status.campaignId()).isEqualTo(campaignId);
        assertThat(status.status()).isEqualTo(CampaignStatus.PROCESSING);
        assertThat(status.total()).isEqualTo(3);
        assertThat(status.remaining()).isGreaterThan(0);

        awaitCompleted(campaignId, 3, 0);
    }

    @Test
    void completesCampaignWithAllRecipientsSent() {
        UUID campaignId = createCampaign(List.of("john@example.com", "jane@example.com", "alice@example.com"));

        CampaignStatusResponse finalStatus = awaitCompleted(campaignId, 3, 0);
        assertThat(finalStatus.remaining()).isZero();
    }

    @Test
    void retriesTransientFailureAndEventuallySends() {
        UUID campaignId = createCampaign(List.of("temp-fail@example.com"));

        awaitCompleted(campaignId, 1, 0);

        // 2 transient failures + 1 success = 3 attempts, bounded retry demonstrated
        CampaignRecipient recipient = recipientRepository
                .findByCampaignIdAndEmail(campaignId, "temp-fail@example.com").orElseThrow();
        assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(recipient.getAttempts()).isEqualTo(3);
        assertThat(recipient.getLastError()).contains("451");
    }

    @Test
    void transientFailuresAreBoundedByMaxAttemptsAndEndAsFailed() {
        int maxAttempts = properties.retry().maxAttempts();

        // always-fail@ answers 451 on EVERY attempt — the only thing that can stop it
        // is the retry budget, so this is the direct test of "limited number of retries"
        UUID campaignId = createCampaign(List.of("always-fail@example.com", "john@example.com"));

        awaitCompleted(campaignId, 1, 1);

        CampaignRecipient exhausted = recipientRepository
                .findByCampaignIdAndEmail(campaignId, "always-fail@example.com").orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(exhausted.getAttempts()).isEqualTo(maxAttempts);
        assertThat(exhausted.getLastError()).contains("retries exhausted");

        // the provider was called exactly maxAttempts times and never once delivered —
        // retrying stopped, it did not merely slow down
        DeliveryLog.Delivery delivery = deliveryLog.find(exhausted.getId()).orElseThrow();
        assertThat(delivery.providerCalls()).isEqualTo(maxAttempts);
        assertThat(delivery.delivered()).isZero();
    }

    @Test
    void exhaustedRecipientStaysFailedAndIsNeverRetriedAgain() {
        UUID campaignId = createCampaign(List.of("always-fail@example.com"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(getStatus(campaignId).failed()).isEqualTo(1));

        UUID recipientId = recipientRepository
                .findByCampaignIdAndEmail(campaignId, "always-fail@example.com").orElseThrow().getId();
        int callsAtExhaustion = deliveryLog.find(recipientId).orElseThrow().providerCalls();

        // FAILED is terminal: several poller cycles later the provider has not been
        // called again — no runaway retry loop after the budget is spent
        await().pollDelay(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deliveryLog.find(recipientId).orElseThrow().providerCalls())
                        .isEqualTo(callsAtExhaustion));
    }

    @Test
    void permanentFailureIsRecordedAndDoesNotStopOthers() {
        UUID campaignId = createCampaign(
                List.of("john@example.com", "bounce@example.com", "jane@example.com"));

        awaitCompleted(campaignId, 2, 1);

        // permanently rejected: terminal after the FIRST attempt — no endless retrying
        CampaignRecipient bounced = recipientRepository
                .findByCampaignIdAndEmail(campaignId, "bounce@example.com").orElseThrow();
        assertThat(bounced.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(bounced.getAttempts()).isEqualTo(1);
        assertThat(bounced.getLastError()).contains("550");
    }

    private CampaignStatusResponse awaitCompleted(UUID campaignId, long expectedSent, long expectedFailed) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            CampaignStatusResponse status = getStatus(campaignId);
            assertThat(status.status()).isEqualTo(CampaignStatus.COMPLETED);
            assertThat(status.sent()).isEqualTo(expectedSent);
            assertThat(status.failed()).isEqualTo(expectedFailed);
        });
        return getStatus(campaignId);
    }
}
