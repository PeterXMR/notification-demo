package com.example.notification;

import com.example.notification.api.dto.CreateCampaignRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies API behavior when the database is unreachable: endpoints must answer
 * 503 Service Unavailable with a stable error body — not a raw 500.
 *
 * Uses its OWN Postgres container (not the shared one from {@link IntegrationTestBase})
 * because the container is stopped mid-test, which would break every other test class.
 * The class is single-shot: the container cannot be restarted for further test methods.
 * {@code @DirtiesContext} closes the context afterwards so its scheduled pollers don't
 * keep hammering the dead datasource for the rest of the suite.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Fail fast instead of waiting Hikari's default 30s for a connection.
        properties = "spring.datasource.hikari.connection-timeout=1000")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseOutageIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void endpointsReturn503WhenDatabaseIsDown() {
        // sanity: app is up and serving while the DB is alive
        ResponseEntity<String> healthy = rest.getForEntity("/api/campaigns/" + UUID.randomUUID(), String.class);
        assertThat(healthy.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        POSTGRES.stop();

        ResponseEntity<String> status = rest.getForEntity("/api/campaigns/" + UUID.randomUUID(), String.class);
        assertThat(status.getStatusCode())
                .as("GET status while DB is down")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(status.getBody()).contains("SERVICE_UNAVAILABLE");

        ResponseEntity<String> created = rest.postForEntity("/api/campaigns",
                new CreateCampaignRequest("Subject", "Message", List.of("user1@example.com")), String.class);
        assertThat(created.getStatusCode())
                .as("POST campaign while DB is down")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(created.getBody()).contains("SERVICE_UNAVAILABLE");
    }
}
