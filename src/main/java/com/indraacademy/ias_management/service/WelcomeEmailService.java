package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Sends the "welcome" email to a newly-created STUDENT or TEACHER account. Shared by both
 * manual registration ({@code AuthController.registerUser}) and CSV bulk import
 * ({@code StudentBulkImportService} / {@code TeacherBulkImportService}) so the two flows
 * cannot drift into different copy or behavior.
 *
 * Callers are expected to invoke this only AFTER the {@code User} row has been committed
 * (i.e. after a successful {@code userRepository.save(user)} that was not rejected as a
 * duplicate userId) — that ordering, combined with userId's DB uniqueness, is what makes a
 * retried/duplicate registration attempt structurally unable to trigger a second send: a
 * retry either 409s before save() (manual flow) or throws from save() and is caught per-row
 * (bulk import), so this method is never reached a second time for the same account.
 *
 * Every failure mode here (missing email, template error, SMTP failure) is caught and logged,
 * never thrown — sending the welcome email must never roll back or fail account creation.
 */
@Service
public class WelcomeEmailService {

    private static final Logger log = LoggerFactory.getLogger(WelcomeEmailService.class);

    @Autowired private EmailService emailService;
    @Autowired private SchoolRepository schoolRepository;

    @Value("${frontend.url:https://edunexify.co.in}")
    private String frontendUrl;

    /**
     * Builds and sends the welcome email. Safe to call unconditionally after account
     * creation — does nothing (just logs) if the account has no email on file, and never
     * throws back to the caller.
     *
     * @param userId   the new account's login ID (Student/Teacher ID)
     * @param name     the person's display name
     * @param role     {@link Role#STUDENT} or {@link Role#TEACHER}
     * @param email    the address to send to; skipped silently if null/blank
     * @param schoolId the owning school, used to look up the school name for the email body
     */
    public void sendWelcomeEmail(String userId, String name, String role, String email, Long schoolId) {
        try {
            if (email == null || email.isBlank()) {
                log.warn("Skipping welcome email for {} {}: no email on file.", role, userId);
                return;
            }
            String schoolName = schoolId != null
                    ? schoolRepository.findById(schoolId).map(School::getName).orElse("your school")
                    : "your school";
            String safeName = (name != null && !name.isBlank()) ? name : userId;
            String roleLabel = humanRoleLabel(role);
            String loginUrl = frontendUrl + "/home";

            String subject = "Welcome to Edunexify, " + safeName + "!";
            String htmlBody = buildWelcomeHtml(safeName, userId, roleLabel, schoolName, loginUrl);

            emailService.sendHtmlEmail(email, subject, htmlBody);
            log.info("Welcome email queued for {} {} ({})", role, userId, email);
        } catch (Exception e) {
            // Never let a welcome-email failure affect the caller — account creation already
            // succeeded by the time this runs.
            log.error("Failed to send welcome email for {} {}: {}", role, userId, e.getMessage(), e);
        }
    }

    private String humanRoleLabel(String role) {
        if (Role.TEACHER.equals(role)) return "Teacher";
        if (Role.STUDENT.equals(role)) return "Student";
        return role;
    }

