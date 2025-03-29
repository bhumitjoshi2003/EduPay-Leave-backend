package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    @Transactional
    void deleteByAbsentDateAndClassName(LocalDate absentDate, String className);

    List<Attendance> findByAbsentDateAndClassName(LocalDate absentDate, String className);
}