package com.example.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One recipient of one campaign — the unit of work and the unit of state.
 * All processing state lives in this row, so it survives restarts and
 * the status API can aggregate over it.
 *
 * Implements {@link Persistable} (assigned id): saveAll of up to 100 rows runs
 * as batched INSERTs instead of merge + SELECT per row.
 */
@Entity
@Table(name = "campaign_recipient")
public class CampaignRecipient implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecipientStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignRecipient() {
        // JPA
    }

    private CampaignRecipient(Campaign campaign, UUID userId, String email) {
        this.id = UUID.randomUUID();
        this.campaign = campaign;
        this.userId = userId;
        this.email = email;
        this.status = RecipientStatus.PENDING;
        this.attempts = 0;
        this.updatedAt = Instant.now();
    }

    public static CampaignRecipient pending(Campaign campaign, User user) {
        return new CampaignRecipient(campaign, user.getId(), user.getEmail());
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    private void markNotNew() {
        this.isNew = false;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public String getEmail() {
        return email;
    }

    public RecipientStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
