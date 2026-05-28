package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class TimetableService {

    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);

    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<TimetableEntry> getByClass(String className, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        if (sectionId != null) {
            return timetableRepository.findByClassNameAndSectionIdAndSchoolIdOrderByDayAscPeriodNumberAsc(className, sectionId, schoolId);
        }
        // No section filter → return all entries for the class (all sections)
        return timetableRepository.findByClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(className, schoolId);
    }

    @Transactional(readOnly = true)
    public List<TimetableEntry> getByTeacher(String teacherId) {
        return timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc(teacherId, securityUtil.getSchoolId());
    }

    public TimetableEntry create(TimetableEntry entry, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        if (slotExists(schoolId, entry.getClassName(), entry.getSectionId(), entry.getDay(), entry.getPeriodNumber())) {
            throw new DataIntegrityViolationException(
                    "Period " + entry.getPeriodNumber() + " on " + entry.getDay()
                            + " is already assigned for class " + entry.getClassName()
                            + (entry.getSectionId() != null ? " (section)" : ""));
        }

        entry.setSchoolId(schoolId);
        resolveTeacherName(entry);
        resolveSectionName(entry);

        TimetableEntry saved = timetableRepository.save(entry);
        log.info("Timetable entry created: id={}, class={}, day={}, period={}",
                saved.getId(), saved.getClassName(), saved.getDay(), saved.getPeriodNumber());

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "CREATE_TIMETABLE_ENTRY",
                "TimetableEntry",
                saved.getId().toString(),
                null,
                toJson(saved),
                request.getRemoteAddr()
        );

        return saved;
    }

    public TimetableEntry update(Long id, TimetableEntry incoming, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        TimetableEntry existing = timetableRepository.findById(id)
                .filter(e -> schoolId.equals(e.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Timetable entry not found: " + id));

        String oldValue = toJson(existing);

        // Check unique constraint only if slot (class/section/day/period) changed
        boolean slotChanged = !existing.getClassName().equals(incoming.getClassName())
                || !java.util.Objects.equals(existing.getSectionId(), incoming.getSectionId())
                || !existing.getDay().equals(incoming.getDay())
                || !existing.getPeriodNumber().equals(incoming.getPeriodNumber());

        if (slotChanged && slotExistsExcluding(schoolId, incoming.getClassName(), incoming.getSectionId(),
                incoming.getDay(), incoming.getPeriodNumber(), id)) {
            throw new DataIntegrityViolationException(
                    "Period " + incoming.getPeriodNumber() + " on " + incoming.getDay()
                            + " is already assigned for class " + incoming.getClassName()
                            + (incoming.getSectionId() != null ? " (section)" : ""));
        }

        existing.setClassName(incoming.getClassName());
        existing.setSectionId(incoming.getSectionId());
        existing.setDay(incoming.getDay());
        existing.setPeriodNumber(incoming.getPeriodNumber());
        existing.setStartTime(incoming.getStartTime());
        existing.setEndTime(incoming.getEndTime());
        existing.setSubjectName(incoming.getSubjectName());
        existing.setTeacherId(incoming.getTeacherId());

        // Re-fetch teacher and section names
        resolveTeacherName(existing);
        resolveSectionName(existing);

        TimetableEntry saved = timetableRepository.save(existing);
        log.info("Timetable entry updated: id={}", saved.getId());

        auditService.logUpdate(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "UPDATE_TIMETABLE_ENTRY",
                "TimetableEntry",
                saved.getId().toString(),
                oldValue,
                toJson(saved),
                request.getRemoteAddr()
        );

        return saved;
    }

    public void delete(Long id, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        TimetableEntry existing = timetableRepository.findById(id)
                .filter(e -> schoolId.equals(e.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Timetable entry not found: " + id));

        String oldValue = toJson(existing);
        timetableRepository.deleteById(id);
        log.info("Timetable entry deleted: id={}", id);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "DELETE_TIMETABLE_ENTRY",
                "TimetableEntry",
                id.toString(),
                oldValue,
                null,
                request.getRemoteAddr()
        );
    }

    private boolean slotExists(Long schoolId, String className, Long sectionId, com.indraacademy.ias_management.entity.Day day, Integer periodNumber) {
        if (sectionId != null) {
            return timetableRepository.existsByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolId(className, sectionId, day, periodNumber, schoolId);
        }
        return timetableRepository.existsByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(className, day, periodNumber, schoolId);
    }

    private boolean slotExistsExcluding(Long schoolId, String className, Long sectionId, com.indraacademy.ias_management.entity.Day day, Integer periodNumber, Long excludeId) {
        if (sectionId != null) {
            return timetableRepository.existsByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolIdAndIdNot(className, sectionId, day, periodNumber, schoolId, excludeId);
        }
        return timetableRepository.existsByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolIdAndIdNot(className, day, periodNumber, schoolId, excludeId);
    }

    private void resolveTeacherName(TimetableEntry entry) {
        if (entry.getTeacherId() != null && !entry.getTeacherId().isBlank()) {
            String name = teacherRepository.findByTeacherIdAndSchoolId(entry.getTeacherId(), securityUtil.getSchoolId())
                    .map(t -> t.getName())
                    .orElse(null);
            entry.setTeacherName(name);
        } else {
            entry.setTeacherName(null);
        }
    }

    private void resolveSectionName(TimetableEntry entry) {
        if (entry.getSectionId() != null) {
            String name = sectionRepository.findByIdAndSchoolId(entry.getSectionId(), securityUtil.getSchoolId())
                    .map(s -> s.getName())
                    .orElse(null);
            entry.setSectionName(name);
        } else {
            entry.setSectionName(null);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
