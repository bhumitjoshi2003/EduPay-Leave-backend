package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Per-row copy attempt, isolated in its own {@code REQUIRES_NEW} transaction — the same shape as
 * {@link TimetableSessionCopyWorker} and {@code StudentEnrollmentBackfillWorker}, so one bad
 * source row can never roll back rows already successfully copied. Never touches
 * {@code Teacher.classTeacher}/{@code classTeacherSectionId}, {@code timetable_entry}, or
 * {@code teacher_class_grant} — this worker writes {@code class_teacher_responsibility} rows only.
 */
@Service
public class ClassTeacherResponsibilityCopyWorker {

    private final ClassTeacherResponsibilityRepository responsibilityRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final SectionRepository sectionRepository;
    private final TeacherRepository teacherRepository;

    public ClassTeacherResponsibilityCopyWorker(
            ClassTeacherResponsibilityRepository responsibilityRepository,
            SchoolClassRepository schoolClassRepository,
            SectionRepository sectionRepository,
            TeacherRepository teacherRepository) {
        this.responsibilityRepository = responsibilityRepository;
        this.schoolClassRepository = schoolClassRepository;
        this.sectionRepository = sectionRepository;
        this.teacherRepository = teacherRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Evaluation attempt(Long schoolId, ClassTeacherResponsibility source, Long targetSessionId) {
        SchoolClass schoolClass = schoolClassRepository.findByIdAndSchoolId(source.getClassId(), schoolId).orElse(null);
        if (schoolClass == null) {
            return Evaluation.skipped(Outcome.SKIPPED_INVALID_CLASS,
                    "Class " + source.getClassId() + " no longer exists in this school");
        }
        if (source.getSectionId() != null) {
            Section section = sectionRepository.findByIdAndSchoolId(source.getSectionId(), schoolId).orElse(null);
            if (section == null || !Objects.equals(section.getClassId(), schoolClass.getId())) {
                return Evaluation.skipped(Outcome.SKIPPED_INVALID_SECTION,
                        "Section " + source.getSectionId() + " no longer resolves within class " + schoolClass.getName());
            }
        }

        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(source.getTeacherId(), schoolId).orElse(null);
        if (teacher == null || teacher.getStatus() != TeacherStatus.ACTIVE) {
            return Evaluation.skipped(Outcome.SKIPPED_INELIGIBLE_TEACHER,
                    "Teacher " + source.getTeacherId() + " is missing or not ACTIVE");
        }

        List<ClassTeacherResponsibility> existingTargetRows = responsibilityRepository
                .findByAcademicSessionIdAndSchoolId(targetSessionId, schoolId).stream()
                .filter(r -> Objects.equals(r.getClassId(), source.getClassId())
                        && Objects.equals(r.getSectionId(), source.getSectionId()))
                .toList();

        if (!existingTargetRows.isEmpty()) {
            ClassTeacherResponsibility existing = existingTargetRows.get(0);
            if (Objects.equals(existing.getTeacherId(), source.getTeacherId())) {
                return Evaluation.alreadyCopied(existing);
            }
            return Evaluation.conflict("A different responsibility (teacher " + existing.getTeacherId()
                    + ") already exists for this class/section in the target session");
        }

        ClassTeacherResponsibility candidate = new ClassTeacherResponsibility();
        candidate.setSchoolId(schoolId);
        candidate.setAcademicSessionId(targetSessionId);
        candidate.setClassId(schoolClass.getId());
        candidate.setSectionId(source.getSectionId());
        candidate.setTeacherId(source.getTeacherId());

        try {
            ClassTeacherResponsibility saved = responsibilityRepository.saveAndFlush(candidate);
            return Evaluation.copied(saved);
        } catch (DataIntegrityViolationException e) {
            return Evaluation.conflict(e.getMessage());
        }
    }

    public enum Outcome {
        COPIED, ALREADY_COPIED, SKIPPED_INELIGIBLE_TEACHER, SKIPPED_INVALID_CLASS,
        SKIPPED_INVALID_SECTION, CONFLICT, FAILURE
    }

    public record Evaluation(Outcome outcome, String reason, ClassTeacherResponsibility entry) {
        static Evaluation copied(ClassTeacherResponsibility entry) { return new Evaluation(Outcome.COPIED, "Copied", entry); }
        static Evaluation alreadyCopied(ClassTeacherResponsibility entry) { return new Evaluation(Outcome.ALREADY_COPIED, "Already copied", entry); }
        static Evaluation skipped(Outcome outcome, String reason) { return new Evaluation(outcome, reason, null); }
        static Evaluation conflict(String reason) { return new Evaluation(Outcome.CONFLICT, reason, null); }
    }
}
