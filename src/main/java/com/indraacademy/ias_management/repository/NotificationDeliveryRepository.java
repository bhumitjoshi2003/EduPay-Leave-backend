package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    List<NotificationDelivery> findByNotificationIdAndSchoolId(Long notificationId, Long schoolId);
    List<NotificationDelivery> findByStatusAndNextAttemptAtLessThanEqual(
            NotificationDeliveryStatus status, LocalDateTime nextAttemptAt);
    boolean existsBySchoolIdAndUserNotificationIdAndChannel(
            Long schoolId, Long userNotificationId, ExternalDeliveryChannel channel);
    List<NotificationDelivery> findByNotificationIdAndSchoolIdAndChannel(
            Long notificationId, Long schoolId, ExternalDeliveryChannel channel);

    @Query(value = """
            SELECT id
            FROM notification_deliveries
            WHERE (
                status IN ('PENDING', 'FAILED_RETRYABLE')
                AND COALESCE(next_attempt_at, created_at) <= :now
            ) OR (
                status = 'PROCESSING'
                AND (lease_until IS NULL OR lease_until <= :now)
            )
            ORDER BY COALESCE(next_attempt_at, created_at), id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> lockEligibleIds(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    @Modifying
    @Query("""
            DELETE FROM NotificationDelivery d
            WHERE d.status IN :terminalStatuses
              AND COALESCE(d.sentAt, d.createdAt) < :cutoff
            """)
    int deleteTerminalBefore(@Param("terminalStatuses") List<NotificationDeliveryStatus> terminalStatuses,
                             @Param("cutoff") LocalDateTime cutoff);
}
