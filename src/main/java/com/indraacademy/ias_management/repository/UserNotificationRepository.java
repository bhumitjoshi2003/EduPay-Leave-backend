package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    List<UserNotification> findByUserIdOrderByCreatedAtDesc(String userId);

    List<UserNotification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(String userId);

    long countByUserIdAndIsReadFalse(String userId);

    Optional<UserNotification> findByUserIdAndNotificationId(String userId, Long notificationId);

    boolean existsByUserIdAndNotificationId(String userId, Long notificationId);
}