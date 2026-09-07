package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class StudentEnrollmentBackfillWorker {

    private final StudentRepository studentRepository;
    private final AcademicSessionRepository sessionRepository;
    private final SchoolClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    public StudentEnrollmentBackfillWorker(
            StudentRepository studentRepository,
            AcademicSessionRepository sessionRepository,
            SchoolClassRepository classRepository,
            SectionRepository sectionRepository,
            StudentEnrollmentRepository enrollmentRepository) {
        this.studentRepository = studentRepository;
        this.sessionRepository = sessionRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Evaluation evaluate(Long schoolId, String studentId, LocalDate asOfDate) {
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElse(null);
        if (student == null) {
            return Evaluation.failure("Student disappeared during backfill evaluation");
        }
        return resolve(student, schoolId, asOfDate);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Evaluation create(Long schoolId, String studentId, LocalDate asOfDate) {
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElse(null);
        if (student == null) {
            return Evaluation.failure("Student disappeared before enrollment creation");
        }

        Evaluation evaluation = resolve(student, schoolId, asOfDate);
        if (evaluation.outcome != Outcome.ELIGIBLE) {
            return evaluation;
        }

        enrollmentRepository.saveAndFlush(evaluation.enrollment);
        return Evaluation.created("Enrollment created", evaluation.enrollment);
    }

    private Evaluation resolve(Student student, Long schoolId, LocalDate asOfDate) {
        StudentStatus studentStatus = student.getStatus();
        if (studentStatus != StudentStatus.ACTIVE && studentStatus != StudentStatus.UPCOMING) {
            return Evaluation.skipped(Outcome.SKIPPED_STATUS,
                    "Student status " + studentStatus + " is not eligible for current-state backfill");
        }

        AcademicSession session;
        LocalDate effectiveFrom;
        StudentEnrollmentStatus enrollmentStatus;
        if (studentStatus == StudentStatus.ACTIVE) {
            Optional<AcademicSession> current = sessionRepository.findBySchoolIdAndCurrentTrue(schoolId);
            if (current.isEmpty()) {
                return Evaluation.skipped(Outcome.SKIPPED_NO_SESSION,
                        "ACTIVE student has no authoritative current academic session");
            }
            session = current.get();
            effectiveFrom = asOfDate;
            enrollmentStatus = StudentEnrollmentStatus.ACTIVE;
        } else {
            if (student.getJoiningDate() == null) {
                return Evaluation.skipped(Outcome.SKIPPED_INVALID_DATE,
                        "UPCOMING student has no joining date");
            }
            effectiveFrom = student.getJoiningDate();
            List<AcademicSession> covering = sessionRepository
                    .findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            schoolId, effectiveFrom, effectiveFrom);
            if (covering.isEmpty()) {
                return Evaluation.skipped(Outcome.SKIPPED_NO_SESSION,
                        "No configured academic session contains joining date " + effectiveFrom);
            }
            if (covering.size() > 1) {
                return Evaluation.conflict(
                        "Multiple academic sessions contain joining date " + effectiveFrom, null);
            }
            session = covering.get(0);
            enrollmentStatus = StudentEnrollmentStatus.PLANNED;
        }

        if (effectiveFrom.isBefore(session.getStartDate()) || effectiveFrom.isAfter(session.getEndDate())) {
            return Evaluation.skipped(Outcome.SKIPPED_INVALID_DATE,
                    "Effective date " + effectiveFrom + " is outside session " + session.getLabel());
        }

        if (student.getClassId() == null) {
            return Evaluation.skipped(Outcome.SKIPPED_INVALID_CLASS, "Student has no classId");
        }
        Optional<SchoolClass> resolvedClass = classRepository.findByIdAndSchoolId(student.getClassId(), schoolId);
        if (resolvedClass.isEmpty()) {
            return Evaluation.skipped(Outcome.SKIPPED_INVALID_CLASS,
                    "classId does not resolve within the student's school");
        }

        Section resolvedSection = null;
        if (student.getSectionId() != null) {
            Optional<Section> section = sectionRepository.findByIdAndSchoolId(student.getSectionId(), schoolId);
            if (section.isEmpty() || !Objects.equals(section.get().getClassId(), resolvedClass.get().getId())) {
                return Evaluation.skipped(Outcome.SKIPPED_INVALID_SECTION,
                        "sectionId does not resolve within the student's school and class");
            }
            resolvedSection = section.get();
        }

        StudentEnrollment proposed = proposedEnrollment(
                student, schoolId, session, resolvedClass.get(), resolvedSection,
                enrollmentStatus, effectiveFrom);
        List<StudentEnrollment> existing = enrollmentRepository
                .findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                        schoolId, student.getStudentId(), session.getId());
        if (existing.stream().anyMatch(row -> sameEnrollment(row, proposed))) {
            return Evaluation.alreadyPresent("Matching enrollment already exists", proposed);
        }
        if (!existing.isEmpty() || enrollmentRepository.existsOverlappingEnrollment(
                schoolId, student.getStudentId(), session.getId(), effectiveFrom, null)) {
            return Evaluation.conflict("Existing enrollment for the resolved session differs or conflicts", proposed);
        }

        return Evaluation.eligible("Validated for enrollment creation", proposed);
    }

    private StudentEnrollment proposedEnrollment(
            Student student,
            Long schoolId,
            AcademicSession session,
            SchoolClass schoolClass,
            Section section,
            StudentEnrollmentStatus status,
            LocalDate effectiveFrom) {
        StudentEnrollment enrollment = new StudentEnrollment();
        enrollment.setSchoolId(schoolId);
        enrollment.setStudentId(student.getStudentId());
        enrollment.setAcademicSessionId(session.getId());
        enrollment.setClassId(schoolClass.getId());
        enrollment.setClassNameSnapshot(schoolClass.getName());
        if (section != null) {
            enrollment.setSectionId(section.getId());
            enrollment.setSectionNameSnapshot(section.getName());
        }
        enrollment.setStatus(status);
        enrollment.setEffectiveFrom(effectiveFrom);
        return enrollment;
    }

    private boolean sameEnrollment(StudentEnrollment existing, StudentEnrollment proposed) {
        return existing.getStatus() == proposed.getStatus()
                && existing.getEffectiveUntil() == null
                && existing.getClosureReason() == null
                && Objects.equals(existing.getEffectiveFrom(), proposed.getEffectiveFrom())
                && Objects.equals(existing.getClassId(), proposed.getClassId())
                && Objects.equals(existing.getClassNameSnapshot(), proposed.getClassNameSnapshot())
                && Objects.equals(existing.getSectionId(), proposed.getSectionId())
                && Objects.equals(existing.getSectionNameSnapshot(), proposed.getSectionNameSnapshot());
    }

    public enum Outcome {
        ELIGIBLE,
        CREATED,
        ALREADY_PRESENT,
        SKIPPED_NO_SESSION,
        SKIPPED_INVALID_CLASS,
        SKIPPED_INVALID_SECTION,
        SKIPPED_STATUS,
        SKIPPED_INVALID_DATE,
        CONFLICT,
        FAILURE
    }

    public record Evaluation(Outcome outcome, String reason, StudentEnrollment enrollment) {
        static Evaluation eligible(String reason, StudentEnrollment enrollment) {
            return new Evaluation(Outcome.ELIGIBLE, reason, enrollment);
        }
        static Evaluation created(String reason, StudentEnrollment enrollment) {
            return new Evaluation(Outcome.CREATED, reason, enrollment);
        }
        static Evaluation alreadyPresent(String reason, StudentEnrollment enrollment) {
            return new Evaluation(Outcome.ALREADY_PRESENT, reason, enrollment);
        }
        static Evaluation conflict(String reason, StudentEnrollment enrollment) {
            return new Evaluation(Outcome.CONFLICT, reason, enrollment);
        }
        static Evaluation skipped(Outcome outcome, String reason) {
            return new Evaluation(outcome, reason, null);
        }
        static Evaluation failure(String reason) {
            return new Evaluation(Outcome.FAILURE, reason, null);
        }
    }
}
