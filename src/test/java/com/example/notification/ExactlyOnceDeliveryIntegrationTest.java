package com.example.notification;

import com.example.notification.domain.Campaign;
import com.example.notification.domain.CampaignRecipient;
import com.example.notification.domain.RecipientStatus;
import com.example.notification.domain.User;
import com.example.notification.mail.DeliveryLog;
import com.example.notification.repository.CampaignRecipientRepository;
import com.example.notification.repository.CampaignRepository;
import com.example.notification.repository.UserRepository;
import com.example.notification.worker.SendWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The acceptance criterion "internal retries must not cause uncontrolled repeated sending
 * of the same notification", proven at the only place that can prove it: the receiving side.
 *
 * The application's own defences (CAS claim + fencing token) are verified in
 * {@link RecipientStateFencingIntegrationTest}, but those only govern DATABASE state — they
 * cannot prove that no second message was handed to the provider. These tests assert against
 * the {@link DeliveryLog}, which records what the provider actually did per notification id.
 *
 * The invariant is deliberately absolute: however many workers pile onto one recipient,
 * {@code delivered == 1}.
 */
class ExactlyOnceDeliveryIntegrationTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SendWorker sendWorker;

    @Autowired
    private DeliveryLog deliveryLog;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentWorkersOnTheSameRecipientDeliverOnlyOnce() throws Exception {
        UUID recipientId = insertPendingRecipient("john@example.com");

        // dispatcher and recovery poller may legitimately both pick up the same row;
        // the conditional claim (PENDING -> SENDING) must let exactly one through
        int workers = 6;
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    startTogether.await();
                    sendWorker.process(recipientId);
                    return null;
                });
            }
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        DeliveryLog.Delivery delivery = deliveryLog.find(recipientId).orElseThrow();
        assertThat(delivery.delivered()).isEqualTo(1);
        // losers never even reached the provider — they lost the claim, not the delivery race
        assertThat(delivery.providerCalls()).isEqualTo(1);
        assertThat(recipientRepository.findById(recipientId).orElseThrow().getStatus())
                .isEqualTo(RecipientStatus.SENT);
    }

    @Test
    void staleWorkerStillInFlightCannotCauseASecondDelivery() throws Exception {
        // slow@ keeps worker A inside the provider call long enough to interleave
        UUID recipientId = insertPendingRecipient("slow@example.com");

        Thread workerA = new Thread(() -> sendWorker.process(recipientId), "worker-A");
        workerA.start();

        // A has claimed the row and is now talking to the provider
        await().atMost(TIMEOUT).until(() -> currentStatus(recipientId).equals("SENDING"));

        // the recovery poller decides A is stuck and returns the row to circulation,
        // so worker B claims and processes THE SAME notification while A is still in flight
        jdbc.update("UPDATE campaign_recipient SET status = 'PENDING', updated_at = now() WHERE id = ?",
                recipientId);
        sendWorker.process(recipientId);

        workerA.join(TIMEOUT.toMillis());
        assertThat(workerA.isAlive()).isFalse();

        DeliveryLog.Delivery delivery = deliveryLog.find(recipientId).orElseThrow();
        // two workers really did call the provider for one notification...
        assertThat(delivery.providerCalls()).isGreaterThanOrEqualTo(2);
        // ...and the idempotency key made sure only one message went out
        assertThat(delivery.delivered()).isEqualTo(1);
        assertThat(delivery.duplicatesSuppressed()).isGreaterThanOrEqualTo(1);

        // the recipient still settles cleanly: the stale worker's result was fenced off
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(recipientRepository.findById(recipientId).orElseThrow().getStatus())
                        .isEqualTo(RecipientStatus.SENT));
    }

    @Test
    void retryingAfterALostResultDoesNotResendTheMessage() {
        // the classic at-least-once window: the provider accepted the message but the
        // application died before recording SENT, so the row is retried from scratch
        UUID recipientId = insertPendingRecipient("jane@example.com");

        sendWorker.process(recipientId);
        assertThat(deliveryLog.find(recipientId).orElseThrow().delivered()).isEqualTo(1);

        // pretend the SENT write was lost and the poller picked the row up again
        jdbc.update("""
                UPDATE campaign_recipient
                SET status = 'PENDING', attempts = 0, next_attempt_at = NULL, updated_at = now()
                WHERE id = ?
                """, recipientId);
        sendWorker.process(recipientId);

        DeliveryLog.Delivery delivery = deliveryLog.find(recipientId).orElseThrow();
        assertThat(delivery.providerCalls()).isEqualTo(2);
        assertThat(delivery.delivered()).isEqualTo(1);
        assertThat(delivery.duplicatesSuppressed()).isEqualTo(1);
    }

    private String currentStatus(UUID recipientId) {
        return jdbc.queryForObject("SELECT status FROM campaign_recipient WHERE id = ?",
                String.class, recipientId);
    }

    /**
     * Inserts the row directly, without publishing the creation event — nothing dispatches
     * it, so each test drives the worker itself and the delivery counts stay meaningful.
     */
    private UUID insertPendingRecipient(String email) {
        User user = userRepository.findByEmailIn(List.of(email)).getFirst();
        Campaign campaign = campaignRepository.save(new Campaign("Idempotency subject", "Idempotency message"));
        CampaignRecipient recipient = recipientRepository.save(CampaignRecipient.pending(campaign, user));
        return recipient.getId();
    }
}
