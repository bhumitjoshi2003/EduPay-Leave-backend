package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolFeeSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SchoolFeeSettingsRepository extends JpaRepository<SchoolFeeSettings, Long> {
    Optional<SchoolFeeSettings> findBySchoolId(Long schoolId);
}

