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

    List<BusFees> findByAcademicYear(String academicYear);

    BusFees findByMinDistanceLessThanEqualAndMaxDistanceGreaterThanAndAcademicYear(Double minDistance, Double maxDistance, String academicYear);

    @Query("SELECT b.fees FROM BusFees b WHERE b.academicYear = :academicYear AND b.minDistance <= :distance AND (b.maxDistance >= :distance OR b.maxDistance IS NULL)")
    BigDecimal findFeesByDistanceAndAcademicYear(@Param("distance") Double distance, @Param("academicYear") String academicYear);

    void deleteByAcademicYear(String academicYear);
}
