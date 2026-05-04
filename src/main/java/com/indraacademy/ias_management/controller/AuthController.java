package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ChangePasswordRequest;
import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.EmailService;
import com.indraacademy.ias_management.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private EmailService emailService;
    @Autowired private AuthService authService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AdminRepository adminRepository;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${auth.cookie.secure}")
    private boolean isSecure;

    @Value("${auth.cookie.sameSite}")
    private String sameSite;

    @GetMapping("/hari")
    public String message() {
        return "HARIBOL";
    }

    /**
     * Returns fresh user info from the database on every call.
     * The JwtAuthFilter validates the HttpOnly accessToken cookie and populates
     * the SecurityContext, so this endpoint is always authoritative — never stale.
     * Returns 401 automatically if the cookie is missing or expired.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        String userId = authService.getUserId();
        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found");
        }

        User user = userOptional.get();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("userId", user.getUserId());
        body.put("role", user.getRole());
        body.put("name", resolveName(user.getUserId(), user.getRole()));
        body.put("className", resolveClassName(user.getUserId(), user.getRole()));

        return ResponseEntity.ok(body);
    }

//    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        log.info("Request to register new user: {}", user.getUserId());
        if (user.getPassword() == null) {
            log.error("Registration failed: Password is required.");
            return ResponseEntity.badRequest().body("Password is required.");
        }

        try {
            // Original logic for registrationSecret check was commented out, leaving it out here as well.
            if (userRepository.findByUserId(user.getUserId()).isPresent()) {
                log.warn("Registration failed: User ID {} already exists.", user.getUserId());
                return ResponseEntity.status(HttpStatus.CONFLICT).body("User ID already exists.");
            }
            user.setPassword(passwordEncoder.encode(user.getPassword()));
            userRepository.save(user);
            log.info("User registered successfully: {}", user.getUserId());
            return ResponseEntity.ok("User registered successfully");
        } catch (Exception e) {
            log.error("Error during user registration for ID: {}", user.getUserId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to register user.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody User user, HttpServletResponse response) {
        Optional<User> found = userRepository.findByUserId(user.getUserId());
        if (found.isEmpty() || !passwordEncoder.matches(user.getPassword(), found.get().getPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid credentials");
        }

        User loggedIn = found.get();

        String accessToken = Jwts.builder()
                .setSubject(loggedIn.getUserId())
                .claim("role", loggedIn.getRole())
                .claim("userId", loggedIn.getUserId())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + (1000L * 60 * 60)))
                .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        String refreshToken = Jwts.builder()
                .setSubject(loggedIn.getUserId())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 7)))
                .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", accessToken, Duration.ofHours(1)).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", refreshToken, Duration.ofDays(7)).toString());

        Map<String, String> body = new LinkedHashMap<>();
        body.put("userId", loggedIn.getUserId());
        body.put("role", loggedIn.getRole());
        body.put("name", resolveName(loggedIn.getUserId(), loggedIn.getRole()));
        body.put("className", resolveClassName(loggedIn.getUserId(), loggedIn.getRole()));

        return ResponseEntity.ok(body);
    }

    private ResponseCookie buildCookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .sameSite(sameSite)
                .maxAge(maxAge)
                .build();
    }

    private String resolveName(String userId, String role) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentId(userId).map(Student::getName).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teacherRepository.findById(userId).map(Teacher::getName).orElse(null);
            } else {
                return adminRepository.findById(userId).map(Admin::getName).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve name for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private String resolveClassName(String userId, String role) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentId(userId).map(Student::getClassName).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teacherRepository.findById(userId).map(Teacher::getClassTeacher).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve className for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }


    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(HttpServletRequest request, HttpServletResponse response) {

        jakarta.servlet.http.Cookie refreshCookieRaw = WebUtils.getCookie(request, "refreshToken");
        if (refreshCookieRaw == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token missing");
        }

        String refreshToken = refreshCookieRaw.getValue();

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(jwtUtil.getPublicKey())
                    .build()
                    .parseClaimsJws(refreshToken)
                    .getBody();

            String userId = claims.getSubject();
            Optional<User> userOptional = userRepository.findByUserId(userId);

            if (userOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid refresh token");
            }

            User loggedIn = userOptional.get();
            String newAccessToken = jwtUtil.generateAccessToken(userId, loggedIn.getRole());

            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", newAccessToken, Duration.ofHours(1)).toString());

            Map<String, String> body = new LinkedHashMap<>();
            body.put("userId", loggedIn.getUserId());
            body.put("role", loggedIn.getRole());
            body.put("name", resolveName(loggedIn.getUserId(), loggedIn.getRole()));
            body.put("className", resolveClassName(loggedIn.getUserId(), loggedIn.getRole()));

            return ResponseEntity.ok(body);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token expired or invalid");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", "", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", "", Duration.ZERO).toString());

        return ResponseEntity.ok("Logged out successfully");
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request) {

        // 1. Extract info from SecurityContext (populated by JwtAuthFilter from the accessToken cookie)
        String callingUserId = authService.getUserId();
        String callingUserRole = authService.getRole();

        String targetUserId = (request.getUserId() != null && !request.getUserId().isEmpty())
                ? request.getUserId()
                : callingUserId;

        log.info("Password change request by {} ({}) for target {}", callingUserId, callingUserRole, targetUserId);

        Optional<User> userOptional = userRepository.findByUserId(targetUserId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Target user not found");
        }

        User targetUser = userOptional.get();
        String targetUserRole = targetUser.getRole();
        boolean isSelfUpdate = callingUserId.equals(targetUserId);

        if (!isSelfUpdate) {
            // Only Admins or Super Admins can change others' passwords
            if (!"ADMIN".equals(callingUserRole) && !"SUPER_ADMIN".equals(callingUserRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Access Denied: You cannot change other users' passwords.");
            }

            // Regular ADMINs cannot touch other ADMINs or SUPER_ADMINs
            if ("ADMIN".equals(callingUserRole)) {
                if ("SUPER_ADMIN".equals(targetUserRole) || "ADMIN".equals(targetUserRole)) {
                    log.warn("Unauthorized attempt: Admin {} tried to reset Admin {}", callingUserId, targetUserId);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("Admins cannot change passwords for other Admins or Super Admins.");
                }
            }
        }

        // If changing YOUR OWN password, you must provide the old one.
        if (isSelfUpdate) {
            if (request.getOldPassword() == null || request.getOldPassword().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Current password is required to change your own password.");
            }
            if (!passwordEncoder.matches(request.getOldPassword(), targetUser.getPassword())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Invalid current password.");
            }
        }

        // Save New Password
        try {
            targetUser.setPassword(passwordEncoder.encode(request.getNewPassword()));
            userRepository.save(targetUser);

            log.info("Password successfully updated for user {}", targetUserId);
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            log.error("Database error during password update for {}: {}", targetUserId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update password.");
        }
    }

    @PostMapping("/request-password-reset")
    public ResponseEntity<?> requestPasswordReset(@RequestBody User request) {
        String email = request.getEmail();
        String userId = request.getUserId();

        if (userId == null || userId.isEmpty() || email == null || email.isEmpty()) {
            log.warn("Request password reset failed: User ID and Email are required.");
            return ResponseEntity.badRequest().body("User ID and Email are required.");
        }
        log.info("Request to initiate password reset for user ID: {}", userId);

        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            log.warn("Password reset failed: User {} not found.", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
        }

        User user = userOptional.get();
        if (!user.getEmail().equalsIgnoreCase(email)) {
            log.warn("Password reset failed for user {}: Email does not match.", userId);
            return ResponseEntity.badRequest().body("Email address does not match the registered email.");
        }

        try {
            String resetToken = UUID.randomUUID().toString();
            user.setResetToken(resetToken);
            user.setResetTokenExpiry(new Date(System.currentTimeMillis() + 3600000));
            userRepository.save(user);

            String resetLink = frontendUrl + "/reset-password?token=" + resetToken;
            String subject   = "Password Reset Request – Indra Academy";
            String htmlBody  = buildPasswordResetHtml(resetLink);
            emailService.sendHtmlEmail(user.getEmail(), subject, htmlBody);

            log.info("Password reset link sent to email for user: {}", userId);

            return ResponseEntity.ok(new HashMap<String, String>() {{
                put("message", "Password reset link sent to your email address.");
            }});
        } catch (Exception e) {
            log.error("Error during password reset request for user: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to process password reset request.");
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestParam String token, @RequestBody HashMap<String, String> requestBody) {
        String newPassword = requestBody.get("newPassword");

        if (newPassword == null || newPassword.isEmpty()) {
            log.warn("Password reset failed: New password cannot be empty.");
            return ResponseEntity.badRequest().body("New password cannot be empty.");
        }
        log.info("Attempting to reset password using token.");

        try {
            Optional<User> userOptional = userRepository.findByResetToken(token);
            if (userOptional.isEmpty()) {
                log.warn("Password reset failed: Invalid reset token.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid reset token.");
            }

            User user = userOptional.get();
            if (user.getResetTokenExpiry().before(new Date())) {
                log.warn("Password reset failed for user {}: Token has expired.", user.getUserId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Reset token has expired.");
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            user.setResetToken(null);
            user.setResetTokenExpiry(null);
            userRepository.save(user);
            log.info("Password reset successfully for user: {}", user.getUserId());

            return ResponseEntity.ok("Password reset successfully.");
        } catch (Exception e) {
            log.error("Unexpected error during password reset.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to reset password.");
        }
    }

    private String buildPasswordResetHtml(String resetLink) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Password Reset</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:32px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background-color:#3730a3;border-radius:16px 16px 0 0;padding:32px 40px 24px;">
                            <p style="margin:0 0 10px;font-size:44px;line-height:1;">&#128274;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">Indra Academy</h1>
                            <p style="margin:6px 0 0;color:rgba(255,255,255,0.75);font-size:13px;">Sr. Sec. School</p>
                          </td>
                        </tr>

                        <!-- Band -->
                        <tr>
                          <td align="center" style="background-color:#4f46e5;padding:10px 40px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              Password Reset Request
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <p style="margin:0 0 16px;font-size:16px;color:#111827;">Hello,</p>
                            <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                              We received a request to reset the password for your Indra Academy account.
                              Click the button below to set a new password. This link will expire in
                              <strong style="color:#111827;">1 hour</strong>.
                            </p>

                            <!-- CTA Button -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td align="center">
                                  <a href="%s"
                                     style="display:inline-block;background-color:#4f46e5;color:#ffffff;text-decoration:none;
                                            font-size:15px;font-weight:700;padding:14px 36px;border-radius:10px;
                                            letter-spacing:0.3px;">
                                    &#128273;&nbsp; Reset My Password
                                  </a>
                                </td>
                              </tr>
                            </table>

                            <!-- Security note -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fef2f2;border-left:4px solid #dc2626;padding:14px 18px;border-radius:0 8px 8px 0;">
                                  <p style="margin:0;font-size:13px;color:#991b1b;line-height:1.7;">
                                    <strong>Didn't request this?</strong> If you did not request a password reset,
                                    please ignore this email. Your account remains secure.
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <p style="margin:0 0 6px;font-size:12px;color:#9ca3af;">Or copy and paste this URL into your browser:</p>
                            <p style="margin:0 0 28px;font-size:11px;color:#6b7280;word-break:break-all;">%s</p>

                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                              With regards,<br>
                              <strong>Indra Academy Sr. Sec. School</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">IT &amp; Administration</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; 2026 Indra Academy Sr. Sec. School. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(resetLink, resetLink);
    }
}