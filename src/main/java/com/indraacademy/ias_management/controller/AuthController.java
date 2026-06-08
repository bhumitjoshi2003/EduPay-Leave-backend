package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.RateLimiter;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ChangePasswordRequest;
import com.indraacademy.ias_management.dto.LoginRequest;
import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PermissionService;
import com.indraacademy.ias_management.service.EmailService;
import com.indraacademy.ias_management.util.JwtUtil;
import com.indraacademy.ias_management.util.SchoolContext;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final Set<String> VALID_ROLES = Set.of(
            Role.SUPER_ADMIN, Role.ADMIN, Role.SUB_ADMIN, Role.TEACHER, Role.STUDENT);

    @Autowired private UserRepository userRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private EmailService emailService;
    @Autowired private AuthService authService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AdminRepository adminRepository;
    @Autowired private com.indraacademy.ias_management.service.EntitlementService entitlementService;
    @Autowired private com.indraacademy.ias_management.repository.SchoolEffectiveEntitlementRepository entitlementRepo;
    @Autowired private RateLimiter rateLimiter;
    @Autowired private PermissionService permissionService;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${auth.cookie.secure}")
    private boolean isSecure;

    @Value("${auth.cookie.sameSite}")
    private String sameSite;

    @Value("${jwt.access-token.expiry-minutes}")
    private long accessTokenExpiryMinutes;

    @Value("${jwt.refresh-token.expiry-days}")
    private long refreshTokenExpiryDays;

    /** Cookie domain — must cover all school subdomains (e.g. "edunexify.co.in"). */
    @Value("${app.base-domain:edunexify.co.in}")
    private String cookieDomain;

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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getUserId());
        body.put("role", user.getRole());
        body.put("name", resolveName(user.getUserId(), user.getRole(), user.getSchoolId()));
        body.put("className", resolveClassName(user.getUserId(), user.getRole(), user.getSchoolId()));
        body.put("schoolSlug", resolveSchoolSlug(user.getSchoolId()));

        // Entitlement fields — only for school-scoped users (not SUPER_ADMIN)
        appendEntitlementFields(body, user.getSchoolId());

        // Permission keys for the user's role at their school
        try {
            List<String> permKeys = permissionService.getPermissionKeysForRole(user.getRole(), user.getSchoolId());
            body.put("permissionKeys", permKeys);
        } catch (Exception e) {
            body.put("permissionKeys", List.of());
        }

        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        log.info("Request to register new user: {}", user.getUserId());

        if (user.getPassword() == null || user.getRole() == null || user.getUserId() == null) {
            return ResponseEntity.badRequest().body("userId, password, and role are required.");
        }
        if (!VALID_ROLES.contains(user.getRole())) {
            return ResponseEntity.badRequest().body("Invalid role.");
        }

        String callerRole = authService.getRole();
        boolean callerIsSuperAdmin = Role.SUPER_ADMIN.equals(callerRole);

        // Only SUPER_ADMIN may create another SUPER_ADMIN
        if (Role.SUPER_ADMIN.equals(user.getRole()) && !callerIsSuperAdmin) {
            log.warn("Privilege escalation attempt: caller {} ({}) tried to create SUPER_ADMIN account '{}'",
                    authService.getUserId(), callerRole, user.getUserId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only a SUPER_ADMIN can create a SUPER_ADMIN account.");
        }

        if (userRepository.findByUserId(user.getUserId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("User ID already exists.");
        }

        // SUPER_ADMIN may set any schoolId; anyone else inherits from their JWT.
        if (!callerIsSuperAdmin) {
            Long callerSchoolId = SchoolContext.get();
            if (callerSchoolId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Caller has no schoolId.");
            }
            user.setSchoolId(callerSchoolId);
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userRepository.save(user);
        return ResponseEntity.ok("User registered successfully");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletResponse response) {
        if (rateLimiter.isRateLimited("login:" + req.getUserId(), 5, 300000)) {
            return ResponseEntity.status(429).body("Too many login attempts. Try again in 5 minutes.");
        }

        Optional<User> found = userRepository.findByUserId(req.getUserId());
        if (found.isEmpty()) {
            log.warn("Login failed: userId={} not found", req.getUserId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid credentials");
        }
        if (!passwordEncoder.matches(req.getPassword(), found.get().getPassword())) {
            log.warn("Login failed: wrong password for userId={}", req.getUserId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid credentials");
        }

        User loggedIn = found.get();

        // Reject login if the school has been deactivated (SUPER_ADMIN has no schoolId — skip for them)
        if (loggedIn.getSchoolId() != null) {
            School school = schoolRepository.findById(loggedIn.getSchoolId()).orElse(null);
            if (school == null || !school.isActive()) {
                log.warn("Login rejected for userId={}: school {} is inactive", loggedIn.getUserId(), loggedIn.getSchoolId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Your school account has been deactivated. Please contact Edunexify support.");
            }

            // Subscription enforcement is handled by SubscriptionEnforcementFilter:
            // - GET requests are always allowed (read-only access)
            // - POST/PUT/DELETE/PATCH are blocked with 402
            // All roles (including STUDENT and TEACHER) can log in and view data
            // even when the subscription is expired.
        }

        // Option B — school-scoped login enforcement.
        // schoolSlug is only sent when the user is on a branded login page.
        // SUPER_ADMIN has no schoolId so we skip the check for them.
        if (req.getSchoolSlug() != null && !req.getSchoolSlug().isBlank()
                && loggedIn.getSchoolId() != null) {
            String userSchoolSlug = resolveSchoolSlug(loggedIn.getSchoolId());
            if (!req.getSchoolSlug().equalsIgnoreCase(userSchoolSlug)) {
                log.warn("Login rejected: userId={} attempted login on school slug '{}' but belongs to '{}'",
                        loggedIn.getUserId(), req.getSchoolSlug(), userSchoolSlug);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("This account does not belong to this school.");
            }
        }

        String accessToken = Jwts.builder()
                .setSubject(loggedIn.getUserId())
                .claim("role", loggedIn.getRole())
                .claim("userId", loggedIn.getUserId())
                .claim("schoolId", loggedIn.getSchoolId())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + (1000L * 60 * accessTokenExpiryMinutes)))
                .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        // Generate a unique JTI and persist it so we can revoke this refresh token on logout
        String jti = UUID.randomUUID().toString();
        loggedIn.setRefreshTokenId(jti);
        userRepository.save(loggedIn);

        String refreshToken = Jwts.builder()
                .setSubject(loggedIn.getUserId())
                .setId(jti)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * refreshTokenExpiryDays)))
                .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        // Clear any stale cookies from previous sessions before issuing new ones.
        // Two clearing passes: once with Domain (covers post-domain-fix cookies) and once
        // without Domain (covers legacy host-only cookies set before the Domain fix).
        clearCookies(response);

        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", accessToken, Duration.ofMinutes(accessTokenExpiryMinutes)).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", refreshToken, Duration.ofDays(refreshTokenExpiryDays)).toString());

        log.info("Login success: userId={}, role={}, schoolId={}", loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", loggedIn.getUserId());
        body.put("role", loggedIn.getRole());
        body.put("name", resolveName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
        body.put("className", resolveClassName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
        body.put("schoolSlug", resolveSchoolSlug(loggedIn.getSchoolId()));
        appendEntitlementFields(body, loggedIn.getSchoolId());

        return ResponseEntity.ok(body);
    }

    private ResponseCookie buildCookie(String name, String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .sameSite(sameSite)
                .maxAge(maxAge);
        // Only set Domain when configured — omitting it makes the cookie host-only,
        // which is required for localhost development (browsers reject Domain=edunexify.co.in on localhost).
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        return builder.build();
    }

    /**
     * Sends Max-Age=0 clearing cookies to remove any stale session cookies from
     * previous logins. Two passes are required:
     *   1. With Domain= — clears cookies that were set with the domain attribute
     *      (sessions after the domain fix was deployed).
     *   2. Without Domain — clears legacy host-only cookies set before the domain fix.
     * Both passes are needed during the migration window while old host-only cookies
     * may still be present in users' browsers.
     */
    private void clearCookies(HttpServletResponse response) {
        // Pass 1: domain-scoped cookies
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken",  "", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", "", Duration.ZERO).toString());

        // Pass 2: host-only cookies (no Domain attribute)
        ResponseCookie clearAccess  = ResponseCookie.from("accessToken",  "").httpOnly(true).secure(isSecure).path("/").sameSite(sameSite).maxAge(Duration.ZERO).build();
        ResponseCookie clearRefresh = ResponseCookie.from("refreshToken", "").httpOnly(true).secure(isSecure).path("/").sameSite(sameSite).maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearAccess.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefresh.toString());
    }

    /**
     * Appends subscription entitlement fields to the auth response body.
     * SUPER_ADMIN has no schoolId → all fields are null/empty.
     * School users with no subscription yet → fields are null/empty (no exception thrown).
     */
    private void appendEntitlementFields(Map<String, Object> body, Long schoolId) {
        if (schoolId == null) {
            body.put("featureKeys",         List.of());
            body.put("planTier",            null);
            body.put("planVersion",         null);
            body.put("subscriptionStatus",  null);
            body.put("trialEndsAt",         null);
            body.put("expiresAt",           null);
            body.put("graceEndsAt",         null);
            return;
        }
        try {
            var ent = entitlementRepo.findById(schoolId).orElse(null);
            if (ent == null) {
                body.put("featureKeys",         List.of());
                body.put("planTier",            null);
                body.put("planVersion",         null);
                body.put("subscriptionStatus",  null);
                body.put("trialEndsAt",         null);
                body.put("expiresAt",           null);
                body.put("graceEndsAt",         null);
            } else {
                body.put("featureKeys",         entitlementService.getEffectiveFeatureKeys(schoolId));
                body.put("planTier",            ent.getPlanTier());
                body.put("planVersion",         ent.getPlanVersion());
                body.put("subscriptionStatus",  ent.getSubscriptionStatus());
                body.put("trialEndsAt",         ent.getTrialEndsAt() != null ? ent.getTrialEndsAt().toString() : null);
                body.put("expiresAt",           ent.getExpiresAt() != null ? ent.getExpiresAt().toString() : null);
                body.put("graceEndsAt",         ent.getGraceEndsAt() != null ? ent.getGraceEndsAt().toString() : null);
            }
        } catch (Exception e) {
            log.warn("Could not load entitlement for schoolId={}: {}", schoolId, e.getMessage());
            body.put("featureKeys",         List.of());
            body.put("planTier",            null);
            body.put("planVersion",         null);
            body.put("subscriptionStatus",  null);
            body.put("trialEndsAt",         null);
            body.put("expiresAt",           null);
            body.put("graceEndsAt",         null);
        }
    }

    private String resolveSchoolSlug(Long schoolId) {
        if (schoolId == null) return null;
        return schoolRepository.findById(schoolId).map(School::getSlug).orElse(null);
    }

    private String resolveName(String userId, String role, Long schoolId) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(Student::getName).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teacherRepository.findByTeacherIdAndSchoolId(userId, schoolId)
                        .map(Teacher::getName).orElse(null);
            } else {
                // ADMIN, SUB_ADMIN: scope to school; SUPER_ADMIN has null schoolId so fall back to findById
                if (schoolId != null) {
                    return adminRepository.findByAdminIdAndSchoolId(userId, schoolId)
                            .map(Admin::getName).orElse(null);
                }
                return adminRepository.findById(userId).map(Admin::getName).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve name for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private String resolveClassName(String userId, String role, Long schoolId) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(Student::getClassName).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teacherRepository.findByTeacherIdAndSchoolId(userId, schoolId)
                        .map(Teacher::getClassTeacher).orElse(null);
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
            String tokenJti = claims.getId();

            Optional<User> userOptional = userRepository.findByUserId(userId);

            if (userOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid refresh token");
            }

            User loggedIn = userOptional.get();

            // Verify the JTI matches the stored value — rejects any token issued before the last logout
            if (tokenJti == null || !tokenJti.equals(loggedIn.getRefreshTokenId())) {
                log.warn("Refresh token JTI mismatch for userId={} — token has been revoked.", userId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token has been revoked.");
            }

            // Reject token refresh if the school has been deactivated
            if (loggedIn.getSchoolId() != null) {
                School school = schoolRepository.findById(loggedIn.getSchoolId()).orElse(null);
                if (school == null || !school.isActive()) {
                    log.warn("Token refresh rejected for userId={}: school {} is inactive", userId, loggedIn.getSchoolId());
                    // Clear all cookies (both domain-scoped and host-only) so the client is fully logged out
                    clearCookies(response);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("Your school account has been deactivated. Please contact Edunexify support.");
                }

                // Block token refresh for STUDENT/TEACHER when subscription is EXPIRED.
                // ADMIN/SUB_ADMIN are allowed to keep their session so they can renew.
                String refreshRole = loggedIn.getRole();
                if (Role.STUDENT.equals(refreshRole) || Role.TEACHER.equals(refreshRole)) {
                    var ent = entitlementRepo.findById(loggedIn.getSchoolId()).orElse(null);
                    if (ent != null && "EXPIRED".equals(ent.getSubscriptionStatus())) {
                        log.warn("Token refresh rejected for userId={} ({}): school {} subscription is EXPIRED",
                                userId, refreshRole, loggedIn.getSchoolId());
                        clearCookies(response);
                        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                                .body("Your school's subscription has expired. Please contact your school administrator.");
                    }
                }
            }

            String newAccessToken = jwtUtil.generateAccessToken(userId, loggedIn.getRole(), loggedIn.getSchoolId());

            // Rotate the JTI so the old refresh token cannot be reused
            String newJti = UUID.randomUUID().toString();
            loggedIn.setRefreshTokenId(newJti);
            userRepository.save(loggedIn);

            String newRefreshToken = Jwts.builder()
                    .setSubject(userId)
                    .setId(newJti)
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * refreshTokenExpiryDays)))
                    .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                    .compact();

            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", newAccessToken, Duration.ofMinutes(accessTokenExpiryMinutes)).toString());
            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", newRefreshToken, Duration.ofDays(refreshTokenExpiryDays)).toString());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", loggedIn.getUserId());
            body.put("role", loggedIn.getRole());
            body.put("name", resolveName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
            body.put("className", resolveClassName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
            body.put("schoolSlug", resolveSchoolSlug(loggedIn.getSchoolId()));
            appendEntitlementFields(body, loggedIn.getSchoolId());

            return ResponseEntity.ok(body);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token expired or invalid");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        // Revoke the refresh token server-side by clearing the stored JTI.
        // This ensures the token cannot be reused even if someone captured its raw value.
        jakarta.servlet.http.Cookie refreshCookieRaw = WebUtils.getCookie(request, "refreshToken");
        if (refreshCookieRaw != null) {
            try {
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(jwtUtil.getPublicKey())
                        .build()
                        .parseClaimsJws(refreshCookieRaw.getValue())
                        .getBody();
                String userId = claims.getSubject();
                userRepository.findByUserId(userId).ifPresent(user -> {
                    user.setRefreshTokenId(null);
                    userRepository.save(user);
                });
                log.info("Refresh token revoked server-side for userId={}", userId);
            } catch (Exception e) {
                // Token may already be expired or malformed — still clear cookies
                log.debug("Could not parse refresh token during logout: {}", e.getMessage());
            }
        }

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

        if (rateLimiter.isRateLimited("reset:" + email, 3, 3600000)) {
            return ResponseEntity.status(429).body("Too many reset requests. Try again in 1 hour.");
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
            String rawToken = UUID.randomUUID().toString();
            user.setResetToken(hashToken(rawToken));
            user.setResetTokenExpiry(new Date(System.currentTimeMillis() + 3600000));
            userRepository.save(user);

            String resetLink = frontendUrl + "/reset-password?token=" + rawToken;
            String subject   = "Password Reset Request – Edunexify";
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
            Optional<User> userOptional = userRepository.findByResetToken(hashToken(token));
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

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash token", e);
        }
    }

    private String buildPasswordResetHtml(String resetLink) {
        int year = LocalDate.now().getYear();
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
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">Edunexify</h1>
                            <p style="margin:6px 0 0;color:rgba(255,255,255,0.75);font-size:13px;">School Management Platform</p>
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
                              We received a request to reset the password for your Edunexify account.
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
                              <strong>Edunexify</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">IT &amp; Support</span>
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
                """.formatted(resetLink, resetLink, year);
    }
}