package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Per-row copy attempt, isolated in its own {@code REQUIRES_NEW} transaction — mirrors
 * {@code StudentEnrollmentBackfillWorker}'s shape exactly, so one bad source row can never roll
 * back rows already successfully copied earlier in the same run.
 */
@Service
public class TimetableSessionCopyWorker {

    private final TimetableRepository timetableRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final SectionRepository sectionRepository;
    private final TeacherRepository teacherRepository;

    public TimetableSessionCopyWorker(
            TimetableRepository timetableRepository,
            SchoolClassRepository schoolClassRepository,
            SectionRepository sectionRepository,
            TeacherRepository teacherRepository) {
        this.timetableRepository = timetableRepository;
        this.schoolClassRepository = schoolClassRepository;
        this.sectionRepository = sectionRepository;
        this.teacherRepository = teacherRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Evaluation attempt(Long schoolId, TimetableEntry source, Long targetSessionId) {
        // Canonical class/section references must still exist in the current school config —
        // re-resolved fresh, never assumed still valid from the source row's snapshot.
        SchoolClass schoolClass = schoolClassRepository.findByIdAndSchoolId(source.getClassId(), schoolId).orElse(null);
        if (schoolClass == null) {
            return Evaluation.skipped(Outcome.SKIPPED_INVALID_CLASS,
                    "Class " + source.getClassId() + " no longer exists in this school");
        }
        Section section = null;
        if (source.getSectionId() != null) {
            section = sectionRepository.findByIdAndSchoolId(source.getSectionId(), schoolId).orElse(null);
            if (section == null || !Objects.equals(section.getClassId(), schoolClass.getId())) {
                return Evaluation.skipped(Outcome.SKIPPED_INVALID_SECTION,
                        "Section " + source.getSectionId() + " no longer resolves within class " + schoolClass.getName());
            }
        }

        // Active eligible teachers may copy; LEFT/ineligible/missing teachers are reported and
        // never silently reassigned to someone else.
        if (source.getTeacherId() != null && !source.getTeacherId().isBlank()) {
            Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(source.getTeacherId(), schoolId).orElse(null);
            if (teacher == null || teacher.getStatus() != TeacherStatus.ACTIVE) {
                return Evaluation.skipped(Outcome.SKIPPED_INELIGIBLE_TEACHER,
                        "Teacher " + source.getTeacherId() + " is missing or not ACTIVE");
            }
        }

        TimetableEntry candidate = new TimetableEntry();
        candidate.setSchoolId(schoolId);
        candidate.setAcademicSessionId(targetSessionId);
        candidate.setClassId(schoolClass.getId());
        candidate.setClassName(schoolClass.getName());
        candidate.setSectionId(section != null ? section.getId() : null);
        candidate.setSectionName(section != null ? section.getName() : null);
        candidate.setDay(source.getDay());
        candidate.setPeriodNumber(source.getPeriodNumber());
        candidate.setStartTime(source.getStartTime());
        candidate.setEndTime(source.getEndTime());
        candidate.setSubjectName(source.getSubjectName());
        candidate.setTeacherId(source.getTeacherId());
        candidate.setTeacherName(source.getTeacherName());
        // Simultaneous tags are copied as data, including questionable ones — never reinterpreted.
        candidate.setSimultaneousGroup(source.getSimultaneousGroup());

        // Idempotency: has this exact source row already been copied into the target session by
        // an earlier run? A field-identical occupant of the same slot means yes — report
        // ALREADY_COPIED rather than creating a duplicate or erroring.
        List<TimetableEntry> occupants = fetchSlot(candidate, schoolId, targetSessionId);
        for (TimetableEntry existingTarget : occupants) {
            if (isFieldIdentical(existingTarget, candidate)) {
                return Evaluation.alreadyCopied(existingTarget);
            }
        }

        TimetableEntry saved = timetableRepository.save(candidate);
        return Evaluation.copied(saved);
    }

    private List<TimetableEntry> fetchSlot(TimetableEntry candidate, Long schoolId, Long targetSessionId) {
        if (candidate.getSectionId() != null) {
            return timetableRepository.findByAcademicSessionIdAndClassIdAndSectionIdAndDayAndPeriodNumberAndSchoolId(
                    targetSessionId, candidate.getClassId(), candidate.getSectionId(), candidate.getDay(),
                    candidate.getPeriodNumber(), schoolId);
        }
        return timetableRepository.findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                targetSessionId, candidate.getClassId(), candidate.getDay(), candidate.getPeriodNumber(), schoolId);
    }

    private boolean isFieldIdentical(TimetableEntry a, TimetableEntry b) {
        return Objects.equals(a.getSubjectName(), b.getSubjectName())
                && Objects.equals(a.getTeacherId(), b.getTeacherId())
                && Objects.equals(a.getStartTime(), b.getStartTime())
                && Objects.equals(a.getEndTime(), b.getEndTime())
                && Objects.equals(normalizeGroup(a.getSimultaneousGroup()), normalizeGroup(b.getSimultaneousGroup()));
    }

    private String normalizeGroup(String group) {
        if (group == null) return null;
        String trimmed = group.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum Outcome {
        COPIED, ALREADY_COPIED, SKIPPED_INELIGIBLE_TEACHER, SKIPPED_INVALID_CLASS,
        SKIPPED_INVALID_SECTION, FAILURE
    }

    public record Evaluation(Outcome outcome, String reason, TimetableEntry entry) {
        static Evaluation copied(TimetableEntry entry) { return new Evaluation(Outcome.COPIED, "Copied", entry); }
        static Evaluation alreadyCopied(TimetableEntry entry) { return new Evaluation(Outcome.ALREADY_COPIED, "Already copied", entry); }
        static Evaluation skipped(Outcome outcome, String reason) { return new Evaluation(outcome, reason, null); }
        static Evaluation failure(String reason) { return new Evaluation(Outcome.FAILURE, reason, null); }
    }
}
