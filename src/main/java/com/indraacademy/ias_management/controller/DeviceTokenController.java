package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.DeviceToken;
import com.indraacademy.ias_management.repository.DeviceTokenRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api/users/device-token")
public class DeviceTokenController {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenController.class);

    @Autowired private DeviceTokenRepository deviceTokenRepository;
    @Autowired private AuthService authService;
    @Autowired private SecurityUtil securityUtil;

    /**
     * Registers (or updates) an FCM device token for the currently authenticated user.
     * If the token already exists for another user it is re-assigned; if it already
     * belongs to this user it is left unchanged.
     */
    @PostMapping
    public ResponseEntity<?> registerToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body("Missing 'token' field.");
        }

        String userId = authService.getUserId();

        Optional<DeviceToken> existing = deviceTokenRepository.findByToken(token);
        if (existing.isPresent()) {
            DeviceToken dt = existing.get();
            Long schoolId = securityUtil.getSchoolId();
            if (userId.equals(dt.getUserId()) && Objects.equals(schoolId, dt.getSchoolId())) {
                // Same user, same token — nothing to do
                return ResponseEntity.ok(Map.of("message", "Token registered."));
            }
            // Possession of the current device token permits the authenticated
            // account to claim it. Update in place; never perform a client-driven
            // unscoped delete of another user's row.
            dt.setUserId(userId);
            dt.setSchoolId(schoolId);
            dt.setCreatedAt(LocalDateTime.now());
            deviceTokenRepository.save(dt);
            log.info("Reassigned FCM token to authenticated user {} in school {}", userId, schoolId);
            return ResponseEntity.ok(Map.of("message", "Token registered."));
        }

        DeviceToken newToken = new DeviceToken(userId, token, LocalDateTime.now());
        newToken.setSchoolId(securityUtil.getSchoolId());
        deviceTokenRepository.save(newToken);
        log.info("Registered FCM token for user {}", userId);
        return ResponseEntity.ok(Map.of("message", "Token registered."));
    }

    /**
     * Removes an FCM device token (called on logout or when the app receives a
     * token-refresh callback).
     */
    @DeleteMapping
    public ResponseEntity<?> removeToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body("Missing 'token' field.");
        }

        String userId = authService.getUserId();
        Long schoolId = securityUtil.getSchoolId();
        long removed = deviceTokenRepository.deleteByTokenAndUserIdAndSchoolId(token, userId, schoolId);
        log.info("Scoped FCM token removal for user {} in school {} (removed={})", userId, schoolId, removed);
        return ResponseEntity.ok(Map.of("message", "Token removed."));
    }
}
