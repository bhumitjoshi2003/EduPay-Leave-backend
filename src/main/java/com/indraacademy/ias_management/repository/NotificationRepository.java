package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findBySchoolIdAndCreatedAtBefore(Long schoolId, LocalDateTime twoMonthsAgo);

    // Platform-wide lookup (used by cleanupOldNotifications scheduler — no schoolId filter)
    List<Notification> findByCreatedAtBefore(LocalDateTime dateTime);

    int deleteBySchoolIdAndCreatedAtBefore(Long schoolId, LocalDateTime fourDaysAgo);

    List<Notification> findBySchoolIdAndCreatedByIsNotNull(Long schoolId);

    Page<Notification> findBySchoolIdAndCreatedByIsNotNull(Long schoolId, Pageable pageable);

    java.util.Optional<Notification> findByIdAndSchoolId(Long id, Long schoolId);
}
