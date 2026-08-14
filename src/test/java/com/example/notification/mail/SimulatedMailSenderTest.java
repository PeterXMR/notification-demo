package com.example.notification.mail;

import com.example.notification.config.NotificationProperties;
import com.example.notification.exception.AddressTransientMailException;
import com.example.notification.exception.PermanentMailException;
import com.example.notification.exception.TransientMailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedMailSenderTest {

    private SimulatedMailSender sender;
    private DeliveryLog deliveryLog;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Simulator(1, 1, 2),
                new NotificationProperties.Worker(1),
                new NotificationProperties.Retry(3, 100),
                new NotificationProperties.Poller(1000, 5000, 100));
        deliveryLog = new DeliveryLog();
        sender = new SimulatedMailSender(properties, deliveryLog);
    }

    private void send(String recipient) {
        sender.send(UUID.randomUUID(), recipient, "Subject", "Message");
    }

    @Test
    void ordinaryAddressSucceeds() {
        assertThatCode(() -> send("john@example.com")).doesNotThrowAnyException();
    }

    @Test
    void bounceAddressIsPermanentlyRejected() {
        assertThatThrownBy(() -> send("bounce@example.com"))
                .isInstanceOf(PermanentMailException.class)
                .hasMessageContaining("550");
    }

    @Test
    void tempFailAddressFailsTwiceThenSucceeds() {
        assertThatThrownBy(() -> send("temp-fail@example.com"))
                .isInstanceOf(TransientMailException.class);
        assertThatThrownBy(() -> send("temp-fail@example.com"))
                .isInstanceOf(TransientMailException.class);
        assertThatCode(() -> send("temp-fail@example.com")).doesNotThrowAnyException();
    }

    @Test
    void tempFailCounterResetsAfterSuccessSoNextCampaignBehavesTheSame() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> send("temp-fail@example.com"))
                    .isInstanceOf(TransientMailException.class);
        }
        assertThatCode(() -> send("temp-fail@example.com")).doesNotThrowAnyException();

        // fresh campaign to the same address: deterministic again
        assertThatThrownBy(() -> send("temp-fail@example.com"))
                .isInstanceOf(TransientMailException.class);
    }

    @Test
    void slowAddressStillSucceeds() {
        assertThatCode(() -> send("slow@example.com")).doesNotThrowAnyException();
    }

    @Test
    void deliversTheSameNotificationOnlyOnceAndCountsTheSuppressedDuplicate() {
        UUID notificationId = UUID.randomUUID();

        sender.send(notificationId, "john@example.com", "Subject", "Message");
        sender.send(notificationId, "john@example.com", "Subject", "Message");

        DeliveryLog.Delivery delivery = deliveryLog.find(notificationId).orElseThrow();
        assertThat(delivery.providerCalls()).isEqualTo(2);
        assertThat(delivery.delivered()).isEqualTo(1);
        assertThat(delivery.duplicatesSuppressed()).isEqualTo(1);
    }

    @Test
    void suppressedDuplicateDoesNotFailEvenForAnAddressThatFailsTransiently() {
        UUID notificationId = UUID.randomUUID();

        // first two calls fail transiently, the third one delivers
        assertThatThrownBy(() -> sender.send(notificationId, "temp-fail@example.com", "S", "M"));
        assertThatThrownBy(() -> sender.send(notificationId, "temp-fail@example.com", "S", "M"));
        sender.send(notificationId, "temp-fail@example.com", "S", "M");

        // a late duplicate of an ALREADY DELIVERED notification is a silent no-op,
        // never a fresh transient failure that would restart the retry cycle
        assertThatCode(() -> sender.send(notificationId, "temp-fail@example.com", "S", "M"))
                .doesNotThrowAnyException();
        assertThat(deliveryLog.find(notificationId).orElseThrow().delivered()).isEqualTo(1);
    }

    @Test
    void differentNotificationsToTheSameAddressAreBothDelivered() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        sender.send(first, "john@example.com", "S", "M");
        sender.send(second, "john@example.com", "S", "M");

        assertThat(deliveryLog.find(first).orElseThrow().delivered()).isEqualTo(1);
        assertThat(deliveryLog.find(second).orElseThrow().delivered()).isEqualTo(1);
    }

    @Test
    void alwaysFailAddressNeverSucceeds() {
        UUID notificationId = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> sender.send(notificationId, "always-fail@example.com", "S", "M"))
                    .isInstanceOf(AddressTransientMailException.class)
                    .hasMessageContaining("451");
        }
        assertThat(deliveryLog.find(notificationId).orElseThrow().delivered()).isZero();
    }

    @Test
    void addressScopedFailuresAreNotProviderWideFailures() {
        // temp-fail@/always-fail@ are per-mailbox 4xx responses: they must not be
        // mistaken for provider-wide trouble (see the circuit breaker in ResilientMailSender)
        assertThatThrownBy(() -> send("temp-fail@example.com"))
                .isInstanceOf(AddressTransientMailException.class);
        assertThatThrownBy(() -> send("always-fail@example.com"))
                .isInstanceOf(AddressTransientMailException.class);
    }
}
