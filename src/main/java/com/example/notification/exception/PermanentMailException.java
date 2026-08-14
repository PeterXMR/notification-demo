package com.example.notification.exception;

/** Permanent rejection of the address (hard bounce) — retrying is pointless and must not happen. */
public class PermanentMailException extends RuntimeException {

    public PermanentMailException(String message) {
        super(message);
    }
}
