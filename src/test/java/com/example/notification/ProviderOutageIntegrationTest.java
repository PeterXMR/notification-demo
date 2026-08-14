package com.example.notification;

import com.example.notification.domain.CampaignStatus;
import com.example.notification.domain.RecipientStatus;
import com.example.notification.mail.ResilientMailSender;
import com.example.notification.mail.SimulatedMailSender;
import com.example.notification.repository.CampaignRecipientRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full provider outage: the circuit opens, recipients are parked in PENDING with their
 * retry budget intact (no FAILED), and when the outage ends the half-open probe closes
 * the circuit and the campaign completes on its own — no client intervention.
 */
class ProviderOutageIntegrationTest extends IntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private SimulatedMailSender simulator;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private CampaignRecipientRepository recipientRepository;

    @AfterEach
    void endOutage() {
        simulator.setOutage(false); // safety: never leak an outage into other tests
    }

    @Test
    void outageOpensCircuitParksRecipientsAndCampaignFinishesAfterRecovery() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(ResilientMailSender.BREAKER_NAME);

        // start the outage directly on the simulator bean (test-only toggle, no public API)
        simulator.setOutage(true);

        UUID campaignId = createCampaign(List.of(
                "john@example.com", "jane@example.com", "alice@example.com",
                "bob@example.com", "carol@example.com"));

        // consecutive transient failures trip the breaker
        await().atMost(TIMEOUT).until(() -> breaker.getState() == CircuitBreaker.State.OPEN);

        // while the provider is down: nobody FAILED, campaign still PROCESSING,
        // recipients wait in PENDING/SENDING with retry budget preserved
        var status = getStatus(campaignId);
        assertThat(status.status()).isEqualTo(CampaignStatus.PROCESSING);
        assertThat(status.failed()).isZero();

        simulator.setOutage(false);

        // half-open probe succeeds, circuit closes, campaign completes on its own
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var s = getStatus(campaignId);
            assertThat(s.status()).isEqualTo(CampaignStatus.COMPLETED);
            assertThat(s.sent()).isEqualTo(5);
            assertThat(s.failed()).isZero();
        });

        // nobody exhausted their budget during the outage
        for (String email : List.of("john@example.com", "jane@example.com", "alice@example.com",
                "bob@example.com", "carol@example.com")) {
            var recipient = recipientRepository.findByCampaignIdAndEmail(campaignId, email).orElseThrow();
            assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(recipient.getAttempts()).isLessThan(5);
        }
    }
}
