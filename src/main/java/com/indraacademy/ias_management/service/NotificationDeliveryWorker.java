package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.notification.ClaimedNotificationDelivery;
import com.indraacademy.ias_management.notification.ExternalDeliveryOutcome;
import com.indraacademy.ias_management.notification.ExternalDeliveryResult;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NotificationDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    private final NotificationDeliveryClaimService claimService;
    private final NotificationDeliveryStateService stateService;
    private final NotificationRetryPolicy retryPolicy;
    private final NotificationDeliveryFailureClassifier failureClassifier;
    private final FcmService fcmService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final SchoolRepository schoolRepository;
    private final AtomicBoolean running = new AtomicBoolean();
    private final String workerInstance = UUID.randomUUID().toString();

    @Value("${notification.delivery.batch-size:50}")
    private int batchSize;
    @Value("${notification.delivery.lease-seconds:120}")
    private long leaseSeconds;
    @Value("${notification.delivery.retention-days:90}")
    private long retentionDays;

    public NotificationDeliveryWorker(NotificationDeliveryClaimService claimService,
                                      NotificationDeliveryStateService stateService,
                                      NotificationRetryPolicy retryPolicy,
                                      NotificationDeliveryFailureClassifier failureClassifier,
                                      FcmService fcmService, EmailService emailService,
                                      UserRepository userRepository, SchoolRepository schoolRepository) {
        this.claimService = claimService;
        this.stateService = stateService;
        this.retryPolicy = retryPolicy;
        this.failureClassifier = failureClassifier;
        this.fcmService = fcmService;
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.schoolRepository = schoolRepository;
    }

    @Scheduled(fixedDelayString = "${notification.delivery.poll-interval-ms:30000}",
            initialDelayString = "${notification.delivery.initial-delay-ms:15000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Notification delivery poll skipped because this instance is still running");
            return;
        }
        String leaseOwner = workerInstance + ":" + UUID.randomUUID();
        try {
            List<ClaimedNotificationDelivery> batch = claimService.claim(
                    LocalDateTime.now(), batchSize, Duration.ofSeconds(leaseSeconds), leaseOwner);
            if (!batch.isEmpty()) log.info("Claimed {} notification delivery row(s)", batch.size());
            batch.forEach(this::processDelivery);
        } catch (Exception failure) {
            log.error("Notification delivery poll failed before batch completion", failure);
        } finally {
            running.set(false);
        }
    }

    void processDelivery(ClaimedNotificationDelivery delivery) {
        LocalDateTime now = LocalDateTime.now();
        try {
            User activeRecipient = userRepository
                    .findByUserIdAndSchoolIdAndActiveTrue(delivery.recipientUserId(), delivery.schoolId())
                    .orElse(null);
            if (activeRecipient == null) {
                complete(delivery, ExternalDeliveryResult.skipped("Recipient is inactive or no longer belongs to school"), now);
                return;
            }

            ExternalDeliveryResult result = switch (delivery.channel()) {
                case PUSH -> fcmService.deliverToUser(delivery.recipientUserId(), delivery.schoolId(),
                        delivery.title(), delivery.message(), delivery.inboxId(), delivery.eventCode(),
                        delivery.actionRoute(), delivery.sourceEntityType(), delivery.sourceEntityId(), delivery.actionMetadata());
                case EMAIL -> deliverEmail(delivery, activeRecipient);
            };
            complete(delivery, result, now);
        } catch (Exception failure) {
            ExternalDeliveryOutcome outcome = failureClassifier.classify(failure);
            String detail = safeFailure(failure);
            complete(delivery, outcome == ExternalDeliveryOutcome.PERMANENT_FAILURE
                    ? ExternalDeliveryResult.permanent(detail) : ExternalDeliveryResult.retryable(detail), now);
        }
    }

    private ExternalDeliveryResult deliverEmail(ClaimedNotificationDelivery delivery, User recipient) {
        String email = recipient.getEmail();
        if (email == null || email.isBlank()) return ExternalDeliveryResult.skipped("Recipient has no email address");
        String schoolName = schoolRepository.findById(delivery.schoolId()).map(School::getName).orElse("School");
        emailService.sendNotificationEmailOrThrow(email, delivery.title(),
                emailService.buildAnnouncementHtml(delivery.title(), delivery.message(), schoolName));
        return ExternalDeliveryResult.sent(null);
    }

    private void complete(ClaimedNotificationDelivery delivery, ExternalDeliveryResult result, LocalDateTime now) {
        boolean updated;
        switch (result.outcome()) {
            case SENT -> updated = stateService.markSent(delivery.id(), delivery.leaseOwner(),
                    result.providerMessageId(), now);
            case SKIPPED -> updated = stateService.markSkipped(delivery.id(), delivery.leaseOwner(), result.detail());
            case PERMANENT_FAILURE -> updated = stateService.markFinal(delivery.id(), delivery.leaseOwner(), result.detail());
            case RETRYABLE_FAILURE -> {
                if (retryPolicy.mayRetry(delivery.attemptCount())) {
                    updated = stateService.markRetryable(delivery.id(), delivery.leaseOwner(), result.detail(),
                            now.plus(retryPolicy.delayAfterFailure(delivery.attemptCount())));
                } else {
                    updated = stateService.markFinal(delivery.id(), delivery.leaseOwner(),
                            "Retry limit reached: " + result.detail());
                }
            }
            default -> throw new IllegalStateException("Unsupported delivery result " + result.outcome());
        }
        if (!updated) log.warn("Ignored stale notification delivery completion for row {}", delivery.id());
    }

    @Scheduled(cron = "${notification.delivery.cleanup-cron:0 20 3 * * SUN}")
    public void cleanupTerminalRows() {
        int deleted = stateService.deleteTerminalBefore(LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) log.info("Removed {} terminal notification delivery row(s) past retention", deleted);
    }

    private String safeFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
