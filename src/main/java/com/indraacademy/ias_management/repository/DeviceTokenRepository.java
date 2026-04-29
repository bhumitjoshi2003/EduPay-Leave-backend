package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    List<DeviceToken> findByUserId(String userId);

    Optional<DeviceToken> findByToken(String token);

    boolean existsByToken(String token);

    void deleteByToken(String token);
}
