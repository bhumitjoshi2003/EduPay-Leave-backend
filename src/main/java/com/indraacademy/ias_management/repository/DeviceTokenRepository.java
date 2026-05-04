package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    // Platform-wide lookup (used by FcmService @Async methods — ThreadLocal not available)
    List<DeviceToken> findByUserId(String userId);

    List<DeviceToken> findByUserIdAndSchoolId(String userId, Long schoolId);

    Optional<DeviceToken> findByToken(String token);

    boolean existsByToken(String token);

    @Transactional
    void deleteByToken(String token);
}
