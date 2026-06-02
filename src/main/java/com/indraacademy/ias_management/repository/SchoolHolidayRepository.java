package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolHolidayRepository extends JpaRepository<SchoolHoliday, Long> {

    List<SchoolHoliday> findBySchoolIdOrderByStartDateAsc(Long schoolId);

    List<SchoolHoliday> findBySchoolIdAndAcademicYearOrderByStartDateAsc(Long schoolId, String academicYear);

    /** Find holidays that overlap with the given date range (startDate <= rangeEnd AND endDate >= rangeStart). */
    @Query("SELECT h FROM SchoolHoliday h WHERE h.schoolId = :schoolId " +
           "AND h.startDate <= :rangeEnd AND h.endDate >= :rangeStart " +
           "ORDER BY h.startDate ASC")
    List<SchoolHoliday> findOverlapping(@Param("schoolId") Long schoolId,
                                        @Param("rangeStart") LocalDate rangeStart,
                                        @Param("rangeEnd") LocalDate rangeEnd);

    /** Check if a specific date falls within any holiday. */
    @Query("SELECT COUNT(h) > 0 FROM SchoolHoliday h WHERE h.schoolId = :schoolId " +
           "AND h.startDate <= :date AND h.endDate >= :date")
    boolean existsBySchoolIdAndDateInRange(@Param("schoolId") Long schoolId,
                                           @Param("date") LocalDate date);

    Optional<SchoolHoliday> findByIdAndSchoolId(Long id, Long schoolId);
}
