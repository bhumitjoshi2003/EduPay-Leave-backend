package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Owns the token-based "set/reset your password" email link — the same secure mechanism
 * AuthController's self-service forgot-password flow already used before this class
 * existed (that logic was extracted here, unchanged in behavior, so it could also be
 * reused for Parent account onboarding rather than inventing a second mechanism).
 *
 * <p>A new Parent account (manual or bulk-imported) is created with an unguessable random
 * password that is never surfaced anywhere — the parent's actual first password is
 * whatever they set via the link this class sends, exactly like a normal password reset.
 * No plaintext password ever needs to be communicated to anyone for this to work.
 */
@Service
public class PasswordResetService {

    @Autowired private UserRepository userRepository;
    @Autowired private EmailService emailService;

    @Value("${frontend.url}")
    private String frontendUrl;

    /** Generates a token, persists its hash + 1-hour expiry on {@code user}, and emails the
     *  set/reset-password link with the given subject and intro paragraph. Shared by the
     *  self-service "forgot password" flow (AuthController) and new Parent onboarding
     *  (ParentPortalService / Parent Bulk Import). */
    public void sendResetLink(User user, String subject, String introHtml) {
        String rawToken = UUID.randomUUID().toString();
        user.setResetToken(hashToken(rawToken));
        user.setResetTokenExpiry(new Date(System.currentTimeMillis() + 3600000));
        userRepository.save(user);

        String resetLink = frontendUrl + "/reset-password?token=" + rawToken;
        emailService.sendHtmlEmail(user.getEmail(), subject, buildPasswordResetHtml(resetLink, introHtml));
    }

    /** As {@link #sendResetLink}, but for a brand-new Parent account: the email additionally
     *  states the generated Parent ID (their login username) so it's on record for the
     *  parent to use if they ever need self-service "forgot password" later, and so they
     *  aren't left with no way to know their own login id if this link expires unused. */
    public void sendParentWelcomeLink(User user, String parentName, String schoolName) {
        String rawToken = UUID.randomUUID().toString();
        user.setResetToken(hashToken(rawToken));
        user.setResetTokenExpiry(new Date(System.currentTimeMillis() + 3600000));
        userRepository.save(user);

        String resetLink = frontendUrl + "/reset-password?token=" + rawToken;
        emailService.sendHtmlEmail(user.getEmail(), "Welcome to Edunexify – Set Your Password",
                buildParentWelcomeHtml(resetLink, parentName, user.getUserId(), schoolName));
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash token", e);
        }
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Parent onboarding deliberately has its own renderer rather than sharing the forgot-password
     * copy. Both flows reuse the same secure token lifecycle, but their email purpose and wording
     * must remain distinct.
     */
    private String buildParentWelcomeHtml(String resetLink, String parentName, String parentId, String schoolName) {
        int year = LocalDate.now().getYear();
        String safeParentName = escapeHtml(parentName);
        String safeParentId = escapeHtml(parentId);
        String safeSchoolName = escapeHtml(schoolName);
        String safeResetLink = escapeHtml(resetLink);

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Welcome to Edunexify</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="background-color:#f4f6f9;padding:24px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" role="presentation" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background:linear-gradient(135deg,#0f172a 0%%,#1f6f8b 55%%,#4fbdbd 100%%);border-radius:16px 16px 0 0;padding:26px 20px 20px;">
                            <p style="margin:0 0 8px;font-size:36px;line-height:1;">&#128106;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:20px;font-weight:800;letter-spacing:-0.3px;white-space:nowrap;">Welcome to Edunexify!</h1>
                          </td>
                        </tr>

                        <!-- Title band -->
                        <tr>
                          <td align="center" style="background-color:#0891b2;padding:9px 16px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:0.6px;text-transform:uppercase;white-space:nowrap;">
                              Your Parent Account is Ready
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:26px 28px;">
                            <p style="margin:0 0 16px;font-size:16px;color:#111827;line-height:1.5;">
                              Dear <strong>%s</strong>,
                            </p>

                            <p style="margin:0 0 22px;font-size:15px;color:#6b7280;line-height:1.9;">
                              A parent account has been created for you on the Edunexify portal for
                              <strong style="color:#374151;">%s</strong>. Set your password to securely access
                              the information the school has shared for your child or children.
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
                                        <p style="margin:0 0 4px;font-size:12px;color:#0f766e;line-height:1.4;white-space:nowrap;">Parent ID</p>
                                        <p style="margin:0;font-size:16px;font-weight:800;color:#134e4a;line-height:1.4;word-break:break-word;">%s</p>
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

                            <!-- Expiry guidance -->
                            <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                              <tr>
                                <td style="background-color:#eff6ff;border-left:4px solid #2563eb;padding:12px 16px;border-radius:0 8px 8px 0;">
                                  <p style="margin:0;font-size:12px;color:#1e3a5f;line-height:1.6;">
                                    &#128274; For your security, this password-setup link expires in <strong>1 hour</strong>.
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <!-- CTA Button -->
                            <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                              <tr>
                                <td align="center">
                                  <a href="%s"
                                     style="display:inline-block;background:linear-gradient(135deg,#1f6f8b,#4fbdbd);color:#ffffff;text-decoration:none;font-size:16px;font-weight:800;padding:14px 40px;border-radius:10px;letter-spacing:0.2px;box-shadow:0 6px 16px rgba(31,111,139,0.35);">
                                    Set My Password
                                  </a>
                                </td>
                              </tr>
                            </table>

                            <!-- Security note -->
                            <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                              <tr>
                                <td style="background-color:#fef2f2;border-left:4px solid #dc2626;padding:14px 18px;border-radius:0 8px 8px 0;">
                                  <p style="margin:0;font-size:13px;color:#991b1b;line-height:1.7;">
                                    <strong>Didn't request this?</strong> If you did not expect this email,
                                    please contact the school office. No changes are made to your account until you click the link.
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <p style="margin:0 0 6px;font-size:12px;color:#9ca3af;">If the button does not work, copy and paste this URL into your browser:</p>
                            <p style="margin:0 0 24px;font-size:11px;color:#6b7280;word-break:break-all;">%s</p>

                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 20px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                              With regards,<br>
                              <strong>%s</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">Administration</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:18px 28px;">
                            <p style="margin:0 0 6px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; %d Edunexify. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(safeParentName, safeSchoolName, safeParentId, safeSchoolName,
                        safeResetLink, safeResetLink, safeSchoolName, year);
    }

