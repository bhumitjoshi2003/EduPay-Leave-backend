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

import java.util.List;

/**
 * Seeds feature catalog, default plans (Campus / Academy / Institute),
 * and global subscription config on application startup.
 * Idempotent — skips rows that already exist.
 */
@Component
@Order(10)
public class SubscriptionDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDataInitializer.class);

    @Autowired private FeatureCatalogRepository featureCatalogRepo;
    @Autowired private PlanRepository planRepo;
    @Autowired private PlanFeatureRepository planFeatureRepo;
    @Autowired private GlobalSubscriptionConfigRepository configRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedFeatureCatalog();
        seedDefaultPlans();
        seedGlobalConfig();
    }

    // ── Feature Catalog ───────────────────────────────────────────────────────

    private void seedFeatureCatalog() {
        List<FeatureCatalog> features = List.of(
            // FINANCE
            new FeatureCatalog("FEE_MANAGEMENT",
                "Fee Management",
                "Student fee structures, fee assignment, and fee tracking per academic session.",
                "FINANCE", false),
            new FeatureCatalog("PAYMENT_COLLECTION",
                "Online Payment Collection",
                "Razorpay-powered online fee collection directly within the portal.",
                "FINANCE", false),
            new FeatureCatalog("PAYMENT_HISTORY",
                "Payment History",
                "View and export complete payment history for students and admin.",
                "FINANCE", false),
            new FeatureCatalog("FEE_REMINDERS",
                "Fee Reminders",
                "Bulk push notifications to parents of fee-defaulting students.",
                "FINANCE", false),

            // ACADEMICS
            new FeatureCatalog("EXAM_MARKS",
                "Exams & Marks",
                "Exam configuration, subject-wise mark entry, and student results.",
                "ACADEMICS", false),
            new FeatureCatalog("REPORT_CARD",
                "Report Cards",
                "Printable student report cards generated from exam marks.",
                "ACADEMICS", false),
            new FeatureCatalog("STUDENT_STREAM",
                "Student Stream Assignment",
                "Assign Science, Commerce, or Arts streams to Class 11-12 students.",
                "ACADEMICS", false),

            // COMMUNICATION
            new FeatureCatalog("PUSH_NOTIFICATIONS",
                "Push Notifications",
                "General push notifications to students, parents, and staff.",
                "COMMUNICATION", false),
            new FeatureCatalog("EVENT_CALENDAR",
                "Event Calendar",
                "School events calendar with image management and announcements.",
                "COMMUNICATION", false),

            // ADMIN
            new FeatureCatalog("ANALYTICS",
                "Analytics Dashboard",
                "Admin analytics: attendance trends, fee collection charts, student stats.",
                "ADMIN", false),
            new FeatureCatalog("BULK_IMPORT",
                "Bulk Import",
                "CSV bulk import for students and teachers.",
                "ADMIN", false),
            new FeatureCatalog("STUDENT_PROMOTION",
                "Student Promotion",
                "Year-end student class promotion to the next grade.",
                "ADMIN", false),
            new FeatureCatalog("AUDIT_LOGS",
                "Audit Logs",
                "Paginated audit log of all write operations in the school.",
                "ADMIN", false)
        );

        int seeded = 0;
        for (FeatureCatalog f : features) {
            if (!featureCatalogRepo.existsById(f.getFeatureKey())) {
                featureCatalogRepo.save(f);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} feature catalog entries", seeded);

        // Seed feature dependencies (idempotent — only adds missing deps)
        seedDependency("PAYMENT_COLLECTION", "FEE_MANAGEMENT");
        seedDependency("REPORT_CARD",         "EXAM_MARKS");
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

    // ── Default Plans ─────────────────────────────────────────────────────────

    private void seedDefaultPlans() {
        boolean campusExists   = planRepo.existsByTierIgnoreCase("CAMPUS");
        boolean academyExists  = planRepo.existsByTierIgnoreCase("ACADEMY");
        boolean instituteExists= planRepo.existsByTierIgnoreCase("INSTITUTE");

        if (!campusExists)    seedCampus();
        if (!academyExists)   seedAcademy();
        if (!instituteExists) seedInstitute();
    }

    private void seedCampus() {
        Plan p = new Plan();
        p.setName("Campus");
        p.setTier("CAMPUS");
        p.setVersion("v1");
        p.setPublic(true);
        p.setActive(true);
        p.setPriorityScore(10);
        p.setMaxStudents(300);
        p.setStudentSoftLimitPct(90);
        p.setStudentHardLimitPct(105);
        p.setMaxStaff(20);
        p.setStaffSoftLimitPct(90);
        p.setStaffHardLimitPct(105);
        p.setStorageGbLimit(2);
        p.setStorageSoftLimitPct(90);
        p.setStorageHardLimitPct(105);
        p.setMonthlyPricePaise(99900L);   // ₹999
        p.setAnnualPricePaise(999900L);   // ₹9,999
        Plan saved = planRepo.save(p);

        addFeatures(saved.getId(), List.of(
            "FEE_MANAGEMENT", "PAYMENT_COLLECTION", "PAYMENT_HISTORY", "EXAM_MARKS"
        ));
        log.info("Seeded Campus plan (id={})", saved.getId());
    }

    private void seedAcademy() {
        Plan p = new Plan();
        p.setName("Academy");
        p.setTier("ACADEMY");
        p.setVersion("v1");
        p.setPublic(true);
        p.setActive(true);
        p.setPriorityScore(20);
        p.setMaxStudents(1500);
        p.setStudentSoftLimitPct(95);
        p.setStudentHardLimitPct(110);
        p.setMaxStaff(75);
        p.setStaffSoftLimitPct(95);
        p.setStaffHardLimitPct(110);
        p.setStorageGbLimit(10);
        p.setStorageSoftLimitPct(90);
        p.setStorageHardLimitPct(105);
        p.setMonthlyPricePaise(249900L);  // ₹2,499
        p.setAnnualPricePaise(2499900L);  // ₹24,999
        Plan saved = planRepo.save(p);

        addFeatures(saved.getId(), List.of(
            "FEE_MANAGEMENT", "PAYMENT_COLLECTION", "PAYMENT_HISTORY", "FEE_REMINDERS",
            "EXAM_MARKS", "REPORT_CARD", "STUDENT_STREAM",
            "PUSH_NOTIFICATIONS", "EVENT_CALENDAR",
            "ANALYTICS", "BULK_IMPORT", "STUDENT_PROMOTION"
        ));
        log.info("Seeded Academy plan (id={})", saved.getId());
    }

    private void seedInstitute() {
        Plan p = new Plan();
        p.setName("Institute");
        p.setTier("INSTITUTE");
        p.setVersion("v1");
        p.setPublic(true);
        p.setActive(true);
        p.setPriorityScore(30);
        p.setMaxStudents(5000);
        p.setStudentSoftLimitPct(98);
        p.setStudentHardLimitPct(115);
        p.setMaxStaff(null);             // unlimited
        p.setStaffSoftLimitPct(98);
        p.setStaffHardLimitPct(115);
        p.setStorageGbLimit(50);
        p.setStorageSoftLimitPct(98);
        p.setStorageHardLimitPct(115);
        p.setMonthlyPricePaise(599900L);  // ₹5,999
        p.setAnnualPricePaise(5999900L);  // ₹59,999
        Plan saved = planRepo.save(p);

        addFeatures(saved.getId(), List.of(
            "FEE_MANAGEMENT", "PAYMENT_COLLECTION", "PAYMENT_HISTORY", "FEE_REMINDERS",
            "EXAM_MARKS", "REPORT_CARD", "STUDENT_STREAM",
            "PUSH_NOTIFICATIONS", "EVENT_CALENDAR",
            "ANALYTICS", "BULK_IMPORT", "STUDENT_PROMOTION", "AUDIT_LOGS"
        ));
        log.info("Seeded Institute plan (id={})", saved.getId());
    }

    private void addFeatures(Long planId, List<String> keys) {
        for (String key : keys) {
            if (featureCatalogRepo.existsById(key) &&
                    !planFeatureRepo.existsByPlanIdAndFeatureKey(planId, key)) {
                planFeatureRepo.save(new PlanFeature(planId, key));
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
