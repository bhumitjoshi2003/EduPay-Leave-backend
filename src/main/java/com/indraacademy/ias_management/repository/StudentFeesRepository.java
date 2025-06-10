package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFees;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentFeesRepository extends JpaRepository<StudentFees, Long> {

    List<StudentFees> findByStudentIdAndYearOrderByMonthAsc(String studentId, String year);

    StudentFees findByStudentIdAndYearAndMonth(String studentId, String year, Integer month);

    @Query("SELECT DISTINCT sf.year FROM StudentFees sf WHERE sf.studentId = :studentId")
    List<String> findDistinctYearsByStudentId(String studentId);
}