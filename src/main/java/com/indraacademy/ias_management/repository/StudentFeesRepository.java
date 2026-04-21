package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFees;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentFeesRepository extends JpaRepository<StudentFees, Long> {

    List<StudentFees> findByStudentIdAndYearOrderByMonthAsc(String studentId, String year);

    StudentFees findByStudentIdAndYearAndMonth(String studentId, String year, Integer month);

    @Query("SELECT DISTINCT sf.year FROM StudentFees sf WHERE sf.studentId = :studentId")
    List<String> findDistinctYearsByStudentId(String studentId);

    @Query("SELECT sf FROM StudentFees sf WHERE sf.year = :year AND sf.month = :month AND sf.paid = false")
    List<StudentFees> findAllUnpaidByYearAndMonth(String year, Integer month);

    @Query("SELECT sf FROM StudentFees sf WHERE sf.year = :session AND sf.paid = false")
    List<StudentFees> findAllUnpaidBySession(@Param("session") String session);

    @Query("SELECT sf FROM StudentFees sf WHERE sf.year = :session AND sf.paid = false AND sf.className = :className")
    List<StudentFees> findAllUnpaidBySessionAndClassName(@Param("session") String session, @Param("className") String className);

    /** Count distinct students who have at least one unpaid fee in months 1..currentAcademicMonth for the given session. */
    @Query("SELECT COUNT(DISTINCT sf.studentId) FROM StudentFees sf WHERE sf.year = :session AND sf.paid = false AND sf.month <= :currentAcademicMonth")
    long countDistinctOverdueStudents(@Param("session") String session, @Param("currentAcademicMonth") int currentAcademicMonth);
}