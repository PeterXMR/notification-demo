package com.example.notification.mail;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the simulated provider actually did, keyed by notification id
 * (= {@code campaign_recipient.id}, stable across every retry and restart).
 *
 * This is the evidence behind the "internal retries must not re-send the same
 * notification" guarantee: the application can claim exactly-once, but only the
 * receiving side can prove it. {@link #markDelivered} is the atomic gate — the first
 * caller for a key delivers, everyone after is counted as a suppressed duplicate.
 *
 * In-memory on purpose: it models the provider's own idempotency window, not
 * application state, and it is reset by an application restart just like a real
 * provider's would eventually expire.
 */
@Component
public class DeliveryLog {

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    /** Every call that reached the provider, including ones that ended in a failure. */
    public void recordProviderCall(UUID notificationId, String recipient) {
        entry(notificationId, recipient).providerCalls.incrementAndGet();
    }

    /** Has this notification already been handed over? Checked before any send work happens. */
    public boolean isDelivered(UUID notificationId) {
        Entry entry = entries.get(notificationId);
        return entry != null && entry.delivered.get() > 0;
    }

    public void recordDuplicateSuppressed(UUID notificationId, String recipient) {
        entry(notificationId, recipient).duplicatesSuppressed.incrementAndGet();
    }

    /**
     * Atomically claims the single delivery slot for this notification.
     *
     * @return true if this call delivered the message, false if a concurrent caller won
     * the slot first (counted as a suppressed duplicate — never a failure)
     */
    public boolean markDelivered(UUID notificationId, String recipient) {
        Entry entry = entry(notificationId, recipient);
        if (entry.delivered.compareAndSet(0, 1)) {
            return true;
        }
        entry.duplicatesSuppressed.incrementAndGet();
        return false;
    }

    public Optional<Delivery> find(UUID notificationId) {
        return Optional.ofNullable(entries.get(notificationId)).map(e -> e.snapshot(notificationId));
    }

    private Entry entry(UUID notificationId, String recipient) {
        return entries.computeIfAbsent(notificationId, id -> new Entry(recipient));
    }

    private static final class Entry {
        private final String recipient;
        private final AtomicInteger providerCalls = new AtomicInteger();
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicInteger duplicatesSuppressed = new AtomicInteger();

        private Entry(String recipient) {
            this.recipient = recipient;
        }

        private Delivery snapshot(UUID notificationId) {
            return new Delivery(notificationId, recipient,
                    providerCalls.get(), delivered.get(), duplicatesSuppressed.get());
        }
    }

    /**
     * @param providerCalls        times the provider was called for this notification (failures included)
     * @param delivered            times the message was actually handed over — must never exceed 1
     * @param duplicatesSuppressed re-delivery attempts the provider refused because the key was already delivered
     */
    public record Delivery(UUID notificationId,
                           String recipient,
                           int providerCalls,
                           int delivered,
                           int duplicatesSuppressed) {
    }
}
