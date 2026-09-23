package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findBySchoolIdAndCreatedAtBefore(Long schoolId, LocalDateTime twoMonthsAgo);

    /** Retention cleanup: ids only (never loads entities), oldest first, bounded by the Pageable. */
    @Query("select n.id from Notification n where n.createdAt < :cutoff order by n.createdAt asc, n.id asc")
    List<Long> findIdsCreatedBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** Retention cleanup only — callers must delete user_notifications children first (no DB cascade). */
    @Modifying
    @Query("delete from Notification n where n.id in :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);

    int deleteBySchoolIdAndCreatedAtBefore(Long schoolId, LocalDateTime fourDaysAgo);

    List<Notification> findBySchoolIdAndCreatedByIsNotNull(Long schoolId);

    // Admin's Posted Notices list — restricted to notices actually published through the
    // Notice Board. createdBy is set on every notification (manual and system-generated
    // alike, since it just records the acting user), so it can't distinguish a deliberately
    // posted notice from an operational one like a leave approval; eventCode can, since only
    // NotificationService.createBroadNotification() (the Notice Board publish path) produces
    // NOTICE_PUBLISHED.
    Page<Notification> findBySchoolIdAndEventCodeAndCreatedByIsNotNull(
            Long schoolId, NotificationEventCode eventCode, Pageable pageable);

    java.util.Optional<Notification> findByIdAndSchoolId(Long id, Long schoolId);
    Optional<Notification> findBySchoolIdAndIdempotencyKey(Long schoolId, String idempotencyKey);
}
