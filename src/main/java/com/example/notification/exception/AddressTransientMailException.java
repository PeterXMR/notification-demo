package com.example.notification.exception;

/**
 * Temporary failure that belongs to ONE mailbox (SMTP 4xx such as 451/452 — mailbox busy,
 * quota exceeded, greylisted). Retrying makes sense and the recipient's own retry budget
 * pays for it.
 *
 * The distinction from a plain {@link TransientMailException} matters for the circuit
 * breaker: a single misbehaving mailbox says nothing about the provider's health, so this
 * subtype is listed in {@code ignore-exceptions} and never trips the breaker. Only
 * provider-scoped transient failures (timeouts, 503s) are allowed to open the circuit.
 */
public class AddressTransientMailException extends TransientMailException {

    public AddressTransientMailException(String message) {
        super(message);
    }
}
