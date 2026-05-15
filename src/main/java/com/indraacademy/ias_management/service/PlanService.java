package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    @Autowired private PlanRepository planRepo;
    @Autowired private FeatureCatalogRepository featureCatalogRepo;
    @Autowired private PlanFeatureRepository planFeatureRepo;
    @Autowired private PlanFeatureChangeRepository planFeatureChangeRepo;
    @Autowired private GlobalSubscriptionConfigRepository configRepo;
    @Autowired private EntitlementRefreshService entitlementRefreshService;

    // ── Plans ─────────────────────────────────────────────────────────────────

    public List<PlanResponse> getAllPlans(boolean includeInactive) {
        List<Plan> plans = includeInactive
                ? planRepo.findAllByOrderByPriorityScoreAsc()
                : planRepo.findByIsActiveTrueOrderByPriorityScoreAsc();

        Map<String, FeatureCatalog> catalogMap = featureCatalogRepo.findAll()
                .stream().collect(Collectors.toMap(FeatureCatalog::getFeatureKey, Function.identity()));

        return plans.stream().map(p -> buildPlanResponse(p, catalogMap)).toList();
    }

    public PlanResponse getPlan(Long planId) {
        Plan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        Map<String, FeatureCatalog> catalogMap = featureCatalogRepo.findAll()
                .stream().collect(Collectors.toMap(FeatureCatalog::getFeatureKey, Function.identity()));
        return buildPlanResponse(plan, catalogMap);
    }

    @Transactional
    public PlanResponse createPlan(PlanRequest req, String adminId) {
        Plan plan = new Plan();
        applyRequest(plan, req);
        if (plan.getPriorityScore() == 0) {
            // Default custom plans to 100 if no score given
            plan.setPriorityScore("CAMPUS".equalsIgnoreCase(req.getTier()) ? 10
                    : "ACADEMY".equalsIgnoreCase(req.getTier()) ? 20
                    : "INSTITUTE".equalsIgnoreCase(req.getTier()) ? 30
                    : 100);
        }
        plan = planRepo.save(plan);
        log.info("Plan created: id={}, name='{}', by={}", plan.getId(), plan.getName(), adminId);
        return getPlan(plan.getId());
    }

    @Transactional
    public PlanResponse updatePlan(Long planId, PlanRequest req) {
        Plan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        applyRequest(plan, req);
        planRepo.save(plan);
        log.info("Plan updated: id={}, name='{}'", planId, plan.getName());
        return getPlan(planId);
    }

    @Transactional
    public void deactivatePlan(Long planId) {
        Plan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        plan.setActive(false);
        planRepo.save(plan);
        log.info("Plan deactivated: id={}", planId);
    }

    @Transactional
    public PlanResponse reactivatePlan(Long planId) {
        Plan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        plan.setActive(true);
        planRepo.save(plan);
        log.info("Plan reactivated: id={}", planId);
        return getPlan(planId);
    }

    private void applyRequest(Plan plan, PlanRequest req) {
        if (req.getName() != null)               plan.setName(req.getName());
        if (req.getTier() != null)               plan.setTier(req.getTier().toUpperCase());
        if (req.getVersion() != null)            plan.setVersion(req.getVersion());
        plan.setPublic(req.isPublic());
        if (req.getMaxStudents() != null)        plan.setMaxStudents(req.getMaxStudents());
        if (req.getStudentSoftLimitPct() != null) plan.setStudentSoftLimitPct(req.getStudentSoftLimitPct());
        if (req.getStudentHardLimitPct() != null) plan.setStudentHardLimitPct(req.getStudentHardLimitPct());
        if (req.getMaxStaff() != null)           plan.setMaxStaff(req.getMaxStaff());
        if (req.getStaffSoftLimitPct() != null)  plan.setStaffSoftLimitPct(req.getStaffSoftLimitPct());
        if (req.getStaffHardLimitPct() != null)  plan.setStaffHardLimitPct(req.getStaffHardLimitPct());
        if (req.getStorageGbLimit() != null)     plan.setStorageGbLimit(req.getStorageGbLimit());
        if (req.getStorageSoftLimitPct() != null) plan.setStorageSoftLimitPct(req.getStorageSoftLimitPct());
        if (req.getStorageHardLimitPct() != null) plan.setStorageHardLimitPct(req.getStorageHardLimitPct());
        if (req.getMonthlyPricePaise() != null)  plan.setMonthlyPricePaise(req.getMonthlyPricePaise());
        if (req.getAnnualPricePaise() != null)   plan.setAnnualPricePaise(req.getAnnualPricePaise());
        if (req.getPriorityScore() != null)      plan.setPriorityScore(req.getPriorityScore());
    }

    // ── Features ──────────────────────────────────────────────────────────────

    public List<FeatureCatalogResponse> getAllFeatures() {
        return featureCatalogRepo.findAllByOrderByCategoryAscDisplayNameAsc()
                .stream().map(FeatureCatalogResponse::from).toList();
    }

    @Transactional
    public void addFeatureToPlan(Long planId, String featureKey, String adminId) {
        if (!planRepo.existsById(planId)) throw new IllegalArgumentException("Plan not found: " + planId);
        FeatureCatalog catalog = featureCatalogRepo.findById(featureKey)
                .orElseThrow(() -> new IllegalArgumentException("Feature not found: " + featureKey));
        if (planFeatureRepo.existsByPlanIdAndFeatureKey(planId, featureKey)) {
            log.info("Feature {} already on plan {} — skipping", featureKey, planId);
            return;
        }

        // Dependency validation: all required features must already be in the plan
        Set<String> missing = catalog.getDependsOn().stream()
                .filter(dep -> !planFeatureRepo.existsByPlanIdAndFeatureKey(planId, dep))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot add '" + featureKey + "': missing required feature(s): " + missing +
                    ". Add them first.");
        }

        planFeatureRepo.save(new PlanFeature(planId, featureKey));

        PlanFeatureChange change = new PlanFeatureChange();
        change.setPlanId(planId);
        change.setFeatureKey(featureKey);
        change.setActionType("ADD");
        change.setPolicy("IMMEDIATE");
        change.setEffectiveAt(LocalDateTime.now());
        change.setApplied(true);
        change.setCreatedBy(adminId);
        planFeatureChangeRepo.save(change);

        log.info("Feature {} added to plan {} by {}", featureKey, planId, adminId);
        entitlementRefreshService.refreshForPlanChange(planId);
    }

    @Transactional
    public void removeFeatureFromPlan(Long planId, String featureKey, String policy, String adminId) {
        if (!planRepo.existsById(planId)) throw new IllegalArgumentException("Plan not found: " + planId);
        if (!planFeatureRepo.existsByPlanIdAndFeatureKey(planId, featureKey)) {
            throw new IllegalArgumentException("Feature " + featureKey + " is not on plan " + planId);
        }

        // Dependency validation: block removal if other plan features depend on this one
        List<PlanFeature> planFeatures = planFeatureRepo.findByPlanId(planId);
        Set<String> planFeatureKeys = planFeatures.stream()
                .map(PlanFeature::getFeatureKey)
                .collect(Collectors.toSet());

        List<FeatureCatalog> dependents = featureCatalogRepo.findAll().stream()
                .filter(f -> planFeatureKeys.contains(f.getFeatureKey())
                        && !f.getFeatureKey().equals(featureKey)
                        && f.getDependsOn().contains(featureKey))
                .toList();
        if (!dependents.isEmpty()) {
            Set<String> dependentKeys = dependents.stream()
                    .map(FeatureCatalog::getFeatureKey).collect(Collectors.toSet());
            throw new IllegalArgumentException(
                    "Cannot remove '" + featureKey + "': feature(s) " + dependentKeys +
                    " depend on it. Remove those first.");
        }

        LocalDateTime effectiveAt = computeEffectiveAt(policy);

        PlanFeatureChange change = new PlanFeatureChange();
        change.setPlanId(planId);
        change.setFeatureKey(featureKey);
        change.setActionType("REMOVE");
        change.setPolicy(policy);
        change.setEffectiveAt(effectiveAt);
        change.setCreatedBy(adminId);

        if ("IMMEDIATE".equalsIgnoreCase(policy)) {
            planFeatureRepo.deleteByPlanIdAndFeatureKey(planId, featureKey);
            change.setApplied(true);
            log.info("Feature {} removed immediately from plan {} by {}", featureKey, planId, adminId);
            entitlementRefreshService.refreshForPlanChange(planId);
        } else {
            change.setApplied(false);
            log.info("Feature {} scheduled for removal from plan {} at {} (policy={}) by {}",
                    featureKey, planId, effectiveAt, policy, adminId);
        }

        planFeatureChangeRepo.save(change);
    }

    /**
     * Scheduled job: applies pending feature removals whose effective_at has passed.
     * Runs every hour. Matches the cron pattern used elsewhere in the project.
     */
    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void applyPendingFeatureRemovals() {
        List<PlanFeatureChange> pending = planFeatureChangeRepo
                .findByActionTypeAndAppliedFalseAndEffectiveAtBefore("REMOVE", LocalDateTime.now());

        if (pending.isEmpty()) return;

        for (PlanFeatureChange change : pending) {
            try {
                planFeatureRepo.deleteByPlanIdAndFeatureKey(change.getPlanId(), change.getFeatureKey());
                change.setApplied(true);
                planFeatureChangeRepo.save(change);
                log.info("Applied scheduled removal: feature={} plan={}", change.getFeatureKey(), change.getPlanId());
                entitlementRefreshService.refreshForPlanChange(change.getPlanId());
            } catch (Exception e) {
                log.error("Failed to apply feature removal id={}: {}", change.getId(), e.getMessage());
            }
        }
    }

    // ── Global Config ─────────────────────────────────────────────────────────

    public GlobalSubscriptionConfigResponse getConfig() {
        return GlobalSubscriptionConfigResponse.from(getOrCreateConfig());
    }

    @Transactional
    public GlobalSubscriptionConfigResponse updateConfig(GlobalSubscriptionConfigRequest req, String adminId) {
        GlobalSubscriptionConfig config = getOrCreateConfig();
        if (req.getGracePeriodDays() != null)  config.setGracePeriodDays(req.getGracePeriodDays());
        if (req.getDefaultTrialDays() != null) config.setDefaultTrialDays(req.getDefaultTrialDays());
        if (req.getExpiryNotifyDays() != null) config.setExpiryNotifyDays(req.getExpiryNotifyDays());
        config.setUpdatedByAdminId(adminId);
        configRepo.save(config);
        log.info("Global subscription config updated by {}", adminId);
        return GlobalSubscriptionConfigResponse.from(config);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GlobalSubscriptionConfig getOrCreateConfig() {
        return configRepo.findById(1).orElseGet(() -> {
            GlobalSubscriptionConfig c = new GlobalSubscriptionConfig();
            c.setConfigId(1);
            return configRepo.save(c);
        });
    }

    private PlanResponse buildPlanResponse(Plan plan, Map<String, FeatureCatalog> catalogMap) {
        List<PlanFeature> planFeatures = planFeatureRepo.findByPlanId(plan.getId());
        List<FeatureCatalog> features = planFeatures.stream()
                .map(pf -> catalogMap.get(pf.getFeatureKey()))
                .filter(f -> f != null)
                .toList();

        List<PlanFeatureChangeResponse> pendingChanges = planFeatureChangeRepo
                .findByPlanIdOrderByCreatedAtDesc(plan.getId())
                .stream()
                .filter(c -> !c.isApplied())
                .map(PlanFeatureChangeResponse::from)
                .toList();

        return PlanResponse.from(plan, features, pendingChanges);
    }

    /**
     * Calculates effective_at timestamp based on the removal policy.
     * IMMEDIATE     → now
     * NEXT_MONTHLY  → 1st of next calendar month
     * NEXT_QUARTERLY→ 1st of next quarter (Apr/Jul/Oct/Jan)
     * NEXT_ANNUAL   → April 1st of next academic year (Indian standard)
     */
    private LocalDateTime computeEffectiveAt(String policy) {
        LocalDate today = LocalDate.now();
        return switch (policy.toUpperCase()) {
            case "IMMEDIATE" -> LocalDateTime.now();
            case "NEXT_MONTHLY" -> today.plusMonths(1).withDayOfMonth(1).atStartOfDay();
            case "NEXT_QUARTERLY" -> {
                int month = today.getMonthValue();
                int nextQuarterMonth = month < 4 ? 4 : month < 7 ? 7 : month < 10 ? 10 : 1;
                int year = nextQuarterMonth == 1 ? today.getYear() + 1 : today.getYear();
                yield LocalDate.of(year, nextQuarterMonth, 1).atStartOfDay();
            }
            case "NEXT_ANNUAL" -> {
                // Indian academic year starts April 1
                int year = today.getMonthValue() >= 4 ? today.getYear() + 1 : today.getYear();
                yield LocalDate.of(year, 4, 1).atStartOfDay();
            }
            default -> LocalDateTime.now().plusMonths(1).withDayOfMonth(1);
        };
    }
}
