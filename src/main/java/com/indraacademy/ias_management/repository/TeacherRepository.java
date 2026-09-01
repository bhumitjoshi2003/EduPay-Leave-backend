package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherRepository extends JpaRepository<Teacher, String> {

    List<Teacher> findBySchoolId(Long schoolId);

    List<Teacher> findByStatusAndSchoolId(TeacherStatus status, Long schoolId);

    Optional<Teacher> findByTeacherIdAndSchoolId(String teacherId, Long schoolId);

    /** A class can legitimately have more than one class-teacher once it has sections (one per
     *  section) — callers that need "the" class teacher for a specific student/section must use
     *  {@link #findByClassTeacherAndClassTeacherSectionIdAndSchoolId} or filter this list
     *  themselves; do not assume a single result. Kept for classes with no sections, where
     *  exactly one (or zero) class-teacher is still the correct expectation. */
    List<Teacher> findByClassTeacherAndSchoolId(String className, Long schoolId);

    List<Teacher> findByClassTeacherAndClassTeacherSectionIdAndSchoolId(String className, Long sectionId, Long schoolId);

    List<Teacher> findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId(String className, Long schoolId);

    long countBySchoolId(Long schoolId);

    long countBySchoolIdAndStatus(Long schoolId, TeacherStatus status);
}
