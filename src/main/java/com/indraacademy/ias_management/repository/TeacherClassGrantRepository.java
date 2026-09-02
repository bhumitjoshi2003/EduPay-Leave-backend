package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.TeacherClassGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeacherClassGrantRepository extends JpaRepository<TeacherClassGrant, Long> {

    List<TeacherClassGrant> findByTeacherIdAndSchoolIdOrderByCreatedAtDesc(String teacherId, Long schoolId);

    boolean existsByTeacherIdAndClassNameAndSectionIdAndSchoolId(
            String teacherId, String className, Long sectionId, Long schoolId);

    Optional<TeacherClassGrant> findByIdAndSchoolId(Long id, Long schoolId);
}
