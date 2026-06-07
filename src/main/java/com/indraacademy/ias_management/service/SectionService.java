package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SectionDTO;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SectionService {

    private static final Logger log = LoggerFactory.getLogger(SectionService.class);

    @Autowired
    private SectionRepository sectionRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ObjectMapper objectMapper;

    public List<SectionDTO> getSectionsForClass(Long classId) {
        Long schoolId = securityUtil.getSchoolId();
        return sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, classId, true)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<SectionDTO> getAllSectionsForClass(Long classId) {
        Long schoolId = securityUtil.getSchoolId();
        return sectionRepository.findBySchoolIdAndClassIdOrderByDisplayOrderAsc(schoolId, classId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<SectionDTO> getAllSectionsForSchool() {
        Long schoolId = securityUtil.getSchoolId();
        return sectionRepository.findBySchoolIdOrderByClassIdAscDisplayOrderAsc(schoolId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public SectionDTO createSection(SectionDTO dto, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        if (sectionRepository.existsBySchoolIdAndClassIdAndName(schoolId, dto.getClassId(), dto.getName().trim())) {
            throw new IllegalArgumentException("Section '" + dto.getName().trim() + "' already exists for this class.");
        }
        Section section = new Section();
        section.setSchoolId(schoolId);
        section.setClassId(dto.getClassId());
        section.setName(dto.getName().trim());
        section.setDisplayOrder(dto.getDisplayOrder());
        section.setActive(true);
        Section saved = sectionRepository.save(section);

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "CREATE_SECTION", "Section", String.valueOf(saved.getId()),
                    null, objectMapper.writeValueAsString(toDTO(saved)),
                    request.getRemoteAddr());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize section for audit log", e);
        }

        return toDTO(saved);
    }

    public SectionDTO updateSection(Long id, SectionDTO dto, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        Section section = sectionRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found."));

        String oldValue;
        try {
            oldValue = objectMapper.writeValueAsString(toDTO(section));
        } catch (JsonProcessingException e) {
            oldValue = null;
        }

        if (dto.getName() != null && !dto.getName().trim().isEmpty()) {
            // Check uniqueness if name is changing
            if (!section.getName().equals(dto.getName().trim())) {
                if (sectionRepository.existsBySchoolIdAndClassIdAndName(schoolId, section.getClassId(), dto.getName().trim())) {
                    throw new IllegalArgumentException("Section '" + dto.getName().trim() + "' already exists for this class.");
                }
            }
            section.setName(dto.getName().trim());
        }
        if (dto.getDisplayOrder() != null) {
            section.setDisplayOrder(dto.getDisplayOrder());
        }
        section.setActive(dto.isActive());
        Section saved = sectionRepository.save(section);

        try {
            auditService.logUpdate(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "UPDATE_SECTION", "Section", String.valueOf(id),
                    oldValue, objectMapper.writeValueAsString(toDTO(saved)),
                    request.getRemoteAddr());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize section for audit log", e);
        }

        return toDTO(saved);
    }

    /**
     * Deletes a section. If any students are assigned to it, their section
     * assignment is automatically cleared so they are not lost.
     * Returns the count of students whose section was cleared.
     */
    @Transactional
    public long deleteSection(Long id, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        Section section = sectionRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found."));

        String oldValue;
        try {
            oldValue = objectMapper.writeValueAsString(toDTO(section));
        } catch (JsonProcessingException e) {
            oldValue = null;
        }

        long affected = studentRepository.countBySchoolIdAndSectionId(schoolId, id);
        if (affected > 0) {
            studentRepository.clearSectionBySchoolAndSectionId(schoolId, id);
        }
        sectionRepository.delete(section);

        auditService.log(
                securityUtil.getUsername(), securityUtil.getRole(),
                "DELETE_SECTION", "Section", String.valueOf(id),
                oldValue, null,
                request.getRemoteAddr());

        return affected;
    }

    private SectionDTO toDTO(Section s) {
        SectionDTO dto = new SectionDTO();
        dto.setId(s.getId());
        dto.setClassId(s.getClassId());
        dto.setName(s.getName());
        dto.setDisplayOrder(s.getDisplayOrder());
        dto.setActive(s.isActive());
        return dto;
    }
}
