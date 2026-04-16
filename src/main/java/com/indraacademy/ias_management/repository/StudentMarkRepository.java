package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentMarkRepository extends JpaRepository<StudentMark, Long> {
    Optional<StudentMark> findByStudentIdAndExamSubjectEntryId(String studentId, Long examSubjectEntryId);
    List<StudentMark> findByExamSubjectEntryId(Long examSubjectEntryId);
    List<StudentMark> findByExamSubjectEntryIdIn(List<Long> examSubjectEntryIds);
    List<StudentMark> findByStudentIdAndExamSubjectEntryIdIn(String studentId, List<Long> examSubjectEntryIds);
}
