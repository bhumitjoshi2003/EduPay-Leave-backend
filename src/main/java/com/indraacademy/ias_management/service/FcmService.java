package com.indraacademy.ias_management.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.indraacademy.ias_management.entity.DeviceToken;
import com.indraacademy.ias_management.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);

    @Autowired private DeviceTokenRepository deviceTokenRepository;

    /**
     * Sends a push notification to all registered devices for the given userId.
     * Stale (UNREGISTERED) tokens are removed automatically.
     */
    @Async
    @Transactional
    public void sendToUser(String userId, String title, String body) {
        if (!isFirebaseAvailable()) {
            log.debug("Firebase not initialised — skipping push for user {}", userId);
            return;
        }

        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            return;
        }

        for (DeviceToken deviceToken : tokens) {
            sendToToken(deviceToken.getToken(), title, body);
        }
    }

    /**
     * Sends a push notification to every device token in the provided list of userIds.
     * This method is @Async so it runs on a background thread. The inner sendToUser()
     * calls are direct (not through the proxy), which is fine — we are already off the
     * request thread, so there is no blocking concern.
     */
    @Async
    @Transactional
    public void sendToUsers(List<String> userIds, String title, String body) {
        if (!isFirebaseAvailable() || userIds == null || userIds.isEmpty()) {
            return;
        }
        for (String userId : userIds) {
            List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
            for (DeviceToken deviceToken : tokens) {
                sendToToken(deviceToken.getToken(), title, body);
            }
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private void sendToToken(String fcmToken, String title, String body) {
        Message message = Message.builder()
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setToken(fcmToken)
                .build();

        try {
            String response = FirebaseMessaging.getInstance().send(message);
            log.debug("FCM message sent. Response: {}", response);
        } catch (FirebaseMessagingException e) {
            if (MessagingErrorCode.UNREGISTERED.equals(e.getMessagingErrorCode())) {
                log.info("Removing stale FCM token: {}", fcmToken);
                deviceTokenRepository.deleteByToken(fcmToken);
            } else {
                log.warn("FCM send failed for token {}: {}", fcmToken, e.getMessage());
            }
        }
    }

    private boolean isFirebaseAvailable() {
        return !FirebaseApp.getApps().isEmpty();
    }
}
