package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Leave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@Repository
public interface LeaveRepository extends JpaRepository<Leave, Long> {
    @Query("SELECT studentId FROM Leave WHERE leaveDate = :date AND className = :className")
    List<String> findByLeaveDateAndClassName(@Param("date") String date, @Param("className") String className);

    void deleteByStudentIdAndLeaveDate(String studentId, String leaveDate);

    List<Leave> findByStudentId(String studentId);

    List<Leave> findByClassName(String className);
}
