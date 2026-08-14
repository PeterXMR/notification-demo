package com.example.notification.worker;

import com.example.notification.config.NotificationProperties;
import com.example.notification.exception.MailProviderUnavailableException;
import com.example.notification.exception.PermanentMailException;
import com.example.notification.exception.TransientMailException;
import com.example.notification.mail.MailSender;
import com.example.notification.service.RecipientStateService;
import com.example.notification.service.RecipientStateService.ClaimedRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Processes one recipient: claim -> send -> record result.
 * Intentionally NOT transactional — it orchestrates; state changes are short transactions
 * in {@link RecipientStateService}. A failure here affects exactly one recipient and
 * never stops the rest of the campaign.
 */
@Component
public class SendWorker {

    private static final Logger log = LoggerFactory.getLogger(SendWorker.class);

    private final RecipientStateService stateService;
    private final MailSender mailSender;
    private final NotificationProperties properties;

    public SendWorker(RecipientStateService stateService,
                      MailSender mailSender,
                      NotificationProperties properties) {
        this.stateService = stateService;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void process(UUID recipientId) {
        stateService.claim(recipientId).ifPresent(recipient -> {
            boolean recorded;
            try {
                // the recipient row id IS the idempotency key: stable across every retry,
                // restart and re-claim, so the provider can refuse a second delivery
                mailSender.send(recipient.id(), recipient.email(), recipient.subject(), recipient.message());
                recorded = stateService.markSent(recipient.id(), recipient.attempts());
            } catch (MailProviderUnavailableException e) {
                // circuit open: provider-wide outage, not this recipient's fault —
                // release the claim with the retry budget restored
                log.info("Provider unavailable, releasing {} without burning an attempt", recipient.email());
                recorded = stateService.release(recipient.id(), recipient.attempts(),
                        Instant.now().plusMillis(properties.retry().backoffMs()), e.getMessage());
            } catch (PermanentMailException e) {
                // permanent rejection: terminal immediately, attempts are irrelevant
                log.warn("Recipient {} permanently rejected: {}", recipient.email(), e.getMessage());
                recorded = stateService.markFailed(recipient.id(), recipient.attempts(), e.getMessage());
            } catch (TransientMailException e) {
                recorded = handleTransient(recipient, e.getMessage());
            } catch (RuntimeException e) {
                // unexpected error: safest to treat as transient — bounded by max attempts anyway
                recorded = handleTransient(recipient, "unexpected: " + e.getMessage());
            }
            if (!recorded) {
                // fence mismatch: this claim was reset by the poller and re-claimed by
                // another worker — our result is stale and correctly discarded
                log.warn("Lost claim ownership of {} (stale worker), result discarded", recipient.email());
            }
        });
    }

    private boolean handleTransient(ClaimedRecipient recipient, String error) {
        int maxAttempts = properties.retry().maxAttempts();
        if (recipient.attempts() >= maxAttempts) {
            log.warn("Recipient {} failed after {} attempts: {}", recipient.email(), recipient.attempts(), error);
            return stateService.markFailed(recipient.id(), recipient.attempts(), "retries exhausted: " + error);
        }
        long backoffMs = properties.retry().backoffMs() * (1L << (recipient.attempts() - 1));
        Instant nextAttemptAt = Instant.now().plusMillis(backoffMs);
        log.info("Recipient {} transient failure (attempt {}/{}), retry in {} ms",
                recipient.email(), recipient.attempts(), maxAttempts, backoffMs);
        return stateService.markRetry(recipient.id(), recipient.attempts(), nextAttemptAt, error);
    }
}
