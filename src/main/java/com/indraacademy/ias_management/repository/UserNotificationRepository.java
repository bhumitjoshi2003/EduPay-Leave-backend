package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    @EntityGraph(attributePaths = "notification")
    List<UserNotification> findByUserIdOrderByCreatedAtDesc(String userId);

    @EntityGraph(attributePaths = "notification")
    Page<UserNotification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    @EntityGraph(attributePaths = "notification")
    List<UserNotification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(String userId);

    long countByUserIdAndIsReadFalse(String userId);

    Optional<UserNotification> findByUserIdAndNotificationId(String userId, Long notificationId);

    boolean existsByUserIdAndNotificationId(String userId, Long notificationId);
}