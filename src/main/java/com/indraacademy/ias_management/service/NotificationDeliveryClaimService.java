package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.notification.ClaimedNotificationDelivery;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationDeliveryClaimService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryClaimService.class);
    private final NotificationDeliveryRepository repository;

    public NotificationDeliveryClaimService(NotificationDeliveryRepository repository) {
        this.repository = repository;
    }

    /** Claims work in one short transaction. No provider/network call belongs in this method. */
    @Transactional
    public List<ClaimedNotificationDelivery> claim(LocalDateTime now, int batchSize,
                                                   Duration leaseDuration, String leaseOwner) {
        if (batchSize < 1 || leaseDuration == null || leaseDuration.isNegative()
                || leaseDuration.isZero() || leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("A positive batch/lease and lease owner are required.");
        }
        List<Long> ids = repository.lockEligibleIds(now, batchSize);
        if (ids.isEmpty()) return List.of();

        List<NotificationDelivery> rows = repository.findAllById(ids);
        List<ClaimedNotificationDelivery> result = new ArrayList<>(rows.size());
        int staleRecovered = 0;
        for (NotificationDelivery row : rows) {
            if (row.getStatus() == NotificationDeliveryStatus.PROCESSING) staleRecovered++;
            row.setStatus(NotificationDeliveryStatus.PROCESSING);
            row.setAttemptCount(row.getAttemptCount() + 1);
            row.setProcessingStartedAt(now);
            row.setLeaseUntil(now.plus(leaseDuration));
            row.setLeaseOwner(leaseOwner);
            row.setLastError(null);
            result.add(new ClaimedNotificationDelivery(
                    row.getId(), row.getSchoolId(), row.getNotification().getId(),
                    row.getRecipientUserId(), row.getChannel(), row.getDestination(),
                    row.getNotification().getTitle(), row.getNotification().getMessage(),
                    row.getUserNotification().getId(),
                    row.getNotification().getEventCode() == null ? null : row.getNotification().getEventCode().name(),
                    row.getNotification().getActionRoute(), row.getNotification().getSourceEntityType(),
                    row.getNotification().getSourceEntityId(), row.getNotification().getActionMetadata(),
                    row.getAttemptCount(), leaseOwner));
        }
        repository.saveAll(rows);
        if (staleRecovered > 0) {
            log.warn("Recovered {} notification delivery lease(s) after worker interruption", staleRecovered);
        }
        return List.copyOf(result);
    }
}
