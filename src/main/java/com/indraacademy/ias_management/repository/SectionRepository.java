package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SectionRepository extends JpaRepository<Section, Long> {
    List<Section> findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(Long schoolId, Long classId, boolean active);
    List<Section> findBySchoolIdAndClassIdOrderByDisplayOrderAsc(Long schoolId, Long classId);
    List<Section> findBySchoolIdOrderByClassIdAscDisplayOrderAsc(Long schoolId);
    Optional<Section> findByIdAndSchoolId(Long id, Long schoolId);
    Optional<Section> findBySchoolIdAndClassIdAndName(Long schoolId, Long classId, String name);
    boolean existsBySchoolIdAndClassIdAndName(Long schoolId, Long classId, String name);
}
