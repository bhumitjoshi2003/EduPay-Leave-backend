package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.DeviceToken;
import com.indraacademy.ias_management.repository.DeviceTokenRepository;
import com.indraacademy.ias_management.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/users/device-token")
@CrossOrigin(origins = "http://localhost:4200")
public class DeviceTokenController {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenController.class);

    @Autowired private DeviceTokenRepository deviceTokenRepository;
    @Autowired private AuthService authService;

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
            if (userId.equals(dt.getUserId())) {
                // Same user, same token — nothing to do
                return ResponseEntity.ok(Map.of("message", "Token registered."));
            }
            // Different user on the same device — delete the old row so the
            // previous user's account is no longer reachable via this device,
            // then fall through to insert a fresh row for the new user.
            deviceTokenRepository.deleteByToken(token);
            log.info("Deleted stale FCM token that belonged to user {} before re-registering for user {}",
                    dt.getUserId(), userId);
        }

        DeviceToken newToken = new DeviceToken(userId, token, LocalDateTime.now());
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

        deviceTokenRepository.deleteByToken(token);
        log.info("Removed FCM token for user {}", authService.getUserId());
        return ResponseEntity.ok(Map.of("message", "Token removed."));
    }
}
