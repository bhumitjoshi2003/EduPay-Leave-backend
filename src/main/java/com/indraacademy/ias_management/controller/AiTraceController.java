package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.AiTraceRequest;
import com.indraacademy.ias_management.service.AiTraceService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest endpoint for AI Copilot chat-turn traces (see edunexify-ai/tracing.py).
 * Same trust pattern as /api/knowledge-base/search: Python forwards the user's own
 * accessToken cookie, so this is a normal authenticated request — no special-casing,
 * no separate internal-secret channel. Fire-and-forget from Python's side; failures
 * here are logged there and never surfaced to the chat user.
 */
@RestController
@RequestMapping("/api/ai")
public class AiTraceController {

    private static final Logger log = LoggerFactory.getLogger(AiTraceController.class);

    @Autowired private AiTraceService aiTraceService;

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/trace")
    public ResponseEntity<Void> recordTrace(@Valid @RequestBody AiTraceRequest request) {
        try {
            aiTraceService.record(request);
        } catch (Exception e) {
            // Telemetry must never fail loudly enough to matter to a caller — log and move on.
            log.warn("Failed to record AI trace event: {}", e.getMessage());
        }
        return ResponseEntity.accepted().build();
    }
}
