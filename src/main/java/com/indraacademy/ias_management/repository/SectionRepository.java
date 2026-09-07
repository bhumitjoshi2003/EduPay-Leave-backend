package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface SectionRepository extends JpaRepository<Section, Long> {
    List<Section> findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(Long schoolId, Long classId, boolean active);
    List<Section> findBySchoolIdAndClassIdOrderByDisplayOrderAsc(Long schoolId, Long classId);
    List<Section> findBySchoolIdOrderByClassIdAscDisplayOrderAsc(Long schoolId);
    List<Section> findBySchoolIdAndActiveOrderByDisplayOrderAsc(Long schoolId, boolean active);
    Optional<Section> findByIdAndSchoolId(Long id, Long schoolId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Section s WHERE s.id = :id AND s.schoolId = :schoolId")
    Optional<Section> findByIdAndSchoolIdForUpdate(@Param("id") Long id, @Param("schoolId") Long schoolId);
    Optional<Section> findBySchoolIdAndClassIdAndName(Long schoolId, Long classId, String name);
    boolean existsBySchoolIdAndClassIdAndName(Long schoolId, Long classId, String name);
}
