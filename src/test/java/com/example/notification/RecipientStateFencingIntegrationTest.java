package com.example.notification;

import com.example.notification.domain.Campaign;
import com.example.notification.domain.CampaignRecipient;
import com.example.notification.domain.RecipientStatus;
import com.example.notification.domain.User;
import com.example.notification.repository.CampaignRecipientRepository;
import com.example.notification.repository.CampaignRepository;
import com.example.notification.repository.UserRepository;
import com.example.notification.service.RecipientStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fencing token (attempts value from the claim) guarantees that a stale worker —
 * one whose SENDING row was reset by the recovery poller and re-claimed by another
 * worker — cannot overwrite the newer claim's state: no SENT row flipped back to
 * PENDING (duplicate send), no delivered recipient recorded FAILED.
 *
 * The "worker B owns the claim" state is written directly via JDBC (with updated_at
 * in the future so the background poller cannot interfere), making the interleaving
 * deterministic instead of racing live workers.
 */
class RecipientStateFencingIntegrationTest extends IntegrationTestBase {

    private static final int STALE_FENCE = 1;   // worker A's claim episode
    private static final int CURRENT_FENCE = 2; // worker B's claim episode (owns the row)

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RecipientStateService stateService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void staleWorkerCannotOverwriteAReclaimedRecipient() {
        UUID recipientId = insertRecipientClaimedByWorkerB();

        // stale worker A (fence from its old claim) tries to record late results —
        // every transition must be a no-op
        assertThat(stateService.markSent(recipientId, STALE_FENCE)).isFalse();
        assertThat(stateService.markRetry(recipientId, STALE_FENCE, Instant.now(), "stale")).isFalse();
        assertThat(stateService.markFailed(recipientId, STALE_FENCE, "stale")).isFalse();
        assertThat(stateService.release(recipientId, STALE_FENCE, Instant.now(), "stale")).isFalse();

        CampaignRecipient untouched = recipientRepository.findById(recipientId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(RecipientStatus.SENDING);
        assertThat(untouched.getAttempts()).isEqualTo(CURRENT_FENCE);

        // the rightful owner (worker B) records its result normally
        assertThat(stateService.markSent(recipientId, CURRENT_FENCE)).isTrue();
        assertThat(recipientRepository.findById(recipientId).orElseThrow().getStatus())
                .isEqualTo(RecipientStatus.SENT);
    }

    private UUID insertRecipientClaimedByWorkerB() {
        User user = userRepository.findByEmailIn(List.of("bob@example.com")).getFirst();
        Campaign campaign = campaignRepository.save(new Campaign("Fencing subject", "Fencing message"));
        UUID id = recipientRepository.save(CampaignRecipient.pending(campaign, user)).getId();

        // worker B's live claim: SENDING with attempts = 2 (A claimed once before,
        // was reset by the poller, B re-claimed). updated_at is set in the future so
        // the background poller can neither stuck-reset nor re-dispatch this row.
        jdbc.update("""
                UPDATE campaign_recipient
                SET status = 'SENDING', attempts = ?, updated_at = now() + INTERVAL '1 hour'
                WHERE id = ?
                """, CURRENT_FENCE, id);
        return id;
    }
}
