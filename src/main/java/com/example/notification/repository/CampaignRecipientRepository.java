package com.example.notification.repository;

import com.example.notification.domain.CampaignRecipient;
import com.example.notification.domain.RecipientStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, UUID> {

    /**
     * Compare-and-set claim: PENDING -> SENDING, attempts + 1. The WHERE clause is the
     * whole concurrency story — of any number of workers trying to claim the same row,
     * the database lets exactly one succeed (returns 1); everyone else gets 0 and walks away.
     * Also respects the retry backoff (next_attempt_at must be due).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CampaignRecipient r
            SET r.status = com.example.notification.domain.RecipientStatus.SENDING,
                r.attempts = r.attempts + 1,
                r.updatedAt = :now
            WHERE r.id = :id
              AND r.status = com.example.notification.domain.RecipientStatus.PENDING
              AND (r.nextAttemptAt IS NULL OR r.nextAttemptAt <= :now)
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * All result transitions (markSent/markRetry/markFailed/releaseClaim) are fenced:
     * {@code status = SENDING AND attempts = :attempts} makes the attempts value from
     * the claim act as an ownership token for this claim episode. A stale worker —
     * one whose SENDING row was reset by the poller and re-claimed (attempts + 1) by
     * someone else — matches 0 rows and cannot overwrite the newer state (no SENT
     * row flipped back to PENDING, no delivered recipient recorded FAILED).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CampaignRecipient r
            SET r.status = com.example.notification.domain.RecipientStatus.SENT,
                r.updatedAt = :now
            WHERE r.id = :id
              AND r.status = com.example.notification.domain.RecipientStatus.SENDING
              AND r.attempts = :attempts
            """)
    int markSent(@Param("id") UUID id, @Param("attempts") int attempts, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CampaignRecipient r
            SET r.status = com.example.notification.domain.RecipientStatus.PENDING,
                r.nextAttemptAt = :nextAttemptAt,
                r.lastError = :error,
                r.updatedAt = :now
            WHERE r.id = :id
              AND r.status = com.example.notification.domain.RecipientStatus.SENDING
              AND r.attempts = :attempts
            """)
    int markRetry(@Param("id") UUID id,
                  @Param("attempts") int attempts,
                  @Param("nextAttemptAt") Instant nextAttemptAt,
                  @Param("error") String error,
                  @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CampaignRecipient r
            SET r.status = com.example.notification.domain.RecipientStatus.FAILED,
                r.lastError = :error,
                r.updatedAt = :now
            WHERE r.id = :id
              AND r.status = com.example.notification.domain.RecipientStatus.SENDING
              AND r.attempts = :attempts
            """)
    int markFailed(@Param("id") UUID id,
                   @Param("attempts") int attempts,
                   @Param("error") String error,
                   @Param("now") Instant now);

    /**
     * Provider outage (circuit breaker open): un-claim the recipient WITHOUT charging
     * its retry budget — SENDING -> PENDING, attempts - 1. A provider-wide outage must
     * never exhaust per-address retries. Fenced by attempts like the mark* queries,
     * so a stale worker can only release ITS OWN claim episode, never someone else's
     * live claim.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CampaignRecipient r
            SET r.status = com.example.notification.domain.RecipientStatus.PENDING,
                r.attempts = r.attempts - 1,
                r.nextAttemptAt = :nextAttemptAt,
                r.lastError = :error,
                r.updatedAt = :now
            WHERE r.id = :id
              AND r.status = com.example.notification.domain.RecipientStatus.SENDING
              AND r.attempts = :attempts
            """)
    int releaseClaim(@Param("id") UUID id,
                     @Param("attempts") int attempts,
                     @Param("nextAttemptAt") Instant nextAttemptAt,
                     @Param("error") String error,
                     @Param("now") Instant now);

    /**
     * Crash recovery: SENDING rows untouched for longer than the stuck timeout belong to
     * a worker that died mid-send (or walked away from a DB failure). Returning them to
     * PENDING puts them back into circulation — with a backoff, so a recipient whose
     * result repeatedly cannot be recorded recirculates at backoff pace, not poller pace.
     * Only rows with retry budget left are reset; exhausted ones are terminally failed
     * by {@link #failStuckExhausted} so the claim/reset cycle cannot loop forever.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CampaignRecipient r
            SET r.status = com.example.notification.domain.RecipientStatus.PENDING,
                r.nextAttemptAt = :nextAttemptAt,
                r.updatedAt = :now
            WHERE r.status = com.example.notification.domain.RecipientStatus.SENDING
              AND r.updatedAt < :stuckBefore
              AND r.attempts < :maxAttempts
            """)
    int resetStuckSending(@Param("stuckBefore") Instant stuckBefore,
                          @Param("nextAttemptAt") Instant nextAttemptAt,
                          @Param("maxAttempts") int maxAttempts,
                          @Param("now") Instant now);

    /**
     * The terminal bound for stuck-claim recovery: a SENDING row whose retry budget is
     * already spent has claimed {@code maxAttempts} times without ever recording a result
     * — repeated crashes or DB failures mid-episode. Without this transition such a row
     * would loop claim -> stuck -> reset forever and its campaign would never complete.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CampaignRecipient r
            SET r.status = com.example.notification.domain.RecipientStatus.FAILED,
                r.lastError = :error,
                r.updatedAt = :now
            WHERE r.status = com.example.notification.domain.RecipientStatus.SENDING
              AND r.updatedAt < :stuckBefore
              AND r.attempts >= :maxAttempts
            """)
    int failStuckExhausted(@Param("stuckBefore") Instant stuckBefore,
                           @Param("maxAttempts") int maxAttempts,
                           @Param("error") String error,
                           @Param("now") Instant now);

    @Query("""
            SELECT r.id
            FROM CampaignRecipient r
            WHERE r.campaign.id = :campaignId
              AND r.status = com.example.notification.domain.RecipientStatus.PENDING
            """)
    List<UUID> findPendingIdsByCampaign(@Param("campaignId") UUID campaignId);

    /**
     * Work selection for the recovery poller: PENDING rows whose backoff has expired
     * (or that never failed). Ordering by next_attempt_at keeps retries fair.
     */
    @Query("""
            SELECT r.id
            FROM CampaignRecipient r
            WHERE r.status = com.example.notification.domain.RecipientStatus.PENDING
              AND (r.nextAttemptAt IS NULL OR r.nextAttemptAt <= :now)
            ORDER BY r.nextAttemptAt ASC NULLS FIRST
            """)
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);

    /**
     * The status API aggregation — campaign status is derived from these counts,
     * never stored, so it cannot drift from reality.
     */
    @Query("""
            SELECT r.status AS status, COUNT(r) AS cnt
            FROM CampaignRecipient r
            WHERE r.campaign.id = :campaignId
            GROUP BY r.status
            """)
    List<StatusCount> countByStatusForCampaign(@Param("campaignId") UUID campaignId);

    @Query("""
            SELECT r
            FROM CampaignRecipient r
            JOIN FETCH r.campaign
            WHERE r.id = :id
            """)
    Optional<CampaignRecipient> findWithCampaignById(@Param("id") UUID id);

    Optional<CampaignRecipient> findByCampaignIdAndEmail(UUID campaignId, String email);

    List<CampaignRecipient> findByCampaignIdOrderByEmail(UUID campaignId);

    interface StatusCount {
        RecipientStatus getStatus();

        long getCnt();
    }
}
