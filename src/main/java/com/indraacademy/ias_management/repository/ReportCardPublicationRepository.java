package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface ReportCardPublicationRepository extends JpaRepository<ReportCardPublication, Long> {

    Optional<ReportCardPublication> findBySchoolIdAndTemplateIdAndSessionAndClassName(
            Long schoolId, Long templateId, String session, String className);

    boolean existsBySchoolIdAndTemplateIdAndSessionAndClassName(
            Long schoolId, Long templateId, String session, String className);

    @Transactional
    void deleteBySchoolIdAndTemplateIdAndSessionAndClassName(
            Long schoolId, Long templateId, String session, String className);
}
