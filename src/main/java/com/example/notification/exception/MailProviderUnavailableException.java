package com.example.notification.exception;

/**
 * The circuit breaker is open — the provider as a whole is considered down.
 * Unlike {@link TransientMailException} this must NOT burn the recipient's retry
 * budget: the recipient is released back to PENDING with attempts restored.
 */
public class MailProviderUnavailableException extends RuntimeException {

    public MailProviderUnavailableException(String message) {
        super(message);
    }
}
