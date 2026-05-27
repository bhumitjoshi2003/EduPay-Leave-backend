package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolHolidayRepository extends JpaRepository<SchoolHoliday, Long> {

    List<SchoolHoliday> findBySchoolIdOrderByDateAsc(Long schoolId);

    List<SchoolHoliday> findBySchoolIdAndAcademicYearOrderByDateAsc(Long schoolId, String academicYear);

    List<SchoolHoliday> findBySchoolIdAndDateBetweenOrderByDateAsc(Long schoolId, LocalDate start, LocalDate end);

    Optional<SchoolHoliday> findBySchoolIdAndDate(Long schoolId, LocalDate date);

    Optional<SchoolHoliday> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsBySchoolIdAndDate(Long schoolId, LocalDate date);
}
