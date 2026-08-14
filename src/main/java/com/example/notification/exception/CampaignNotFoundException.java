package com.example.notification.exception;

import java.util.UUID;

public class CampaignNotFoundException extends RuntimeException {

    private final UUID campaignId;

    public CampaignNotFoundException(UUID campaignId) {
        super("Campaign not found: " + campaignId);
        this.campaignId = campaignId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }
}
