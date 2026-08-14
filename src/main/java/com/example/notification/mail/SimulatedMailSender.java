package com.example.notification.mail;

import com.example.notification.config.NotificationProperties;
import com.example.notification.exception.AddressTransientMailException;
import com.example.notification.exception.PermanentMailException;
import com.example.notification.exception.TransientMailException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, address-driven simulation of an external email provider —
 * demos are repeatable instead of depending on luck:
 *
 * <ul>
 *   <li><b>bounce@...</b> — permanent rejection (SMTP 550), fails immediately, no retry</li>
 *   <li><b>temp-fail@...</b> — transient failure (SMTP 451) N times, then succeeds</li>
 *   <li><b>always-fail@...</b> — transient failure on every attempt, so the recipient
 *       exhausts its retry budget and ends FAILED — the bound on retries made visible</li>
 *   <li><b>slow@...</b> — succeeds after a long latency (keeps the campaign visibly PROCESSING)</li>
 *   <li>anything else — succeeds after the configured base latency</li>
 * </ul>
 *
 * The simulator also honours the {@code notificationId} idempotency key: a notification
 * that was already delivered is never delivered again, no matter how often the
 * application retries it. See {@link DeliveryLog}.
 */
@Component
public class SimulatedMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMailSender.class);

    private final NotificationProperties properties;
    private final DeliveryLog deliveryLog;

    /** Counts consecutive transient failures per address; reset on success so every demo behaves the same. */
    private final ConcurrentHashMap<String, AtomicInteger> transientFailures = new ConcurrentHashMap<>();

    /** Test-only toggle simulating a full provider outage (all sends fail transiently). */
    private volatile boolean outage;

    public SimulatedMailSender(NotificationProperties properties, DeliveryLog deliveryLog) {
        this.properties = properties;
        this.deliveryLog = deliveryLog;
    }

    public void setOutage(boolean outage) {
        this.outage = outage;
        log.info("SIMULATOR: provider outage {}", outage ? "STARTED" : "ENDED");
    }

    @Override
    public void send(UUID notificationId, String recipient, String subject, String message) {
        deliveryLog.recordProviderCall(notificationId, recipient);

        // Idempotency gate, checked BEFORE any send work: a notification that was already
        // delivered is a no-op success. It must not be re-delivered, and equally must not
        // be answered with a fresh failure that would restart the retry cycle.
        if (deliveryLog.isDelivered(notificationId)) {
            deliveryLog.recordDuplicateSuppressed(notificationId, recipient);
            log.warn("SIMULATOR: duplicate suppressed for {} (notification {} already delivered)",
                    recipient, notificationId);
            return;
        }

        String localPart = recipient.substring(0, recipient.indexOf('@'));

        if (outage) {
            // provider-wide: plain TransientMailException, counts towards circuit breaker health
            throw new TransientMailException("503 provider outage (simulated)");
        }

        if (localPart.startsWith("bounce")) {
            log.info("SIMULATOR: permanent reject for {}", recipient);
            throw new PermanentMailException("550 mailbox unavailable: " + recipient);
        }

        if (localPart.startsWith("always-fail")) {
            log.info("SIMULATOR: permanent transient failure for {}", recipient);
            throw new AddressTransientMailException("451 mailbox temporarily unavailable: " + recipient);
        }

        if (localPart.startsWith("temp-fail")) {
            int failures = transientFailures
                    .computeIfAbsent(recipient, k -> new AtomicInteger())
                    .incrementAndGet();
            if (failures <= properties.simulator().transientFailuresBeforeSuccess()) {
                log.info("SIMULATOR: transient failure #{} for {}", failures, recipient);
                throw new AddressTransientMailException("451 temporarily unavailable (attempt " + failures + ")");
            }
        }

        // Same gate, now atomic: if two workers raced past the check above, exactly one
        // of them delivers and the loser is recorded as a suppressed duplicate.
        if (!deliveryLog.markDelivered(notificationId, recipient)) {
            log.warn("SIMULATOR: concurrent duplicate suppressed for {} (notification {})",
                    recipient, notificationId);
            return;
        }

        long latency = localPart.startsWith("slow")
                ? properties.simulator().slowLatencyMs()
                : properties.simulator().latencyMs();
        sleep(latency);

        transientFailures.remove(recipient);
        log.info("SIMULATOR: sent '{}' to {} ({} ms)", subject, recipient, latency);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientMailException("send interrupted");
        }
    }
}
