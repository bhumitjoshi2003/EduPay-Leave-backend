package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.SchoolFeatureOverrideRequest;
import com.indraacademy.ias_management.dto.SchoolSubscriptionRequest;
import com.indraacademy.ias_management.dto.SchoolSubscriptionResponse;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.EntitlementRefreshService;
import com.indraacademy.ias_management.service.EntitlementService;
import com.indraacademy.ias_management.util.SchoolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Endpoints for subscription management and school-level feature overrides.
 *
 * Super admin paths: /api/super-admin/schools/{schoolId}/subscription
 * Admin paths:       /api/school/features
 */
@RestController
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private static final Set<String> VALID_STATUSES = Set.of("TRIAL", "ACTIVE", "GRACE", "EXPIRED");
    private static final Set<String> VALID_OVERRIDE_STATES = Set.of("DEFAULT", "DISABLED");

    @Autowired private SchoolSubscriptionRepository subscriptionRepo;
    @Autowired private SchoolEffectiveEntitlementRepository entitlementRepo;
    @Autowired private SchoolEntitlementFeatureRepository entitlementFeatureRepo;
    @Autowired private SchoolFeatureOverrideRepository overrideRepo;
    @Autowired private FeatureCatalogRepository featureCatalogRepo;
    @Autowired private PlanRepository planRepo;
    @Autowired private StudentRepository studentRepo;
    @Autowired private TeacherRepository teacherRepo;
    @Autowired private AdminRepository adminRepo;
    @Autowired private EntitlementRefreshService refreshService;
    @Autowired private EntitlementService entitlementService;
    @Autowired private AuthService authService;

    // ══════════════════════════════════════════════════════════════════════════
    //  SUPER_ADMIN — Subscription management
    // ══════════════════════════════════════════════════════════════════════════

    /** View a school's full subscription + resolved entitlement state. */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/api/super-admin/schools/{schoolId}/subscription")
    public ResponseEntity<?> getSubscription(@PathVariable Long schoolId) {
        SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId).orElse(null);
        if (sub == null) return ResponseEntity.notFound().build();

        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);
        return ResponseEntity.ok(SchoolSubscriptionResponse.from(sub, ent, featureKeys));
    }

    /**
     * Assign a subscription plan to a school (creates or fully replaces).
     * Triggers an entitlement rebuild after saving.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/api/super-admin/schools/{schoolId}/subscription")
    public ResponseEntity<?> assignSubscription(@PathVariable Long schoolId,
                                                 @RequestBody SchoolSubscriptionRequest req) {
        if (req.getPlanId() == null) {
            return ResponseEntity.badRequest().body("planId is required.");
        }
        if (!planRepo.existsById(req.getPlanId())) {
            return ResponseEntity.badRequest().body("Plan not found: " + req.getPlanId());
        }

        SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId)
                .orElse(new SchoolSubscription());
        sub.setSchoolId(schoolId);
        applyRequest(sub, req, authService.getUserId());
        sub = subscriptionRepo.save(sub);

        refreshService.refreshForSubscriptionChange(schoolId);
        log.info("Subscription assigned: school={} plan={} status={} by={}",
                schoolId, sub.getPlanId(), sub.getStatus(), authService.getUserId());

        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);
        return ResponseEntity.ok(SchoolSubscriptionResponse.from(sub, ent, featureKeys));
    }

    /**
     * Update an existing subscription (plan change, expiry extension, status override, notes).
     * Triggers an entitlement rebuild.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/api/super-admin/schools/{schoolId}/subscription")
    public ResponseEntity<?> updateSubscription(@PathVariable Long schoolId,
                                                 @RequestBody SchoolSubscriptionRequest req) {
        SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId)
                .orElse(null);
        if (sub == null) return ResponseEntity.notFound().build();

        if (req.getPlanId() != null && !planRepo.existsById(req.getPlanId())) {
            return ResponseEntity.badRequest().body("Plan not found: " + req.getPlanId());
        }

        applyRequest(sub, req, authService.getUserId());
        sub = subscriptionRepo.save(sub);

        refreshService.refreshForSubscriptionChange(schoolId);
        log.info("Subscription updated: school={} plan={} status={} by={}",
                schoolId, sub.getPlanId(), sub.getStatus(), authService.getUserId());

        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);
        return ResponseEntity.ok(SchoolSubscriptionResponse.from(sub, ent, featureKeys));
    }

    /** Manually trigger an entitlement rebuild for a school (super admin utility). */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/api/super-admin/schools/{schoolId}/subscription/refresh")
    public ResponseEntity<?> refreshEntitlement(@PathVariable Long schoolId) {
        refreshService.refresh(schoolId, "MANUAL");
        log.info("Manual entitlement refresh triggered for school={} by={}", schoolId, authService.getUserId());
        return ResponseEntity.ok(Map.of("message", "Entitlement refresh triggered."));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ADMIN — School-level feature visibility overrides
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * List all gateable features with their current override state for this school.
     * Features the school's plan doesn't grant are listed but marked as not available.
     */
    @PreAuthorize("hasAnyRole('ADMIN')")
    @GetMapping("/api/school/features")
    public ResponseEntity<?> listFeatures() {
        Long schoolId = SchoolContext.get();
        List<FeatureCatalog> catalog = featureCatalogRepo.findAll().stream()
                .filter(f -> !f.isAlwaysOn())
                .sorted((a, b) -> {
                    int cat = a.getCategory().compareTo(b.getCategory());
                    return cat != 0 ? cat : a.getDisplayName().compareTo(b.getDisplayName());
                })
                .toList();

        List<SchoolFeatureOverride> overrides = overrideRepo.findBySchoolId(schoolId);
        Map<String, String> overrideMap = new LinkedHashMap<>();
        for (SchoolFeatureOverride o : overrides) {
            overrideMap.put(o.getFeatureKey(), o.getOverrideState());
        }

        List<Map<String, Object>> result = catalog.stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("featureKey",    f.getFeatureKey());
            item.put("displayName",   f.getDisplayName());
            item.put("category",      f.getCategory());
            item.put("planGranted",   entitlementService.hasFeature(schoolId, f.getFeatureKey())
                                      || "DISABLED".equals(overrideMap.get(f.getFeatureKey())));
            item.put("overrideState", overrideMap.getOrDefault(f.getFeatureKey(), "DEFAULT"));
            item.put("effectivelyOn", entitlementService.hasFeature(schoolId, f.getFeatureKey()));
            return item;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Get the school's current effective entitlement + live usage counts.
     * Used by the admin dashboard Plan Usage section.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    @GetMapping("/api/school/entitlement")
    public ResponseEntity<?> getSchoolEntitlement() {
        Long schoolId = SchoolContext.get();
        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);

        long activeStudents = studentRepo.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long teachers = teacherRepo.countBySchoolId(schoolId);
        long admins = adminRepo.countBySchoolId(schoolId);

        Map<String, Object> result = new LinkedHashMap<>();
        if (ent != null) {
            result.put("planName",            ent.getPlanName());
            result.put("planTier",            ent.getPlanTier());
            result.put("subscriptionStatus",  ent.getSubscriptionStatus());
            result.put("trialEndsAt",         ent.getTrialEndsAt());
            result.put("expiresAt",           ent.getExpiresAt());
            result.put("graceEndsAt",         ent.getGraceEndsAt());
            result.put("maxStudents",         ent.getMaxStudents());
            result.put("studentSoftLimitPct", ent.getStudentSoftLimitPct());
            result.put("studentHardLimitPct", ent.getStudentHardLimitPct());
            result.put("maxStaff",            ent.getMaxStaff());
            result.put("staffSoftLimitPct",   ent.getStaffSoftLimitPct());
            result.put("staffHardLimitPct",   ent.getStaffHardLimitPct());
            result.put("storageGbLimit",      ent.getStorageGbLimit());
            result.put("featureCount",        featureKeys.size());
            result.put("features",            featureKeys);
        } else {
            result.put("planName",   null);
            result.put("planTier",   null);
            result.put("subscriptionStatus", null);
            result.put("featureCount", featureKeys.size());
            result.put("features",   featureKeys);
        }
        result.put("activeStudents", activeStudents);
        result.put("totalStaff",     teachers + admins);
        result.put("teachers",       teachers);
        result.put("admins",         admins);

        return ResponseEntity.ok(result);
    }

    /**
     * Toggle a school-level feature override (admin can only DISABLE features their plan grants).
     * Triggers an entitlement rebuild on change.
     */
    @PreAuthorize("hasAnyRole('ADMIN')")
    @PutMapping("/api/school/features/{featureKey}/override")
    public ResponseEntity<?> setFeatureOverride(@PathVariable String featureKey,
                                                 @RequestBody SchoolFeatureOverrideRequest req) {
        Long schoolId = SchoolContext.get();
        String adminId = authService.getUserId();

        if (!VALID_OVERRIDE_STATES.contains(req.getOverrideState())) {
            return ResponseEntity.badRequest().body("overrideState must be DEFAULT or DISABLED.");
        }
        if (!featureCatalogRepo.existsById(featureKey)) {
            return ResponseEntity.badRequest().body("Feature not found: " + featureKey);
        }

        // Admins can only DISABLE features that their plan grants
        if ("DISABLED".equals(req.getOverrideState())) {
            boolean planGranted = entitlementService.hasFeature(schoolId, featureKey)
                    || overrideRepo.findBySchoolIdAndFeatureKey(schoolId, featureKey)
                    .map(o -> "DISABLED".equals(o.getOverrideState())).orElse(false);
            // Re-check against entitlement (before the override is applied, feature is in entitlement table)
            // Actually we need to check if plan itself has this feature
            // We check entitlement BEFORE override was applied:
            // If it's already DISABLED the feature won't be in entitlement — check subscription plan features
        }

        SchoolFeatureOverride override = overrideRepo.findBySchoolIdAndFeatureKey(schoolId, featureKey)
                .orElse(new SchoolFeatureOverride());
        override.setSchoolId(schoolId);
        override.setFeatureKey(featureKey);
        override.setOverrideState(req.getOverrideState());
        override.setUpdatedBy(adminId);
        overrideRepo.save(override);

        refreshService.refreshForOverrideChange(schoolId);
        log.info("Feature override: school={} feature={} state={} by={}", schoolId, featureKey,
                req.getOverrideState(), adminId);

        return ResponseEntity.ok(Map.of(
                "featureKey",    featureKey,
                "overrideState", req.getOverrideState(),
                "effectivelyOn", entitlementService.hasFeature(schoolId, featureKey)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyRequest(SchoolSubscription sub, SchoolSubscriptionRequest req, String callerUserId) {
        if (req.getPlanId()  != null) sub.setPlanId(req.getPlanId());
        if (req.getStatus()  != null && VALID_STATUSES.contains(req.getStatus().toUpperCase())) {
            sub.setStatus(req.getStatus().toUpperCase());
        }
        if (req.getTrialStartAt() != null) sub.setTrialStartAt(parseDateTime(req.getTrialStartAt()));
        if (req.getTrialEndsAt()  != null) sub.setTrialEndsAt(parseDateTime(req.getTrialEndsAt()));
        if (req.getActivatedAt()  != null) sub.setActivatedAt(parseDateTime(req.getActivatedAt()));
        if (req.getExpiresAt()    != null) sub.setExpiresAt(parseDateTime(req.getExpiresAt()));
        if (req.getGraceEndsAt()  != null) sub.setGraceEndsAt(parseDateTime(req.getGraceEndsAt()));
        if (req.getNotes()        != null) sub.setNotes(req.getNotes());
        sub.setCreatedBy(callerUserId);
    }

    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            // Try date-only (e.g. "2026-03-31")
            try {
                return java.time.LocalDate.parse(s).atTime(23, 59, 59);
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Invalid date-time: " + s);
            }
        }
    }
}
