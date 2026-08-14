package com.example.notification;

import com.example.notification.domain.Campaign;
import com.example.notification.domain.CampaignRecipient;
import com.example.notification.domain.CampaignStatus;
import com.example.notification.domain.RecipientStatus;
import com.example.notification.domain.User;
import com.example.notification.repository.CampaignRecipientRepository;
import com.example.notification.repository.CampaignRepository;
import com.example.notification.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Restart-safety: campaigns whose in-memory dispatch was lost (restart, crash) are
 * finished by the recovery poller. Rows are inserted directly — no event is published,
 * exactly the situation after an application restart.
 */
class RecoveryPollerIntegrationTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void pollerFinishesCampaignThatLostItsDispatch() {
        UUID campaignId = insertCampaignWithPendingRecipient("john@example.com");

        // nobody dispatched this campaign — only the poller can pick it up
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var status = getStatus(campaignId);
            assertThat(status.status()).isEqualTo(CampaignStatus.COMPLETED);
            assertThat(status.sent()).isEqualTo(1);
        });
    }

    @Test
    void pollerRecoversRecipientStuckInSending() {
        UUID campaignId = insertCampaignWithPendingRecipient("jane@example.com");
        CampaignRecipient recipient = recipientRepository
                .findByCampaignIdAndEmail(campaignId, "jane@example.com").orElseThrow();

        // simulate a worker that died mid-send: SENDING, last touched an hour ago
        jdbc.update("""
                UPDATE campaign_recipient
                SET status = 'SENDING', updated_at = now() - INTERVAL '1 hour'
                WHERE id = ?
                """, recipient.getId());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            CampaignRecipient recovered = recipientRepository.findById(recipient.getId()).orElseThrow();
            assertThat(recovered.getStatus()).isEqualTo(RecipientStatus.SENT);
        });
    }

    private UUID insertCampaignWithPendingRecipient(String email) {
        User user = userRepository.findByEmailIn(List.of(email)).getFirst();
        Campaign campaign = campaignRepository.save(new Campaign("Recovery subject", "Recovery message"));
        recipientRepository.save(CampaignRecipient.pending(campaign, user));
        return campaign.getId();
    }
}
