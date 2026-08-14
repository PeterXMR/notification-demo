package com.example.notification.exception;

/** Temporary technical failure (timeout, provider unavailable) — the send may be retried. */
public class TransientMailException extends RuntimeException {

    public TransientMailException(String message) {
        super(message);
    }
}
