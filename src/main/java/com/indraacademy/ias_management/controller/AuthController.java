package com.indraacademy.ias_management.controller;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Date;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private EmailService emailService;
    @Autowired private AuthService authService;

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @GetMapping("/hari")
    public String message() {
        return "HARIBOL";
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userRepository.save(user);
        return ResponseEntity.ok("User registered successfully");
    }

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody User user) {
        logger.info("Received login request with User: {}", user);
        Optional<User> foundUser = userRepository.findByUserId(user.getUserId());
        if (foundUser.isPresent() && passwordEncoder.matches(user.getPassword(), foundUser.get().getPassword())) {
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
        return ResponseEntity.badRequest().body("Invalid credentials");
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String userId = authService.getUserIdFromToken(authorizationHeader);
        String role = authService.getRoleFromToken(authorizationHeader);
        if(role.equals("ADMIN")){
            userId = request.getUserId();
        }
        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        User user = userOptional.get();
        if (request.getOldPassword() != null && !request.getOldPassword().isEmpty()) {
            if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid old password");
            }
        }
        if(!role.equals("ADMIN") && (request.getOldPassword() == null || request.getOldPassword().isEmpty())){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid old password");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok("Password changed successfully");
    }

    @PostMapping("/request-password-reset")
    public ResponseEntity<?> requestPasswordReset(@RequestBody User request) {
        String email = request.getEmail();
        String userId = request.getUserId();

        if (userId == null || userId.isEmpty() || email == null || email.isEmpty()) {
            return ResponseEntity.badRequest().body("User ID and Email are required.");
        }

        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
        }

        User user = userOptional.get();
        if (!user.getEmail().equalsIgnoreCase(email)) {
            return ResponseEntity.badRequest().body("Email address does not match the registered email.");
        }

        String resetToken = UUID.randomUUID().toString();
        user.setResetToken(resetToken);
        user.setResetTokenExpiry(new Date(System.currentTimeMillis() + 3600000)); // Token expires in 1 hour
        userRepository.save(user);

        String resetLink = "http://localhost:4200/reset-password?token=" + resetToken; //  frontend URL

        String subject = "Password Reset Request";
        String body = "To reset your password, please click on the following link: " + resetLink;
        emailService.sendEmail(user.getEmail(), subject, body);

        return ResponseEntity.ok(new HashMap<String, String>() {{
            put("message", "Password reset link sent to your email address.");
        }});
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestParam String token, @RequestBody HashMap<String, String> requestBody) {
        String newPassword = requestBody.get("newPassword");
        if (newPassword == null || newPassword.isEmpty()) {
            return ResponseEntity.badRequest().body("New password cannot be empty.");
        }

        Optional<User> userOptional = userRepository.findByResetToken(token);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid reset token.");
        }

        User user = userOptional.get();
        if (user.getResetTokenExpiry().before(new Date())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Reset token has expired.");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setResetToken(null);
        user.setResetTokenExpiry(null);
        userRepository.save(user);

        return ResponseEntity.ok("Password reset successfully.");
    }
}

