package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeStructureRuleDto;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.FeeStructureRule;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeHeadRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
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
     * Replaces all existing rules for that class+session.
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

        // Delete existing rules for this class+session
        ruleRepository.deleteBySchoolIdAndAcademicSessionIdAndClassName(schoolId, sessionId, className);

        List<FeeStructureRule> saved = new ArrayList<>();
        for (FeeStructureRuleDto dto : ruleDtos) {
            FeeHead feeHead = feeHeadRepository.findByIdAndSchoolId(dto.getFeeHeadId(), schoolId)
                    .orElseThrow(() -> new IllegalArgumentException("Fee head not found: " + dto.getFeeHeadId()));

            FeeStructureRule rule = new FeeStructureRule();
            rule.setSchoolId(schoolId);
            rule.setFeeHead(feeHead);
            rule.setAcademicSession(session);
            rule.setClassName(className);
            rule.setAmount(dto.getAmount());
            rule.setEffectiveFrom(dto.getEffectiveFrom());
            rule.setEffectiveUntil(dto.getEffectiveUntil());

            saved.add(ruleRepository.save(rule));
        }

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "SAVE_FEE_RULES", "FeeStructureRule",
                    className + "/" + session.getLabel(),
                    null, objectMapper.writeValueAsString(saved.stream().map(this::toDto).collect(Collectors.toList())),
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
