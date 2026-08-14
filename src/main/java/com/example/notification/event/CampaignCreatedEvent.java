package com.example.notification.event;

import java.util.UUID;

/** Published inside the create-campaign transaction; consumed only AFTER_COMMIT. */
public record CampaignCreatedEvent(UUID campaignId) {
}
