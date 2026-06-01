package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.SchoolFeatureOverrideRequest;
import com.indraacademy.ias_management.dto.SchoolSubscriptionRequest;
import com.indraacademy.ias_management.dto.SchoolSubscriptionResponse;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.entity.SubscriptionPlan;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.EntitlementRefreshService;
import com.indraacademy.ias_management.service.EntitlementService;
import com.indraacademy.ias_management.service.RazorpayService;
import com.indraacademy.ias_management.util.SchoolContext;
import com.razorpay.RazorpayException;
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
import org.springframework.data.domain.PageRequest;

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
    private static final Set<String> VALID_OVERRIDE_STATES = Set.of("DEFAULT", "DISABLED", "ENABLED");

    @Autowired private SchoolSubscriptionRepository subscriptionRepo;
    @Autowired private SchoolEffectiveEntitlementRepository entitlementRepo;
    @Autowired private SchoolEntitlementFeatureRepository entitlementFeatureRepo;
    @Autowired private SchoolFeatureOverrideRepository overrideRepo;
    @Autowired private FeatureCatalogRepository featureCatalogRepo;
    @Autowired private PlanRepository planRepo;
    @Autowired private PlanFeatureRepository planFeatureRepository;
    @Autowired private StudentRepository studentRepo;
    @Autowired private TeacherRepository teacherRepo;
    @Autowired private AdminRepository adminRepo;
    @Autowired private GlobalSubscriptionConfigRepository globalConfigRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private SchoolSubscriptionHistoryRepository historyRepo;
    @Autowired private EntitlementRefreshService refreshService;
    @Autowired private EntitlementService entitlementService;
    @Autowired private AuthService authService;
    @Autowired private RazorpayService razorpayService;

    // ══════════════════════════════════════════════════════════════════════════
    //  SUPER_ADMIN — Subscription management
    // ══════════════════════════════════════════════════════════════════════════

    /** View a school's full subscription + resolved entitlement state + live usage counts. */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/api/super-admin/schools/{schoolId}/subscription")
    public ResponseEntity<?> getSubscription(@PathVariable Long schoolId) {
        SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId).orElse(null);
        if (sub == null) return ResponseEntity.notFound().build();

        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);
        long activeStudents = studentRepo.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long currentStaff   = teacherRepo.countBySchoolId(schoolId) + adminRepo.countBySchoolId(schoolId);
        return ResponseEntity.ok(SchoolSubscriptionResponse.from(sub, ent, featureKeys, activeStudents, currentStaff));
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
        syncLegacyPlanField(schoolId, sub.getPlanId());
        log.info("Subscription assigned: school={} plan={} status={} by={}",
                schoolId, sub.getPlanId(), sub.getStatus(), authService.getUserId());
        logHistory(schoolId, "PLAN_ASSIGNED", sub.getPlanId(),
                planRepo.findById(sub.getPlanId()).map(p -> p.getName()).orElse("Unknown"),
                sub.getStatus(), sub.getNotes(), authService.getUserId());

        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);
        long activeStudents = studentRepo.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long currentStaff   = teacherRepo.countBySchoolId(schoolId) + adminRepo.countBySchoolId(schoolId);
        return ResponseEntity.ok(SchoolSubscriptionResponse.from(sub, ent, featureKeys, activeStudents, currentStaff));
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
        syncLegacyPlanField(schoolId, sub.getPlanId());
        log.info("Subscription updated: school={} plan={} status={} by={}",
                schoolId, sub.getPlanId(), sub.getStatus(), authService.getUserId());
        logHistory(schoolId, "PLAN_UPDATED", sub.getPlanId(),
                planRepo.findById(sub.getPlanId()).map(p -> p.getName()).orElse("Unknown"),
                sub.getStatus(), sub.getNotes(), authService.getUserId());

        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);
        long activeStudents = studentRepo.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long currentStaff   = teacherRepo.countBySchoolId(schoolId) + adminRepo.countBySchoolId(schoolId);
        return ResponseEntity.ok(SchoolSubscriptionResponse.from(sub, ent, featureKeys, activeStudents, currentStaff));
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

        // Features the school's current plan actually grants (before overrides)
        SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId).orElse(null);
        java.util.Set<String> planFeatureKeys = sub != null
                ? planFeatureRepository.findByPlanId(sub.getPlanId()).stream()
                    .map(com.indraacademy.ias_management.entity.PlanFeature::getFeatureKey)
                    .collect(java.util.stream.Collectors.toSet())
                : java.util.Set.of();

        List<Map<String, Object>> result = catalog.stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("featureKey",    f.getFeatureKey());
            item.put("displayName",   f.getDisplayName());
            item.put("category",      f.getCategory());
            item.put("planGranted",   planFeatureKeys.contains(f.getFeatureKey()));
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
            result.put("planName",            null);
            result.put("planTier",            null);
            result.put("subscriptionStatus",  null);
            result.put("trialEndsAt",         null);
            result.put("expiresAt",           null);
            result.put("graceEndsAt",         null);
            result.put("maxStudents",         null);
            result.put("studentSoftLimitPct", null);
            result.put("studentHardLimitPct", null);
            result.put("maxStaff",            null);
            result.put("staffSoftLimitPct",   null);
            result.put("staffHardLimitPct",   null);
            result.put("storageGbLimit",      null);
            result.put("featureCount",        featureKeys.size());
            result.put("features",            featureKeys);
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

        // Admins can only set DEFAULT or DISABLED — only super admins can ENABLE features
        if ("ENABLED".equals(req.getOverrideState())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Admins cannot enable features not included in their plan. Contact support.");
        }
        if (!VALID_OVERRIDE_STATES.contains(req.getOverrideState())) {
            return ResponseEntity.badRequest().body("overrideState must be DEFAULT or DISABLED.");
        }
        if (!featureCatalogRepo.existsById(featureKey)) {
            return ResponseEntity.badRequest().body("Feature not found: " + featureKey);
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

    // ══════════════════════════════════════════════════════════════════════════
    //  SUPER_ADMIN — Per-school feature overrides
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * List all features with their current override state for a specific school.
     * Returns both plan-granted features and manually enabled extras.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/api/super-admin/schools/{schoolId}/features")
    public ResponseEntity<?> listSchoolFeatures(@PathVariable Long schoolId) {
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

        // Features the school's current plan grants (independent of overrides)
        SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId).orElse(null);
        java.util.Set<String> planFeatureKeys = sub != null
                ? planFeatureRepository.findByPlanId(sub.getPlanId()).stream()
                    .map(com.indraacademy.ias_management.entity.PlanFeature::getFeatureKey)
                    .collect(java.util.stream.Collectors.toSet())
                : java.util.Set.of();

        List<Map<String, Object>> result = catalog.stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            String state = overrideMap.getOrDefault(f.getFeatureKey(), "DEFAULT");
            boolean planGranted = planFeatureKeys.contains(f.getFeatureKey());
            item.put("featureKey",    f.getFeatureKey());
            item.put("displayName",   f.getDisplayName());
            item.put("category",      f.getCategory());
            item.put("planGranted",   planGranted);
            item.put("overrideState", state);
            item.put("effectivelyOn", entitlementService.hasFeature(schoolId, f.getFeatureKey()));
            return item;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Set a feature override for a specific school (super admin only).
     * DEFAULT  — remove override (revert to plan default)
     * DISABLED — disable even if plan grants it
     * ENABLED  — enable even if plan does not grant it
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/api/super-admin/schools/{schoolId}/features/{featureKey}/override")
    public ResponseEntity<?> setSuperAdminFeatureOverride(@PathVariable Long schoolId,
                                                          @PathVariable String featureKey,
                                                          @RequestBody SchoolFeatureOverrideRequest req) {
        if (!VALID_OVERRIDE_STATES.contains(req.getOverrideState())) {
            return ResponseEntity.badRequest().body("overrideState must be DEFAULT, DISABLED, or ENABLED.");
        }
        if (!featureCatalogRepo.existsById(featureKey)) {
            return ResponseEntity.badRequest().body("Feature not found: " + featureKey);
        }

        if ("DEFAULT".equals(req.getOverrideState())) {
            // Remove any existing override
            overrideRepo.findBySchoolIdAndFeatureKey(schoolId, featureKey)
                    .ifPresent(overrideRepo::delete);
        } else {
            SchoolFeatureOverride override = overrideRepo.findBySchoolIdAndFeatureKey(schoolId, featureKey)
                    .orElse(new SchoolFeatureOverride());
            override.setSchoolId(schoolId);
            override.setFeatureKey(featureKey);
            override.setOverrideState(req.getOverrideState());
            override.setUpdatedBy(authService.getUserId());
            overrideRepo.save(override);
        }

        refreshService.refreshForOverrideChange(schoolId);
        log.info("Super admin feature override: school={} feature={} state={} by={}",
                schoolId, featureKey, req.getOverrideState(), authService.getUserId());

        return ResponseEntity.ok(Map.of(
                "featureKey",    featureKey,
                "overrideState", req.getOverrideState(),
                "effectivelyOn", entitlementService.hasFeature(schoolId, featureKey)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ADMIN — School-initiated plan upgrade via Razorpay
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a Razorpay order for upgrading to a paid plan.
     * Uses platform-global Razorpay keys (subscription revenue goes to platform, not school).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/school/subscription/upgrade/order")
    public ResponseEntity<?> createUpgradeOrder(@RequestBody Map<String, Object> body) {
        Long planId = body.get("planId") != null ? Long.valueOf(body.get("planId").toString()) : null;
        String billingCycle = body.getOrDefault("billingCycle", "MONTHLY").toString().toUpperCase();

        if (planId == null) return ResponseEntity.badRequest().body("planId is required.");
        Plan plan = planRepo.findById(planId).orElse(null);
        if (plan == null || !plan.isPublic() || !plan.isActive()) {
            return ResponseEntity.badRequest().body("Plan not found or not available.");
        }

        Long amountPaise = "ANNUAL".equals(billingCycle) ? plan.getAnnualPricePaise() : plan.getMonthlyPricePaise();
        if (amountPaise == null || amountPaise <= 0) {
            return ResponseEntity.badRequest().body("This plan has no price configured for " + billingCycle + " billing.");
        }

        Long schoolId = SchoolContext.get();
        try {
            Map<String, Object> order = razorpayService.createSubscriptionOrder(amountPaise, planId, plan.getName(), schoolId);
            order.put("billingCycle", billingCycle);
            return ResponseEntity.ok(order);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create upgrade order school={} plan={}", schoolId, planId, e);
            return ResponseEntity.internalServerError().body("Failed to create payment order.");
        }
    }

    /**
     * Verifies the Razorpay payment and activates the subscription.
     * On success, creates or replaces the school's SchoolSubscription and triggers entitlement refresh.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/school/subscription/upgrade/verify")
    public ResponseEntity<?> verifyUpgradePayment(@RequestBody Map<String, String> body) {
        String orderId     = body.get("razorpay_order_id");
        String paymentId   = body.get("razorpay_payment_id");
        String signature   = body.get("razorpay_signature");
        String planIdStr   = body.get("planId");
        String billingCycle = body.getOrDefault("billingCycle", "MONTHLY").toUpperCase();

        if (orderId == null || paymentId == null || signature == null || planIdStr == null) {
            return ResponseEntity.badRequest().body("Missing required payment verification fields.");
        }

        Long schoolId = SchoolContext.get();
        try {
            boolean valid = razorpayService.verifySubscriptionSignature(orderId, paymentId, signature);
            if (!valid) {
                log.warn("Invalid subscription payment signature school={} order={}", schoolId, orderId);
                return ResponseEntity.status(400).body("Payment signature verification failed.");
            }

            Long planId = Long.valueOf(planIdStr);
            Plan plan = planRepo.findById(planId)
                    .orElseThrow(() -> new IllegalArgumentException("Plan not found."));

            LocalDateTime now = LocalDateTime.now();
            SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId)
                    .orElse(new SchoolSubscription());
            sub.setSchoolId(schoolId);
            sub.setPlanId(planId);
            sub.setStatus("ACTIVE");
            sub.setActivatedAt(now);
            sub.setExpiresAt("ANNUAL".equals(billingCycle) ? now.plusYears(1) : now.plusMonths(1));

            GlobalSubscriptionConfig config = globalConfigRepo.findById(1).orElse(new GlobalSubscriptionConfig());
            sub.setGraceEndsAt(sub.getExpiresAt().plusDays(config.getGracePeriodDays()));
            sub.setNotes("Activated via Razorpay payment " + paymentId + " (" + billingCycle + ")");
            sub.setCreatedBy(authService.getUserId());
            subscriptionRepo.save(sub);

            refreshService.refreshForSubscriptionChange(schoolId);
            syncLegacyPlanField(schoolId, planId);
            log.info("Subscription activated school={} plan='{}' cycle={} paymentId={} by={}",
                    schoolId, plan.getName(), billingCycle, paymentId, authService.getUserId());
            logHistory(schoolId, "PAYMENT_SUCCESS", planId, plan.getName(), "ACTIVE",
                    "Razorpay payment " + paymentId + " (" + billingCycle + ")", authService.getUserId());

            SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
            List<String> featureKeys = entitlementService.getEffectiveFeatureKeys(schoolId);
            long activeStudents = studentRepo.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
            long currentStaff   = teacherRepo.countBySchoolId(schoolId) + adminRepo.countBySchoolId(schoolId);
            return ResponseEntity.ok(SchoolSubscriptionResponse.from(sub, ent, featureKeys, activeStudents, currentStaff));

        } catch (RazorpayException e) {
            log.error("Razorpay signature verification failed school={} order={}", schoolId, orderId, e);
            return ResponseEntity.status(400).body("Payment signature verification failed.");
        } catch (Exception e) {
            log.error("Subscription upgrade verification failed school={}", schoolId, e);
            return ResponseEntity.internalServerError().body("Payment verification failed: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ADMIN — Subscription history (own school)
    // ══════════════════════════════════════════════════════════════════════════

    /** Returns the last 20 subscription lifecycle events for the requesting school. */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    @GetMapping("/api/school/subscription/history")
    public ResponseEntity<?> getSubscriptionHistory() {
        Long schoolId = SchoolContext.get();
        List<SchoolSubscriptionHistory> history =
                historyRepo.findBySchoolIdOrderByOccurredAtDesc(schoolId, PageRequest.of(0, 20));
        return ResponseEntity.ok(history);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SUPER_ADMIN — Subscription health overview
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a summary of every school's current subscription status.
     * Sorted: EXPIRED → GRACE → TRIAL → ACTIVE → no-entitlement.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/api/super-admin/subscription-health")
    public ResponseEntity<?> getSubscriptionHealth() {
        List<SchoolEffectiveEntitlement> entitlements = entitlementRepo.findAll();
        Map<Long, School> schoolMap = new java.util.HashMap<>();
        schoolRepo.findAll().forEach(s -> schoolMap.put(s.getId(), s));

        java.util.function.Function<String, Integer> sortKey = status -> switch (status == null ? "" : status) {
            case "EXPIRED" -> 0;
            case "GRACE"   -> 1;
            case "TRIAL"   -> 2;
            case "ACTIVE"  -> 3;
            default        -> 4;
        };

        List<Map<String, Object>> result = entitlements.stream()
                .sorted(java.util.Comparator.comparingInt(e -> sortKey.apply(e.getSubscriptionStatus())))
                .map(ent -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    School school = schoolMap.get(ent.getSchoolId());
                    item.put("schoolId",           ent.getSchoolId());
                    item.put("schoolName",          school != null ? school.getName() : "Unknown");
                    item.put("isActive",            school != null && school.isActive());
                    item.put("subscriptionStatus",  ent.getSubscriptionStatus());
                    item.put("planName",            ent.getPlanName());
                    item.put("planTier",            ent.getPlanTier());
                    item.put("trialEndsAt",         ent.getTrialEndsAt());
                    item.put("expiresAt",           ent.getExpiresAt());
                    item.put("graceEndsAt",         ent.getGraceEndsAt());
                    item.put("maxStudents",         ent.getMaxStudents());
                    item.put("lastRebuiltAt",       ent.getLastRebuiltAt());
                    return item;
                })
                .toList();

        return ResponseEntity.ok(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Updates the legacy School.plan enum to reflect the current active subscription plan.
     * Used to keep the settings endpoint in sync with the entitlement system.
     */
    private void syncLegacyPlanField(Long schoolId, Long planId) {
        if (planId == null) return;
        try {
            Plan plan = planRepo.findById(planId).orElse(null);
            if (plan == null) return;
            School school = schoolRepo.findById(schoolId).orElse(null);
            if (school == null) return;
            school.setPlan(mapToLegacyPlan(plan.getTier(), plan.getName()));
            schoolRepo.save(school);
        } catch (Exception e) {
            log.warn("Failed to sync legacy plan field for school={}: {}", schoolId, e.getMessage());
        }
    }

    private SubscriptionPlan mapToLegacyPlan(String tier, String planName) {
        // Try tier first, then plan name — case-insensitive match against enum values
        for (SubscriptionPlan lp : SubscriptionPlan.values()) {
            if (lp.name().equalsIgnoreCase(tier)) return lp;
        }
        for (SubscriptionPlan lp : SubscriptionPlan.values()) {
            if (lp.name().equalsIgnoreCase(planName)) return lp;
        }
        // Any named paid plan that doesn't match the old enum → ENTERPRISE
        return SubscriptionPlan.ENTERPRISE;
    }

    private void logHistory(Long schoolId, String eventType, Long planId, String planName,
                             String status, String notes, String performedBy) {
        try {
            historyRepo.save(new SchoolSubscriptionHistory(
                    schoolId, eventType, planId, planName, status, notes, performedBy));
        } catch (Exception e) {
            log.warn("Failed to save subscription history schoolId={} event={}: {}", schoolId, eventType, e.getMessage());
        }
    }

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
        if (sub.getCreatedBy() == null) sub.setCreatedBy(callerUserId);
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
