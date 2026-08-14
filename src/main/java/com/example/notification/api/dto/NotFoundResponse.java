package com.example.notification.api.dto;

import java.util.UUID;

/** 404 — unknown campaign id. */
public record NotFoundResponse(String error, UUID campaignId) {
}
