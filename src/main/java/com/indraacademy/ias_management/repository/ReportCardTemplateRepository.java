package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportCardTemplateRepository extends JpaRepository<ReportCardTemplate, Long> {

    List<ReportCardTemplate> findBySchoolIdAndIsActiveTrueOrderByIsDefaultDescNameAsc(Long schoolId);

    Optional<ReportCardTemplate> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsByNameAndSchoolId(String name, Long schoolId);

    Optional<ReportCardTemplate> findBySchoolIdAndIsDefaultTrue(Long schoolId);

    @Transactional
    void deleteByIdAndSchoolId(Long id, Long schoolId);
}
