package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ClassSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClassSubjectRepository extends JpaRepository<ClassSubject, Long> {

    List<ClassSubject> findByClassNameAndSchoolId(String className, Long schoolId);

    boolean existsByClassNameAndSubjectNameAndSchoolId(String className, String subjectName, Long schoolId);

    List<ClassSubject> findBySchoolId(Long schoolId);

    boolean existsByClassNameAndSubjectNameAndOptionalTrueAndSchoolId(String className, String subjectName, Long schoolId);

    List<ClassSubject> findByClassNameAndOptionalTrueAndSchoolId(String className, Long schoolId);

    boolean existsByIdAndSchoolId(Long id, Long schoolId);
}
