package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentElectiveEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentElectiveEnrollmentRepository extends JpaRepository<StudentElectiveEnrollment, Long> {

    List<StudentElectiveEnrollment> findByClassNameAndSchoolId(String className, Long schoolId);

    List<StudentElectiveEnrollment> findByStudentIdAndSchoolId(String studentId, Long schoolId);

    List<StudentElectiveEnrollment> findByClassNameAndSubjectNameAndSchoolId(String className, String subjectName, Long schoolId);

    Optional<StudentElectiveEnrollment> findByStudentIdAndClassNameAndOptionalGroupAndSchoolId(
            String studentId, String className, String optionalGroup, Long schoolId);

    void deleteByStudentIdAndClassNameAndOptionalGroupAndSchoolId(
            String studentId, String className, String optionalGroup, Long schoolId);
}
