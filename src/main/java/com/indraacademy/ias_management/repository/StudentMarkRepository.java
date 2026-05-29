package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentMarkRepository extends JpaRepository<StudentMark, Long> {

    Optional<StudentMark> findByStudentIdAndExamSubjectEntryIdAndSchoolId(String studentId, Long examSubjectEntryId, Long schoolId);

    List<StudentMark> findByExamSubjectEntryIdAndSchoolId(Long examSubjectEntryId, Long schoolId);

    List<StudentMark> findByExamSubjectEntryIdInAndSchoolId(List<Long> examSubjectEntryIds, Long schoolId);

    List<StudentMark> findByStudentIdAndExamSubjectEntryIdInAndSchoolId(String studentId, List<Long> examSubjectEntryIds, Long schoolId);

    @Transactional
    void deleteByExamSubjectEntryIdAndSchoolId(Long examSubjectEntryId, Long schoolId);

    @Transactional
    void deleteByExamSubjectEntryIdInAndSchoolId(List<Long> examSubjectEntryIds, Long schoolId);
}
