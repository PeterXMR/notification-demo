package com.example.notification.api.dto;

import com.example.notification.domain.CampaignStatus;

import java.util.UUID;

public record CampaignCreatedResponse(UUID campaignId, CampaignStatus status) {
}
