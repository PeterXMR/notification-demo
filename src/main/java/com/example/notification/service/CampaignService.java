package com.example.notification.service;

import com.example.notification.api.dto.CampaignCreatedResponse;
import com.example.notification.api.dto.CampaignDeliveriesResponse;
import com.example.notification.api.dto.CampaignStatusResponse;
import com.example.notification.api.dto.CreateCampaignRequest;
import com.example.notification.domain.Campaign;
import com.example.notification.domain.CampaignRecipient;
import com.example.notification.domain.CampaignStatus;
import com.example.notification.domain.RecipientStatus;
import com.example.notification.domain.User;
import com.example.notification.event.CampaignCreatedEvent;
import com.example.notification.exception.CampaignNotFoundException;
import com.example.notification.exception.InvalidRecipientsException;
import com.example.notification.mail.DeliveryLog;
import com.example.notification.repository.CampaignRecipientRepository;
import com.example.notification.repository.CampaignRepository;
import com.example.notification.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final DeliveryLog deliveryLog;

    public CampaignService(CampaignRepository campaignRepository,
                           CampaignRecipientRepository recipientRepository,
                           UserRepository userRepository,
                           ApplicationEventPublisher eventPublisher,
                           DeliveryLog deliveryLog) {
        this.campaignRepository = campaignRepository;
        this.recipientRepository = recipientRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.deliveryLog = deliveryLog;
    }

    /**
     * Validates and persists the campaign with all recipients in PENDING — in one transaction.
     * Sending starts only AFTER_COMMIT (see {@link com.example.notification.worker.CampaignDispatcher}),
     * so the client gets the campaignId without waiting for a single email, and the status
     * API sees the campaign immediately.
     */
    @Transactional
    public CampaignCreatedResponse create(CreateCampaignRequest request) {
        // silent deduplication (case-insensitive); "total" reflects unique recipients
        List<String> emails = request.recipients().stream()
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();

        Map<String, User> usersByEmail = userRepository.findByEmailIn(emails).stream()
                .collect(Collectors.toMap(User::getEmail, Function.identity()));

        List<String> unknown = emails.stream()
                .filter(e -> !usersByEmail.containsKey(e))
                .toList();
        List<String> inactive = emails.stream()
                .filter(e -> usersByEmail.containsKey(e) && !usersByEmail.get(e).isActive())
                .toList();
        if (!unknown.isEmpty() || !inactive.isEmpty()) {
            throw new InvalidRecipientsException(unknown, inactive);
        }

        Campaign campaign = campaignRepository.save(new Campaign(request.subject(), request.message()));
        List<CampaignRecipient> recipients = emails.stream()
                .map(e -> CampaignRecipient.pending(campaign, usersByEmail.get(e)))
                .toList();
        recipientRepository.saveAll(recipients);

        eventPublisher.publishEvent(new CampaignCreatedEvent(campaign.getId()));
        log.info("Campaign {} accepted with {} recipient(s)", campaign.getId(), recipients.size());

        return new CampaignCreatedResponse(campaign.getId(), CampaignStatus.PROCESSING);
    }

    /**
     * Campaign status is a pure function of one aggregation query — nothing is stored,
     * so the numbers cannot drift from reality and are correct even right after a restart.
     */
    @Transactional(readOnly = true)
    public CampaignStatusResponse getStatus(UUID campaignId) {
        if (!campaignRepository.existsById(campaignId)) {
            throw new CampaignNotFoundException(campaignId);
        }

        Map<RecipientStatus, Long> counts = new EnumMap<>(RecipientStatus.class);
        recipientRepository.countByStatusForCampaign(campaignId)
                .forEach(row -> counts.put(row.getStatus(), row.getCnt()));

        long sent = counts.getOrDefault(RecipientStatus.SENT, 0L);
        long failed = counts.getOrDefault(RecipientStatus.FAILED, 0L);
        long remaining = counts.getOrDefault(RecipientStatus.PENDING, 0L)
                + counts.getOrDefault(RecipientStatus.SENDING, 0L);
        long total = sent + failed + remaining;

        CampaignStatus status = remaining > 0 ? CampaignStatus.PROCESSING : CampaignStatus.COMPLETED;
        return new CampaignStatusResponse(campaignId, status, total, sent, failed, remaining);
    }

    /**
     * Verification view for the exactly-once guarantee: DB attempts joined with the
     * provider-side {@link DeliveryLog}. For every delivered recipient the invariant
     * {@code delivered == 1} holds regardless of retries — visible proof that internal
     * repetition never re-sends an already delivered notification.
     */
    @Transactional(readOnly = true)
    public CampaignDeliveriesResponse getDeliveries(UUID campaignId) {
        if (!campaignRepository.existsById(campaignId)) {
            throw new CampaignNotFoundException(campaignId);
        }

        List<CampaignDeliveriesResponse.RecipientDeliveries> recipients =
                recipientRepository.findByCampaignIdOrderByEmail(campaignId).stream()
                        .map(r -> {
                            DeliveryLog.Delivery d = deliveryLog.find(r.getId())
                                    .orElse(new DeliveryLog.Delivery(r.getId(), r.getEmail(), 0, 0, 0));
                            return new CampaignDeliveriesResponse.RecipientDeliveries(
                                    r.getEmail(), r.getStatus(), r.getAttempts(),
                                    d.providerCalls(), d.delivered(), d.duplicatesSuppressed());
                        })
                        .toList();

        long totalDelivered = recipients.stream()
                .mapToLong(CampaignDeliveriesResponse.RecipientDeliveries::delivered).sum();
        long totalDuplicates = recipients.stream()
                .mapToLong(CampaignDeliveriesResponse.RecipientDeliveries::duplicatesSuppressed).sum();

        return new CampaignDeliveriesResponse(campaignId, recipients, totalDelivered, totalDuplicates);
    }
}
