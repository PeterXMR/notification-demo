package com.example.notification.worker;

import com.example.notification.config.NotificationProperties;
import com.example.notification.repository.CampaignRecipientRepository;
import com.example.notification.service.RecipientStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The completion guarantee. One mechanism covers three situations:
 * <ul>
 *   <li>restart after accepting a campaign — PENDING rows survived, poller finishes them</li>
 *   <li>transient failure with backoff — next_attempt_at became due, poller retries</li>
 *   <li>worker crash mid-send — orphaned SENDING rows are returned to circulation</li>
 * </ul>
 * Double submission (poller + dispatcher racing) is harmless: the CAS claim lets
 * exactly one processing attempt through.
 */
@Component
public class RecoveryPoller {

    private static final Logger log = LoggerFactory.getLogger(RecoveryPoller.class);

    private final CampaignRecipientRepository recipientRepository;
    private final RecipientStateService stateService;
    private final SendWorker sendWorker;
    private final ThreadPoolTaskExecutor mailExecutor;
    private final NotificationProperties properties;

    public RecoveryPoller(CampaignRecipientRepository recipientRepository,
                          RecipientStateService stateService,
                          SendWorker sendWorker,
                          @Qualifier("mailExecutor") ThreadPoolTaskExecutor mailExecutor,
                          NotificationProperties properties) {
        this.recipientRepository = recipientRepository;
        this.stateService = stateService;
        this.sendWorker = sendWorker;
        this.mailExecutor = mailExecutor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${notification.poller.interval-ms}")
    public void poll() {
        Instant now = Instant.now();
        Instant stuckBefore = now.minusMillis(properties.poller().stuckTimeoutMs());

        int failed = stateService.failStuckExhausted(stuckBefore, properties.retry().maxAttempts());
        if (failed > 0) {
            log.warn("Terminally failed {} recipient(s): retry budget exhausted without a recorded result", failed);
        }

        int reset = stateService.resetStuckSending(stuckBefore,
                now.plusMillis(properties.retry().backoffMs()), properties.retry().maxAttempts());
        if (reset > 0) {
            log.warn("Recovered {} recipient(s) stuck in SENDING", reset);
        }

        List<UUID> due = recipientRepository.findDueIds(now, PageRequest.of(0, properties.poller().batchSize()));
        if (!due.isEmpty()) {
            log.info("Poller picked up {} due recipient(s)", due.size());
            due.forEach(id -> mailExecutor.execute(() -> sendWorker.process(id)));
        }
    }
}
