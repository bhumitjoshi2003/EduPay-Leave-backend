package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Leave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Repository
public interface LeaveRepository extends JpaRepository<Leave, String> {

    void deleteByStudentIdAndLeaveDate(String studentId, String leaveDate);

    Page<Leave> findByStudentIdContaining(String studentId, Pageable pageable);

    Page<Leave> findByLeaveDate(String leaveDate, Pageable pageable);

    Page<Leave> findByStudentIdContainingAndLeaveDate(String studentId, String leaveDate, Pageable pageable);

    Page<Leave> findByClassNameAndStudentIdContainingAndLeaveDate(String className, String studentId, String leaveDate, Pageable pageable);

    Page<Leave> findByClassNameAndStudentIdContaining(String className, String studentId, Pageable pageable);

    Page<Leave> findByClassNameAndLeaveDate(String className, String leaveDate, Pageable pageable);

    Page<Leave> findByClassName(String className, Pageable pageable);

    Page<Leave> findByStudentId(String studentId, Pageable pageable);

    @Query("SELECT studentId FROM Leave WHERE leaveDate = :date AND className = :className")
    List<String> findByLeaveDateAndClassName(@Param("date") String date, @Param("className") String className);

    Leave findByStudentIdAndLeaveDate(String studentId, String leaveDate);
}