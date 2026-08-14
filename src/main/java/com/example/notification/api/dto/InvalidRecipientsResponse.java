package com.example.notification.api.dto;

import java.util.List;

/** 422 — semantically invalid recipients; the whole campaign was rejected (all-or-nothing). */
public record InvalidRecipientsResponse(String error, List<String> unknown, List<String> inactive) {
}
