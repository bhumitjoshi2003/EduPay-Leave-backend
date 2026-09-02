package com.indraacademy.ias_management.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.indraacademy.ias_management.entity.DeviceToken;
import com.indraacademy.ias_management.notification.ExternalDeliveryOutcome;
import com.indraacademy.ias_management.notification.ExternalDeliveryResult;
import com.indraacademy.ias_management.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class FcmService {
    private static final Logger log = LoggerFactory.getLogger(FcmService.class);
    private static final int MAX_MULTICAST_SIZE = 500;
    private final DeviceTokenRepository deviceTokenRepository;
    private final FirebaseMessagingGateway gateway;

    public FcmService(DeviceTokenRepository deviceTokenRepository, FirebaseMessagingGateway gateway) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.gateway = gateway;
    }

    /** Compatibility entry point for older callers. Durable notifications use the worker below. */
    @Async
    public void sendToUser(String userId, Long schoolId, String title, String body) {
        ExternalDeliveryResult result = deliverToUser(userId, schoolId, title, body, null, null, null, null, null, null);
        if (result.outcome() != ExternalDeliveryOutcome.SENT && result.outcome() != ExternalDeliveryOutcome.SKIPPED) {
            log.warn("Legacy push delivery failed for user {} in school {}: {}", userId, schoolId, result.detail());
        }
    }

    @Async
    public void sendToUsers(List<String> userIds, Long schoolId, String title, String body) {
        if (userIds == null) return;
        userIds.forEach(userId -> sendToUser(userId, schoolId, title, body));
    }

    /** Synchronous provider operation used outside the claim transaction by the delivery worker. */
    public ExternalDeliveryResult deliverToUser(String userId, Long schoolId, String title, String body) {
        return deliverToUser(userId, schoolId, title, body, null, null, null, null, null, null);
    }

    public ExternalDeliveryResult deliverToUser(String userId, Long schoolId, String title, String body,
                                                Long inboxId, String eventCode, String actionRoute,
                                                String sourceEntityType, String sourceEntityId, String actionMetadata) {
        if (!gateway.isAvailable()) return ExternalDeliveryResult.retryable("Firebase is not initialized");
        List<DeviceToken> registrations = deviceTokenRepository.findByUserIdAndSchoolId(userId, schoolId);
        if (registrations.isEmpty()) return ExternalDeliveryResult.skipped("No registered device token");

        int successes = 0;
        int retryableFailures = 0;
        int permanentFailures = 0;
        String firstMessageId = null;
        for (int start = 0; start < registrations.size(); start += MAX_MULTICAST_SIZE) {
            List<DeviceToken> chunk = registrations.subList(start, Math.min(start + MAX_MULTICAST_SIZE, registrations.size()));
            List<String> tokens = chunk.stream().map(DeviceToken::getToken).toList();
            try {
                BatchResponse response = gateway.send(tokens, title, body,
                        navigationData(inboxId, eventCode, actionRoute, sourceEntityType, sourceEntityId, actionMetadata));
                List<SendResponse> responses = response.getResponses();
                for (int i = 0; i < responses.size(); i++) {
                    SendResponse item = responses.get(i);
                    if (item.isSuccessful()) {
                        successes++;
                        if (firstMessageId == null) firstMessageId = item.getMessageId();
                        continue;
                    }
                    MessagingErrorCode code = item.getException() == null ? null
                            : item.getException().getMessagingErrorCode();
                    if (isInvalidToken(code)) {
                        long removed = deviceTokenRepository.deleteByTokenAndUserIdAndSchoolId(
                                tokens.get(i), userId, schoolId);
                        log.info("Removed {} invalid FCM registration(s) for user {} in school {}",
                                removed, userId, schoolId);
                        permanentFailures++;
                    } else if (isPermanent(code)) {
                        permanentFailures++;
                    } else {
                        retryableFailures++;
                    }
                }
            } catch (Exception failure) {
                if (failure instanceof FirebaseMessagingException messaging
                        && isPermanent(messaging.getMessagingErrorCode())) {
                    permanentFailures += chunk.size();
                } else {
                    retryableFailures += chunk.size();
                }
            }
        }
        if (successes > 0) return ExternalDeliveryResult.sent(firstMessageId);
        if (retryableFailures > 0) return ExternalDeliveryResult.retryable(
                "Push provider temporarily failed for " + retryableFailures + " device(s)");
        return ExternalDeliveryResult.permanent(
                "No deliverable device token (" + permanentFailures + " invalid/permanent failure(s))");
    }

    private Map<String, String> navigationData(Long inboxId, String eventCode, String actionRoute,
                                               String sourceEntityType, String sourceEntityId, String actionMetadata) {
        Map<String, String> data = new LinkedHashMap<>();
        if (inboxId != null) data.put("notificationId", inboxId.toString());
        putIfPresent(data, "eventCode", eventCode);
        putIfPresent(data, "actionRoute", actionRoute);
        putIfPresent(data, "sourceEntityType", sourceEntityType);
        putIfPresent(data, "sourceEntityId", sourceEntityId);
        // Metadata is authored by trusted business-event publishers and must contain only routing context.
        putIfPresent(data, "actionMetadata", actionMetadata);
        return Map.copyOf(data);
    }

    private void putIfPresent(Map<String, String> data, String key, String value) {
        if (value != null && !value.isBlank()) data.put(key, value);
    }

    private boolean isInvalidToken(MessagingErrorCode code) {
        // INVALID_ARGUMENT may describe the message payload rather than the token.
        // Only UNREGISTERED unambiguously proves this exact registration is stale.
        return code == MessagingErrorCode.UNREGISTERED;
    }

    private boolean isPermanent(MessagingErrorCode code) {
        return isInvalidToken(code) || code == MessagingErrorCode.INVALID_ARGUMENT
                || code == MessagingErrorCode.SENDER_ID_MISMATCH;
    }
}
