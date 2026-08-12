package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeStructureRuleDto;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.FeeStructureRule;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeHeadRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FeeRuleService {

    @Autowired
    private FeeStructureRuleRepository ruleRepository;

    @Autowired
    private FeeHeadRepository feeHeadRepository;

    @Autowired
    private AcademicSessionRepository sessionRepository;

    @Autowired
    private SchoolClassRepository schoolClassRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<FeeStructureRuleDto> getRulesBySessionAndClass(Long sessionId, String className) {
        Long schoolId = securityUtil.getSchoolId();
        return ruleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(schoolId, sessionId, className)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FeeStructureRuleDto> getRulesBySession(Long sessionId) {
        Long schoolId = securityUtil.getSchoolId();
        return ruleRepository.findBySchoolIdAndAcademicSessionId(schoolId, sessionId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Bulk save/update rules for a class in a session.
     *
     * Used to hard-delete every existing rule for the class+session before inserting the new
     * set — which meant an admin editing a price destroyed the only record of what the price
     * used to be, and made it impossible for any asOf-dated lookup (backfill, StudentFees
     * generation, admin recalculation) to ever resolve a historical date correctly, since the
     * rule that was active back then was simply gone. Now closes out (effectiveUntil) an
     * existing open-ended rule instead of deleting it when its price/dates actually change,
     * and only inserts a new row for what's genuinely new or changed — unchanged rules are
     * left as-is. A fee head present today but dropped from the incoming payload is closed
     * out too, never deleted. See migration V28 for the schema change (partial unique index
     * on open-ended rules) this depends on.
     */
    @Transactional
    public List<FeeStructureRuleDto> saveRulesForClass(
            Long sessionId, String className,
            List<FeeStructureRuleDto> ruleDtos,
            HttpServletRequest request) {

        Long schoolId = securityUtil.getSchoolId();

        AcademicSession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getSchoolId().equals(schoolId))
                .orElseThrow(() -> new IllegalArgumentException("Session not found."));

        // SchoolClass is authoritative — it must be impossible to create fee rules for a
        // class name that was never configured (or belongs to a different school).
        SchoolClass schoolClass = schoolClassRepository
                .findBySchoolIdAndName(schoolId, className)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Class '" + className + "' is not configured for this school. " +
                        "Please add it in Class Management before setting its fee structure."));
        // Canonical name from here on, not the (possibly differently-cased) caller-supplied one.
        final String resolvedClassName = schoolClass.getName();

        // Validate: no duplicate feeHeadIds in the incoming payload
        Set<Long> seenFeeHeadIds = new HashSet<>();
        for (FeeStructureRuleDto dto : ruleDtos) {
            if (dto.getFeeHeadId() == null) {
                throw new IllegalArgumentException("Each rule must have a feeHeadId.");
            }
            if (!seenFeeHeadIds.add(dto.getFeeHeadId())) {
                throw new IllegalArgumentException(
                        "Duplicate fee head ID " + dto.getFeeHeadId() + " in rules for class " + resolvedClassName +
                        ". Each fee head may only appear once per class per session.");
            }
        }

        List<FeeStructureRule> existingOpenRules = ruleRepository
                .findBySchoolIdAndAcademicSessionIdAndClassName(schoolId, sessionId, resolvedClassName)
                .stream()
                .filter(r -> r.getEffectiveUntil() == null)
                .collect(Collectors.toList());
        String oldValueJson;
        try {
            oldValueJson = objectMapper.writeValueAsString(
                    existingOpenRules.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (JsonProcessingException e) {
            oldValueJson = null;
        }
        Map<Long, FeeStructureRule> openRuleByFeeHead = existingOpenRules.stream()
                .collect(Collectors.toMap(r -> r.getFeeHead().getId(), r -> r));

        List<FeeStructureRule> saved = new ArrayList<>();
        for (FeeStructureRuleDto dto : ruleDtos) {
            FeeStructureRule existing = openRuleByFeeHead.remove(dto.getFeeHeadId());
            boolean unchanged = existing != null
                    && existing.getAmount() == dto.getAmount()
                    && Objects.equals(existing.getEffectiveUntil(), dto.getEffectiveUntil())
                    && Objects.equals(existing.getEffectiveFrom(), dto.getEffectiveFrom());
            if (unchanged) {
                saved.add(existing);
                continue;
            }

            FeeHead feeHead = feeHeadRepository.findByIdAndSchoolId(dto.getFeeHeadId(), schoolId)
                    .orElseThrow(() -> new IllegalArgumentException("Fee head not found: " + dto.getFeeHeadId()));

            if (existing != null) {
                // Close out the old rule instead of deleting it, and flush immediately so the
                // UPDATE is committed before the new row's INSERT — otherwise Hibernate's
                // default flush ordering (inserts before updates) could momentarily present
                // two open-ended rows for the same fee head to the partial unique index.
                LocalDate closeoutDate = dto.getEffectiveFrom() != null ? dto.getEffectiveFrom() : LocalDate.now();
                existing.setEffectiveUntil(closeoutDate.isAfter(existing.getEffectiveFrom())
                        ? closeoutDate.minusDays(1) : existing.getEffectiveFrom());
                ruleRepository.saveAndFlush(existing);
            }

            FeeStructureRule rule = new FeeStructureRule();
            rule.setSchoolId(schoolId);
            rule.setFeeHead(feeHead);
            rule.setAcademicSession(session);
            rule.setClassName(resolvedClassName);
            rule.setClassId(schoolClass.getId());
            rule.setAmount(dto.getAmount());
            rule.setEffectiveFrom(dto.getEffectiveFrom());
            rule.setEffectiveUntil(dto.getEffectiveUntil());

            saved.add(ruleRepository.save(rule));
        }

        // Any fee head that had an open rule but is absent from the incoming payload is being
        // removed from this class's structure — close it out too, never delete.
        for (FeeStructureRule stillOpen : openRuleByFeeHead.values()) {
            stillOpen.setEffectiveUntil(LocalDate.now());
            ruleRepository.save(stillOpen);
        }

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "SAVE_FEE_RULES", "FeeStructureRule",
                    resolvedClassName + "/" + session.getLabel(),
                    oldValueJson, objectMapper.writeValueAsString(saved.stream().map(this::toDto).collect(Collectors.toList())),
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}

        return saved.stream().map(this::toDto).collect(Collectors.toList());
    }

    private FeeStructureRuleDto toDto(FeeStructureRule entity) {
        FeeStructureRuleDto dto = new FeeStructureRuleDto();
        dto.setId(entity.getId());
        dto.setFeeHeadId(entity.getFeeHead().getId());
        dto.setFeeHeadName(entity.getFeeHead().getName());
        dto.setFeeHeadCode(entity.getFeeHead().getCode());
        dto.setAcademicSessionId(entity.getAcademicSession().getId());
        dto.setClassName(entity.getClassName());
        dto.setAmount(entity.getAmount());
        dto.setEffectiveFrom(entity.getEffectiveFrom());
        dto.setEffectiveUntil(entity.getEffectiveUntil());
        return dto;
    }
}
