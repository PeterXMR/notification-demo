package com.example.notification.api.dto;

import com.example.notification.domain.RecipientStatus;

import java.util.List;
import java.util.UUID;

/**
 * Per-recipient view of what the mail provider actually did — the verification API for
 * the "internal retries must not re-send the same notification" guarantee. For every
 * delivered recipient {@code delivered == 1} must hold, no matter how many retries or
 * how many times the provider was called.
 */
public record CampaignDeliveriesResponse(UUID campaignId,
                                         List<RecipientDeliveries> recipients,
                                         long totalDelivered,
                                         long totalDuplicatesSuppressed) {

    /**
     * @param attempts             claim episodes recorded in the database
     * @param providerCalls        calls that reached the provider (failures included)
     * @param delivered            messages actually handed over — never more than 1
     * @param duplicatesSuppressed re-sends the provider refused thanks to the idempotency key
     */
    public record RecipientDeliveries(String email,
                                      RecipientStatus status,
                                      int attempts,
                                      int providerCalls,
                                      int delivered,
                                      int duplicatesSuppressed) {
    }
}
