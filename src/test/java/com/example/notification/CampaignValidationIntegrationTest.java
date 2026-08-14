package com.example.notification;

import com.example.notification.domain.CampaignRecipient;
import com.example.notification.mail.DeliveryLog;
import com.example.notification.repository.CampaignRecipientRepository;
import com.example.notification.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CampaignValidationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private DeliveryLog deliveryLog;

    @Test
    void rejectsBlankSubject() {
        ResponseEntity<String> response = postCampaign("  ", "Message", List.of("john@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_ERROR").contains("subject");
    }

    @Test
    void rejectsBlankMessage() {
        ResponseEntity<String> response = postCampaign("Subject", "", List.of("john@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_ERROR").contains("message");
    }

    @Test
    void rejectsEmptyRecipients() {
        ResponseEntity<String> response = postCampaign("Subject", "Message", List.of());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("recipients");
    }

    @Test
    void rejectsSubjectLongerThanColumnLimit() {
        // 255 = campaign.subject VARCHAR(255); one char over must be a clean 400,
        // not an insert-time DB constraint violation surfacing as 500
        ResponseEntity<String> response =
                postCampaign("s".repeat(256), "Message", List.of("john@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_ERROR").contains("subject");
    }

    @Test
    void acceptsSubjectAtExactlyTheColumnLimit() {
        ResponseEntity<String> response =
                postCampaign("s".repeat(255), "Message", List.of("john@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void rejectsEmailLongerThanColumnLimit() {
        // syntactically valid (passes @Email) but longer than users.email VARCHAR(320)
        String oversized = "a".repeat(310) + "@example.com";

        ResponseEntity<String> response = postCampaign("Subject", "Message", List.of(oversized));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("recipient email must not exceed");
    }

    @Test
    void rejectsInvalidEmailFormat() {
        ResponseEntity<String> response =
                postCampaign("Subject", "Message", List.of("john@example.com", "not-an-email"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("invalid email format");
    }

    @Test
    void rejectsUnknownRecipientWithWholeList() {
        long campaignsBefore = campaignRepository.count();

        ResponseEntity<String> response = postCampaign("Subject", "Message",
                List.of("john@example.com", "ghost@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("INVALID_RECIPIENTS").contains("ghost@example.com");
        // all-or-nothing: nothing was persisted
        assertThat(campaignRepository.count()).isEqualTo(campaignsBefore);
    }

    @Test
    void rejectsInactiveRecipientWithoutPersistingAnything() {
        long campaignsBefore = campaignRepository.count();

        ResponseEntity<String> response = postCampaign("Subject", "Message",
                List.of("john@example.com", "inactive@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("INVALID_RECIPIENTS").contains("inactive@example.com");
        // all-or-nothing also for the inactive case — consistent with unknown recipients
        assertThat(campaignRepository.count()).isEqualTo(campaignsBefore);
    }

    @Test
    void reportsUnknownAndInactiveRecipientsTogetherInOneResponse() {
        long campaignsBefore = campaignRepository.count();

        // one response must name BOTH problem groups — the caller fixes the request
        // in one round-trip instead of peeling errors one at a time
        ResponseEntity<String> response = postCampaign("Subject", "Message",
                List.of("john@example.com", "ghost@example.com", "inactive@example.com", "user97@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody())
                .contains("ghost@example.com")
                .contains("inactive@example.com")
                .contains("user97@example.com");
        assertThat(campaignRepository.count()).isEqualTo(campaignsBefore);
    }

    @Test
    void deduplicatesRepeatedAddressesCaseInsensitively() {
        UUID campaignId = createCampaign(
                List.of("john@example.com", "JOHN@example.com", "jane@example.com"));

        // total reflects unique recipients, duplicate is silently collapsed
        assertThat(getStatus(campaignId).total()).isEqualTo(2);
    }

    @Test
    void rejectsWhitespacePaddedEmailAsClientError() {
        // @Email bean validation fires before any service-level normalisation:
        // a padded address is a 400, not something we silently clean up
        ResponseEntity<String> response = postCampaign("Subject", "Message",
                List.of(" bob@example.com "));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("invalid email format");
    }

    @Test
    void deduplicatedAddressGetsExactlyOneRecipientRowAndOneDelivery() {
        UUID campaignId = createCampaign(
                List.of("bob@example.com", "BOB@example.com", "Bob@Example.com"));

        assertThat(getStatus(campaignId).total()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getStatus(campaignId).sent()).isEqualTo(1));

        // dedup happens BEFORE persisting: one row in the DB, one physical delivery —
        // a triple-listed address must not receive the same notification three times
        CampaignRecipient only = recipientRepository
                .findByCampaignIdAndEmail(campaignId, "bob@example.com").orElseThrow();
        assertThat(recipientRepository.findByCampaignIdOrderByEmail(campaignId)).hasSize(1);
        assertThat(deliveryLog.find(only.getId()).orElseThrow().delivered()).isEqualTo(1);
    }

    @Test
    void returnsNotFoundForUnknownCampaign() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/campaigns/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("CAMPAIGN_NOT_FOUND");
    }
}
