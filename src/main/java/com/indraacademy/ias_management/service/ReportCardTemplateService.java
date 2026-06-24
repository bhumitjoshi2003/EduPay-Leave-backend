package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardSectionUpdateRequest;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateRequest;
import com.indraacademy.ias_management.entity.AssessmentGroup;
import com.indraacademy.ias_management.entity.ReportCardTemplate;
import com.indraacademy.ias_management.entity.ReportCardTemplateSection;
import com.indraacademy.ias_management.repository.AssessmentGroupRepository;
import com.indraacademy.ias_management.repository.ReportCardTemplateSectionRepository;
import com.indraacademy.ias_management.repository.ReportCardTemplateRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class ReportCardTemplateService {

    /** All section types in default display order. */
    private static final String[] DEFAULT_SECTIONS = {
            "SCHOOL_HEADER",
            "STUDENT_INFO",
            "MARKS_TABLE",
            "ASSESSMENT_SUMMARY",
            "ATTENDANCE",
            "CO_SCHOLASTIC",
            "TEACHER_REMARKS",
            "PRINCIPAL_REMARKS",
            "PROMOTION_STATUS",
            "SIGNATURES"
    };

    @Autowired private ReportCardTemplateRepository templateRepo;
    @Autowired private ReportCardTemplateSectionRepository sectionRepo;
    @Autowired private AssessmentGroupRepository groupRepo;
    @Autowired private SecurityUtil securityUtil;

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReportCardTemplateDTO> listTemplates() {
        Long schoolId = securityUtil.getSchoolId();
        List<ReportCardTemplate> templates =
                templateRepo.findBySchoolIdAndIsActiveTrueOrderByIsDefaultDescNameAsc(schoolId);
        return templates.stream().map(this::toDTO).collect(Collectors.toList());
    }

    // ── Get one ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportCardTemplateDTO getTemplate(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardTemplate t = templateRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + id));
        return toDTO(t);
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public ReportCardTemplateDTO createTemplate(ReportCardTemplateRequest req) {
        Long schoolId = securityUtil.getSchoolId();

        if (templateRepo.existsByNameAndSchoolId(req.getName(), schoolId)) {
            throw new IllegalArgumentException("A template with this name already exists.");
        }

        // Validate assessment group belongs to this school
        groupRepo.findByIdAndSchoolId(req.getAssessmentGroupId(), schoolId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Assessment group not found: " + req.getAssessmentGroupId()));

        // If setting as default, clear any existing default
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            clearDefaultFlag(schoolId);
        }

        ReportCardTemplate t = new ReportCardTemplate();
        t.setSchoolId(schoolId);
        t.setName(req.getName());
        t.setDescription(req.getDescription());
        t.setAssessmentGroupId(req.getAssessmentGroupId());
        t.setGradingOverride(req.getGradingOverride());
        t.setIsDefault(Boolean.TRUE.equals(req.getIsDefault()));
        t.setBrandingJson(req.getBrandingJson());
        t.setIsActive(true);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        t = templateRepo.save(t);

        // Auto-create all 10 default sections
        createDefaultSections(t.getId());

        return toDTO(t);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public ReportCardTemplateDTO updateTemplate(Long id, ReportCardTemplateRequest req) {
        Long schoolId = securityUtil.getSchoolId();

        ReportCardTemplate t = templateRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + id));

        // Name uniqueness check (excluding self)
        if (!t.getName().equals(req.getName())
                && templateRepo.existsByNameAndSchoolId(req.getName(), schoolId)) {
            throw new IllegalArgumentException("A template with this name already exists.");
        }

        // Validate assessment group
        groupRepo.findByIdAndSchoolId(req.getAssessmentGroupId(), schoolId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Assessment group not found: " + req.getAssessmentGroupId()));

        if (Boolean.TRUE.equals(req.getIsDefault()) && !Boolean.TRUE.equals(t.getIsDefault())) {
            clearDefaultFlag(schoolId);
        }

        t.setName(req.getName());
        t.setDescription(req.getDescription());
        t.setAssessmentGroupId(req.getAssessmentGroupId());
        t.setGradingOverride(req.getGradingOverride());
        t.setIsDefault(Boolean.TRUE.equals(req.getIsDefault()));
        if (req.getBrandingJson() != null) {
            t.setBrandingJson(req.getBrandingJson());
        }
        t.setUpdatedAt(LocalDateTime.now());
        templateRepo.save(t);

        return toDTO(t);
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────

    @Transactional
    public void deleteTemplate(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardTemplate t = templateRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + id));
        if (Boolean.TRUE.equals(t.getIsDefault())) {
            throw new IllegalStateException("Cannot delete the default template. Set another template as default first.");
        }
        t.setIsActive(false);
        t.setUpdatedAt(LocalDateTime.now());
        templateRepo.save(t);
    }

    // ── Update sections ───────────────────────────────────────────────────

    @Transactional
    public ReportCardTemplateDTO updateSections(Long templateId, ReportCardSectionUpdateRequest req) {
        Long schoolId = securityUtil.getSchoolId();

        // Ensure template belongs to this school
        ReportCardTemplate t = templateRepo.findByIdAndSchoolId(templateId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));

        // Delete existing sections and replace
        sectionRepo.deleteByTemplateId(templateId);

        int order = 0;
        for (ReportCardSectionUpdateRequest.SectionItem item : req.getSections()) {
            ReportCardTemplateSection s = new ReportCardTemplateSection();
            s.setTemplateId(templateId);
            s.setSectionType(item.getSectionType());
            s.setEnabled(Boolean.TRUE.equals(item.getEnabled()));
            s.setDisplayOrder(item.getDisplayOrder() != null ? item.getDisplayOrder() : order);
            s.setConfigJson(item.getConfigJson());
            sectionRepo.save(s);
            order++;
        }

        t.setUpdatedAt(LocalDateTime.now());
        templateRepo.save(t);

        return toDTO(t);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void clearDefaultFlag(Long schoolId) {
        templateRepo.findBySchoolIdAndIsDefaultTrue(schoolId).ifPresent(existing -> {
            existing.setIsDefault(false);
            templateRepo.save(existing);
        });
    }

    private void createDefaultSections(Long templateId) {
        for (int i = 0; i < DEFAULT_SECTIONS.length; i++) {
            ReportCardTemplateSection s = new ReportCardTemplateSection();
            s.setTemplateId(templateId);
            s.setSectionType(DEFAULT_SECTIONS[i]);
            s.setEnabled(true);
            s.setDisplayOrder(i);
            sectionRepo.save(s);
        }
    }

    private ReportCardTemplateDTO toDTO(ReportCardTemplate t) {
        List<ReportCardTemplateSection> sections =
                sectionRepo.findByTemplateIdOrderByDisplayOrderAsc(t.getId());

        String groupName = groupRepo.findById(t.getAssessmentGroupId())
                .map(AssessmentGroup::getName).orElse("");

        List<ReportCardTemplateDTO.SectionDTO> sectionDTOs = sections.stream()
                .map(s -> new ReportCardTemplateDTO.SectionDTO(
                        s.getId(), s.getSectionType(), s.getEnabled(),
                        s.getDisplayOrder(), s.getConfigJson()))
                .collect(Collectors.toList());

        return new ReportCardTemplateDTO(
                t.getId(), t.getSchoolId(), t.getName(), t.getDescription(),
                t.getAssessmentGroupId(), groupName,
                t.getGradingOverride(), t.getIsDefault(), t.getIsActive(),
                t.getCreatedAt(), t.getUpdatedAt(), sectionDTOs, t.getBrandingJson());
    }
}
