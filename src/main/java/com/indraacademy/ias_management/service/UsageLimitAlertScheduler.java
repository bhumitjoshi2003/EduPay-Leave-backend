package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolEffectiveEntitlement;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily scheduler that emails school admins when their student or staff usage
 * is approaching the soft or hard limit configured in their plan.
 *
 * Fires at 10:00 every day. Only alerts for TRIAL or ACTIVE subscriptions
 * (grace/expired schools have other notifications covering their situation).
 *
 * Alert thresholds (based on plan's softLimitPct and hardLimitPct):
 *   - WARN  : usage% >= softLimitPct (default 90%)
 *   - CRITICAL : usage% >= hardLimitPct (default 105%)
 */
@Service
public class UsageLimitAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(UsageLimitAlertScheduler.class);

    @Autowired private SchoolEffectiveEntitlementRepository entitlementRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private AdminRepository adminRepo;
    @Autowired private StudentRepository studentRepo;
    @Autowired private TeacherRepository teacherRepo;
    @Autowired private EmailService emailService;

    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Kolkata")
    public void sendUsageLimitAlerts() {
        log.info("Starting scheduled job: sendUsageLimitAlerts at {}", LocalDateTime.now());

        List<SchoolEffectiveEntitlement> entitlements = entitlementRepo.findAll();
        int alertsSent = 0;

        for (SchoolEffectiveEntitlement ent : entitlements) {
            // Only alert active/trial schools — grace/expired are handled elsewhere
            String status = ent.getSubscriptionStatus();
            if (!"TRIAL".equals(status) && !"ACTIVE".equals(status)) continue;

            Long schoolId = ent.getSchoolId();
            try {
                boolean sentForSchool = checkAndAlertSchool(schoolId, ent);
                if (sentForSchool) alertsSent++;
            } catch (Exception e) {
                log.error("Failed usage alert check for schoolId={}: {}", schoolId, e.getMessage());
            }
        }

        log.info("Finished sendUsageLimitAlerts: {} alert email(s) sent.", alertsSent);
    }

    /**
     * Returns true if at least one alert email was sent for this school.
     */
    private boolean checkAndAlertSchool(Long schoolId, SchoolEffectiveEntitlement ent) {
        long activeStudents = studentRepo.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long totalStaff     = teacherRepo.countBySchoolId(schoolId) + adminRepo.countBySchoolId(schoolId);

        Integer maxStudents = ent.getMaxStudents();
        Integer maxStaff    = ent.getMaxStaff();

        int studentSoftPct = ent.getStudentSoftLimitPct() != null ? ent.getStudentSoftLimitPct() : 90;
        int studentHardPct = ent.getStudentHardLimitPct() != null ? ent.getStudentHardLimitPct() : 105;
        int staffSoftPct   = ent.getStaffSoftLimitPct()   != null ? ent.getStaffSoftLimitPct()   : 90;
        int staffHardPct   = ent.getStaffHardLimitPct()   != null ? ent.getStaffHardLimitPct()   : 105;

        boolean studentAlert = maxStudents != null && maxStudents > 0
                && computePct(activeStudents, maxStudents) >= studentSoftPct;
        boolean staffAlert   = maxStaff    != null && maxStaff    > 0
                && computePct(totalStaff, maxStaff) >= staffSoftPct;

        if (!studentAlert && !staffAlert) return false;

        String recipientEmail = resolveAdminEmail(schoolId);
        if (recipientEmail == null) {
            log.warn("No admin email for schoolId={} — skipping usage alert", schoolId);
            return false;
        }

        School school = schoolRepo.findById(schoolId).orElse(null);
        String schoolName = school != null ? school.getName() : "Your School";

        String html = buildUsageAlertHtml(
                schoolName,
                ent.getPlanName(),
                activeStudents, maxStudents, studentSoftPct, studentHardPct,
                totalStaff,    maxStaff,    staffSoftPct,   staffHardPct,
                studentAlert,  staffAlert
        );
        String subject = "Usage Limit Warning — " + schoolName + " is approaching plan limits";
        emailService.sendHtmlEmail(recipientEmail, subject, html);
        log.info("Sent usage alert to {} for school '{}' (students={}/{}, staff={}/{})",
                recipientEmail, schoolName, activeStudents, maxStudents, totalStaff, maxStaff);
        return true;
    }

    private int computePct(long current, int max) {
        if (max <= 0) return 0;
        return (int) Math.round((current * 100.0) / max);
    }

    private String resolveAdminEmail(Long schoolId) {
        School school = schoolRepo.findById(schoolId).orElse(null);
        if (school != null && school.getEmail() != null && !school.getEmail().isBlank()) {
            return school.getEmail();
        }
        List<Admin> admins = adminRepo.findBySchoolId(schoolId);
        return admins.isEmpty() ? null : admins.get(0).getEmail();
    }

    private String buildUsageAlertHtml(String schoolName, String planName,
                                        long students, Integer maxStudents,
                                        int studentSoftPct, int studentHardPct,
                                        long staff, Integer maxStaff,
                                        int staffSoftPct, int staffHardPct,
                                        boolean studentAlert, boolean staffAlert) {
        String safe = (schoolName != null && !schoolName.isBlank()) ? schoolName : "School";
        String plan = (planName   != null && !planName.isBlank())   ? planName   : "your plan";
        int year    = LocalDateTime.now().getYear();

        StringBuilder rows = new StringBuilder();
        if (studentAlert && maxStudents != null) {
            int pct = computePct(students, maxStudents);
            boolean critical = pct >= studentHardPct;
            String color = critical ? "#dc2626" : "#d97706";
            rows.append("""
                    <tr>
                      <td style="padding:10px 0;font-size:13px;color:#374151;font-weight:600;">Students</td>
                      <td style="padding:10px 0;font-size:13px;color:#374151;">%d / %d</td>
                      <td style="padding:10px 0;font-size:13px;font-weight:700;color:%s;">%d%%  %s</td>
                    </tr>
                    """.formatted(students, maxStudents, color, pct, critical ? "⛔ Hard limit!" : "⚠️ Approaching limit"));
        }
        if (staffAlert && maxStaff != null) {
            int pct = computePct(staff, maxStaff);
            boolean critical = pct >= staffHardPct;
            String color = critical ? "#dc2626" : "#d97706";
            rows.append("""
                    <tr>
                      <td style="padding:10px 0;font-size:13px;color:#374151;font-weight:600;">Staff</td>
                      <td style="padding:10px 0;font-size:13px;color:#374151;">%d / %d</td>
                      <td style="padding:10px 0;font-size:13px;font-weight:700;color:%s;">%d%%  %s</td>
                    </tr>
                    """.formatted(staff, maxStaff, color, pct, critical ? "⛔ Hard limit!" : "⚠️ Approaching limit"));
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Usage Limit Warning</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:32px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background-color:#92400e;border-radius:16px 16px 0 0;padding:32px 40px 24px;">
                            <p style="margin:0 0 10px;font-size:44px;line-height:1;">&#9888;&#65039;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">%s</h1>
                          </td>
                        </tr>
                        <tr>
                          <td align="center" style="background-color:#d97706;padding:10px 40px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              Plan Usage Limit Warning
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <p style="margin:0 0 20px;font-size:16px;color:#111827;">Dear Administrator,</p>
                            <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                              Your Edunexify <strong style="color:#111827;">%s</strong> plan for
                              <strong style="color:#111827;">%s</strong> is approaching its usage limits.
                              Please review the details below.
                            </p>

                            <!-- Usage table -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fffbeb;border:2px solid #fcd34d;border-radius:12px;padding:20px 24px;">
                                  <p style="margin:0 0 14px;font-size:11px;font-weight:700;color:#d97706;letter-spacing:1.5px;text-transform:uppercase;">Usage Summary</p>
                                  <table width="100%%" cellpadding="0" cellspacing="0">
                                    <tr>
                                      <th style="text-align:left;font-size:11px;color:#9ca3af;padding-bottom:8px;font-weight:600;">Resource</th>
                                      <th style="text-align:left;font-size:11px;color:#9ca3af;padding-bottom:8px;font-weight:600;">Used / Limit</th>
                                      <th style="text-align:left;font-size:11px;color:#9ca3af;padding-bottom:8px;font-weight:600;">Status</th>
                                    </tr>
                                    %s
                                  </table>
                                </td>
                              </tr>
                            </table>

                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fff7ed;border-left:4px solid #f97316;padding:14px 18px;border-radius:0 8px 8px 0;">
                                  <p style="margin:0;font-size:13px;color:#9a3412;line-height:1.7;">
                                    To avoid disruption, consider upgrading your plan or managing your resources.
                                    Log in to the admin dashboard to review current usage.
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
                """.formatted(safe, plan, safe, rows.toString(), year);
    }
}
