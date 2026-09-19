package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.School;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolRepository extends JpaRepository<School, Long> {
    Optional<School> findBySlug(String slug);
    boolean existsBySlug(String slug);
    long countByActiveTrue();

    /**
     * The targeted startup-rebuild query for the dynamic teacher attendance reminder scheduler
     * (see TeacherAttendanceReminderDynamicScheduler) — deliberately narrower than findAll() so a
     * backend restart never loads every school just to find the handful with the reminder on.
     */
    List<School> findByActiveTrueAndTeacherAttendanceReminderEnabledTrue();
}
