package com.example.notification.worker;

import com.example.notification.config.NotificationProperties;
import com.example.notification.exception.TransientMailException;
import com.example.notification.mail.MailSender;
import com.example.notification.service.RecipientStateService;
import com.example.notification.service.RecipientStateService.ClaimedRecipient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A database failure while claiming or RECORDING a result is an infrastructure fault,
 * not a mail failure: the worker must not misclassify it via the transient-mail path
 * (which would mark a possibly-delivered recipient FAILED at max attempts). It records
 * nothing and leaves the claim to the poller's stuck-SENDING recovery, which enforces
 * backoff and the terminal max-attempts bound. The claim's attempt stays spent — that
 * is deliberate: it keeps repeated unrecordable episodes bounded, like crash recovery.
 */
class SendWorkerTest {

    private static final NotificationProperties PROPERTIES = new NotificationProperties(
            new NotificationProperties.Simulator(0, 0, 0),
            new NotificationProperties.Worker(1),
            new NotificationProperties.Retry(3, 100),
            new NotificationProperties.Poller(1000, 5000, 10));

    private final RecipientStateService stateService = mock(RecipientStateService.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final SendWorker worker = new SendWorker(stateService, mailSender, PROPERTIES);

    private final UUID id = UUID.randomUUID();

    private ClaimedRecipient claimed(int attempts) {
        ClaimedRecipient recipient = new ClaimedRecipient(id, "user1@example.com", attempts, "Subject", "Message");
        when(stateService.claim(id)).thenReturn(Optional.of(recipient));
        return recipient;
    }

    @Test
    void dbOutageAfterDeliveredSendAtMaxAttemptsDoesNotMarkFailed() {
        claimed(3); // last allowed attempt: a misclassified transient failure would mark FAILED
        when(stateService.markSent(id, 3)).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

        assertThatCode(() -> worker.process(id)).doesNotThrowAnyException();

        verify(mailSender).send(id, "user1@example.com", "Subject", "Message");
        verify(stateService, never()).markFailed(any(), anyInt(), anyString());
        verify(stateService, never()).markRetry(any(), anyInt(), any(), anyString());
    }

    @Test
    void dbOutageAfterDeliveredSendRecordsNothing() {
        claimed(1);
        when(stateService.markSent(id, 1)).thenThrow(
                new DataAccessResourceFailureException("An I/O error occurred while sending to the backend"));

        assertThatCode(() -> worker.process(id)).doesNotThrowAnyException();

        // no state write at all — the claim expires and the poller re-circulates the row
        verify(stateService, never()).markRetry(any(), anyInt(), any(), anyString());
        verify(stateService, never()).markFailed(any(), anyInt(), anyString());
        verify(stateService, never()).release(any(), anyInt(), any(), anyString());
    }

    @Test
    void dbOutageWhileRecordingTransientMailFailureDoesNotPropagate() {
        // the DB can also die while a MAIL failure is being recorded (markRetry inside
        // the transient arm) — that must be swallowed by the same walk-away path, not
        // escape onto the executor thread
        claimed(1);
        doThrow(new TransientMailException("mailbox busy"))
                .when(mailSender).send(id, "user1@example.com", "Subject", "Message");
        when(stateService.markRetry(any(), anyInt(), any(), anyString())).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

        assertThatCode(() -> worker.process(id)).doesNotThrowAnyException();

        verify(stateService, never()).markFailed(any(), anyInt(), anyString());
    }

    @Test
    void dbOutageDuringClaimDoesNotPropagate() {
        when(stateService.claim(id)).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

        assertThatCode(() -> worker.process(id)).doesNotThrowAnyException();

        verify(mailSender, never()).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void transientMailFailureStillGoesThroughRetryPath() {
        claimed(1);
        doThrow(new TransientMailException("mailbox busy"))
                .when(mailSender).send(id, "user1@example.com", "Subject", "Message");

        worker.process(id);

        verify(stateService).markRetry(any(), anyInt(), any(Instant.class), anyString());
    }
}
