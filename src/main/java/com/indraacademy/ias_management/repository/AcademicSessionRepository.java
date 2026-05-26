package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AcademicSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AcademicSessionRepository extends JpaRepository<AcademicSession, Long> {

    List<AcademicSession> findBySchoolIdOrderByStartDateDesc(Long schoolId);

    Optional<AcademicSession> findBySchoolIdAndLabel(Long schoolId, String label);

    Optional<AcademicSession> findBySchoolIdAndCurrentTrue(Long schoolId);

    boolean existsBySchoolIdAndLabel(Long schoolId, String label);
}
