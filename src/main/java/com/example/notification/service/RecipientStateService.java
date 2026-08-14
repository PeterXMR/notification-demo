package com.example.notification.service;

import com.example.notification.domain.CampaignRecipient;
import com.example.notification.repository.CampaignRecipientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * All recipient state transitions. Each method is one short transaction — the worker
 * deliberately holds NO transaction while talking to the mail provider, so the slow
 * network call never blocks a DB connection or a lock.
 *
 * This is a separate bean (not methods on the worker) so that @Transactional goes
 * through the Spring proxy — self-invocation would silently skip it.
 */
@Service
public class RecipientStateService {

    private final CampaignRecipientRepository repository;

    public RecipientStateService(CampaignRecipientRepository repository) {
        this.repository = repository;
    }

    /**
     * TX1: atomically claim the recipient (PENDING -> SENDING, attempts + 1).
     * Empty result means another worker won the row, or its backoff is not due — walk away.
     */
    @Transactional
    public Optional<ClaimedRecipient> claim(UUID recipientId) {
        int updated = repository.claim(recipientId, Instant.now());
        if (updated == 0) {
            return Optional.empty();
        }
        CampaignRecipient recipient = repository.findWithCampaignById(recipientId).orElseThrow();
        return Optional.of(new ClaimedRecipient(
                recipient.getId(),
                recipient.getEmail(),
                recipient.getAttempts(),
                recipient.getCampaign().getSubject(),
                recipient.getCampaign().getMessage()));
    }

    /**
     * TX2 (success): SENDING -> SENT. The fence (attempts from the claim snapshot)
     * guarantees only the current claim's owner can record a result — a stale worker
     * returns false and its result is discarded.
     */
    @Transactional
    public boolean markSent(UUID recipientId, int fenceAttempts) {
        return repository.markSent(recipientId, fenceAttempts, Instant.now()) == 1;
    }

    /** TX2 (transient failure, attempts left): SENDING -> PENDING with exponential backoff. */
    @Transactional
    public boolean markRetry(UUID recipientId, int fenceAttempts, Instant nextAttemptAt, String error) {
        return repository.markRetry(recipientId, fenceAttempts, nextAttemptAt, truncate(error), Instant.now()) == 1;
    }

    /** TX2 (permanent failure or retries exhausted): SENDING -> FAILED (terminal). */
    @Transactional
    public boolean markFailed(UUID recipientId, int fenceAttempts, String error) {
        return repository.markFailed(recipientId, fenceAttempts, truncate(error), Instant.now()) == 1;
    }

    /**
     * TX2 (circuit open — provider outage): SENDING -> PENDING with attempts RESTORED.
     * The outage is the provider's fault, not the address's; the recipient keeps
     * its full retry budget and is retried once the circuit may be closed again.
     */
    @Transactional
    public boolean release(UUID recipientId, int fenceAttempts, Instant nextAttemptAt, String error) {
        return repository.releaseClaim(recipientId, fenceAttempts, nextAttemptAt, truncate(error), Instant.now()) == 1;
    }

    /**
     * Crash recovery: orphaned SENDING rows (worker died mid-send, or walked away from a
     * DB failure) go back to PENDING with a backoff — provided they still have retry
     * budget. Exhausted rows are terminally FAILED instead (see {@link #failStuckExhausted}).
     */
    @Transactional
    public int resetStuckSending(Instant stuckBefore, Instant nextAttemptAt, int maxAttempts) {
        return repository.resetStuckSending(stuckBefore, nextAttemptAt, maxAttempts, Instant.now());
    }

    /** Terminal bound for stuck-claim recovery: budget spent without a recorded result -> FAILED. */
    @Transactional
    public int failStuckExhausted(Instant stuckBefore, int maxAttempts) {
        return repository.failStuckExhausted(stuckBefore, maxAttempts,
                "retries exhausted: claim expired repeatedly without a recorded result", Instant.now());
    }

    private String truncate(String error) {
        return error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    /** Snapshot handed to the worker — everything needed to send without touching the DB again. */
    public record ClaimedRecipient(UUID id, String email, int attempts, String subject, String message) {
    }
}
