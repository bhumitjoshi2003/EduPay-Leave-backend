package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentMarkRepository extends JpaRepository<StudentMark, Long> {

    Optional<StudentMark> findByStudentIdAndExamSubjectEntryIdAndSchoolId(String studentId, Long examSubjectEntryId, Long schoolId);

    List<StudentMark> findByExamSubjectEntryIdAndSchoolId(Long examSubjectEntryId, Long schoolId);

    List<StudentMark> findByExamSubjectEntryIdInAndSchoolId(List<Long> examSubjectEntryIds, Long schoolId);

    List<StudentMark> findByStudentIdAndExamSubjectEntryIdInAndSchoolId(String studentId, List<Long> examSubjectEntryIds, Long schoolId);

    List<StudentMark> findByStudentIdAndSchoolId(String studentId, Long schoolId);

    /** Marks are never deleted as a side effect of exam/subject changes (Results Phase 1). */
    long countByExamSubjectEntryIdInAndSchoolId(java.util.Collection<Long> examSubjectEntryIds, Long schoolId);

    /** Highest mark entered for a subject entry — max marks can never be lowered below it. */
    @org.springframework.data.jpa.repository.Query("SELECT MAX(m.marksObtained) FROM StudentMark m "
            + "WHERE m.examSubjectEntryId = :entryId AND m.schoolId = :schoolId")
    Double findHighestMark(@org.springframework.data.repository.query.Param("entryId") Long examSubjectEntryId,
                           @org.springframework.data.repository.query.Param("schoolId") Long schoolId);
}
