package com.example.notification.domain;

/**
 * Campaign status is never stored — it is derived from recipient states:
 * PROCESSING while any recipient is PENDING or SENDING, COMPLETED otherwise.
 */
public enum CampaignStatus {
    PROCESSING,
    COMPLETED
}