    /** Renders the ordinary self-service forgot-password email with reset-specific wording. */
    private String buildPasswordResetHtml(String resetLink, String trustedIntroHtml) {
        int year = LocalDate.now().getYear();
        String safeResetLink = escapeHtml(resetLink);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Password Reset Request - Edunexify</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="background-color:#f4f6f9;padding:24px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" role="presentation" style="max-width:600px;width:100%%;">
                        <tr>
                          <td align="center" style="background:linear-gradient(135deg,#0f172a 0%%,#1f6f8b 55%%,#4fbdbd 100%%);border-radius:16px 16px 0 0;padding:26px 20px 20px;">
                            <p style="margin:0 0 8px;font-size:36px;line-height:1;">&#128274;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:20px;font-weight:800;letter-spacing:-0.3px;white-space:nowrap;">Edunexify</h1>
                          </td>
                        </tr>
                        <tr>
                          <td align="center" style="background-color:#0891b2;padding:9px 16px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:0.6px;text-transform:uppercase;white-space:nowrap;">Password Reset Request</p>
                          </td>
                        </tr>
                        <tr>
                          <td style="background-color:#ffffff;padding:26px 28px;">
                            <p style="margin:0 0 22px;font-size:15px;color:#6b7280;line-height:1.9;">%s</p>
                            <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                              <tr><td align="center">
                                <a href="%s" style="display:inline-block;background:linear-gradient(135deg,#1f6f8b,#4fbdbd);color:#ffffff;text-decoration:none;font-size:16px;font-weight:800;padding:14px 40px;border-radius:10px;letter-spacing:0.2px;box-shadow:0 6px 16px rgba(31,111,139,0.35);">Reset My Password</a>
                              </td></tr>
                            </table>
                            <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="margin-bottom:22px;">
                              <tr><td style="background-color:#fef2f2;border-left:4px solid #dc2626;padding:14px 18px;border-radius:0 8px 8px 0;">
                                <p style="margin:0;font-size:13px;color:#991b1b;line-height:1.7;"><strong>Didn't request this?</strong> You can safely ignore this email. Your password will remain unchanged unless this link is used.</p>
                              </td></tr>
                            </table>
                            <p style="margin:0 0 6px;font-size:12px;color:#9ca3af;">If the button does not work, copy and paste this URL into your browser:</p>
                            <p style="margin:0 0 24px;font-size:11px;color:#6b7280;word-break:break-all;">%s</p>
                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 20px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">With regards,<br><strong>Edunexify</strong><br><span style="font-size:12px;color:#9ca3af;">IT &amp; Support</span></p>
                          </td>
                        </tr>
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:18px 28px;">
                            <p style="margin:0 0 6px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; %d Edunexify. All rights reserved.</p>
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(trustedIntroHtml, safeResetLink, safeResetLink, year);
    }
}
