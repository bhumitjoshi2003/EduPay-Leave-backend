package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.*;
import com.indraacademy.ias_management.service.PlanService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SUPER_ADMIN endpoints for managing subscription plans, features, and global config.
 * All routes under /api/super-admin/
 */
@RestController
@RequestMapping("/api/super-admin")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    @Autowired private PlanService planService;
    @Autowired private SecurityUtil securityUtil;

    // ── Plans ─────────────────────────────────────────────────────────────────

    /**
     * GET /api/super-admin/plans
     * List all plans. Pass ?includeInactive=true to include deactivated plans.
     */
    @GetMapping("/plans")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public List<PlanResponse> getAllPlans(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        log.info("GET /api/super-admin/plans includeInactive={}", includeInactive);
        return planService.getAllPlans(includeInactive);
    }

    /**
     * GET /api/super-admin/plans/{planId}
     * Get a single plan with its features and pending changes.
     */
    @GetMapping("/plans/{planId}")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public PlanResponse getPlan(@PathVariable Long planId) {
        log.info("GET /api/super-admin/plans/{}", planId);
        return planService.getPlan(planId);
    }

    /**
     * POST /api/super-admin/plans
     * Create a new plan.
     */
    @PostMapping("/plans")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public PlanResponse createPlan(@RequestBody PlanRequest request) {
        String adminId = securityUtil.getUsername();
        log.info("POST /api/super-admin/plans name='{}' by={}", request.getName(), adminId);
        return planService.createPlan(request, adminId);
    }

    /**
     * PUT /api/super-admin/plans/{planId}
     * Update plan details (name, limits, pricing). Does not affect features.
     */
    @PutMapping("/plans/{planId}")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public PlanResponse updatePlan(@PathVariable Long planId, @RequestBody PlanRequest request) {
        log.info("PUT /api/super-admin/plans/{}", planId);
        return planService.updatePlan(planId, request);
    }

    /**
     * DELETE /api/super-admin/plans/{planId}
     * Soft-delete (deactivate) a plan. Schools already on it are unaffected.
     */
    @DeleteMapping("/plans/{planId}")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Void> deactivatePlan(@PathVariable Long planId) {
        log.info("DELETE /api/super-admin/plans/{}", planId);
        planService.deactivatePlan(planId);
        return ResponseEntity.noContent().build();
    }

    // ── Plan Features ─────────────────────────────────────────────────────────

    /**
     * POST /api/super-admin/plans/{planId}/features
     * Add a feature to a plan immediately.
     * Body: { "featureKey": "ANALYTICS" }
     */
    @PostMapping("/plans/{planId}/features")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Void> addFeature(@PathVariable Long planId,
                                           @RequestBody AddPlanFeatureRequest request) {
        String adminId = securityUtil.getUsername();
        log.info("POST /api/super-admin/plans/{}/features feature={} by={}", planId, request.getFeatureKey(), adminId);
        planService.addFeatureToPlan(planId, request.getFeatureKey(), adminId);
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/super-admin/plans/{planId}/features/{featureKey}
     * Remove a feature from a plan with a given policy.
     * Body: { "policy": "NEXT_MONTHLY" }  (IMMEDIATE | NEXT_MONTHLY | NEXT_QUARTERLY | NEXT_ANNUAL)
     */
    @DeleteMapping("/plans/{planId}/features/{featureKey}")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Void> removeFeature(@PathVariable Long planId,
                                              @PathVariable String featureKey,
                                              @RequestBody RemovePlanFeatureRequest request) {
        String adminId = securityUtil.getUsername();
        log.info("DELETE /api/super-admin/plans/{}/features/{} policy={} by={}",
                planId, featureKey, request.getPolicy(), adminId);
        planService.removeFeatureFromPlan(planId, featureKey, request.getPolicy(), adminId);
        return ResponseEntity.ok().build();
    }

    // ── Feature Catalog ───────────────────────────────────────────────────────

    /**
     * GET /api/super-admin/features
     * List all features in the catalog (gateable + always-on).
     */
    @GetMapping("/features")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public List<FeatureCatalogResponse> getAllFeatures() {
        log.info("GET /api/super-admin/features");
        return planService.getAllFeatures();
    }

    // ── Global Subscription Config ────────────────────────────────────────────

    /**
     * GET /api/super-admin/subscription-config
     * Get platform-wide subscription settings.
     */
    @GetMapping("/subscription-config")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public GlobalSubscriptionConfigResponse getConfig() {
        log.info("GET /api/super-admin/subscription-config");
        return planService.getConfig();
    }

    /**
     * PUT /api/super-admin/subscription-config
     * Update platform-wide subscription settings.
     */
    @PutMapping("/subscription-config")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public GlobalSubscriptionConfigResponse updateConfig(
            @RequestBody GlobalSubscriptionConfigRequest request) {
        String adminId = securityUtil.getUsername();
        log.info("PUT /api/super-admin/subscription-config by={}", adminId);
        return planService.updateConfig(request, adminId);
    }
}
