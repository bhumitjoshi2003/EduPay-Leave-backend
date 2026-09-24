package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.HomeworkClasswork;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HomeworkClassworkRepository extends JpaRepository<HomeworkClasswork, Long> {

    Optional<HomeworkClasswork> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsBySchoolIdAndTimetableEntryIdAndWorkDate(Long schoolId, Long timetableEntryId, LocalDate workDate);

    List<HomeworkClasswork> findBySchoolIdAndTeacherIdAndWorkDateOrderByCreatedAtAsc(
            Long schoolId, String teacherId, LocalDate workDate);

    List<HomeworkClasswork> findBySchoolIdAndTeacherIdOrderByWorkDateDescIdDesc(
            Long schoolId, String teacherId, Pageable pageable);

    /** A student's class: whole-class posts (section_id NULL) plus their own section's posts. */
    String CLASS_SCOPE = "w.schoolId = :schoolId AND w.academicSessionId = :sessionId AND w.classId = :classId "
            + "AND (w.sectionId IS NULL OR w.sectionId = :sectionId) ";

    @Query("SELECT w FROM HomeworkClasswork w WHERE " + CLASS_SCOPE
            + "AND w.workDate = :date ORDER BY w.createdAt ASC")
    List<HomeworkClasswork> findForClassOnDate(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                               @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                               @Param("date") LocalDate date);

    @Query("SELECT w FROM HomeworkClasswork w WHERE " + CLASS_SCOPE
            + "AND w.homework IS NOT NULL AND w.dueDate IS NOT NULL AND w.dueDate >= :from "
            + "ORDER BY w.dueDate ASC, w.id ASC")
    List<HomeworkClasswork> findHomeworkDueFrom(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                                @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                                @Param("from") LocalDate from, Pageable pageable);

    @Query("SELECT w FROM HomeworkClasswork w WHERE " + CLASS_SCOPE
            + "AND w.workDate < :before ORDER BY w.workDate DESC, w.id DESC")
    List<HomeworkClasswork> findForClassBefore(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                               @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                               @Param("before") LocalDate before, Pageable pageable);
}
