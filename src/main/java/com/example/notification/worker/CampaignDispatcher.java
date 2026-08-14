package com.example.notification.worker;

import com.example.notification.event.CampaignCreatedEvent;
import com.example.notification.repository.CampaignRecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * Kicks off sending for a freshly created campaign.
 *
 * AFTER_COMMIT + @Async is the whole trick: the HTTP response is written and the
 * PENDING rows are visible to every other connection BEFORE the first send starts.
 * Without @Async the listener would still run on the request thread; without
 * AFTER_COMMIT the worker could read uncommitted data.
 *
 * This dispatch is only a latency optimisation — the RecoveryPoller gives the
 * actual completion guarantee (e.g. after a restart, where no event ever fires).
 */
@Component
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);

    private final CampaignRecipientRepository recipientRepository;
    private final SendWorker sendWorker;
    private final ThreadPoolTaskExecutor mailExecutor;

    public CampaignDispatcher(CampaignRecipientRepository recipientRepository,
                              SendWorker sendWorker,
                              @Qualifier("mailExecutor") ThreadPoolTaskExecutor mailExecutor) {
        this.recipientRepository = recipientRepository;
        this.sendWorker = sendWorker;
        this.mailExecutor = mailExecutor;
    }

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCampaignCreated(CampaignCreatedEvent event) {
        List<UUID> pendingIds = recipientRepository.findPendingIdsByCampaign(event.campaignId());
        log.info("Dispatching campaign {} ({} recipients)", event.campaignId(), pendingIds.size());
        pendingIds.forEach(id -> mailExecutor.execute(() -> sendWorker.process(id)));
    }
}
