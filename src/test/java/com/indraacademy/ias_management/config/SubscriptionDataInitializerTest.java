package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.entity.FeatureCatalog;
import com.indraacademy.ias_management.entity.GlobalSubscriptionConfig;
import com.indraacademy.ias_management.entity.Plan;
import com.indraacademy.ias_management.entity.PlanFeature;
import com.indraacademy.ias_management.entity.SchoolEntitlementFeature;
import com.indraacademy.ias_management.entity.SchoolFeatureOverride;
import com.indraacademy.ias_management.repository.FeatureCatalogRepository;
import com.indraacademy.ias_management.repository.GlobalSubscriptionConfigRepository;
import com.indraacademy.ias_management.repository.PlanFeatureRepository;
import com.indraacademy.ias_management.repository.PlanRepository;
import com.indraacademy.ias_management.repository.SchoolEntitlementFeatureRepository;
import com.indraacademy.ias_management.repository.SchoolFeatureOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Proves the Phase 2 catalog migration ({@link SubscriptionDataInitializer}) is correct and, per
 * its own javadoc, safely re-runnable: converges an old/pre-redesign catalog to the final target
 * shape on first run, then makes zero further writes on a second run against its own output.
 *
 * Repositories are backed by simple in-memory maps/lists driven through Mockito answers, rather
 * than fully stubbing every call combination — this lets the same fakes represent both a
 * pre-migration DB snapshot and the initializer's own output, so idempotency can be verified by
 * literally running it twice against the same backing state.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionDataInitializerTest {

    @Mock private FeatureCatalogRepository featureCatalogRepo;
    @Mock private PlanRepository planRepo;
    @Mock private PlanFeatureRepository planFeatureRepo;
    @Mock private SchoolFeatureOverrideRepository overrideRepo;
    @Mock private SchoolEntitlementFeatureRepository entitlementFeatureRepo;
    @Mock private GlobalSubscriptionConfigRepository configRepo;

    private SubscriptionDataInitializer initializer;

    private Map<String, FeatureCatalog> catalog;
    private Map<Long, Plan> plans;
    private List<PlanFeature> planFeatures;
    private List<SchoolFeatureOverride> overrides;
    private List<SchoolEntitlementFeature> entitlementFeatures;
    private Map<Integer, GlobalSubscriptionConfig> globalConfig;
    private AtomicLong planIdSeq;

    @BeforeEach
    void setUp() {
        initializer = new SubscriptionDataInitializer();
        ReflectionTestUtils.setField(initializer, "featureCatalogRepo", featureCatalogRepo);
        ReflectionTestUtils.setField(initializer, "planRepo", planRepo);
        ReflectionTestUtils.setField(initializer, "planFeatureRepo", planFeatureRepo);
        ReflectionTestUtils.setField(initializer, "overrideRepo", overrideRepo);
        ReflectionTestUtils.setField(initializer, "entitlementFeatureRepo", entitlementFeatureRepo);
        ReflectionTestUtils.setField(initializer, "configRepo", configRepo);

        catalog = new LinkedHashMap<>();
        plans = new LinkedHashMap<>();
        planFeatures = new ArrayList<>();
        overrides = new ArrayList<>();
        entitlementFeatures = new ArrayList<>();
        globalConfig = new LinkedHashMap<>();
        planIdSeq = new AtomicLong(1);

        lenient().when(featureCatalogRepo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(catalog.get((String) inv.getArgument(0))));
        lenient().when(featureCatalogRepo.existsById(anyString()))
                .thenAnswer(inv -> catalog.containsKey((String) inv.getArgument(0)));
        lenient().when(featureCatalogRepo.save(any(FeatureCatalog.class)))
                .thenAnswer(inv -> {
                    FeatureCatalog fc = inv.getArgument(0);
                    catalog.put(fc.getFeatureKey(), fc);
                    return fc;
                });
        lenient().doAnswer(inv -> catalog.remove((String) inv.getArgument(0)))
                .when(featureCatalogRepo).deleteById(anyString());

        lenient().when(planRepo.findAll()).thenAnswer(inv -> new ArrayList<>(plans.values()));
        lenient().when(planRepo.existsByTierIgnoreCase(anyString()))
                .thenAnswer(inv -> {
                    String tier = inv.getArgument(0);
                    return plans.values().stream().anyMatch(p -> p.getTier().equalsIgnoreCase(tier));
                });
        lenient().when(planRepo.save(any(Plan.class)))
                .thenAnswer(inv -> {
                    Plan p = inv.getArgument(0);
                    if (p.getId() == null) p.setId(planIdSeq.getAndIncrement());
                    plans.put(p.getId(), p);
                    return p;
                });

        lenient().when(planFeatureRepo.existsByPlanIdAndFeatureKey(any(), anyString()))
                .thenAnswer(inv -> planFeatures.stream().anyMatch(pf ->
                        pf.getPlanId().equals(inv.getArgument(0)) && pf.getFeatureKey().equals(inv.getArgument(1))));
        lenient().when(planFeatureRepo.save(any(PlanFeature.class)))
                .thenAnswer(inv -> {
                    planFeatures.add(inv.getArgument(0));
                    return inv.getArgument(0);
                });
        lenient().doAnswer(inv -> {
            Long planId = inv.getArgument(0);
            String key = inv.getArgument(1);
            planFeatures.removeIf(pf -> pf.getPlanId().equals(planId) && pf.getFeatureKey().equals(key));
            return null;
        }).when(planFeatureRepo).deleteByPlanIdAndFeatureKey(any(), anyString());

        lenient().when(overrideRepo.findAll()).thenAnswer(inv -> new ArrayList<>(overrides));
        lenient().doAnswer(inv -> overrides.remove((SchoolFeatureOverride) inv.getArgument(0)))
                .when(overrideRepo).delete(any(SchoolFeatureOverride.class));

        lenient().when(entitlementFeatureRepo.findAll()).thenAnswer(inv -> new ArrayList<>(entitlementFeatures));
        lenient().doAnswer(inv -> entitlementFeatures.remove((SchoolEntitlementFeature) inv.getArgument(0)))
                .when(entitlementFeatureRepo).delete(any(SchoolEntitlementFeature.class));

        lenient().when(configRepo.existsById(eq(1)))
                .thenAnswer(inv -> globalConfig.containsKey(1));
        lenient().when(configRepo.save(any(GlobalSubscriptionConfig.class)))
                .thenAnswer(inv -> {
                    GlobalSubscriptionConfig c = inv.getArgument(0);
                    globalConfig.put(c.getConfigId(), c);
                    return c;
                });
    }

    // ─── Fresh DB (nothing seeded yet) ───

    @Test
    void freshDatabase_endsWithExactly16CatalogKeys_8CoreAnd10Tiered() {
        initializer.run(null);

        // 8 Core entries (including EVENT_CALENDAR/STUDENT_PROMOTION, now first-class Core keys
        // owned by this initializer) + 10 TIERED_FEATURES = 18 total.
        assertThat(catalog.keySet()).containsExactlyInAnyOrder(
                "ATTENDANCE", "LEAVE_MANAGEMENT", "TIMETABLE", "SCHOOL_ADMINISTRATION",
                "NOTICE_BOARD", "HOLIDAY_CALENDAR", "EVENT_CALENDAR", "STUDENT_PROMOTION",
                "FEE_MANAGEMENT", "PAYMENT_COLLECTION", "EXAM_MARKS", "FEE_REMINDERS",
                "REPORT_CARD", "BULK_COMMUNICATIONS", "BULK_IMPORT", "ANALYTICS",
                "AI_COPILOT", "AUDIT_LOGS"
        );
    }

    @Test
    void freshDatabase_seedsAllThreePlans() {
        initializer.run(null);

        assertThat(plans.values()).extracting(Plan::getTier)
                .containsExactlyInAnyOrder("CAMPUS", "ACADEMY", "INSTITUTE");
    }

    @Test
    void freshDatabase_grantsExpectedFeatureCountsPerPlan_3Tiered8CoreForCampus() {
        initializer.run(null);

        Plan campus = plans.values().stream().filter(p -> p.getTier().equals("CAMPUS")).findFirst().orElseThrow();
        long grants = planFeatures.stream().filter(pf -> pf.getPlanId().equals(campus.getId())).count();

        // 3 tiered (FEE_MANAGEMENT, PAYMENT_COLLECTION, EXAM_MARKS) + 8 core = 11
        assertThat(grants).isEqualTo(11);
    }

    @Test
    void freshDatabase_grantsExpectedFeatureCountsPerPlan_9Tiered8CoreForAcademy() {
        initializer.run(null);

        Plan academy = plans.values().stream().filter(p -> p.getTier().equals("ACADEMY")).findFirst().orElseThrow();
        long grants = planFeatures.stream().filter(pf -> pf.getPlanId().equals(academy.getId())).count();

        assertThat(grants).isEqualTo(17); // 9 tiered + 8 core
    }

    @Test
    void freshDatabase_grantsExpectedFeatureCountsPerPlan_10Tiered8CoreForInstitute() {
        initializer.run(null);

        Plan institute = plans.values().stream().filter(p -> p.getTier().equals("INSTITUTE")).findFirst().orElseThrow();
        long grants = planFeatures.stream().filter(pf -> pf.getPlanId().equals(institute.getId())).count();

        assertThat(grants).isEqualTo(18); // 10 tiered (all of them) + 8 core
    }

    @Test
    void freshDatabase_aiCopilotGrantedToAcademyAndInstitute_notCampus() {
        initializer.run(null);

        assertThat(grantedTiersFor("AI_COPILOT")).containsExactlyInAnyOrder("ACADEMY", "INSTITUTE");
    }

    @Test
    void freshDatabase_bulkCommunicationsGrantedToAcademyAndInstitute_notCampus() {
        initializer.run(null);

        assertThat(grantedTiersFor("BULK_COMMUNICATIONS")).containsExactlyInAnyOrder("ACADEMY", "INSTITUTE");
    }

    @Test
    void freshDatabase_auditLogsGrantedToInstituteOnly() {
        initializer.run(null);

        assertThat(grantedTiersFor("AUDIT_LOGS")).containsExactlyInAnyOrder("INSTITUTE");
    }

    private List<String> grantedTiersFor(String featureKey) {
        return planFeatures.stream()
                .filter(pf -> pf.getFeatureKey().equals(featureKey))
                .map(pf -> plans.get(pf.getPlanId()).getTier())
                .toList();
    }

    @Test
    void freshDatabase_seedsAiAndKbLimitsPerTier_campusNull_academy2000and50_institute10000and500() {
        initializer.run(null);

        Plan campus = planByTier("CAMPUS");
        Plan academy = planByTier("ACADEMY");
        Plan institute = planByTier("INSTITUTE");

        assertThat(campus.getMaxAiMessagesMonthly()).isNull();
        assertThat(campus.getMaxKbDocuments()).isNull();

        assertThat(academy.getMaxAiMessagesMonthly()).isEqualTo(2000);
        assertThat(academy.getMaxKbDocuments()).isEqualTo(50);

        assertThat(institute.getMaxAiMessagesMonthly()).isEqualTo(10000);
        assertThat(institute.getMaxKbDocuments()).isEqualTo(500);
    }

    private Plan planByTier(String tier) {
        return plans.values().stream().filter(p -> p.getTier().equals(tier)).findFirst().orElseThrow();
    }

    @Test
    void freshDatabase_seedsGlobalConfigOnce() {
        initializer.run(null);

        assertThat(globalConfig).hasSize(1);
        assertThat(globalConfig.get(1).getDefaultTrialDays()).isEqualTo(30);
        assertThat(globalConfig.get(1).getGracePeriodDays()).isEqualTo(15);
    }

    @Test
    void freshDatabase_dependenciesAreSeeded() {
        initializer.run(null);

        assertThat(catalog.get("PAYMENT_COLLECTION").getDependsOn()).contains("FEE_MANAGEMENT");
        assertThat(catalog.get("REPORT_CARD").getDependsOn()).contains("EXAM_MARKS");
    }

    // ─── Idempotency: running against the initializer's own prior output changes nothing ───

    @Test
    void reRunningAgainstOwnOutput_addsNoDuplicatePlans() {
        initializer.run(null);
        int plansAfterFirstRun = plans.size();

        initializer.run(null);

        assertThat(plans).hasSize(plansAfterFirstRun);
    }

    @Test
    void reRunningAgainstOwnOutput_addsNoDuplicateFeatureGrants() {
        initializer.run(null);
        int grantsAfterFirstRun = planFeatures.size();

        initializer.run(null);

        assertThat(planFeatures).hasSize(grantsAfterFirstRun);
    }

    @Test
    void reRunningAgainstOwnOutput_leavesCatalogSizeUnchanged() {
        initializer.run(null);
        int catalogSizeAfterFirstRun = catalog.size();

        initializer.run(null);

        assertThat(catalog).hasSize(catalogSizeAfterFirstRun);
    }

    @Test
    void reRunningAgainstOwnOutput_leavesAiKbLimitsUnchanged() {
        initializer.run(null);
        initializer.run(null);

        assertThat(planByTier("ACADEMY").getMaxAiMessagesMonthly()).isEqualTo(2000);
        assertThat(planByTier("INSTITUTE").getMaxKbDocuments()).isEqualTo(500);
    }

    // ─── Convergence against a pre-redesign (old catalog) snapshot ───

    @Test
    void oldCatalog_retiredKeysAreRemoved_alongWithReferences() {
        // Simulate the pre-redesign catalog: a retired key exists, is granted to a plan, has a
        // per-school override, and has a resolved entitlement row referencing it.
        catalog.put("PAYMENT_HISTORY", new FeatureCatalog(
                "PAYMENT_HISTORY", "Payment History", "old", "FINANCE", false));

        Plan oldPlan = new Plan();
        oldPlan.setId(99L);
        oldPlan.setTier("ACADEMY");
        oldPlan.setName("Academy");
        plans.put(99L, oldPlan);
        planFeatures.add(new PlanFeature(99L, "PAYMENT_HISTORY"));

        SchoolFeatureOverride override = new SchoolFeatureOverride();
        override.setSchoolId(5L);
        override.setFeatureKey("PAYMENT_HISTORY");
        overrides.add(override);

        SchoolEntitlementFeature entitlement = new SchoolEntitlementFeature(5L, "PAYMENT_HISTORY", 99L);
        entitlementFeatures.add(entitlement);

        initializer.run(null);

        assertThat(catalog).doesNotContainKey("PAYMENT_HISTORY");
        assertThat(planFeatures).noneMatch(pf -> pf.getFeatureKey().equals("PAYMENT_HISTORY"));
        assertThat(overrides).isEmpty();
        assertThat(entitlementFeatures).isEmpty();
    }

    @Test
    void oldCatalog_allThreeRetiredKeysAreRemoved() {
        for (String key : Set.of("PAYMENT_HISTORY", "STUDENT_STREAM", "PUSH_NOTIFICATIONS")) {
            catalog.put(key, new FeatureCatalog(key, key, "old", "MISC", false));
        }

        initializer.run(null);

        assertThat(catalog.keySet())
                .doesNotContain("PAYMENT_HISTORY", "STUDENT_STREAM", "PUSH_NOTIFICATIONS");
    }

    @Test
    void oldCatalog_existingEventCalendarAndStudentPromotionKeys_areFlippedToAlwaysOn() {
        // EVENT_CALENDAR/STUDENT_PROMOTION are now first-class CORE_FEATURES entries owned by
        // this initializer (previously they were left orphaned, isAlwaysOn=false, by mistake) —
        // a pre-existing row for either must be corrected to isAlwaysOn=true on this run.
        catalog.put("EVENT_CALENDAR", new FeatureCatalog(
                "EVENT_CALENDAR", "Event Calendar", "old", "COMMUNICATION", false));
        catalog.put("STUDENT_PROMOTION", new FeatureCatalog(
                "STUDENT_PROMOTION", "Student Promotion", "old", "ADMIN", false));

        initializer.run(null);

        assertThat(catalog.get("EVENT_CALENDAR").isAlwaysOn()).isTrue();
        assertThat(catalog.get("STUDENT_PROMOTION").isAlwaysOn()).isTrue();
    }

    @Test
    void oldCatalog_existingCoreKeyWithStaleMetadata_isCorrectedInPlace() {
        catalog.put("ATTENDANCE", new FeatureCatalog(
                "ATTENDANCE", "Old Name", "Old description", "OLD_CATEGORY", false));

        initializer.run(null);

        FeatureCatalog corrected = catalog.get("ATTENDANCE");
        assertThat(corrected.getDisplayName()).isEqualTo("Attendance");
        assertThat(corrected.isAlwaysOn()).isTrue();
        assertThat(corrected.getCategory()).isEqualTo("ACADEMICS");
    }

    @Test
    void oldCatalog_existingPlanIsNotDuplicated_onlyFeatureGrantsAndLimitsAreReconciled() {
        Plan existingAcademy = new Plan();
        existingAcademy.setId(42L);
        existingAcademy.setTier("ACADEMY");
        existingAcademy.setName("Academy");
        plans.put(42L, existingAcademy);

        initializer.run(null);

        assertThat(plans.values().stream().filter(p -> p.getTier().equals("ACADEMY")).count()).isEqualTo(1);
        assertThat(existingAcademy.getMaxAiMessagesMonthly()).isEqualTo(2000);
    }

    @Test
    void oldCatalog_existingSchoolOverrideOnANonRetiredKey_isLeftUntouched() {
        SchoolFeatureOverride override = new SchoolFeatureOverride();
        override.setSchoolId(7L);
        override.setFeatureKey("FEE_MANAGEMENT"); // not retired
        overrides.add(override);

        initializer.run(null);

        assertThat(overrides).contains(override);
    }

    @Test
    void oldCatalog_planWithExistingGrantOnAKeyStillInCatalog_isNeverRemoved() {
        Plan existingCampus = new Plan();
        existingCampus.setId(11L);
        existingCampus.setTier("CAMPUS");
        existingCampus.setName("Campus");
        plans.put(11L, existingCampus);
        // Campus doesn't normally get AI_COPILOT per TIERED_PLAN_FEATURES, but if a school
        // already had a manually-granted key still present in the catalog, reconciliation must
        // never revoke it — only retireFeatureCatalogKeys removes grants, and only for keys
        // actually being deleted.
        planFeatures.add(new PlanFeature(11L, "AI_COPILOT"));

        initializer.run(null);

        assertThat(planFeatures).anyMatch(pf -> pf.getPlanId().equals(11L) && pf.getFeatureKey().equals("AI_COPILOT"));
    }
}
