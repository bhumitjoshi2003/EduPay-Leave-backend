package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single orchestrator for all entitlement rebuilds.
 *
 * All triggers — plan feature changes, subscription changes, school overrides, grants,
 * and the nightly scheduler — must go through this service. Never write directly to
 * SchoolEffectiveEntitlement or SchoolEntitlementFeature from other services.
 *
 * Rebuild steps for a school:
 *   1. Load active subscription → planId, status, timestamps
 *   2. Load plan's current features from PlanFeature table
 *   3. Apply school's DISABLED overrides (subtract from feature set)
 *   4. Copy plan limits to SchoolEffectiveEntitlement
 *   5. Resolve subscription status from timestamps + GlobalSubscriptionConfig grace period
 *   6. Upsert SchoolEffectiveEntitlement row
 *   7. Delete + re-insert SchoolEntitlementFeature rows
 */
@Service
public class EntitlementRefreshService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementRefreshService.class);

    @Autowired private SchoolSubscriptionRepository subscriptionRepo;
    @Autowired private SchoolEffectiveEntitlementRepository entitlementRepo;
    @Autowired private SchoolEntitlementFeatureRepository entitlementFeatureRepo;
    @Autowired private SchoolFeatureOverrideRepository overrideRepo;
    @Autowired private PlanRepository planRepo;
    @Autowired private PlanFeatureRepository planFeatureRepo;
    @Autowired private GlobalSubscriptionConfigRepository configRepo;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Rebuild entitlement for a single school. Safe to call from any trigger. */
    @Transactional
    public void refresh(Long schoolId) {
        refresh(schoolId, "MANUAL");
    }

    /**
     * Rebuild for all schools on a given plan.
     * Called after plan feature adds/removes so affected schools see updated entitlements.
     */
    @Transactional
    public void refreshForPlanChange(Long planId) {
        List<Long> schoolIds = subscriptionRepo.findSchoolIdsByPlanId(planId);
        log.info("Refreshing entitlements for {} school(s) on plan {}", schoolIds.size(), planId);
        for (Long schoolId : schoolIds) {
            refresh(schoolId, "PLAN_CHANGE");
        }
    }

    /**
     * Rebuild after a subscription change (plan assignment, status update, expiry change).
     * Also triggers PLAN_CHANGE refresh since the school may be moving between plans.
     */
    @Transactional
    public void refreshForSubscriptionChange(Long schoolId) {
        refresh(schoolId, "SUBSCRIPTION_CHANGE");
    }

    /** Rebuild after an admin updates a school-level feature override. */
    @Transactional
    public void refreshForOverrideChange(Long schoolId) {
        refresh(schoolId, "OVERRIDE_CHANGE");
    }

    /**
     * Nightly job at 2 AM: refreshes all non-expired subscriptions.
     * Handles status transitions: TRIAL→EXPIRED, ACTIVE→GRACE→EXPIRED.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void refreshAll() {
        List<SchoolSubscription> active = subscriptionRepo.findAllActive();
        log.info("Nightly entitlement refresh: {} school(s)", active.size());
        for (SchoolSubscription sub : active) {
            try {
                refresh(sub.getSchoolId(), "SCHEDULER");
            } catch (Exception e) {
                log.error("Failed nightly refresh for schoolId={}: {}", sub.getSchoolId(), e.getMessage());
            }
        }
    }

    // ── Core rebuild logic ────────────────────────────────────────────────────

    @Transactional
    public void refresh(Long schoolId, String triggeredBy) {
        SchoolSubscription sub = subscriptionRepo.findBySchoolId(schoolId).orElse(null);
        if (sub == null) {
            log.debug("No subscription for schoolId={} — skipping entitlement refresh", schoolId);
            return;
        }

        Plan plan = planRepo.findById(sub.getPlanId()).orElse(null);
        if (plan == null) {
            log.warn("Plan {} not found for schoolId={} — skipping entitlement refresh", sub.getPlanId(), schoolId);
            return;
        }

        // Step 1: Resolve subscription status from timestamps
        String resolvedStatus = resolveStatus(sub);
        if (!resolvedStatus.equals(sub.getStatus())) {
            sub.setStatus(resolvedStatus);
            subscriptionRepo.save(sub);
            log.info("School {} subscription status transitioned to {}", schoolId, resolvedStatus);
        }

        // Step 2: Load plan features
        List<PlanFeature> planFeatures = planFeatureRepo.findByPlanId(plan.getId());
        Set<String> planFeatureKeys = planFeatures.stream()
                .map(PlanFeature::getFeatureKey)
                .collect(Collectors.toSet());

        // Step 3: Apply school-level overrides
        // DISABLED overrides remove a plan feature; ENABLED overrides add a feature beyond the plan.
        List<SchoolFeatureOverride> allOverrides = overrideRepo.findBySchoolId(schoolId);
        Set<String> disabledKeys = allOverrides.stream()
                .filter(o -> "DISABLED".equals(o.getOverrideState()))
                .map(SchoolFeatureOverride::getFeatureKey)
                .collect(Collectors.toSet());
        Set<String> enabledKeys = allOverrides.stream()
                .filter(o -> "ENABLED".equals(o.getOverrideState()))
                .map(SchoolFeatureOverride::getFeatureKey)
                .collect(Collectors.toSet());

        Set<String> effectiveKeys = planFeatureKeys.stream()
                .filter(k -> !disabledKeys.contains(k))
                .collect(Collectors.toCollection(java.util.HashSet::new));
        effectiveKeys.addAll(enabledKeys);  // super admin can grant extras beyond the plan

        // EXPIRED schools lose all features (overrides included)
        if ("EXPIRED".equals(resolvedStatus)) {
            effectiveKeys = Set.of();
        }

        // Step 4: Upsert SchoolEffectiveEntitlement
        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId)
                .orElse(new SchoolEffectiveEntitlement());
        ent.setSchoolId(schoolId);
        ent.setPlanId(plan.getId());
        ent.setPlanName(plan.getName());
        ent.setPlanTier(plan.getTier());
        ent.setPlanVersion(plan.getVersion());
        ent.setResolvedPriorityScore(plan.getPriorityScore());
        ent.setSubscriptionStatus(resolvedStatus);
        ent.setEntitlementSource("PLAN");
        ent.setTrialEndsAt(sub.getTrialEndsAt());
        ent.setExpiresAt(sub.getExpiresAt());
        ent.setGraceEndsAt(sub.getGraceEndsAt());
        // Limits from plan
        ent.setMaxStudents(plan.getMaxStudents());
        ent.setStudentSoftLimitPct(plan.getStudentSoftLimitPct());
        ent.setStudentHardLimitPct(plan.getStudentHardLimitPct());
        ent.setMaxStaff(plan.getMaxStaff());
        ent.setStaffSoftLimitPct(plan.getStaffSoftLimitPct());
        ent.setStaffHardLimitPct(plan.getStaffHardLimitPct());
        ent.setStorageGbLimit(plan.getStorageGbLimit());
        ent.setStorageSoftLimitPct(plan.getStorageSoftLimitPct());
        ent.setStorageHardLimitPct(plan.getStorageHardLimitPct());
        ent.setLastRebuiltAt(LocalDateTime.now());
        ent.setRebuiltBy(triggeredBy);
        entitlementRepo.save(ent);

        // Step 5: Rebuild feature rows (delete all + re-insert)
        entitlementFeatureRepo.deleteBySchoolId(schoolId);
        for (String key : effectiveKeys) {
            entitlementFeatureRepo.save(new SchoolEntitlementFeature(schoolId, key, plan.getId()));
        }

        log.info("Entitlement rebuilt for schoolId={} plan='{}' status={} features={} trigger={}",
                schoolId, plan.getName(), resolvedStatus, effectiveKeys.size(), triggeredBy);
    }

    // ── Status resolution ─────────────────────────────────────────────────────

    /**
     * Resolves the effective subscription status based on current timestamps.
     * Priority: EXPIRED (grace ended) > GRACE > ACTIVE > TRIAL
     *
     * If no explicit timestamps → stays in whatever status was set by the admin.
     */
    private String resolveStatus(SchoolSubscription sub) {
        LocalDateTime now = LocalDateTime.now();

        // Grace period ended → fully expired
        if (sub.getGraceEndsAt() != null && now.isAfter(sub.getGraceEndsAt())) {
            return "EXPIRED";
        }

        // Subscription expired but still within grace
        if (sub.getExpiresAt() != null && now.isAfter(sub.getExpiresAt())) {
            return "GRACE";
        }

        // Trial ended (no paid period set) → expired immediately
        if ("TRIAL".equals(sub.getStatus()) && sub.getTrialEndsAt() != null
                && now.isAfter(sub.getTrialEndsAt()) && sub.getExpiresAt() == null) {
            return "EXPIRED";
        }

        // No timestamp-driven transition needed
        return sub.getStatus();
    }
}
