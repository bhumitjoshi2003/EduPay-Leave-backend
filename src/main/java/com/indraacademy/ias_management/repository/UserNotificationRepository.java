package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;
import com.indraacademy.ias_management.notification.NotificationCategory;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    @EntityGraph(attributePaths = "notification")
    List<UserNotification> findByUserIdAndSchoolIdOrderByCreatedAtDesc(String userId, Long schoolId);

    @EntityGraph(attributePaths = "notification")
    Page<UserNotification> findByUserIdAndSchoolIdOrderByCreatedAtDesc(String userId, Long schoolId, Pageable pageable);

    @EntityGraph(attributePaths = "notification")
    @Query("select u from UserNotification u where u.userId = :userId and u.schoolId = :schoolId " +
            "and (:isRead is null or u.isRead = :isRead) " +
            "and (:category is null or u.notification.category = :category)")
    Page<UserNotification> findInbox(@Param("userId") String userId, @Param("schoolId") Long schoolId,
                                     @Param("isRead") Boolean isRead,
                                     @Param("category") NotificationCategory category,
                                     Pageable pageable);

    @EntityGraph(attributePaths = "notification")
    List<UserNotification> findByUserIdAndSchoolIdAndIsReadFalseOrderByCreatedAtDesc(String userId, Long schoolId);

    long countByUserIdAndSchoolIdAndIsReadFalse(String userId, Long schoolId);

    Optional<UserNotification> findByUserIdAndSchoolIdAndNotificationId(String userId, Long schoolId, Long notificationId);

    boolean existsByUserIdAndSchoolIdAndNotificationId(String userId, Long schoolId, Long notificationId);

    @EntityGraph(attributePaths = "notification")
    Optional<UserNotification> findByIdAndUserIdAndSchoolId(Long id, String userId, Long schoolId);

    List<UserNotification> findByNotificationIdAndSchoolId(Long notificationId, Long schoolId);

    @Modifying
    @Query("update UserNotification u set u.isRead = true, u.readAt = CURRENT_TIMESTAMP " +
            "where u.userId = :userId and u.schoolId = :schoolId and u.isRead = false")
    int markAllRead(String userId, Long schoolId);
}
