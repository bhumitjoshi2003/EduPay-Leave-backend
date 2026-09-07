package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Phase F4 wires up the first real consumer of the F2 foundation table: session-scoped CRUD
 * (ClassTeacherResponsibilityService), copy-between-sessions, and read-only activation
 * preview/apply (ClassTeacherActivationService). Every method here is tenant-scoped by
 * schoolId — never trust an id alone.
 */
public interface ClassTeacherResponsibilityRepository extends JpaRepository<ClassTeacherResponsibility, Long> {

    List<ClassTeacherResponsibility> findByAcademicSessionIdAndSchoolId(Long academicSessionId, Long schoolId);

    Optional<ClassTeacherResponsibility> findByIdAndSchoolId(Long id, Long schoolId);

    /** Phase F5B.1: backs {@code SectionService#deleteSection}'s explicit pre-check — V54's
     *  {@code fk_class_teacher_responsibility_section} (ON DELETE RESTRICT) would otherwise reject
     *  the delete with a raw, unhandled constraint violation instead of a clean application error. */
    boolean existsBySchoolIdAndSectionId(Long schoolId, Long sectionId);
}
