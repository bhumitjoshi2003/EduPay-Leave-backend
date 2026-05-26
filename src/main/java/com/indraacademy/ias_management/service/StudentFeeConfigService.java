package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.StudentFeeConfigDto;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeHeadRepository;
import com.indraacademy.ias_management.repository.StudentFeeConfigRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class StudentFeeConfigService {

    @Autowired
    private StudentFeeConfigRepository configRepository;

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
    public List<StudentFeeConfigDto> getStudentConfigs(String studentId, Long sessionId) {
        Long schoolId = securityUtil.getSchoolId();
        return configRepository.findBySchoolIdAndStudentIdAndAcademicSessionId(schoolId, studentId, sessionId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public StudentFeeConfigDto createConfig(StudentFeeConfigDto dto, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();

        FeeHead feeHead = feeHeadRepository.findByIdAndSchoolId(dto.getFeeHeadId(), schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Fee head not found."));

        AcademicSession session = sessionRepository.findById(dto.getAcademicSessionId())
                .filter(s -> s.getSchoolId().equals(schoolId))
                .orElseThrow(() -> new IllegalArgumentException("Session not found."));

        StudentFeeConfig config = new StudentFeeConfig();
        config.setSchoolId(schoolId);
        config.setStudentId(dto.getStudentId());
        config.setFeeHead(feeHead);
        config.setAcademicSession(session);
        config.setConfigType(FeeConfigType.valueOf(dto.getConfigType()));
        config.setValue(dto.getValue());
        config.setReason(dto.getReason());
        config.setApprovedBy(securityUtil.getUsername());
        config.setValidFrom(dto.getValidFrom());
        config.setValidUntil(dto.getValidUntil());

        StudentFeeConfig saved = configRepository.save(config);

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "CREATE_STUDENT_FEE_CONFIG", "StudentFeeConfig", String.valueOf(saved.getId()),
                    null, objectMapper.writeValueAsString(toDto(saved)),
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}

        return toDto(saved);
    }

    @Transactional
    public void deleteConfig(Long configId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        StudentFeeConfig config = configRepository.findById(configId)
                .filter(c -> c.getSchoolId().equals(schoolId))
                .orElseThrow(() -> new IllegalArgumentException("Config not found."));

        configRepository.delete(config);

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "DELETE_STUDENT_FEE_CONFIG", "StudentFeeConfig", String.valueOf(configId),
                    objectMapper.writeValueAsString(toDto(config)), null,
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}
    }

    private StudentFeeConfigDto toDto(StudentFeeConfig config) {
        StudentFeeConfigDto dto = new StudentFeeConfigDto();
        dto.setId(config.getId());
        dto.setStudentId(config.getStudentId());
        dto.setFeeHeadId(config.getFeeHead().getId());
        dto.setFeeHeadName(config.getFeeHead().getName());
        dto.setAcademicSessionId(config.getAcademicSession().getId());
        dto.setConfigType(config.getConfigType().name());
        dto.setValue(config.getValue());
        dto.setReason(config.getReason());
        dto.setValidFrom(config.getValidFrom());
        dto.setValidUntil(config.getValidUntil());
        return dto;
    }
}
