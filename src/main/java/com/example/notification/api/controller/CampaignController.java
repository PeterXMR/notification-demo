package com.example.notification.api.controller;

import com.example.notification.api.dto.CampaignCreatedResponse;
import com.example.notification.api.dto.CampaignDeliveriesResponse;
import com.example.notification.api.dto.CampaignStatusResponse;
import com.example.notification.api.dto.CreateCampaignRequest;
import com.example.notification.service.CampaignService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    /** 202 Accepted — the request is taken, processing continues in the background. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CampaignCreatedResponse create(@Valid @RequestBody CreateCampaignRequest request) {
        return campaignService.create(request);
    }

    @GetMapping("/{campaignId}")
    public CampaignStatusResponse status(@PathVariable UUID campaignId) {
        return campaignService.getStatus(campaignId);
    }

    /**
     * Demo/verification endpoint: what the provider actually did per recipient.
     * Lets the Postman collection assert that no recipient was delivered more than once
     * (the "no uncontrolled repeated sending" acceptance criterion).
     *
     * Deliberately part of the public API for this assignment: the live Postman demo is a
     * required deliverable, and the aggregate status endpoint cannot distinguish one
     * provider call from five. The provider-side counters only make the exactly-once
     * guarantee observable — enforcement lives in the workers' state transitions and is
     * independently covered by integration tests. In a production system this endpoint
     * would shrink to per-recipient state (status, attempts, lastError) or move behind
     * a demo profile.
     */
    @GetMapping("/{campaignId}/deliveries")
    public CampaignDeliveriesResponse deliveries(@PathVariable UUID campaignId) {
        return campaignService.getDeliveries(campaignId);
    }
}
