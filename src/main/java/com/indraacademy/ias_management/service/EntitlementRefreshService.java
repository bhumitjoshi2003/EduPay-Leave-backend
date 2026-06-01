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
import java.time.format.DateTimeFormatter;
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
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private AdminRepository adminRepo;
    @Autowired private EmailService emailService;
    @Autowired private SchoolSubscriptionHistoryRepository historyRepo;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy");

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
        String previousStatus = sub.getStatus();
        String resolvedStatus = resolveStatus(sub);
        if (!resolvedStatus.equals(previousStatus)) {
            sub.setStatus(resolvedStatus);
            subscriptionRepo.save(sub);
            log.info("School {} subscription status transitioned {} → {}", schoolId, previousStatus, resolvedStatus);
            // Log history event for automatic status transitions
            try {
                historyRepo.save(new SchoolSubscriptionHistory(
                        schoolId, "STATUS_CHANGED", sub.getPlanId(), plan.getName(),
                        resolvedStatus, previousStatus + " → " + resolvedStatus, "SCHEDULER"));
            } catch (Exception e) {
                log.warn("Failed to save STATUS_CHANGED history for schoolId={}: {}", schoolId, e.getMessage());
            }
            // Notify admin when entering GRACE period
            if ("GRACE".equals(resolvedStatus)) {
                sendGracePeriodEntryEmail(schoolId, plan.getName(), sub.getGraceEndsAt());
            }
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

    // ── Grace period email notification ──────────────────────────────────────

    private void sendGracePeriodEntryEmail(Long schoolId, String planName, LocalDateTime graceEndsAt) {
        try {
            School school = schoolRepo.findById(schoolId).orElse(null);
            if (school == null) return;

            String recipientEmail = school.getEmail();
            if (recipientEmail == null || recipientEmail.isBlank()) {
                List<Admin> admins = adminRepo.findBySchoolId(schoolId);
                if (!admins.isEmpty()) recipientEmail = admins.get(0).getEmail();
            }
            if (recipientEmail == null || recipientEmail.isBlank()) {
                log.warn("No admin email for school {} — skipping grace period notification", schoolId);
                return;
            }

            String graceStr = graceEndsAt != null ? graceEndsAt.format(DATE_FMT) : "soon";
            String subject = "Action Required: Your Edunexify subscription has expired — " + school.getName();
            String html = buildGracePeriodHtml(school.getName(), planName, graceStr);
            emailService.sendHtmlEmail(recipientEmail, subject, html);
            log.info("Sent grace period entry email to {} for school '{}'", recipientEmail, school.getName());
        } catch (Exception e) {
            log.error("Failed to send grace period email for schoolId={}: {}", schoolId, e.getMessage());
        }
    }

    private String buildGracePeriodHtml(String schoolName, String planName, String graceEndsStr) {
        String safe = (schoolName != null && !schoolName.isBlank()) ? schoolName : "School";
        String plan = (planName  != null && !planName.isBlank())  ? planName  : "your plan";
        int year = LocalDateTime.now().getYear();
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Subscription Expired — Grace Period Active</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:32px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background-color:#7f1d1d;border-radius:16px 16px 0 0;padding:32px 40px 24px;">
                            <p style="margin:0 0 10px;font-size:44px;line-height:1;">&#128680;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">%s</h1>
                          </td>
                        </tr>
                        <tr>
                          <td align="center" style="background-color:#dc2626;padding:10px 40px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              Subscription Expired — Grace Period Active
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <p style="margin:0 0 20px;font-size:16px;color:#111827;">Dear Administrator,</p>
                            <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                              Your Edunexify <strong style="color:#111827;">%s</strong> subscription for
                              <strong style="color:#111827;">%s</strong> has expired. You are now in a
                              <strong style="color:#dc2626;">grace period</strong> that ends on
                              <strong style="color:#dc2626;">%s</strong>.
                            </p>

                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fef2f2;border:2px solid #fca5a5;border-radius:12px;padding:20px 24px;">
                                  <p style="margin:0 0 10px;font-size:11px;font-weight:700;color:#dc2626;letter-spacing:1.5px;text-transform:uppercase;">Important Notice</p>
                                  <p style="margin:0;font-size:13px;color:#7f1d1d;line-height:1.7;">
                                    During the grace period, your school still has access to all features.
                                    After <strong>%s</strong>, access will be suspended until the subscription is renewed.
                                    Please renew immediately to avoid service interruption.
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                              With regards,<br>
                              <strong>The Edunexify Team</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">Platform Administration</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; %d Edunexify. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(safe, plan, safe, graceEndsStr, graceEndsStr, year);
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

        // Dates extended by super admin but status was left stale — correct it.
        // If expiry is in the future, the school is active (not expired or in grace).
        if (("EXPIRED".equals(sub.getStatus()) || "GRACE".equals(sub.getStatus()))
                && sub.getExpiresAt() != null && now.isBefore(sub.getExpiresAt())) {
            // Still within trial period?
            if (sub.getTrialEndsAt() != null && now.isBefore(sub.getTrialEndsAt())) {
                return "TRIAL";
            }
            return "ACTIVE";
        }

        // No timestamp-driven transition needed
        return sub.getStatus();
    }
}
