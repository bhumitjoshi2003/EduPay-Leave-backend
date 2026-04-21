package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class TimetableService {

    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);

    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    public List<TimetableEntry> getByClass(String className) {
        return timetableRepository.findByClassNameOrderByDayAscPeriodNumberAsc(className);
    }

    public List<TimetableEntry> getByTeacher(String teacherId) {
        return timetableRepository.findByTeacherIdOrderByDayAscPeriodNumberAsc(teacherId);
    }

    public TimetableEntry create(TimetableEntry entry, HttpServletRequest request) {
        if (timetableRepository.existsByClassNameAndDayAndPeriodNumber(
                entry.getClassName(), entry.getDay(), entry.getPeriodNumber())) {
            throw new DataIntegrityViolationException(
                    "Period " + entry.getPeriodNumber() + " on " + entry.getDay()
                            + " is already assigned for class " + entry.getClassName());
        }

        resolveTeacherName(entry);

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
        TimetableEntry existing = timetableRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Timetable entry not found: " + id));

        String oldValue = toJson(existing);

        // Check unique constraint only if day/period/class changed
        boolean slotChanged = !existing.getClassName().equals(incoming.getClassName())
                || !existing.getDay().equals(incoming.getDay())
                || !existing.getPeriodNumber().equals(incoming.getPeriodNumber());

        if (slotChanged && timetableRepository.existsByClassNameAndDayAndPeriodNumberAndIdNot(
                incoming.getClassName(), incoming.getDay(), incoming.getPeriodNumber(), id)) {
            throw new DataIntegrityViolationException(
                    "Period " + incoming.getPeriodNumber() + " on " + incoming.getDay()
                            + " is already assigned for class " + incoming.getClassName());
        }

        existing.setClassName(incoming.getClassName());
        existing.setDay(incoming.getDay());
        existing.setPeriodNumber(incoming.getPeriodNumber());
        existing.setStartTime(incoming.getStartTime());
        existing.setEndTime(incoming.getEndTime());
        existing.setSubjectName(incoming.getSubjectName());
        existing.setTeacherId(incoming.getTeacherId());

        // Re-fetch teacher name if teacherId changed
        resolveTeacherName(existing);

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
        TimetableEntry existing = timetableRepository.findById(id)
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

    private void resolveTeacherName(TimetableEntry entry) {
        if (entry.getTeacherId() != null && !entry.getTeacherId().isBlank()) {
            String name = teacherRepository.findById(entry.getTeacherId())
                    .map(t -> t.getName())
                    .orElse(null);
            entry.setTeacherName(name);
        } else {
            entry.setTeacherName(null);
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
