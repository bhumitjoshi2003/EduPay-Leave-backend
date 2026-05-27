package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    List<NotificationChannel> findBySchoolId(Long schoolId);

    Optional<NotificationChannel> findBySchoolIdAndChannelType(Long schoolId, String channelType);

    List<NotificationChannel> findBySchoolIdAndEnabledTrue(Long schoolId);
}
