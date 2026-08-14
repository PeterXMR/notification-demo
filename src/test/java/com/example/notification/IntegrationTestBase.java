package com.example.notification;

import com.example.notification.api.dto.CampaignStatusResponse;
import com.example.notification.api.dto.CreateCampaignRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

/**
 * Shared Postgres container (singleton pattern) — started once, reused by all test classes.
 * Tests run against the same database engine as production, including Flyway migrations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    protected ResponseEntity<String> postCampaign(String subject, String message, List<String> recipients) {
        return rest.postForEntity("/api/campaigns",
                new CreateCampaignRequest(subject, message, recipients), String.class);
    }

    protected UUID createCampaign(List<String> recipients) {
        ResponseEntity<CampaignCreated> response = rest.postForEntity("/api/campaigns",
                new CreateCampaignRequest("Test subject", "Test message", recipients), CampaignCreated.class);
        if (response.getStatusCode().value() != 202 || response.getBody() == null) {
            throw new AssertionError("Expected 202 with body, got: " + response.getStatusCode());
        }
        return response.getBody().campaignId();
    }

    protected CampaignStatusResponse getStatus(UUID campaignId) {
        ResponseEntity<CampaignStatusResponse> response =
                rest.getForEntity("/api/campaigns/" + campaignId, CampaignStatusResponse.class);
        if (response.getBody() == null) {
            throw new AssertionError("Expected status body, got: " + response.getStatusCode());
        }
        return response.getBody();
    }

    protected record CampaignCreated(UUID campaignId, String status) {
    }
}
