package com.example.notification;

import com.example.notification.api.dto.CampaignStatusResponse;
import com.example.notification.api.dto.CreateCampaignRequest;
import com.example.notification.domain.CampaignStatus;
import com.example.notification.domain.User;
import com.example.notification.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The recipient limit is {@link CreateCampaignRequest#MAX_RECIPIENTS} = 100. */
class CampaignLimitIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    private List<String> bulkEmails;

    @BeforeEach
    void seedBulkUsers() {
        bulkEmails = IntStream.rangeClosed(1, CreateCampaignRequest.MAX_RECIPIENTS)
                .mapToObj(i -> "bulk" + i + "@load.test")
                .toList();
        List<String> missing = bulkEmails.stream()
                .filter(email -> userRepository.findByEmailIn(List.of(email)).isEmpty())
                .toList();
        userRepository.saveAll(missing.stream()
                .map(email -> new User(UUID.randomUUID(), email, true))
                .toList());
    }

    @Test
    void acceptsCampaignWithExactlyMaxRecipientsAndCompletesIt() {
        assertThat(bulkEmails).hasSize(100);

        UUID campaignId = createCampaign(bulkEmails);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            CampaignStatusResponse status = getStatus(campaignId);
            assertThat(status.status()).isEqualTo(CampaignStatus.COMPLETED);
        });

        CampaignStatusResponse finalStatus = getStatus(campaignId);
        assertThat(finalStatus.total()).isEqualTo(100);
        assertThat(finalStatus.sent()).isEqualTo(100);
        assertThat(finalStatus.failed()).isZero();
    }

    @Test
    void rejectsCampaignExceedingMaxRecipients() {
        // 101 syntactically valid addresses — the size limit must fire before any DB lookup
        List<String> tooMany = IntStream.rangeClosed(1, CreateCampaignRequest.MAX_RECIPIENTS + 1)
                .mapToObj(i -> "overflow" + i + "@load.test")
                .toList();

        ResponseEntity<String> response = postCampaign("Subject", "Message", tooMany);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("recipients must not exceed 100");
    }
}
