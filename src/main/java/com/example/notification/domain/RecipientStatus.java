package com.example.notification.domain;

/**
 * Per-recipient (= per-email) state machine:
 *
 * <pre>
 * PENDING ── claim ──► SENDING ──► SENT                    (success)
 *    ▲                    │
 *    │                    ├──────► FAILED                  (permanent error, or attempts == max)
 *    └── transient error ─┘        (backoff, attempts &lt; max)
 * </pre>
 *
 * SENT and FAILED are terminal — no query in the application ever selects them
 * for processing, which structurally rules out endless retries.
 */
public enum RecipientStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}
