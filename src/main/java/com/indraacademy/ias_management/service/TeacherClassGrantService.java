package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.TeacherClassGrant;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherClassGrantRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Admin-managed grants letting a specific teacher self-serve timetable periods for a class or
 * section they don't yet have any other connection to — see
 * {@link TimetableService#authorizeTeacherWrite} for how this is consulted alongside "already
 * teaches" and "is the class-teacher of" as the third way a teacher can be authorized.
 */
@Service
public class TeacherClassGrantService {

    @Autowired private TeacherClassGrantRepository grantRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<TeacherClassGrant> getForTeacher(String teacherId) {
        return grantRepository.findByTeacherIdAndSchoolIdOrderByCreatedAtDesc(teacherId, securityUtil.getSchoolId());
    }

    public TeacherClassGrant create(String teacherId, String className, Long sectionId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();

        teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));

        SchoolClass schoolClass = schoolClassRepository.findBySchoolIdAndName(schoolId, className)
                .orElseThrow(() -> new IllegalArgumentException("Class '" + className + "' does not exist."));

        String sectionName = null;
        List<Section> activeSections = sectionRepository
                .findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, schoolClass.getId(), true);
        if (!activeSections.isEmpty()) {
            if (sectionId == null) {
                throw new IllegalArgumentException(
                        "Class '" + className + "' has sections configured — a section must be selected.");
            }
            Section section = activeSections.stream()
                    .filter(s -> s.getId().equals(sectionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The selected section does not belong to class '" + className + "' in this school, or is inactive."));
            sectionName = section.getName();
        } else if (sectionId != null) {
            throw new IllegalArgumentException(
                    "Class '" + className + "' has no configured sections — a section cannot be selected.");
        }

        if (grantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId(teacherId, className, sectionId, schoolId)) {
            throw new IllegalArgumentException("This teacher is already granted access to this class/section.");
        }

        TeacherClassGrant grant = new TeacherClassGrant();
        grant.setSchoolId(schoolId);
        grant.setTeacherId(teacherId);
        grant.setClassName(className);
        grant.setClassId(schoolClass.getId());
        grant.setSectionId(sectionId);
        grant.setSectionName(sectionName);
        grant.setGrantedBy(securityUtil.getUsername());
        grant.setCreatedAt(LocalDateTime.now());

        TeacherClassGrant saved = grantRepository.save(grant);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "GRANT_TEACHER_CLASS_ACCESS",
                "TeacherClassGrant",
                saved.getId().toString(),
                null,
                toJson(saved),
                request.getRemoteAddr()
        );

        return saved;
    }

    public void delete(Long id, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        TeacherClassGrant existing = grantRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Grant not found: " + id));

        grantRepository.deleteById(id);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "REVOKE_TEACHER_CLASS_ACCESS",
                "TeacherClassGrant",
                id.toString(),
                toJson(existing),
                null,
                request.getRemoteAddr()
        );
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
