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
    List<UserNotification> findByUserIdAndSchoolIdOrderByCreatedAtDesc(String userId, Long schoolId);

    @EntityGraph(attributePaths = "notification")
    Page<UserNotification> findByUserIdAndSchoolIdOrderByCreatedAtDesc(String userId, Long schoolId, Pageable pageable);

    @EntityGraph(attributePaths = "notification")
    List<UserNotification> findByUserIdAndSchoolIdAndIsReadFalseOrderByCreatedAtDesc(String userId, Long schoolId);

    long countByUserIdAndSchoolIdAndIsReadFalse(String userId, Long schoolId);

    Optional<UserNotification> findByUserIdAndSchoolIdAndNotificationId(String userId, Long schoolId, Long notificationId);

    boolean existsByUserIdAndSchoolIdAndNotificationId(String userId, Long schoolId, Long notificationId);
}