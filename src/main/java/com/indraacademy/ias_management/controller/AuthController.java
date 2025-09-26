package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ChangePasswordRequest;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.EmailService;
import com.indraacademy.ias_management.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Date;
import java.util.HashMap;
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

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${registration.secret-key}")
    private String registrationSecretKey;

    @GetMapping("/hari")
    public String message() {
        return "HARIBOL";
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
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
    public ResponseEntity<?> loginUser(@RequestBody User user) {
        log.info("Received login request for User: {}", user.getUserId());

        if (user.getUserId() == null || user.getPassword() == null) {
            return ResponseEntity.badRequest().body("User ID and password are required.");
        }

        Optional<User> foundUser = userRepository.findByUserId(user.getUserId());
        if (foundUser.isPresent() && passwordEncoder.matches(user.getPassword(), foundUser.get().getPassword())) {
            log.info("Login successful for user: {}", user.getUserId());
            String token = Jwts.builder()
                    .setSubject(foundUser.get().getUserId())
                    .claim("role", foundUser.get().getRole())
                    .claim("userId", foundUser.get().getUserId())
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                    .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                    .compact();
            return ResponseEntity.ok(token);
        }
        log.warn("Login failed for user: {}. Invalid credentials.", user.getUserId());
        return ResponseEntity.badRequest().body("Invalid credentials");
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String callingUserId = authService.getUserIdFromToken(authorizationHeader);
        String role = authService.getRoleFromToken(authorizationHeader);
        log.info("Request to change password by user: {} (Role: {})", callingUserId, role);

        String targetUserId = callingUserId;
        if(Role.ADMIN.equals(role)){
            targetUserId = request.getUserId();
            log.info("Admin is changing password for target user: {}", targetUserId);
        }

        if (targetUserId == null) {
            log.error("Target user ID is null in change password request.");
            return ResponseEntity.badRequest().body("User ID is required.");
        }

        Optional<User> userOptional = userRepository.findByUserId(targetUserId);
        if (userOptional.isEmpty()) {
            log.warn("User {} not found for password change.", targetUserId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        User user = userOptional.get();
        if(!Role.ADMIN.equals(role) && (request.getOldPassword() == null || request.getOldPassword().isEmpty())){
            log.warn("Non-admin user {} failed to provide old password.", callingUserId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Old password is required for non-admin users.");
        }

        if (!Role.ADMIN.equals(role) && !passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            log.warn("Invalid old password provided by user: {}", callingUserId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid old password");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed successfully for user: {}", targetUserId);
        return ResponseEntity.ok("Password changed successfully");
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
            String subject = "Password Reset Request";
            String body = "To reset your password, please click on the following link: " + resetLink;
            emailService.sendEmail(user.getEmail(), subject, body);

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
}