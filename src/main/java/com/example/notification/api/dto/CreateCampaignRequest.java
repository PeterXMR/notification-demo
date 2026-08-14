package com.example.notification.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateCampaignRequest(

        @NotBlank(message = "subject must not be blank")
        @Size(max = CreateCampaignRequest.MAX_SUBJECT_LENGTH,
                message = "subject must not exceed " + CreateCampaignRequest.MAX_SUBJECT_LENGTH + " characters")
        String subject,

        @NotBlank(message = "message must not be blank")
        String message,

        @NotEmpty(message = "recipients must not be empty")
        @Size(max = CreateCampaignRequest.MAX_RECIPIENTS,
                message = "recipients must not exceed " + CreateCampaignRequest.MAX_RECIPIENTS)
        List<@NotBlank(message = "recipient email must not be blank")
             @Email(message = "invalid email format")
             @Size(max = CreateCampaignRequest.MAX_EMAIL_LENGTH,
                     message = "recipient email must not exceed " + CreateCampaignRequest.MAX_EMAIL_LENGTH
                             + " characters") String> recipients) {

    public static final int MAX_RECIPIENTS = 100;

    // mirror the DB column limits (campaign.subject VARCHAR(255), users.email VARCHAR(320))
    // so an oversized value is a clean 400 instead of an insert-time constraint violation
    public static final int MAX_SUBJECT_LENGTH = 255;
    public static final int MAX_EMAIL_LENGTH = 320;
}
