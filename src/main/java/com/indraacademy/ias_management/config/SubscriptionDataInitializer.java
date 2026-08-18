package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Seeds the feature catalog, default plans (Campus / Academy / Institute), and global
 * subscription config on application startup.
 *
 * Idempotent, but not purely additive any more — running this on an already-seeded database
 * converges the catalog metadata below (adds missing rows, retires keys removed
 * from the catalog, corrects isAlwaysOn flags, and refreshes the
 * AI/KB limit numbers on existing plans) rather than only filling gaps. Running it any number
 * of times produces the same end state — every step re-derives from {@link #CORE_FEATURES} /
 * {@link #TIERED_PLAN_FEATURES} rather than assuming "already exists" means "already correct."
 *
 * This class only ever seeds/corrects DATA — it adds no enforcement. Nothing here calls
 * EntitlementService.checkLimit()/hasFeature() or blocks any request; a school's actual access
 * today is unaffected by any change made here.
 */
@Component
@Order(10)
public class SubscriptionDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDataInitializer.class);

    @Autowired private FeatureCatalogRepository featureCatalogRepo;
    @Autowired private PlanRepository planRepo;
    @Autowired private PlanFeatureRepository planFeatureRepo;
    @Autowired private SchoolFeatureOverrideRepository overrideRepo;
    @Autowired private SchoolEntitlementFeatureRepository entitlementFeatureRepo;
    @Autowired private GlobalSubscriptionConfigRepository configRepo;
    private final Set<Long> plansCreatedThisRun = new HashSet<>();

    /**
     * Feature keys that were catalogued as separately-gateable but never held enough
     * standalone commercial value to justify their own key — folded into a neighbor.
     * PAYMENT_HISTORY → PAYMENT_COLLECTION (meaningless without collection), STUDENT_STREAM →
     * EXAM_MARKS (curriculum config, not a separate sale), PUSH_NOTIFICATIONS → dropped
     * entirely (it's delivery infrastructure, not a capability — the sellable part is bulk send
     * volume, now BULK_COMMUNICATIONS).
     */
    private static final List<String> RETIRED_FEATURE_KEYS = List.of(
            "PAYMENT_HISTORY", "STUDENT_STREAM", "PUSH_NOTIFICATIONS"
    );

    /**
     * Core capabilities — every school on every plan gets these regardless of tier, covering
     * functionality that works ungated for every school today (attendance, leave, timetable,
     * school/roster administration, notices, holidays, event calendar, student promotion) — this
     * makes that a documented decision rather than an accident. EVENT_CALENDAR and
     * STUDENT_PROMOTION were previously catalogued but left outside this initializer's ownership
     * (an oversight, not a design choice) — they're now first-class Core entries here like
     * everything else in this list, so the catalog has no orphaned keys.
     *
     * isAlwaysOn is documentation-only today — EntitlementRefreshService.refresh() does not
     * special-case it, so these keys are also explicitly granted to all three plans below to
     * make "core" true in practice, not just in the catalog's metadata.
     */
    private static final List<FeatureCatalog> CORE_FEATURES = List.of(
            new FeatureCatalog("ATTENDANCE",
                    "Attendance",
                    "Student daily attendance and teacher check-in/check-out, including GPS-based check-in.",
                    "ACADEMICS", true),
            new FeatureCatalog("LEAVE_MANAGEMENT",
                    "Leave Management",
                    "Student and teacher leave application, review, and approval.",
                    "ADMIN", true),
            new FeatureCatalog("TIMETABLE",
                    "Timetable",
                    "Class and teacher timetable management.",
                    "ACADEMICS", true),
            new FeatureCatalog("SCHOOL_ADMINISTRATION",
                    "School Administration",
                    "Student, teacher, and admin records and search; class, section, and academic session management.",
                    "ADMIN", true),
            new FeatureCatalog("NOTICE_BOARD",
                    "Notice Board",
                    "In-app notices and announcements.",
                    "COMMUNICATION", true),
            new FeatureCatalog("HOLIDAY_CALENDAR",
                    "Holiday Calendar",
                    "School holiday calendar.",
                    "COMMUNICATION", true),
            new FeatureCatalog("EVENT_CALENDAR",
                    "Event Calendar",
                    "School event calendar and event creation.",
                    "COMMUNICATION", true),
            new FeatureCatalog("STUDENT_PROMOTION",
                    "Student Promotion",
                    "Promoting students to the next class/session at year end.",
                    "ADMIN", true)
    );

    /**
     * Tiered (paid, gateable) catalog — the final, redesigned 10-key set. No requireFeature()
     * enforcement exists for any of these yet (that's a later phase); this only seeds the
     * catalog + plan membership data.
     */
    private static final List<FeatureCatalog> TIERED_FEATURES = List.of(
            new FeatureCatalog("FEE_MANAGEMENT",
                    "Fee Management",
                    "Student fee structures, fee assignment, fee tracking, bus fees, invoices, and credit notes.",
                    "FINANCE", false),
            new FeatureCatalog("PAYMENT_COLLECTION",
                    "Online Payment Collection",
                    "Razorpay-powered online fee collection and payment history.",
                    "FINANCE", false),
            new FeatureCatalog("EXAM_MARKS",
                    "Exams & Marks",
                    "Exam configuration, subject-wise mark entry, results, assessment groups, and stream/elective subject assignment.",
                    "ACADEMICS", false),
            new FeatureCatalog("FEE_REMINDERS",
                    "Fee Reminders",
                    "Manual and automated reminders to parents of fee-defaulting students.",
                    "FINANCE", false),
            new FeatureCatalog("REPORT_CARD",
                    "Report Cards",
                    "Report card templates, remarks/co-scholastic entry, single and bulk PDF generation, and public verification.",
                    "ACADEMICS", false),
            new FeatureCatalog("BULK_COMMUNICATIONS",
                    "Bulk Communications",
                    "Bulk email/push notice blasts to a class or all teachers, and report-card email-blast.",
                    "COMMUNICATION", false),
            new FeatureCatalog("BULK_IMPORT",
                    "Bulk Import",
                    "CSV bulk import for students and teachers.",
                    "ADMIN", false),
            new FeatureCatalog("ANALYTICS",
                    "Analytics Dashboard",
                    "Admin analytics: attendance trends, fee collection charts, student stats.",
                    "ADMIN", false),
            new FeatureCatalog("AI_COPILOT",
                    "AI Copilot",
                    "Role-scoped AI chat assistant, approval-gated fee/attendance/leave workflows, and knowledge-base search.",
                    "ADMIN", false),
            new FeatureCatalog("AUDIT_LOGS",
                    "Audit Logs",
                    "Paginated audit log of all write operations in the school.",
                    "ADMIN", false)
    );

    /** Which tiered keys each plan grants, by tier name. Core keys are handled separately and apply to all three. */
    private static final Map<String, List<String>> TIERED_PLAN_FEATURES = Map.of(
            "CAMPUS", List.of("FEE_MANAGEMENT", "PAYMENT_COLLECTION", "EXAM_MARKS"),
            "ACADEMY", List.of(
                    "FEE_MANAGEMENT", "PAYMENT_COLLECTION", "EXAM_MARKS",
                    "FEE_REMINDERS", "REPORT_CARD", "BULK_COMMUNICATIONS", "BULK_IMPORT", "ANALYTICS", "AI_COPILOT"
            ),
            "INSTITUTE", List.of(
                    "FEE_MANAGEMENT", "PAYMENT_COLLECTION", "EXAM_MARKS",
                    "FEE_REMINDERS", "REPORT_CARD", "BULK_COMMUNICATIONS", "BULK_IMPORT", "ANALYTICS", "AI_COPILOT",
                    "AUDIT_LOGS"
            )
    );

    /** AI/KB limits by tier — null means "not included" for AI_MESSAGES/KB_DOCUMENTS specifically (see Plan javadoc). */
    private static final Map<String, int[]> AI_KB_LIMITS_BY_TIER = new LinkedHashMap<>() {{
        // {maxAiMessagesMonthly, maxKbDocuments} — -1 sentinel means "leave null" (Campus: not included).
        put("CAMPUS", new int[]{-1, -1});
        put("ACADEMY", new int[]{2000, 50});
        put("INSTITUTE", new int[]{10000, 500});
    }};

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedFeatureCatalog();
        retireFeatureCatalogKeys();
        seedDefaultPlans();
        reconcilePlanFeatureGrants();
        reconcilePlanLimits();
        seedGlobalConfig();
    }

    // ── Feature Catalog ───────────────────────────────────────────────────────

    private void seedFeatureCatalog() {
        int seeded = 0;
        for (FeatureCatalog f : CORE_FEATURES) {
            seeded += upsertCatalogEntry(f);
        }
        for (FeatureCatalog f : TIERED_FEATURES) {
            seeded += upsertCatalogEntry(f);
        }
        if (seeded > 0) log.info("Seeded/updated {} feature catalog entries", seeded);

        // Feature dependencies (idempotent — only adds missing deps; both sides still exist
        // in the catalog after this migration, so no cleanup needed here).
        seedDependency("PAYMENT_COLLECTION", "FEE_MANAGEMENT");
        seedDependency("REPORT_CARD", "EXAM_MARKS");
    }

    /**
     * Creates the row if missing; if it already exists, corrects displayName/description/
     * category/isAlwaysOn to match the target above. Returns 1 if anything changed, 0 otherwise.
     */
    private int upsertCatalogEntry(FeatureCatalog target) {
        FeatureCatalog existing = featureCatalogRepo.findById(target.getFeatureKey()).orElse(null);
        if (existing == null) {
            featureCatalogRepo.save(target);
            return 1;
        }
        boolean changed = false;
        if (!target.getDisplayName().equals(existing.getDisplayName())) {
            existing.setDisplayName(target.getDisplayName());
            changed = true;
        }
        if (!target.getDescription().equals(existing.getDescription())) {
            existing.setDescription(target.getDescription());
            changed = true;
        }
        if (!target.getCategory().equals(existing.getCategory())) {
            existing.setCategory(target.getCategory());
            changed = true;
        }
        if (target.isAlwaysOn() != existing.isAlwaysOn()) {
            existing.setAlwaysOn(target.isAlwaysOn());
            changed = true;
        }
        if (changed) {
            featureCatalogRepo.save(existing);
            log.info("Updated feature catalog entry: {}", target.getFeatureKey());
            return 1;
        }
        return 0;
    }

    private void seedDependency(String featureKey, String dependsOnKey) {
        featureCatalogRepo.findById(featureKey).ifPresent(f -> {
            if (!f.getDependsOn().contains(dependsOnKey)) {
                f.getDependsOn().add(dependsOnKey);
                featureCatalogRepo.save(f);
                log.info("Seeded dependency: {} → {}", featureKey, dependsOnKey);
            }
        });
    }

    /**
     * Removes retired feature keys and everything referencing them — plan grants, per-school
     * overrides, and resolved entitlement rows — so no orphaned reference to a deleted catalog
     * row is left behind. Safe on repeated runs: once a key is gone, every step here is a no-op
     * for it.
     */
    private void retireFeatureCatalogKeys() {
        for (String key : RETIRED_FEATURE_KEYS) {
            if (featureCatalogRepo.findById(key).isEmpty()) continue;

            for (Plan plan : planRepo.findAll()) {
                planFeatureRepo.deleteByPlanIdAndFeatureKey(plan.getId(), key);
            }
            overrideRepo.findAll().stream()
                    .filter(o -> key.equals(o.getFeatureKey()))
                    .forEach(overrideRepo::delete);
            entitlementFeatureRepo.findAll().stream()
                    .filter(e -> key.equals(e.getFeatureKey()))
                    .forEach(entitlementFeatureRepo::delete);

            featureCatalogRepo.deleteById(key);
            log.info("Retired feature catalog key: {}", key);
        }
    }

    // ── Default Plans ─────────────────────────────────────────────────────────

    private void seedDefaultPlans() {
        if (!planRepo.existsByTierIgnoreCase("CAMPUS")) seedPlan(
                "Campus", "CAMPUS", 10, 300, 90, 105, 20, 90, 105, 2, 90, 105, 99900L, 999900L);
        if (!planRepo.existsByTierIgnoreCase("ACADEMY")) seedPlan(
                "Academy", "ACADEMY", 20, 1500, 95, 110, 75, 95, 110, 10, 90, 105, 249900L, 2499900L);
        if (!planRepo.existsByTierIgnoreCase("INSTITUTE")) seedPlan(
                "Institute", "INSTITUTE", 30, 5000, 98, 115, null, 98, 115, 50, 98, 115, 599900L, 5999900L);
    }

    private void seedPlan(String name, String tier, int priorityScore,
                           Integer maxStudents, int studentSoft, int studentHard,
                           Integer maxStaff, int staffSoft, int staffHard,
                           Integer storageGb, int storageSoft, int storageHard,
                           Long monthlyPaise, Long annualPaise) {
        Plan p = new Plan();
        p.setName(name);
        p.setTier(tier);
        p.setVersion("v1");
        p.setPublic(true);
        p.setActive(true);
        p.setPriorityScore(priorityScore);
        p.setMaxStudents(maxStudents);
        p.setStudentSoftLimitPct(studentSoft);
        p.setStudentHardLimitPct(studentHard);
        p.setMaxStaff(maxStaff);
        p.setStaffSoftLimitPct(staffSoft);
        p.setStaffHardLimitPct(staffHard);
        p.setStorageGbLimit(storageGb);
        p.setStorageSoftLimitPct(storageSoft);
        p.setStorageHardLimitPct(storageHard);
        p.setMonthlyPricePaise(monthlyPaise);
        p.setAnnualPricePaise(annualPaise);
        Plan saved = planRepo.save(p);
        plansCreatedThisRun.add(saved.getId());
        log.info("Seeded {} plan (id={})", name, saved.getId());
    }

    /**
     * Applies tier defaults only to plans created during this startup, then ensures every
     * existing plan contains all always-on core keys. Existing tiered membership is deliberately
     * preserved so a deployment cannot undo Super Admin feature changes.
     */
    private void reconcilePlanFeatureGrants() {
        for (Plan plan : planRepo.findAll()) {
            // Tier defaults are seed data, not an invariant. Applying them on every restart
            // would undo intentional Super Admin removals during the next deployment.
            if (plansCreatedThisRun.contains(plan.getId())) {
                List<String> tieredKeys = TIERED_PLAN_FEATURES.getOrDefault(plan.getTier().toUpperCase(), List.of());
                addFeatures(plan.getId(), tieredKeys);
            }

            List<String> coreKeys = CORE_FEATURES.stream().map(FeatureCatalog::getFeatureKey).toList();
            addFeatures(plan.getId(), coreKeys);
        }
    }

    private void addFeatures(Long planId, List<String> keys) {
        for (String key : keys) {
            if (featureCatalogRepo.existsById(key) &&
                    !planFeatureRepo.existsByPlanIdAndFeatureKey(planId, key)) {
                planFeatureRepo.save(new PlanFeature(planId, key));
                log.info("Granted feature {} to planId={}", key, planId);
            }
        }
    }

    /** Sets/refreshes the AI message and knowledge-base document limits on every plan, by tier. */
    private void reconcilePlanLimits() {
        for (Plan plan : planRepo.findAll()) {
            int[] limits = AI_KB_LIMITS_BY_TIER.get(plan.getTier().toUpperCase());
            if (limits == null) continue;

            Integer targetAiMessages = limits[0] == -1 ? null : limits[0];
            Integer targetKbDocs = limits[1] == -1 ? null : limits[1];

            boolean changed = false;
            if (!java.util.Objects.equals(plan.getMaxAiMessagesMonthly(), targetAiMessages)) {
                plan.setMaxAiMessagesMonthly(targetAiMessages);
                changed = true;
            }
            if (!java.util.Objects.equals(plan.getMaxKbDocuments(), targetKbDocs)) {
                plan.setMaxKbDocuments(targetKbDocs);
                changed = true;
            }
            if (changed) {
                planRepo.save(plan);
                log.info("Set AI/KB limits for {} plan: maxAiMessagesMonthly={}, maxKbDocuments={}",
                        plan.getName(), targetAiMessages, targetKbDocs);
            }
        }
    }

    // ── Global Config ─────────────────────────────────────────────────────────

    private void seedGlobalConfig() {
        if (!configRepo.existsById(1)) {
            GlobalSubscriptionConfig config = new GlobalSubscriptionConfig();
            config.setConfigId(1);
            configRepo.save(config);
            log.info("Seeded global subscription config (gracePeriod=15d, trial=30d, notify=1d)");
        }
    }
}
