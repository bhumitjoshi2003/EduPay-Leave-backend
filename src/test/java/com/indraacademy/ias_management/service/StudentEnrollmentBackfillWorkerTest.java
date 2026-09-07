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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentEnrollmentBackfillWorkerTest {

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CLASS_ID = 20L;
    private static final Long SECTION_ID = 30L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);

    @Mock StudentRepository studentRepository;
    @Mock AcademicSessionRepository sessionRepository;
    @Mock SchoolClassRepository classRepository;
    @Mock SectionRepository sectionRepository;
    @Mock StudentEnrollmentRepository enrollmentRepository;

    private StudentEnrollmentBackfillWorker worker;

    @BeforeEach
    void setUp() {
        worker = new StudentEnrollmentBackfillWorker(
                studentRepository, sessionRepository, classRepository,
                sectionRepository, enrollmentRepository);
    }

    @Test
    void validActiveStudentCreatesActiveEnrollmentFromAsOfDate() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, SECTION_ID, null);
        stubStudentAndValidReferences(student, currentSession(), schoolClass(), section(CLASS_ID));

        var result = worker.create(SCHOOL_ID, "S1", AS_OF);

        assertThat(result.outcome()).isEqualTo(StudentEnrollmentBackfillWorker.Outcome.CREATED);
        ArgumentCaptor<StudentEnrollment> captor = ArgumentCaptor.forClass(StudentEnrollment.class);
        verify(enrollmentRepository).saveAndFlush(captor.capture());
        StudentEnrollment saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(saved.getEffectiveFrom()).isEqualTo(AS_OF);
        assertThat(saved.getEffectiveFrom()).isNotEqualTo(currentSession().getStartDate());
        assertThat(saved.getClassNameSnapshot()).isEqualTo("Class 10");
        assertThat(saved.getSectionNameSnapshot()).isEqualTo("A");
    }

    @Test
    void upcomingStudentUsesJoiningDateAndItsCoveringFutureSession() {
        LocalDate joiningDate = LocalDate.of(2027, 4, 5);
        AcademicSession future = session(11L, "2027-2028",
                LocalDate.of(2027, 4, 1), LocalDate.of(2028, 3, 31), false);
        Student student = student("S2", StudentStatus.UPCOMING, CLASS_ID, null, joiningDate);
        when(studentRepository.findByStudentIdAndSchoolId("S2", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(sessionRepository.findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                SCHOOL_ID, joiningDate, joiningDate)).thenReturn(List.of(future));
        when(classRepository.findByIdAndSchoolId(CLASS_ID, SCHOOL_ID)).thenReturn(Optional.of(schoolClass()));

        var result = worker.create(SCHOOL_ID, "S2", AS_OF);

        assertThat(result.enrollment().getAcademicSessionId()).isEqualTo(11L);
        assertThat(result.enrollment().getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        assertThat(result.enrollment().getEffectiveFrom()).isEqualTo(joiningDate);
        verify(sessionRepository, never()).findBySchoolIdAndCurrentTrue(any());
    }

    @Test
    void activeWithoutCurrentSessionIsSkipped() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, null, null);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.empty());

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_NO_SESSION);
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void upcomingWithoutCoveringSessionIsSkipped() {
        LocalDate joiningDate = LocalDate.of(2028, 4, 1);
        Student student = student("S1", StudentStatus.UPCOMING, CLASS_ID, null, joiningDate);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(sessionRepository.findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                SCHOOL_ID, joiningDate, joiningDate)).thenReturn(List.of());

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_NO_SESSION);
    }

    @Test
    void upcomingWithoutJoiningDateIsSkipped() {
        Student student = student("S1", StudentStatus.UPCOMING, CLASS_ID, null, null);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_INVALID_DATE);
    }

    @Test
    void activeAsOfDateOutsideCurrentSessionIsSkipped() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, null, null);
        AcademicSession old = session(SESSION_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(old));

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_INVALID_DATE);
    }

    @Test
    void missingClassIsSkipped() {
        Student student = student("S1", StudentStatus.ACTIVE, null, null, null);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(currentSession()));

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_INVALID_CLASS);
    }

    @Test
    void classFromAnotherTenantIsSkipped() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, null, null);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(currentSession()));
        when(classRepository.findByIdAndSchoolId(CLASS_ID, SCHOOL_ID)).thenReturn(Optional.empty());

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_INVALID_CLASS);
        verify(classRepository).findByIdAndSchoolId(CLASS_ID, SCHOOL_ID);
        verify(classRepository, never()).findById(CLASS_ID);
    }

    @Test
    void invalidOrCrossTenantSectionIsSkippedWithoutClearingIt() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, SECTION_ID, null);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(currentSession()));
        when(classRepository.findByIdAndSchoolId(CLASS_ID, SCHOOL_ID)).thenReturn(Optional.of(schoolClass()));
        when(sectionRepository.findByIdAndSchoolId(SECTION_ID, SCHOOL_ID)).thenReturn(Optional.empty());

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_INVALID_SECTION);
        assertThat(student.getSectionId()).isEqualTo(SECTION_ID);
    }

    @Test
    void sectionFromAnotherClassIsSkipped() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, SECTION_ID, null);
        stubStudentAndValidReferences(student, currentSession(), schoolClass(), section(999L));

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.SKIPPED_INVALID_SECTION);
    }

    @Test
    void exitedAndLegacyInactiveStatusesAreSkipped() {
        for (StudentStatus status : List.of(StudentStatus.INACTIVE, StudentStatus.GRADUATED,
                StudentStatus.TRANSFERRED, StudentStatus.WITHDRAWN)) {
            Student student = student("S-" + status, status, CLASS_ID, null, null);
            when(studentRepository.findByStudentIdAndSchoolId(student.getStudentId(), SCHOOL_ID))
                    .thenReturn(Optional.of(student));
            assertOutcome(worker.create(SCHOOL_ID, student.getStudentId(), AS_OF),
                    StudentEnrollmentBackfillWorker.Outcome.SKIPPED_STATUS);
        }
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void matchingExistingEnrollmentIsIdempotent() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, SECTION_ID, null);
        stubStudentAndValidReferences(student, currentSession(), schoolClass(), section(CLASS_ID));
        StudentEnrollment existing = matchingEnrollment("S1");
        when(enrollmentRepository.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                SCHOOL_ID, "S1", SESSION_ID)).thenReturn(List.of(existing));

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.ALREADY_PRESENT);
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rerunningAfterSuccessfulCreationAddsNoSecondEnrollment() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, SECTION_ID, null);
        stubStudentAndValidReferences(student, currentSession(), schoolClass(), section(CLASS_ID));
        AtomicReference<StudentEnrollment> persisted = new AtomicReference<>();
        when(enrollmentRepository.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                SCHOOL_ID, "S1", SESSION_ID))
                .thenAnswer(invocation -> persisted.get() == null ? List.of() : List.of(persisted.get()));
        when(enrollmentRepository.saveAndFlush(any(StudentEnrollment.class)))
                .thenAnswer(invocation -> {
                    StudentEnrollment enrollment = invocation.getArgument(0);
                    persisted.set(enrollment);
                    return enrollment;
                });

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.CREATED);
        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.ALREADY_PRESENT);

        verify(enrollmentRepository, times(1)).saveAndFlush(any(StudentEnrollment.class));
    }

    @Test
    void differingExistingEnrollmentIsConflictAndUntouched() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, SECTION_ID, null);
        stubStudentAndValidReferences(student, currentSession(), schoolClass(), section(CLASS_ID));
        StudentEnrollment existing = matchingEnrollment("S1");
        existing.setClassNameSnapshot("Legacy different snapshot");
        when(enrollmentRepository.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                SCHOOL_ID, "S1", SESSION_ID)).thenReturn(List.of(existing));

        assertOutcome(worker.create(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.CONFLICT);
        assertThat(existing.getClassNameSnapshot()).isEqualTo("Legacy different snapshot");
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void dryRunEvaluationNeverPersists() {
        Student student = student("S1", StudentStatus.ACTIVE, CLASS_ID, SECTION_ID, null);
        stubStudentAndValidReferences(student, currentSession(), schoolClass(), section(CLASS_ID));

        assertOutcome(worker.evaluate(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.ELIGIBLE);
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void allStudentResolutionStartsWithTenantScopedLookup() {
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.empty());

        assertOutcome(worker.evaluate(SCHOOL_ID, "S1", AS_OF),
                StudentEnrollmentBackfillWorker.Outcome.FAILURE);
        verify(studentRepository).findByStudentIdAndSchoolId("S1", SCHOOL_ID);
        verify(studentRepository, never()).findById("S1");
    }

    private void stubStudentAndValidReferences(Student student, AcademicSession session,
                                               SchoolClass schoolClass, Section section) {
        when(studentRepository.findByStudentIdAndSchoolId(student.getStudentId(), SCHOOL_ID))
                .thenReturn(Optional.of(student));
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(session));
        when(classRepository.findByIdAndSchoolId(CLASS_ID, SCHOOL_ID)).thenReturn(Optional.of(schoolClass));
        if (student.getSectionId() != null) {
            when(sectionRepository.findByIdAndSchoolId(SECTION_ID, SCHOOL_ID)).thenReturn(Optional.of(section));
        }
    }

    private Student student(String id, StudentStatus status, Long classId, Long sectionId, LocalDate joiningDate) {
        Student student = new Student();
        student.setStudentId(id);
        student.setSchoolId(SCHOOL_ID);
        student.setStatus(status);
        student.setClassId(classId);
        student.setSectionId(sectionId);
        student.setJoiningDate(joiningDate);
        return student;
    }

    private AcademicSession currentSession() {
        return session(SESSION_ID, "2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), true);
    }

    private AcademicSession session(Long id, String label, LocalDate start, LocalDate end, boolean current) {
        AcademicSession session = new AcademicSession();
        session.setId(id);
        session.setSchoolId(SCHOOL_ID);
        session.setLabel(label);
        session.setStartDate(start);
        session.setEndDate(end);
        session.setCurrent(current);
        return session;
    }

    private SchoolClass schoolClass() {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.setId(CLASS_ID);
        schoolClass.setSchoolId(SCHOOL_ID);
        schoolClass.setName("Class 10");
        return schoolClass;
    }

    private Section section(Long classId) {
        Section section = new Section();
        section.setId(SECTION_ID);
        section.setSchoolId(SCHOOL_ID);
        section.setClassId(classId);
        section.setName("A");
        return section;
    }

    private StudentEnrollment matchingEnrollment(String studentId) {
        StudentEnrollment enrollment = new StudentEnrollment();
        enrollment.setSchoolId(SCHOOL_ID);
        enrollment.setStudentId(studentId);
        enrollment.setAcademicSessionId(SESSION_ID);
        enrollment.setClassId(CLASS_ID);
        enrollment.setClassNameSnapshot("Class 10");
        enrollment.setSectionId(SECTION_ID);
        enrollment.setSectionNameSnapshot("A");
        enrollment.setStatus(StudentEnrollmentStatus.ACTIVE);
        enrollment.setEffectiveFrom(AS_OF);
        return enrollment;
    }

    private void assertOutcome(StudentEnrollmentBackfillWorker.Evaluation evaluation,
                               StudentEnrollmentBackfillWorker.Outcome expected) {
        assertThat(evaluation.outcome()).isEqualTo(expected);
    }
}
