package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.BusFees;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BusFeesRepository extends JpaRepository<BusFees, Long> {

    List<BusFees> findByAcademicYear(String academicYear);

    BusFees findByMinDistanceLessThanEqualAndMaxDistanceGreaterThanAndAcademicYear(Double minDistance, Double maxDistance, String academicYear);

    void deleteByAcademicYear(String academicYear);
}
