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
    @Autowired private TimetableValidationService timetableValidationService;
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
        timetableValidationService.validate(entry, schoolId, null);

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

        existing.setClassName(incoming.getClassName());
        existing.setSectionId(incoming.getSectionId());
        existing.setDay(incoming.getDay());
        existing.setPeriodNumber(incoming.getPeriodNumber());
        existing.setStartTime(incoming.getStartTime());
        existing.setEndTime(incoming.getEndTime());
        existing.setSubjectName(incoming.getSubjectName());
        existing.setTeacherId(incoming.getTeacherId());
        existing.setSimultaneousGroup(incoming.getSimultaneousGroup());

        // Always re-validate (slot consistency + teacher conflict) against the merged state,
        // excluding this entry's own id — simpler and more correct than only checking when the
        // slot key itself changed, since a teacher-conflict can newly arise even when the slot
        // key stays the same (e.g. only the teacher or time was edited).
        timetableValidationService.validate(existing, schoolId, id);

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

    /**
     * Adds a second (or further) subject to the exact same class/section/day/period/time slot
     * as the entry at {@code existingId} — the "+ Simultaneous" action. Class/section/day/period
     * /time are inherited from the existing entry rather than trusted from the client, so the
     * new row can never target a different slot by mistake.
     *
     * <p>The simultaneousGroup tag itself is fully automatic: if the existing entry doesn't have
     * one yet, a fresh one is generated and saved onto it here; if it already has one (e.g. this
     * is the third subject joining an existing pair), that tag is reused as-is. Either way, the
     * admin never sees or types this value — see TimetableEntry#simultaneousGroup and
     * TimetableValidationService for why the tag exists at all.
     */
    @Transactional
    public TimetableEntry addSimultaneous(Long existingId, String subjectName, String teacherId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        TimetableEntry existing = timetableRepository.findById(existingId)
                .filter(e -> schoolId.equals(e.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Timetable entry not found: " + existingId));

        String group = existing.getSimultaneousGroup();
        if (group == null || group.isBlank()) {
            group = "sg-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String oldValue = toJson(existing);
            existing.setSimultaneousGroup(group);
            timetableRepository.save(existing);
            auditService.logUpdate(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_TIMETABLE_ENTRY",
                    "TimetableEntry",
                    existing.getId().toString(),
                    oldValue,
                    toJson(existing),
                    request.getRemoteAddr()
            );
        }

        TimetableEntry candidate = new TimetableEntry();
        candidate.setSchoolId(schoolId);
        candidate.setClassName(existing.getClassName());
        candidate.setClassId(existing.getClassId());
        candidate.setSectionId(existing.getSectionId());
        candidate.setDay(existing.getDay());
        candidate.setPeriodNumber(existing.getPeriodNumber());
        candidate.setStartTime(existing.getStartTime());
        candidate.setEndTime(existing.getEndTime());
        candidate.setSubjectName(subjectName);
        candidate.setTeacherId(teacherId);
        candidate.setSimultaneousGroup(group);

        timetableValidationService.validate(candidate, schoolId, null);
        resolveTeacherName(candidate);
        resolveSectionName(candidate);

        TimetableEntry saved = timetableRepository.save(candidate);
        log.info("Timetable simultaneous entry created: id={}, pairedWith={}, group={}", saved.getId(), existingId, group);

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
