package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.BusFees;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface BusFeesRepository extends JpaRepository<BusFees, Long> {

    List<BusFees> findBySchoolId(Long schoolId);

    List<BusFees> findByAcademicYearAndSchoolId(String academicYear, Long schoolId);

    BusFees findByMinDistanceLessThanEqualAndMaxDistanceGreaterThanAndAcademicYearAndSchoolId(
            Double minDistance, Double maxDistance, String academicYear, Long schoolId);

    @Query("SELECT b.fees FROM BusFees b WHERE b.schoolId = :schoolId AND b.academicYear = :academicYear AND b.minDistance <= :distance AND (b.maxDistance >= :distance OR b.maxDistance IS NULL)")
    BigDecimal findFeesByDistanceAndAcademicYearAndSchoolId(
            @Param("distance") Double distance,
            @Param("academicYear") String academicYear,
            @Param("schoolId") Long schoolId);

    void deleteByAcademicYearAndSchoolId(String academicYear, Long schoolId);
}
