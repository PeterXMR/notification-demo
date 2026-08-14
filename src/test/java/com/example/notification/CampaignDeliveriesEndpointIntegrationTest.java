package com.example.notification;

import com.example.notification.api.dto.CampaignDeliveriesResponse;
import com.example.notification.domain.CampaignStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The demo/verification endpoint behind the "no uncontrolled repeated sending" criterion:
 * it exposes, per recipient, how often the provider was called vs how often the message
 * was actually delivered — so Postman can assert delivered == 1 for every sent recipient.
 */
class CampaignDeliveriesEndpointIntegrationTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test
    void reportsExactlyOneDeliveryPerSentRecipientEvenWithRetries() {
        UUID campaignId = createCampaign(
                List.of("john@example.com", "temp-fail@example.com", "bounce@example.com"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(getStatus(campaignId).status()).isEqualTo(CampaignStatus.COMPLETED));

        CampaignDeliveriesResponse response = getDeliveries(campaignId);

        assertThat(response.campaignId()).isEqualTo(campaignId);
        assertThat(response.recipients()).hasSize(3);

        var john = recipient(response, "john@example.com");
        assertThat(john.delivered()).isEqualTo(1);
        assertThat(john.providerCalls()).isEqualTo(1);
        assertThat(john.duplicatesSuppressed()).isZero();

        // retried recipient: several provider calls, but still exactly one delivery
        var tempFail = recipient(response, "temp-fail@example.com");
        assertThat(tempFail.delivered()).isEqualTo(1);
        assertThat(tempFail.providerCalls()).isEqualTo(3);
        assertThat(tempFail.duplicatesSuppressed()).isZero();

        // bounced recipient: called once, never delivered
        var bounce = recipient(response, "bounce@example.com");
        assertThat(bounce.delivered()).isZero();
        assertThat(bounce.providerCalls()).isEqualTo(1);

        // the campaign-level summary Postman asserts on: 3 recipients, 5 provider calls,
        // 2 deliveries, 0 duplicates
        assertThat(response.totalDelivered()).isEqualTo(2);
        assertThat(response.totalDuplicatesSuppressed()).isZero();
    }

    @Test
    void returns404ForUnknownCampaign() {
        var response = rest.getForEntity(
                "/api/campaigns/" + UUID.randomUUID() + "/deliveries", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("CAMPAIGN_NOT_FOUND");
    }

    private CampaignDeliveriesResponse getDeliveries(UUID campaignId) {
        var response = rest.getForEntity("/api/campaigns/" + campaignId + "/deliveries",
                CampaignDeliveriesResponse.class);
        if (response.getBody() == null) {
            throw new AssertionError("Expected deliveries body, got: " + response.getStatusCode());
        }
        return response.getBody();
    }

    private CampaignDeliveriesResponse.RecipientDeliveries recipient(
            CampaignDeliveriesResponse response, String email) {
        return response.recipients().stream()
                .filter(r -> r.email().equals(email))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing recipient " + email));
    }
}
