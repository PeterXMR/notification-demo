package com.example.notification.api.dto;

/** 503 — a backing service (database) is temporarily unreachable; the client should retry. */
public record ServiceUnavailableResponse(String error, String message) {
}
