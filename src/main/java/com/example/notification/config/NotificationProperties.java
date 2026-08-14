package com.example.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Simulator simulator, Worker worker, Retry retry, Poller poller) {

    public record Simulator(long latencyMs, long slowLatencyMs, int transientFailuresBeforeSuccess) {
    }

    public record Worker(int concurrency) {
    }

    public record Retry(int maxAttempts, long backoffMs) {
    }

    public record Poller(long intervalMs, long stuckTimeoutMs, int batchSize) {
    }
}
