package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Teacher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherRepository extends JpaRepository<Teacher, String> {

    List<Teacher> findBySchoolId(Long schoolId);

    Optional<Teacher> findByTeacherIdAndSchoolId(String teacherId, Long schoolId);

    // Platform-wide lookups (used by scheduler/async email — no schoolId filter)
    Optional<Teacher> findByClassTeacher(String className);

    Optional<Teacher> findByClassTeacherAndSchoolId(String className, Long schoolId);

    long countBySchoolId(Long schoolId);
}
