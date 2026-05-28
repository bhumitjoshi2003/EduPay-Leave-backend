package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeHeadDto;
import com.indraacademy.ias_management.entity.FeeFrequency;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.repository.FeeHeadRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FeeHeadService {

    @Autowired
    private FeeHeadRepository feeHeadRepository;

    @Autowired
    private FeeStructureRuleRepository feeStructureRuleRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<FeeHeadDto> getActiveFeeHeads() {
        Long schoolId = securityUtil.getSchoolId();
        return feeHeadRepository.findBySchoolIdAndActiveTrueOrderByDisplayOrder(schoolId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FeeHeadDto> getAllFeeHeads() {
        Long schoolId = securityUtil.getSchoolId();
        return feeHeadRepository.findBySchoolIdOrderByDisplayOrder(schoolId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public FeeHeadDto createFeeHead(FeeHeadDto dto, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();

        if (feeHeadRepository.existsBySchoolIdAndCode(schoolId, dto.getCode().toUpperCase())) {
            throw new IllegalArgumentException("Fee head with code '" + dto.getCode() + "' already exists.");
        }

        FeeHead entity = new FeeHead();
        entity.setSchoolId(schoolId);
        entity.setName(dto.getName());
        entity.setCode(dto.getCode().toUpperCase());
        entity.setFrequency(FeeFrequency.valueOf(dto.getFrequency()));
        entity.setDueMonths(dto.getDueMonths());
        entity.setOptional(dto.isOptional());
        entity.setRefundable(dto.isRefundable());
        entity.setSiblingDiscountPct(dto.getSiblingDiscountPct());
        entity.setDisplayOrder(dto.getDisplayOrder());
        entity.setActive(dto.isActive());

        FeeHead saved = feeHeadRepository.save(entity);

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "CREATE_FEE_HEAD", "FeeHead", String.valueOf(saved.getId()),
                    null, objectMapper.writeValueAsString(saved),
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}

        return toDto(saved);
    }

    @Transactional
    public FeeHeadDto updateFeeHead(Long id, FeeHeadDto dto, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        FeeHead existing = feeHeadRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Fee head not found."));

        String oldJson;
        try { oldJson = objectMapper.writeValueAsString(existing); }
        catch (JsonProcessingException e) { oldJson = null; }

        existing.setName(dto.getName());
        existing.setFrequency(FeeFrequency.valueOf(dto.getFrequency()));
        existing.setDueMonths(dto.getDueMonths());
        existing.setOptional(dto.isOptional());
        existing.setRefundable(dto.isRefundable());
        existing.setSiblingDiscountPct(dto.getSiblingDiscountPct());
        existing.setDisplayOrder(dto.getDisplayOrder());
        existing.setActive(dto.isActive());

        FeeHead saved = feeHeadRepository.save(existing);

        try {
            auditService.logUpdate(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "UPDATE_FEE_HEAD", "FeeHead", String.valueOf(saved.getId()),
                    oldJson, objectMapper.writeValueAsString(saved),
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}

        return toDto(saved);
    }

    @Transactional
    public void deleteFeeHead(Long id, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        FeeHead existing = feeHeadRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Fee head not found."));

        String oldJson;
        try { oldJson = objectMapper.writeValueAsString(existing); }
        catch (JsonProcessingException e) { oldJson = null; }

        if (feeStructureRuleRepository.existsBySchoolIdAndFeeHeadId(schoolId, id)) {
            // Soft delete — fee rules reference this fee head
            existing.setActive(false);
            feeHeadRepository.save(existing);
        } else {
            // Hard delete — no references
            feeHeadRepository.delete(existing);
        }

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "DELETE_FEE_HEAD", "FeeHead", String.valueOf(id),
                    oldJson, null,
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}
    }

    private FeeHeadDto toDto(FeeHead entity) {
        FeeHeadDto dto = new FeeHeadDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setCode(entity.getCode());
        dto.setFrequency(entity.getFrequency().name());
        dto.setDueMonths(entity.getDueMonths());
        dto.setOptional(entity.isOptional());
        dto.setRefundable(entity.isRefundable());
        dto.setSiblingDiscountPct(entity.getSiblingDiscountPct());
        dto.setDisplayOrder(entity.getDisplayOrder());
        dto.setActive(entity.isActive());
        return dto;
    }
}
