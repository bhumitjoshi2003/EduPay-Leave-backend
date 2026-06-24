package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AssessmentGroupDTO;
import com.indraacademy.ias_management.dto.AssessmentGroupDTO.*;
import com.indraacademy.ias_management.dto.AssessmentGroupRequest;
import com.indraacademy.ias_management.dto.AssessmentGroupRequest.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AssessmentGroupService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentGroupService.class);

    @Autowired private AssessmentGroupRepository groupRepo;
    @Autowired private AssessmentGroupExamMappingRepository mappingRepo;
    @Autowired private AssessmentGroupCompositionRepository compositionRepo;
    @Autowired private AssessmentGroupResultRepository resultRepo;
    @Autowired private ExamConfigRepository examConfigRepo;
    @Autowired private SecurityUtil securityUtil;

    // ── Read ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "assessment-groups", key = "@securityUtil.getSchoolId() + ':' + #session + '-' + #className")
    public List<AssessmentGroupDTO> getGroups(String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        log.info("GET assessment groups session={} class={} school={}", session, className, schoolId);

        List<AssessmentGroup> groups = groupRepo
                .findBySessionAndClassNameAndSchoolIdOrderByDisplayOrderAsc(session, className, schoolId);

        return groups.stream()
                .map(g -> toDTO(g, schoolId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AssessmentGroupDTO getGroup(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + id));
        return toDTO(group, schoolId);
    }

    // ── Write ──────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = "assessment-groups", allEntries = true)
    public AssessmentGroupDTO createGroup(AssessmentGroupRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        log.info("CREATE assessment group '{}' session={} class={} school={}",
                req.getName(), req.getSession(), req.getClassName(), schoolId);

        if (groupRepo.existsBySessionAndClassNameAndNameAndSchoolId(
                req.getSession(), req.getClassName(), req.getName(), schoolId)) {
            throw new IllegalArgumentException(
                    "An assessment group named '" + req.getName() +
                    "' already exists for " + req.getClassName() + " / " + req.getSession());
        }

        AssessmentGroup group = new AssessmentGroup();
        group.setSchoolId(schoolId);
        group.setSession(req.getSession());
        group.setClassName(req.getClassName());
        group.setName(req.getName());
        group.setDisplayName(req.getDisplayName());
        group.setGroupType(req.getGroupType());
        group.setDisplayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0);
        group.setCreatedAt(LocalDateTime.now());
        group = groupRepo.save(group);

        saveMappingsOrCompositions(group, req, schoolId);
        return toDTO(group, schoolId);
    }

    @Transactional
    @CacheEvict(value = "assessment-groups", allEntries = true)
    public AssessmentGroupDTO updateGroup(Long id, AssessmentGroupRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + id));
        log.info("UPDATE assessment group id={} school={}", id, schoolId);

        // Check name uniqueness only if name changed
        if (!group.getName().equals(req.getName()) &&
                groupRepo.existsBySessionAndClassNameAndNameAndSchoolId(
                        req.getSession(), req.getClassName(), req.getName(), schoolId)) {
            throw new IllegalArgumentException(
                    "An assessment group named '" + req.getName() + "' already exists.");
        }

        group.setName(req.getName());
        group.setDisplayName(req.getDisplayName());
        group.setDisplayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : group.getDisplayOrder());
        // groupType and session/className are immutable after creation for safety
        groupRepo.save(group);

        // Replace mappings / compositions
        if ("EXAM_BASED".equals(group.getGroupType())) {
            mappingRepo.deleteByAssessmentGroupIdAndSchoolId(id, schoolId);
        } else {
            compositionRepo.deleteByParentGroupIdAndSchoolId(id, schoolId);
        }
        saveMappingsOrCompositions(group, req, schoolId);

        // Invalidate cached results for this group
        resultRepo.deleteByAssessmentGroupIdAndSession(id, group.getSession());

        return toDTO(group, schoolId);
    }

    @Transactional
    @CacheEvict(value = "assessment-groups", allEntries = true)
    public void deleteGroup(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + id));
        log.info("DELETE assessment group id={} '{}' school={}", id, group.getName(), schoolId);

        // Child records cascade-delete via FK ON DELETE CASCADE
        groupRepo.delete(group);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void saveMappingsOrCompositions(AssessmentGroup group, AssessmentGroupRequest req, Long schoolId) {
        if ("EXAM_BASED".equals(group.getGroupType()) && req.getExamMappings() != null) {
            validateWeightageSum(req.getExamMappings().stream()
                    .map(ExamMappingItem::getWeightage).collect(Collectors.toList()));

            for (ExamMappingItem item : req.getExamMappings()) {
                AssessmentGroupExamMapping mapping = new AssessmentGroupExamMapping();
                mapping.setSchoolId(schoolId);
                mapping.setAssessmentGroupId(group.getId());
                mapping.setExamConfigId(item.getExamConfigId());
                mapping.setWeightage(item.getWeightage());
                mapping.setDisplayOrder(item.getDisplayOrder() != null ? item.getDisplayOrder() : 0);
                mappingRepo.save(mapping);
            }
        } else if ("GROUP_BASED".equals(group.getGroupType()) && req.getCompositions() != null) {
            validateWeightageSum(req.getCompositions().stream()
                    .map(CompositionItem::getWeightage).collect(Collectors.toList()));

            for (CompositionItem item : req.getCompositions()) {
                AssessmentGroupComposition comp = new AssessmentGroupComposition();
                comp.setSchoolId(schoolId);
                comp.setParentGroupId(group.getId());
                comp.setChildGroupId(item.getChildGroupId());
                comp.setWeightage(item.getWeightage());
                comp.setDisplayOrder(item.getDisplayOrder() != null ? item.getDisplayOrder() : 0);
                compositionRepo.save(comp);
            }
        }
    }

    private void validateWeightageSum(List<BigDecimal> weightages) {
        if (weightages == null || weightages.isEmpty()) return;
        BigDecimal sum = weightages.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // Allow ±0.005 rounding tolerance
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.005")) > 0) {
            throw new IllegalArgumentException(
                    "Weightages must sum to 1.0 (100%). Got: " + sum.toPlainString());
        }
    }

    private AssessmentGroupDTO toDTO(AssessmentGroup group, Long schoolId) {
        List<ExamMappingDTO> examMappings = null;
        List<CompositionDTO> compositions = null;

        if ("EXAM_BASED".equals(group.getGroupType())) {
            List<AssessmentGroupExamMapping> mappings =
                    mappingRepo.findByAssessmentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId);

            // Batch load exam names
            List<Long> examIds = mappings.stream()
                    .map(AssessmentGroupExamMapping::getExamConfigId)
                    .collect(Collectors.toList());
            Map<Long, String> examNames = examConfigRepo.findAllById(examIds).stream()
                    .collect(Collectors.toMap(ExamConfig::getId, ExamConfig::getExamName));

            examMappings = mappings.stream()
                    .map(m -> new ExamMappingDTO(m.getId(), m.getExamConfigId(),
                            examNames.getOrDefault(m.getExamConfigId(), "Unknown"),
                            m.getWeightage(), m.getDisplayOrder()))
                    .collect(Collectors.toList());

        } else {
            List<AssessmentGroupComposition> comps =
                    compositionRepo.findByParentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId);

            List<Long> childIds = comps.stream()
                    .map(AssessmentGroupComposition::getChildGroupId)
                    .collect(Collectors.toList());
            Map<Long, String> childNames = groupRepo.findAllById(childIds).stream()
                    .collect(Collectors.toMap(AssessmentGroup::getId, AssessmentGroup::getName));

            compositions = comps.stream()
                    .map(c -> new CompositionDTO(c.getId(), c.getChildGroupId(),
                            childNames.getOrDefault(c.getChildGroupId(), "Unknown"),
                            c.getWeightage(), c.getDisplayOrder()))
                    .collect(Collectors.toList());
        }

        return new AssessmentGroupDTO(
                group.getId(), group.getSession(), group.getClassName(),
                group.getName(), group.getDisplayName(), group.getGroupType(),
                group.getDisplayOrder(), examMappings, compositions);
    }
}
