package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SectionDTO;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SectionService {

    @Autowired
    private SectionRepository sectionRepository;

    @Autowired
    private SecurityUtil securityUtil;

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

    public SectionDTO createSection(SectionDTO dto) {
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
        return toDTO(saved);
    }

    public SectionDTO updateSection(Long id, SectionDTO dto) {
        Long schoolId = securityUtil.getSchoolId();
        Section section = sectionRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found."));

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
        return toDTO(saved);
    }

    public void deleteSection(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        Section section = sectionRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found."));
        sectionRepository.delete(section);
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
