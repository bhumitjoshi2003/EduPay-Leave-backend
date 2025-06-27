package com.indraacademy.ias_management.repository;


import com.indraacademy.ias_management.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByCreatedAtBefore(LocalDateTime twoMonthsAgo);

    int deleteByCreatedAtBefore(LocalDateTime fourDaysAgo);

    List<Notification> findByCreatedByIsNotNull();
}