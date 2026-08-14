package com.example.notification.mail;

import com.example.notification.config.NotificationProperties;
import com.example.notification.exception.AddressTransientMailException;
import com.example.notification.exception.MailProviderUnavailableException;
import com.example.notification.exception.PermanentMailException;
import com.example.notification.exception.TransientMailException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ResilientMailSenderTest {

    private SimulatedMailSender simulator;
    private ResilientMailSender sender;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Simulator(1, 1, 2),
                new NotificationProperties.Worker(1),
                new NotificationProperties.Retry(5, 100),
                new NotificationProperties.Poller(1000, 5000, 100));
        simulator = new SimulatedMailSender(properties, new DeliveryLog());

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .ignoreExceptions(PermanentMailException.class, AddressTransientMailException.class)
                .build();
        sender = new ResilientMailSender(simulator, CircuitBreakerRegistry.of(config));
    }

    /** Each call is a distinct notification, matching how the worker uses the sender. */
    private void send(String recipient) {
        sender.send(UUID.randomUUID(), recipient, "S", "M");
    }

    @Test
    void opensCircuitAfterConsecutiveTransientFailuresAndStopsCallingProvider() {
        simulator.setOutage(true);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> send("john@example.com"))
                    .isInstanceOf(TransientMailException.class);
        }

        // circuit is OPEN now: rejected instantly, provider is no longer called
        assertThatThrownBy(() -> send("john@example.com"))
                .isInstanceOf(MailProviderUnavailableException.class);
        assertThat(sender.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void permanentBouncesDoNotTripTheCircuit() {
        for (int i = 0; i < 6; i++) {
            // hard bounce = address problem, not provider health — breaker must ignore it
            assertThatThrownBy(() -> send("bounce@example.com"))
                    .isInstanceOf(PermanentMailException.class);
        }
        assertThat(sender.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void addressScopedTransientFailuresDoNotTripTheCircuit() {
        for (int i = 0; i < 8; i++) {
            // one mailbox answering 451 on every attempt says nothing about provider
            // health — if it opened the circuit, that address could never exhaust its
            // retry budget (every attempt would be refunded) and would retry forever
            assertThatThrownBy(() -> send("always-fail@example.com"))
                    .isInstanceOf(AddressTransientMailException.class);
        }
        assertThat(sender.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // and a healthy address is still served normally
        assertThatCode(() -> send("john@example.com")).doesNotThrowAnyException();
    }

    @Test
    void failedHalfOpenProbeIsNotChargedToTheRecipient() {
        simulator.setOutage(true);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> send("john@example.com"))
                    .isInstanceOf(TransientMailException.class);
        }
        assertThat(sender.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        // outage continues; wait for the automatic HALF_OPEN transition
        await().atMost(Duration.ofSeconds(2))
                .until(() -> sender.circuitState() == CircuitBreaker.State.HALF_OPEN);

        // the probe reaches the still-failing provider, but surfaces as
        // provider-unavailable (release path, budget preserved), not transient
        assertThatThrownBy(() -> send("john@example.com"))
                .isInstanceOf(MailProviderUnavailableException.class)
                .hasMessageContaining("probe");
        assertThat(sender.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpenProbeClosesCircuitAfterOutageEnds() {
        simulator.setOutage(true);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> send("john@example.com"))
                    .isInstanceOf(TransientMailException.class);
        }
        assertThat(sender.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        simulator.setOutage(false);

        // after wait-duration the breaker transitions to HALF_OPEN and admits 1 probe
        await().atMost(Duration.ofSeconds(2))
                .until(() -> sender.circuitState() == CircuitBreaker.State.HALF_OPEN);
        assertThatCode(() -> send("john@example.com"))
                .doesNotThrowAnyException();

        // successful probe closes the circuit — normal operation resumes
        assertThat(sender.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThatCode(() -> send("jane@example.com"))
                .doesNotThrowAnyException();
    }
}
