package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.entity.GlobalSubscriptionConfig;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolSubscription;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.GlobalSubscriptionConfigRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.SchoolSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Daily scheduler that sends multi-stage email warnings to school admins before
 * their subscription (trial or paid) expires.
 *
 * Fires at 09:00 every day. Sends at milestone thresholds: 14, 7, 3, and 1 day(s)
 * before expiry — each with progressively more urgent messaging.
 */
@Service
public class SubscriptionExpiryNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryNotificationScheduler.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    /** Days-before-expiry at which a reminder email is sent. Ordered most → least. */
    private static final int[] NOTIFY_MILESTONES = { 14, 7, 3, 1 };

    @Autowired private SchoolSubscriptionRepository subscriptionRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private AdminRepository adminRepo;
    @Autowired private GlobalSubscriptionConfigRepository configRepo;
    @Autowired private EmailService emailService;

    @Scheduled(cron = "0 0 9 * * *")
    public void sendExpiryNotifications() {
        log.info("Starting scheduled job: sendExpiryNotifications at {}", LocalDateTime.now());

        LocalDateTime now = LocalDateTime.now();
        // Load all TRIAL/ACTIVE subs expiring within the furthest milestone window (14 days)
        LocalDateTime window = now.plusDays(NOTIFY_MILESTONES[0]);
        List<SchoolSubscription> candidates = subscriptionRepo.findExpiringSoon(now, window);

        if (candidates.isEmpty()) {
            log.info("No subscriptions expiring within {} day(s). Nothing to notify.", NOTIFY_MILESTONES[0]);
            return;
        }
        log.info("Found {} candidate subscription(s) within {} days.", candidates.size(), NOTIFY_MILESTONES[0]);

        int sent = 0;
        for (SchoolSubscription sub : candidates) {
            try {
                Long schoolId = sub.getSchoolId();
                LocalDateTime expiryDt = "TRIAL".equals(sub.getStatus()) ? sub.getTrialEndsAt() : sub.getExpiresAt();
                if (expiryDt == null) continue;

                long daysLeft = ChronoUnit.DAYS.between(now.toLocalDate(), expiryDt.toLocalDate());

                // Only send if today is exactly a milestone day
                boolean isMilestone = false;
                for (int milestone : NOTIFY_MILESTONES) {
                    if (daysLeft == milestone) { isMilestone = true; break; }
                }
                if (!isMilestone) continue;

                School school = schoolRepo.findById(schoolId).orElse(null);
                if (school == null) {
                    log.warn("School not found for schoolId={}", schoolId);
                    continue;
                }

                String recipientEmail = school.getEmail();
                if (recipientEmail == null || recipientEmail.isBlank()) {
                    List<Admin> admins = adminRepo.findBySchoolId(schoolId);
                    if (!admins.isEmpty()) recipientEmail = admins.get(0).getEmail();
                }
                if (recipientEmail == null || recipientEmail.isBlank()) {
                    log.warn("No admin email for school '{}' (id={}). Skipping.", school.getName(), schoolId);
                    continue;
                }

                boolean isTrial = "TRIAL".equals(sub.getStatus());
                String expiryStr = expiryDt.format(DATE_FMT);

                String subject = buildSubject(school.getName(), expiryStr, isTrial, (int) daysLeft);
                String htmlBody = buildExpiryHtml(school.getName(), expiryStr, isTrial, (int) daysLeft);
                emailService.sendHtmlEmail(recipientEmail, subject, htmlBody);
                log.info("Sent {}-day expiry reminder to {} for school '{}' (expires {})",
                        daysLeft, recipientEmail, school.getName(), expiryStr);
                sent++;

            } catch (Exception e) {
                log.error("Failed to send expiry notification for schoolId={}", sub.getSchoolId(), e);
            }
        }

        log.info("Finished sendExpiryNotifications: {} email(s) sent.", sent);
    }

    private String buildSubject(String schoolName, String expiryStr, boolean isTrial, int daysLeft) {
        String urgency = daysLeft <= 1 ? "⚠️ Final Warning: " : daysLeft <= 3 ? "Urgent: " : "";
        String typeLabel = isTrial ? "trial" : "subscription";
        return urgency + "Your Edunexify " + typeLabel + " expires in " + daysLeft
               + " day" + (daysLeft == 1 ? "" : "s") + " — " + schoolName;
    }

    private String buildExpiryHtml(String schoolName, String expiryStr, boolean isTrial, int notifyDays) {
        String safeSchool = (schoolName != null && !schoolName.isBlank()) ? schoolName : "School";
        int year = LocalDateTime.now().getYear();
        String typeLabel = isTrial ? "trial" : "subscription";
        String actionText = isTrial
                ? "Upgrade to a paid plan to continue enjoying uninterrupted access to all features."
                : "Please contact us to renew your subscription and avoid service interruption.";

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Subscription Expiry Notice</title>
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

                        <!-- Band -->
                        <tr>
                          <td align="center" style="background-color:#d97706;padding:10px 40px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              Subscription Expiry Notice
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <p style="margin:0 0 20px;font-size:16px;color:#111827;">Dear Administrator,</p>
                            <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                              This is a reminder that your Edunexify <strong style="color:#111827;">%s</strong>
                              for <strong style="color:#111827;">%s</strong> will expire in
                              <strong style="color:#b45309;">%d day(s)</strong>, on
                              <strong style="color:#111827;">%s</strong>.
                            </p>

                            <!-- Info box -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fffbeb;border:2px solid #fcd34d;border-radius:12px;padding:20px 24px;">
                                  <p style="margin:0 0 10px;font-size:11px;font-weight:700;color:#d97706;letter-spacing:1.5px;text-transform:uppercase;">Expiry Details</p>
                                  <table width="100%%" cellpadding="0" cellspacing="0">
                                    <tr>
                                      <td style="font-size:13px;color:#92400e;padding:4px 0;width:40%%;font-weight:600;">School</td>
                                      <td style="font-size:13px;color:#1f2937;padding:4px 0;font-weight:700;">%s</td>
                                    </tr>
                                    <tr>
                                      <td style="font-size:13px;color:#92400e;padding:4px 0;font-weight:600;">Plan Type</td>
                                      <td style="font-size:13px;color:#1f2937;padding:4px 0;font-weight:700;">%s</td>
                                    </tr>
                                    <tr>
                                      <td style="font-size:13px;color:#92400e;padding:4px 0;font-weight:600;">Expiry Date</td>
                                      <td style="font-size:13px;color:#b45309;padding:4px 0;font-weight:700;">%s</td>
                                    </tr>
                                  </table>
                                </td>
                              </tr>
                            </table>

                            <!-- Action note -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fff7ed;border-left:4px solid #f97316;padding:14px 18px;border-radius:0 8px 8px 0;">
                                  <p style="margin:0;font-size:13px;color:#9a3412;line-height:1.7;">
                                    %s
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
                """.formatted(safeSchool, typeLabel, safeSchool, notifyDays, expiryStr,
                              safeSchool, isTrial ? "Trial" : "Paid", expiryStr,
                              actionText, year);
    }
}
