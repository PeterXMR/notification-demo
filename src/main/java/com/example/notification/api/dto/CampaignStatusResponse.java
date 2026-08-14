package com.example.notification.api.dto;

import com.example.notification.domain.CampaignStatus;

import java.util.UUID;

public record CampaignStatusResponse(
        UUID campaignId,
        CampaignStatus status,
        long total,
        long sent,
        long failed,
        long remaining) {
}
