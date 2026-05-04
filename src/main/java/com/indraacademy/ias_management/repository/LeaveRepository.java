package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Repository
public interface LeaveRepository extends JpaRepository<Leave, Long> {

    void deleteByStudentIdAndLeaveDateAndSchoolId(String studentId, String leaveDate, Long schoolId);

    Page<Leave> findByStudentIdContainingAndSchoolId(String studentId, Long schoolId, Pageable pageable);

    Page<Leave> findByLeaveDateAndSchoolId(String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByStudentIdContainingAndLeaveDateAndSchoolId(String studentId, String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndStudentIdContainingAndLeaveDateAndSchoolId(String className, String studentId, String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndStudentIdContainingAndSchoolId(String className, String studentId, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndLeaveDateAndSchoolId(String className, String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndSchoolId(String className, Long schoolId, Pageable pageable);

    Page<Leave> findByStudentIdAndSchoolId(String studentId, Long schoolId, Pageable pageable);

    @Query("SELECT l.studentId FROM Leave l WHERE l.leaveDate = :date AND l.className = :className AND l.schoolId = :schoolId")
    List<String> findByLeaveDateAndClassNameAndSchoolId(@Param("date") String date, @Param("className") String className, @Param("schoolId") Long schoolId);

    Leave findByStudentIdAndLeaveDateAndSchoolId(String studentId, String leaveDate, Long schoolId);

    long countBySchoolIdAndAppliedDateBetween(Long schoolId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    long countByStatusAndSchoolIdAndAppliedDateBetween(LeaveStatus status, Long schoolId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    long countByStatusAndSchoolId(LeaveStatus status, Long schoolId);
}
