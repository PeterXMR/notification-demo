package com.example.notification.api.dto;

import java.util.List;

/** 400 — syntactic problems: blank fields, invalid email format, recipient limit exceeded. */
public record ValidationErrorResponse(String error, List<String> details) {
}
