package com.example.notification.mail;

import com.example.notification.exception.PermanentMailException;
import com.example.notification.exception.TransientMailException;

import java.util.UUID;

/**
 * Abstraction over the external email provider. The application only depends on the
 * transient/permanent error contract — swapping the simulator for a real provider
 * (SES, SendGrid, ...) means mapping its error codes onto these two exceptions.
 */
public interface MailSender {

    /**
     * @param notificationId idempotency key identifying THIS notification — the same value
     *                       for every retry of the same campaign recipient, so a provider
     *                       that honours it can refuse a duplicate delivery. Real providers
     *                       expose the same concept (SES {@code MessageDeduplicationId},
     *                       SendGrid/Stripe-style {@code Idempotency-Key} headers).
     * @throws TransientMailException temporary technical failure — retrying makes sense
     * @throws PermanentMailException the address is permanently rejected — never retry
     */
    void send(UUID notificationId, String recipient, String subject, String message);
}