    private String buildWelcomeHtml(String name, String userId, String roleLabel, String schoolName, String loginUrl) {
        int year = LocalDate.now().getYear();
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Welcome to Edunexify</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:24px 16px;">
                <tr>
                  <td align="center">
                    <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                      <!-- ── Header ── -->
                      <tr>
                        <td align="center" style="background:linear-gradient(135deg,#0f172a 0%%,#1f6f8b 55%%,#4fbdbd 100%%);border-radius:16px 16px 0 0;padding:26px 20px 20px;">
                          <p style="margin:0 0 8px;font-size:36px;line-height:1;">&#127891;</p>
                          <h1 style="margin:0;color:#ffffff;font-size:20px;font-weight:800;letter-spacing:-0.3px;white-space:nowrap;">Welcome to Edunexify!</h1>
                        </td>
                      </tr>

                      <!-- ── Title band ── -->
                      <tr>
                        <td align="center" style="background-color:#0891b2;padding:9px 16px;">
                          <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:0.6px;text-transform:uppercase;white-space:nowrap;">
                            Your %s Account is Ready
                          </p>
                        </td>
                      </tr>

                      <!-- ── Body ── -->
                      <tr>
                        <td style="background-color:#ffffff;padding:26px 28px;">

                          <p style="margin:0 0 16px;font-size:16px;color:#111827;line-height:1.5;">
                            Dear <strong>%s</strong>,
                          </p>

                          <p style="margin:0 0 22px;font-size:15px;color:#6b7280;line-height:1.9;">
                            An account has been created for you on the Edunexify portal for
                            <strong style="color:#374151;">%s</strong>. You can use it to access your
                            %s dashboard, view announcements, and much more.
                          </p>

                          <!-- Account details box -->
                          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                            <tr>
                              <td style="background-color:#f0fdfa;border:2px solid #99f6e4;border-radius:12px;padding:18px 20px;">
                                <p style="margin:0 0 14px;font-size:11px;font-weight:700;color:#0f766e;letter-spacing:1.5px;text-transform:uppercase;">
                                  Your Account Details
                                </p>
                                <table width="100%%" cellpadding="0" cellspacing="0" role="presentation">
                                  <tr>
                                    <td style="padding:0 0 12px;">
                                      <p style="margin:0 0 4px;font-size:12px;color:#0f766e;line-height:1.4;white-space:nowrap;">User ID</p>
                                      <p style="margin:0;font-size:16px;font-weight:800;color:#134e4a;line-height:1.4;word-break:break-word;">%s</p>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:0 0 12px;">
                                      <p style="margin:0 0 4px;font-size:12px;color:#0f766e;line-height:1.4;white-space:nowrap;">Role</p>
                                      <p style="margin:0;font-size:16px;font-weight:700;color:#134e4a;line-height:1.4;word-break:break-word;">%s</p>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:0;">
                                      <p style="margin:0 0 4px;font-size:12px;color:#0f766e;line-height:1.4;white-space:nowrap;">School</p>
                                      <p style="margin:0;font-size:16px;font-weight:700;color:#134e4a;line-height:1.4;word-break:break-word;">%s</p>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                          </table>

                          <!-- First-login password guidance -->
                          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                            <tr>
                              <td style="background-color:#eff6ff;border-left:4px solid #2563eb;padding:12px 16px;border-radius:0 8px 8px 0;">
                                <p style="margin:0;font-size:12px;color:#1e3a5f;line-height:1.6;">
                                  &#128273; <strong>Temporary password:</strong> your date of birth as <strong>YYYYMMDD</strong>
                                  (e.g. 15 Mar 2010 &rarr; 20100315). You'll be asked to set a new password on first sign-in.
                                </p>
                              </td>
                            </tr>
                          </table>

                          <!-- CTA -->
                          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                            <tr>
                              <td align="center">
                                <a href="%s" style="display:inline-block;background:linear-gradient(135deg,#1f6f8b,#4fbdbd);color:#ffffff;font-size:16px;font-weight:800;letter-spacing:0.2px;text-decoration:none;padding:14px 40px;border-radius:10px;box-shadow:0 6px 16px rgba(31,111,139,0.35);">
                                  Sign In
                                </a>
                              </td>
                            </tr>
                          </table>

                          <p style="margin:0 0 24px;font-size:14px;color:#6b7280;line-height:1.8;">
                            If you weren't expecting this email or believe it was sent in error, please
                            contact your school administration.
                          </p>

                          <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 20px;">

                          <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                            With regards,<br>
                            <strong>%s</strong><br>
                            <span style="font-size:12px;color:#9ca3af;">Administration</span>
                          </p>
                        </td>
                      </tr>

                      <!-- ── Footer ── -->
                      <tr>
                        <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:18px 28px;">
                          <p style="margin:0 0 6px;font-size:12px;color:rgba(255,255,255,0.55);">
                            This is an automated message. Please do not reply to this email.
                          </p>
                          <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">
                            &copy; %d Edunexify. All rights reserved.
                          </p>
                        </td>
                      </tr>

                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """.formatted(roleLabel, name, schoolName, roleLabel.toLowerCase(), userId, roleLabel, schoolName, loginUrl, schoolName, year);
    }
}
