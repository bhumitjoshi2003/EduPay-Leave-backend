package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ClassTeacherActivation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClassTeacherActivationRepository extends JpaRepository<ClassTeacherActivation, Long> {

    Optional<ClassTeacherActivation> findBySchoolIdAndAcademicSessionId(Long schoolId, Long academicSessionId);
}
