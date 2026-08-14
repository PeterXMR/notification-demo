package com.example.notification.mail;

import com.example.notification.exception.MailProviderUnavailableException;
import com.example.notification.exception.TransientMailException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Circuit breaker decorator around the actual provider. Separates two failure modes:
 * <ul>
 *   <li><b>this address fails</b> — Transient/Permanent exceptions pass through and are
 *       charged to the recipient's own retry budget;</li>
 *   <li><b>the provider is down</b> — after enough consecutive transient failures the
 *       circuit opens, further sends are rejected instantly as
 *       {@link MailProviderUnavailableException} and recipients keep their budget.</li>
 * </ul>
 * Half-open state lets exactly one probe email through (see application.yml); if the
 * probe succeeds the circuit closes and the campaign resumes on its own.
 * Permanent bounces are ignored by the breaker — a dead mailbox says nothing about
 * provider health.
 */
@Component
@Primary
public class ResilientMailSender implements MailSender {

    public static final String BREAKER_NAME = "mailProvider";

    private final SimulatedMailSender delegate;
    private final CircuitBreaker circuitBreaker;

    public ResilientMailSender(SimulatedMailSender delegate, CircuitBreakerRegistry registry) {
        this.delegate = delegate;
        this.circuitBreaker = registry.circuitBreaker(BREAKER_NAME);
    }

    @Override
    public void send(UUID notificationId, String recipient, String subject, String message) {
        CircuitBreaker.State stateBefore = circuitBreaker.getState();
        try {
            circuitBreaker.executeRunnable(() -> delegate.send(notificationId, recipient, subject, message));
        } catch (CallNotPermittedException e) {
            throw new MailProviderUnavailableException(
                    "mail provider unavailable, circuit is " + circuitBreaker.getState());
        } catch (TransientMailException e) {
            if (stateBefore == CircuitBreaker.State.HALF_OPEN) {
                // failed half-open probe: the provider is still down as a whole — the
                // breaker has already recorded the failure (back to OPEN), but the
                // probing recipient must not be charged an attempt for volunteering
                throw new MailProviderUnavailableException(
                        "provider still unavailable (failed half-open probe): " + e.getMessage());
            }
            throw e;
        }
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}
