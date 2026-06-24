package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardTemplateSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportCardTemplateSectionRepository extends JpaRepository<ReportCardTemplateSection, Long> {

    List<ReportCardTemplateSection> findByTemplateIdOrderByDisplayOrderAsc(Long templateId);

    Optional<ReportCardTemplateSection> findByTemplateIdAndSectionType(Long templateId, String sectionType);

    @Transactional
    void deleteByTemplateId(Long templateId);
}
