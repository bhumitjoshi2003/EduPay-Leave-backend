package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SectionDTO;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherClassGrantRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
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
    private StudentEnrollmentRepository studentEnrollmentRepository;

    @Autowired
    private TimetableRepository timetableRepository;

    @Autowired
    private ClassTeacherResponsibilityRepository classTeacherResponsibilityRepository;

    @Autowired
    private TeacherClassGrantRepository teacherClassGrantRepository;

    @Autowired
    private TeacherRepository teacherRepository;

    @Autowired
    private AttendanceRepository attendanceRepository;

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
     * Deletes a section only when nothing historically meaningful or currently live still
     * references it. Phase F5B.1: V54/V55 added real, {@code ON DELETE RESTRICT} foreign keys
     * from {@code class_teacher_responsibility} and {@code timetable_entry} to {@code section} —
     * without these explicit pre-checks, deleting a referenced section would fail with a raw,
     * unhandled DB constraint violation instead of a clean application error. The same
     * "preserve historical/live references" policy is extended here to two columns with no DB FK
     * at all ({@code teacher_class_grant.section_id}, {@code attendance.section_id}) and one live
     * authorization projection ({@code Teacher.classTeacherSectionId}), since nothing in this
     * codebase cleans those up when a section is removed and silently orphaning them would leave
     * {@code TeacherClassScopeService}/reporting code reading a dangling id. Legacy Student
     * projections with no enrollment rows retain the old clear-on-delete compatibility (a
     * pre-existing, intentional exception for enrollment-less legacy students only); enrollment-
     * backed projections and every other reference above block deletion instead of being rewritten.
     * Returns the count of legacy Student projections whose section was cleared.
     */
    @Transactional
    public long deleteSection(Long id, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        Section section = sectionRepository.findByIdAndSchoolIdForUpdate(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found."));

        if (studentEnrollmentRepository.existsBySchoolIdAndSectionId(schoolId, id)) {
            throw new IllegalStateException(
                    "Cannot delete this section because student enrollment history references it. " +
                    "Move current students with an explicit enrollment transition; historical references must be preserved.");
        }
        if (timetableRepository.existsBySchoolIdAndSectionId(schoolId, id)) {
            throw new IllegalStateException(
                    "Cannot delete this section because timetable entries reference it. " +
                    "Remove or reassign those periods first; historical timetable references must be preserved.");
        }
        if (classTeacherResponsibilityRepository.existsBySchoolIdAndSectionId(schoolId, id)) {
            throw new IllegalStateException(
                    "Cannot delete this section because a class-teacher responsibility configuration references it. " +
                    "Remove or reassign that responsibility first.");
        }
        if (teacherClassGrantRepository.existsBySchoolIdAndSectionId(schoolId, id)) {
            throw new IllegalStateException(
                    "Cannot delete this section because a teacher has an active self-service grant for it. " +
                    "Revoke that grant first.");
        }
        if (teacherRepository.existsBySchoolIdAndClassTeacherSectionId(schoolId, id)) {
            throw new IllegalStateException(
                    "Cannot delete this section because a teacher is currently assigned as its class-teacher. " +
                    "Reassign or clear that class-teacher assignment first.");
        }
        if (attendanceRepository.existsBySchoolIdAndSectionId(schoolId, id)) {
            throw new IllegalStateException(
                    "Cannot delete this section because attendance history references it. " +
                    "Historical attendance references must be preserved.");
        }

        String oldValue;
        try {
            oldValue = objectMapper.writeValueAsString(toDTO(section));
        } catch (JsonProcessingException e) {
            oldValue = null;
        }

        long affected = studentRepository.clearSectionBySchoolAndSectionId(schoolId, id);
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
