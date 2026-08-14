package com.example.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Implements {@link Persistable} because the id is assigned in the constructor:
 * without it Spring Data would treat every new instance as existing (merge +
 * SELECT-by-id per row) instead of a plain batched INSERT.
 */
@Entity
@Table(name = "campaign")
public class Campaign implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Campaign() {
        // JPA
    }

    public Campaign(String subject, String message) {
        this.id = UUID.randomUUID();
        this.subject = subject;
        this.message = message;
        this.createdAt = Instant.now();
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

    public String getSubject() {
        return subject;
    }

    public String getMessage() {
        return message;
    }
}
