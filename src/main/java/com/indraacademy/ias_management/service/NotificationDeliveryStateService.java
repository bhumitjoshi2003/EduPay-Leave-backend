package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Applies lease-owner-checked delivery transitions in short transactions. */
@Service
public class NotificationDeliveryStateService {
    private static final int MAX_ERROR_LENGTH = 2_000;
    private final NotificationDeliveryRepository repository;

    public NotificationDeliveryStateService(NotificationDeliveryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean markSent(long id, String leaseOwner, String providerMessageId, LocalDateTime now) {
        Optional<NotificationDelivery> claimed = claimed(id, leaseOwner);
        if (claimed.isEmpty()) return false;
        NotificationDelivery row = claimed.get();
        row.setStatus(NotificationDeliveryStatus.SENT);
        row.setSentAt(now);
        row.setProviderMessageId(trim(providerMessageId, 255));
        row.setLastError(null);
        row.setNextAttemptAt(null);
        clearLease(row);
        return true;
    }

    @Transactional
    public boolean markRetryable(long id, String leaseOwner, String error, LocalDateTime retryAt) {
        Optional<NotificationDelivery> claimed = claimed(id, leaseOwner);
        if (claimed.isEmpty()) return false;
        NotificationDelivery row = claimed.get();
        row.setStatus(NotificationDeliveryStatus.FAILED_RETRYABLE);
        row.setLastError(trim(error, MAX_ERROR_LENGTH));
        row.setNextAttemptAt(retryAt);
        clearLease(row);
        return true;
    }

    @Transactional
    public boolean markFinal(long id, String leaseOwner, String error) {
        Optional<NotificationDelivery> claimed = claimed(id, leaseOwner);
        if (claimed.isEmpty()) return false;
        NotificationDelivery row = claimed.get();
        row.setStatus(NotificationDeliveryStatus.FAILED_FINAL);
        row.setLastError(trim(error, MAX_ERROR_LENGTH));
        row.setNextAttemptAt(null);
        clearLease(row);
        return true;
    }

    @Transactional
    public boolean markSkipped(long id, String leaseOwner, String reason) {
        Optional<NotificationDelivery> claimed = claimed(id, leaseOwner);
        if (claimed.isEmpty()) return false;
        NotificationDelivery row = claimed.get();
        row.setStatus(NotificationDeliveryStatus.SKIPPED);
        row.setLastError(trim(reason, MAX_ERROR_LENGTH));
        row.setNextAttemptAt(null);
        clearLease(row);
        return true;
    }

    @Transactional
    public int deleteTerminalBefore(LocalDateTime cutoff) {
        return repository.deleteTerminalBefore(List.of(
                NotificationDeliveryStatus.SENT,
                NotificationDeliveryStatus.FAILED_FINAL,
                NotificationDeliveryStatus.SKIPPED), cutoff);
    }

    private Optional<NotificationDelivery> claimed(long id, String leaseOwner) {
        return repository.findById(id).filter(row -> row.getStatus() == NotificationDeliveryStatus.PROCESSING
                && leaseOwner != null && leaseOwner.equals(row.getLeaseOwner()));
    }

    private void clearLease(NotificationDelivery row) {
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
        row.setProcessingStartedAt(null);
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
