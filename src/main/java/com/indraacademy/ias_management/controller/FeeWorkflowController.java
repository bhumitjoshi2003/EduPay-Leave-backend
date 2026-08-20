package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.*;
import com.indraacademy.ias_management.entity.StudentFeeAssignmentStatus;
import com.indraacademy.ias_management.service.FeeWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/fee-workflow")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class FeeWorkflowController {
    private final FeeWorkflowService service;
    public FeeWorkflowController(FeeWorkflowService service) { this.service = service; }

    @GetMapping("/settings") public ResponseEntity<?> settings() { return ResponseEntity.ok(service.getSettings()); }
    @PutMapping("/settings") public ResponseEntity<?> updateSettings(@RequestBody SettingsUpdate request, HttpServletRequest http) {
        return handle(() -> service.updateSettings(request, http.getRemoteAddr()));
    }
    @GetMapping("/assignments") public ResponseEntity<?> assignments(@RequestParam String session,
            @RequestParam(required=false) String className, @RequestParam(required=false) StudentFeeAssignmentStatus status) {
        return handle(() -> service.listAssignments(session, className, status));
    }
    @GetMapping("/assignments/summary") public ResponseEntity<?> summary(@RequestParam String session) {
        return handle(() -> service.summary(session));
    }
    @PostMapping("/assignments/assign") public ResponseEntity<?> assign(@RequestBody AssignmentRequest request, HttpServletRequest http) {
        return handle(() -> service.assign(request, false, http.getRemoteAddr()));
    }
    @PostMapping("/assignments/exclude") public ResponseEntity<?> exclude(@RequestBody AssignmentRequest request, HttpServletRequest http) {
        return handle(() -> service.assign(request, true, http.getRemoteAddr()));
    }
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody AssignmentRequest request) {
        return handle(() -> service.preview(request));
    }
    @PostMapping("/generate") public ResponseEntity<?> generate(@RequestBody AssignmentRequest request, HttpServletRequest http) {
        return handle(() -> service.generate(request, http.getRemoteAddr()));
    }
    @GetMapping("/generation-batches") public ResponseEntity<?> generationBatches(@RequestParam String session) {
        return handle(() -> service.generationBatches(session));
    }
    @PostMapping("/generation-batches/{batchId}/retry") public ResponseEntity<?> retryBatch(
            @PathVariable Long batchId, HttpServletRequest http) {
        return handle(() -> service.retryGenerationBatch(batchId, http.getRemoteAddr()));
    }
    @GetMapping("/reconciliation") public ResponseEntity<?> reconciliation(@RequestParam String session) {
        return handle(() -> service.reconciliation(session));
    }
    @PostMapping("/transport") public ResponseEntity<?> transport(@RequestBody TransportChangeRequest request, HttpServletRequest http) {
        return handle(() -> service.changeTransport(request, http.getRemoteAddr()));
    }
    @GetMapping("/transport/{studentId}") public ResponseEntity<?> transportHistory(@PathVariable String studentId, @RequestParam String session) {
        return handle(() -> service.transportHistory(studentId, session));
    }
    @PostMapping("/discounts") public ResponseEntity<?> discounts(@RequestBody BulkDiscountRequest request, HttpServletRequest http) {
        return handle(() -> service.applyBulkDiscount(request, http.getRemoteAddr()));
    }
    @GetMapping("/history/{studentId}") public ResponseEntity<?> history(@PathVariable String studentId,
            @RequestParam String session) {
        return handle(() -> service.lifecycleHistory(studentId, session));
    }
    @PutMapping("/discounts/{configId}/future") public ResponseEntity<?> updateFutureDiscount(
            @PathVariable Long configId, @RequestBody DiscountUpdateRequest request, HttpServletRequest http) {
        return handle(() -> service.updateFutureDiscount(configId, request, http.getRemoteAddr()));
    }
    @PostMapping("/discounts/{configId}/expire") public ResponseEntity<?> expireDiscount(
            @PathVariable Long configId, @RequestBody DiscountExpireRequest request, HttpServletRequest http) {
        return handle(() -> service.expireDiscount(configId, request, http.getRemoteAddr()));
    }
    @PostMapping("/discounts/{configId}/revoke-future") public ResponseEntity<?> revokeFutureDiscount(
            @PathVariable Long configId, @RequestBody RevokeFutureRequest request, HttpServletRequest http) {
        return handle(() -> service.revokeFutureDiscount(configId, request, http.getRemoteAddr()));
    }
    @PutMapping("/transport/{assignmentId}/future") public ResponseEntity<?> correctFutureTransport(
            @PathVariable Long assignmentId, @RequestBody TransportCorrectionRequest request, HttpServletRequest http) {
        return handle(() -> service.correctFutureTransport(assignmentId, request, http.getRemoteAddr()));
    }

    private ResponseEntity<?> handle(Action action) {
        try { return ResponseEntity.ok(action.run()); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
    }
    @FunctionalInterface private interface Action { Object run(); }
}
