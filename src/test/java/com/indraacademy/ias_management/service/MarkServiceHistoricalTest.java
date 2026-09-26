package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ExamResultDTO;
import com.indraacademy.ias_management.dto.StudentExamSubjectDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ClassSubject;
import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.entity.ExamSubjectEntry;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentElectiveEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.entity.StudentMark;
import com.indraacademy.ias_management.repository.ClassSubjectRepository;
import com.indraacademy.ias_management.repository.ExamConfigRepository;
import com.indraacademy.ias_management.repository.ExamSubjectEntryRepository;
import com.indraacademy.ias_management.repository.OptionalSubjectRepository;
import com.indraacademy.ias_management.repository.StreamCoreSubjectRepository;
import com.indraacademy.ias_management.repository.StudentElectiveEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentMarkRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.StudentStreamSelectionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase: Historical Results correctness after promotion.
 *
 * getStudentResults(studentId, session) used to look up ExamConfig rows by the student's
 * CURRENT className. After a promotion, querying a past session therefore silently returned
 * nothing, even though the ExamConfig/ExamSubjectEntry/StudentMark rows for that old session
 * still exist untouched. It now derives the historical class from the student's own
 * StudentMark rows for that session (resolveHistoricalClassNamesForSession), falling back to
 * the current class only when there's no mark evidence at all yet (a brand-new, ungraded exam
 * in the CURRENT session). getStudentMarksForExam's elective filtering had the same bug for an
 * explicit historical examConfigId — it now uses the exam's own className instead.
 */
@ExtendWith(MockitoExtension.class)
class MarkServiceHistoricalTest {

    @Mock private StudentMarkRepository studentMarkRepository;
    @Mock private ExamSubjectEntryRepository examSubjectEntryRepository;
    @Mock private ExamConfigRepository examConfigRepository;
    @Mock private StudentStreamSelectionRepository studentStreamSelectionRepository;
    @Mock private StreamCoreSubjectRepository streamCoreSubjectRepository;
    @Mock private OptionalSubjectRepository optionalSubjectRepository;
    @Mock private ClassSubjectRepository classSubjectRepository;
    @Mock private StudentElectiveEnrollmentRepository studentElectiveEnrollmentRepository;
    @Mock private StudentService studentService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private StudentRepository studentRepository;
    @Mock private StudentTemporalMembershipResolver temporalMembershipResolver;
    @Mock private com.indraacademy.ias_management.repository.StudentEnrollmentRepository studentEnrollmentRepository;
    @Mock private com.indraacademy.ias_management.repository.AcademicSessionRepository academicSessionRepository;
    @Mock private com.indraacademy.ias_management.repository.SchoolClassRepository schoolClassRepository;

    private MarkService service;

    private static final Long SCHOOL_ID = 4L;
    private static final String STUDENT_ID = "S1";

    @BeforeEach
    void setUp() {
        service = new MarkService();
        ReflectionTestUtils.setField(service, "studentMarkRepository", studentMarkRepository);
        ReflectionTestUtils.setField(service, "examSubjectEntryRepository", examSubjectEntryRepository);
        ReflectionTestUtils.setField(service, "examConfigRepository", examConfigRepository);
        ReflectionTestUtils.setField(service, "studentStreamSelectionRepository", studentStreamSelectionRepository);
        ReflectionTestUtils.setField(service, "streamCoreSubjectRepository", streamCoreSubjectRepository);
        ReflectionTestUtils.setField(service, "optionalSubjectRepository", optionalSubjectRepository);
        ReflectionTestUtils.setField(service, "classSubjectRepository", classSubjectRepository);
        ReflectionTestUtils.setField(service, "studentElectiveEnrollmentRepository", studentElectiveEnrollmentRepository);
        ReflectionTestUtils.setField(service, "studentService", studentService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "temporalMembershipResolver", temporalMembershipResolver);
        ReflectionTestUtils.setField(service, "studentEnrollmentRepository", studentEnrollmentRepository);
        ReflectionTestUtils.setField(service, "academicSessionRepository", academicSessionRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        // No StudentEnrollment/AcademicSession fixtures exist for any of these tests — they
        // represent a school/period with no E6B enrollment coverage at all, so every call must
        // fall back to the pre-E6D mark-only/legacy bridge exactly as before. Mockito's default
        // answers (Optional.empty()/empty list) for the unstubbed calls below achieve this
        // without needing explicit stubs in each test.
    }

    private Student promotedStudent() {
        Student s = new Student();
        s.setStudentId(STUDENT_ID);
        s.setName("Promoted Pat");
        s.setSchoolId(SCHOOL_ID);
        s.setClassName("10"); // CURRENT class, post-promotion
        return s;
    }

    private ExamConfig examConfig(Long id, String session, String className, String examName) {
        ExamConfig e = new ExamConfig();
        e.setId(id);
        e.setSchoolId(SCHOOL_ID);
        e.setSession(session);
        e.setClassName(className);
        e.setExamName(examName);
        return e;
    }

    private ExamSubjectEntry entry(Long id, Long examConfigId, String subject, int maxMarks) {
        ExamSubjectEntry en = new ExamSubjectEntry();
        en.setId(id);
        en.setSchoolId(SCHOOL_ID);
        en.setExamConfigId(examConfigId);
        en.setSubjectName(subject);
        en.setMaxMarks(maxMarks);
        return en;
    }

    private StudentMark mark(String studentId, Long entryId, Double marks) {
        StudentMark m = new StudentMark();
        m.setSchoolId(SCHOOL_ID);
        m.setStudentId(studentId);
        m.setExamSubjectEntryId(entryId);
        m.setMarksObtained(marks);
        return m;
    }

    // ─── getStudentResults: historical session after promotion ─────────────────

    @Test
    void class9MarksRemainVisibleForOldSessionAfterPromotionToClass10() {
        Student student = promotedStudent(); // current className = "10"
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));

        ExamConfig class9Exam = examConfig(1L, "2025-2026", "9", "Half Yearly");
        ExamSubjectEntry mathEntry = entry(10L, 1L, "Math", 100);
        StudentMark mathMark = mark(STUDENT_ID, 10L, 85.0);

        when(examConfigRepository.findBySessionAndSchoolId("2025-2026", SCHOOL_ID)).thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdInAndSchoolId(eq(List.of(1L)), eq(SCHOOL_ID)))
                .thenReturn(List.of(mathEntry));
        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of(mathMark));
        when(examConfigRepository.findBySessionAndClassNameAndSchoolId("2025-2026", "9", SCHOOL_ID))
                .thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(List.of(mathEntry));
        lenient().when(studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(eq(List.of(10L)), eq(SCHOOL_ID)))
                .thenReturn(List.of(mathMark));

        List<ExamResultDTO> results = service.getStudentResults(STUDENT_ID, "2025-2026");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClassName()).isEqualTo("9"); // NOT "10"
        assertThat(results.get(0).getTotalMarksObtained()).isEqualTo(85.0);
        assertThat(results.get(0).getTotalMaxMarks()).isEqualTo(100.0);
        assertThat(results.get(0).getPercentage()).isEqualTo(85.0);
        // The bug this fixes: never search using the student's CURRENT class for a past session.
        verify(examConfigRepository, never()).findBySessionAndClassNameAndSchoolId(eq("2025-2026"), eq("10"), any());
    }

    @Test
    void aBrandNewUngradedExamInTheCurrentSessionFallsBackToTheCurrentClass() {
        Student student = promotedStudent(); // current className = "10"
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));

        ExamConfig class10Exam = examConfig(2L, "2026-2027", "10", "Unit Test 1");
        ExamSubjectEntry entry = entry(20L, 2L, "Science", 50);

        when(examConfigRepository.findBySessionAndSchoolId("2026-2027", SCHOOL_ID)).thenReturn(List.of(class10Exam));
        when(examSubjectEntryRepository.findByExamConfigIdInAndSchoolId(eq(List.of(2L)), eq(SCHOOL_ID)))
                .thenReturn(List.of(entry));
        // No marks entered yet anywhere in this session — nothing to derive history from.
        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of());
        when(examConfigRepository.findBySessionAndClassNameAndSchoolId("2026-2027", "10", SCHOOL_ID))
                .thenReturn(List.of(class10Exam));
        when(examSubjectEntryRepository.findByExamConfigIdAndSchoolId(2L, SCHOOL_ID)).thenReturn(List.of(entry));
        lenient().when(studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(eq(List.of(20L)), eq(SCHOOL_ID)))
                .thenReturn(List.of());

        List<ExamResultDTO> results = service.getStudentResults(STUDENT_ID, "2026-2027");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClassName()).isEqualTo("10");
        assertThat(results.get(0).getSubjects().get(0).getMarksObtained()).isNull();
    }

    @Test
    void omittingSessionPreservesExistingBehaviourUsingTheCurrentClassAcrossAllSessions() {
        Student student = promotedStudent();
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));
        when(examConfigRepository.findByClassNameAndSchoolId("10", SCHOOL_ID)).thenReturn(List.of());

        service.getStudentResults(STUDENT_ID, null);

        verify(examConfigRepository).findByClassNameAndSchoolId("10", SCHOOL_ID);
        verify(examConfigRepository, never()).findBySessionAndSchoolId(any(), any());
    }

    @Test
    void tenantIsolation_sessionExamLookupIsAlwaysScopedToTheCallersOwnSchool() {
        Student student = promotedStudent();
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));
        when(examConfigRepository.findBySessionAndSchoolId("2025-2026", SCHOOL_ID)).thenReturn(List.of());

        service.getStudentResults(STUDENT_ID, "2025-2026");

        verify(examConfigRepository).findBySessionAndSchoolId("2025-2026", SCHOOL_ID);
    }

    // ─── getStudentMarksForExam: explicit historical examConfigId ───────────────

    @Test
    void explicitHistoricalExamLookupUsesTheExamsOwnClassForElectiveFilteringAfterPromotion() {
        Student student = promotedStudent(); // current className = "10"
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));

        ExamConfig class9Exam = examConfig(1L, "2025-2026", "9", "Half Yearly");
        when(examConfigRepository.findById(1L)).thenReturn(Optional.of(class9Exam));

        ExamSubjectEntry mathEntry = entry(10L, 1L, "Math", 100);
        ExamSubjectEntry sanskritEntry = entry(11L, 1L, "Sanskrit", 100); // elective in class 9
        when(examSubjectEntryRepository.findByExamConfigIdAndSchoolId(1L, SCHOOL_ID))
                .thenReturn(List.of(mathEntry, sanskritEntry));

        ClassSubject sanskritElective = new ClassSubject();
        sanskritElective.setClassName("9");
        sanskritElective.setSubjectName("Sanskrit");
        sanskritElective.setOptional(true);
        when(classSubjectRepository.findByClassNameAndOptionalTrueAndSchoolId("9", SCHOOL_ID))
                .thenReturn(List.of(sanskritElective));

        // The student's class-9 elective choice AND a later, unrelated class-10 elective choice
        // (post-promotion) — the class-10 one must never leak into this class-9 exam's filtering.
        StudentElectiveEnrollment class9Choice = new StudentElectiveEnrollment();
        class9Choice.setStudentId(STUDENT_ID);
        class9Choice.setClassName("9");
        class9Choice.setSubjectName("Sanskrit");
        StudentElectiveEnrollment class10Choice = new StudentElectiveEnrollment();
        class10Choice.setStudentId(STUDENT_ID);
        class10Choice.setClassName("10");
        class10Choice.setSubjectName("Economics");
        when(studentElectiveEnrollmentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID))
                .thenReturn(List.of(class9Choice, class10Choice));

        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of());

        List<StudentExamSubjectDTO> result = service.getStudentMarksForExam(STUDENT_ID, 1L);

        // Both Math (not an elective) and Sanskrit (the student's class-9 elective choice) show up.
        assertThat(result).extracting(StudentExamSubjectDTO::getSubjectName)
                .containsExactlyInAnyOrder("Math", "Sanskrit");
        // Never queries electives using the student's CURRENT class.
        verify(classSubjectRepository, never()).findByClassNameAndOptionalTrueAndSchoolId(eq("10"), any());
    }

    @Test
    void aDifferentClassesElectiveChoiceNeverLeaksIntoAnHistoricalExamsSubjectList() {
        Student student = promotedStudent();
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));

        ExamConfig class9Exam = examConfig(1L, "2025-2026", "9", "Half Yearly");
        when(examConfigRepository.findById(1L)).thenReturn(Optional.of(class9Exam));

        ExamSubjectEntry sanskritEntry = entry(11L, 1L, "Sanskrit", 100); // elective in class 9
        when(examSubjectEntryRepository.findByExamConfigIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(List.of(sanskritEntry));

        ClassSubject sanskritElective = new ClassSubject();
        sanskritElective.setClassName("9");
        sanskritElective.setSubjectName("Sanskrit");
        sanskritElective.setOptional(true);
        when(classSubjectRepository.findByClassNameAndOptionalTrueAndSchoolId("9", SCHOOL_ID))
                .thenReturn(List.of(sanskritElective));

        // Student chose "Sanskrit" as their CLASS-10 elective (post-promotion) — a same-named
        // subject, but recorded under class "10", not "9". It must not satisfy the class-9 exam's
        // elective requirement.
        StudentElectiveEnrollment class10Choice = new StudentElectiveEnrollment();
        class10Choice.setStudentId(STUDENT_ID);
        class10Choice.setClassName("10");
        class10Choice.setSubjectName("Sanskrit");
        when(studentElectiveEnrollmentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID))
                .thenReturn(List.of(class10Choice));
        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of());

        List<StudentExamSubjectDTO> result = service.getStudentMarksForExam(STUDENT_ID, 1L);

        assertThat(result).isEmpty(); // Sanskrit correctly excluded — never enrolled in it FOR class 9
    }

    // ─── E6D: enrollment-authoritative exam discovery ───────────────────────────

    private AcademicSession session(long id, String label, LocalDate start, LocalDate end) {
        AcademicSession s = new AcademicSession();
        s.setId(id);
        s.setSchoolId(SCHOOL_ID);
        s.setLabel(label);
        s.setStartDate(start);
        s.setEndDate(end);
        return s;
    }

    private StudentTemporalMembershipResolver.Session sessionView(AcademicSession s) {
        return new StudentTemporalMembershipResolver.Session(s.getId(), SCHOOL_ID, s.getLabel(), s.getStartDate(), s.getEndDate());
    }

    private StudentTemporalMembershipResolver.Segment segment(
            long enrollmentId, long sessionId, long classId, String className, Long sectionId,
            StudentEnrollmentStatus status, LocalDate from, LocalDate until) {
        return new StudentTemporalMembershipResolver.Segment(
                enrollmentId, SCHOOL_ID, STUDENT_ID, sessionId, classId, className, sectionId, "A", status, from, until);
    }

    @Test
    void promotedStudentDiscoversPriorSessionConfiguredButUnmarkedExamViaEnrollment() {
        Student student = promotedStudent(); // current className = "10"
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));

        ExamConfig class9Exam = examConfig(1L, "2025-2026", "9", "Half Yearly");
        ExamSubjectEntry mathEntry = entry(10L, 1L, "Math", 100); // no exam date recorded

        when(examConfigRepository.findBySessionAndSchoolId("2025-2026", SCHOOL_ID)).thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdInAndSchoolId(eq(List.of(1L)), eq(SCHOOL_ID)))
                .thenReturn(List.of(mathEntry));
        // No marks entered anywhere for this student in this session — nothing to derive from.
        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of());

        AcademicSession session = session(50L, "2025-2026", LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(session));
        StudentTemporalMembershipResolver.Segment closedSegment = segment(
                100L, 50L, 9L, "9", 900L, StudentEnrollmentStatus.CLOSED,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        when(temporalMembershipResolver.realizedEnrollmentSegmentsForSession(SCHOOL_ID, STUDENT_ID, 50L))
                .thenReturn(new StudentTemporalMembershipResolver.SessionResolution(
                        sessionView(session), StudentTemporalMembershipResolver.CoverageClassification.ENROLLMENT_BACKED,
                        List.of(closedSegment), LocalDate.of(2025, 4, 1), false, null));

        when(examConfigRepository.findBySessionAndClassNameAndSchoolId("2025-2026", "9", SCHOOL_ID))
                .thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(List.of(mathEntry));
        lenient().when(studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(eq(List.of(10L)), eq(SCHOOL_ID)))
                .thenReturn(List.of());

        List<ExamResultDTO> results = service.getStudentResults(STUDENT_ID, "2025-2026");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClassName()).isEqualTo("9");
        assertThat(results.get(0).getSubjects().get(0).getMarksObtained()).isNull();
    }

    @Test
    void enrollmentDiscoveredExamWithSubjectDateOutsideTheSegmentIsExcluded() {
        Student student = promotedStudent();
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));

        ExamConfig class9Exam = examConfig(1L, "2025-2026", "9", "Final");
        // Dated AFTER the student's class-9 segment ended (they transferred to class 10 first).
        ExamSubjectEntry novEntry = entry(10L, 1L, "Science", 100);
        novEntry.setExamDate(LocalDate.of(2025, 11, 1));

        when(examConfigRepository.findBySessionAndSchoolId("2025-2026", SCHOOL_ID)).thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdInAndSchoolId(eq(List.of(1L)), eq(SCHOOL_ID)))
                .thenReturn(List.of(novEntry));
        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of());

        AcademicSession session = session(50L, "2025-2026", LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(session));
        // Segment covers only Apr-Aug — the student had moved on before the November exam date.
        StudentTemporalMembershipResolver.Segment closedSegment = segment(
                100L, 50L, 9L, "9", 900L, StudentEnrollmentStatus.CLOSED,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 8, 31));
        when(temporalMembershipResolver.realizedEnrollmentSegmentsForSession(SCHOOL_ID, STUDENT_ID, 50L))
                .thenReturn(new StudentTemporalMembershipResolver.SessionResolution(
                        sessionView(session), StudentTemporalMembershipResolver.CoverageClassification.ENROLLMENT_BACKED,
                        List.of(closedSegment), LocalDate.of(2025, 4, 1), false, null));
        when(examConfigRepository.findBySessionAndClassNameAndSchoolId("2025-2026", "9", SCHOOL_ID))
                .thenReturn(List.of(class9Exam));

        List<ExamResultDTO> results = service.getStudentResults(STUDENT_ID, "2025-2026");

        assertThat(results).isEmpty(); // no mark, and the exam's only subject date is outside the segment
    }

    @Test
    void enrollmentConflictFallsBackToExistingMarkEvidenceOnly() {
        Student student = promotedStudent();
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));

        ExamConfig class9Exam = examConfig(1L, "2025-2026", "9", "Half Yearly");
        ExamSubjectEntry mathEntry = entry(10L, 1L, "Math", 100);
        StudentMark mathMark = mark(STUDENT_ID, 10L, 70.0);

        when(examConfigRepository.findBySessionAndSchoolId("2025-2026", SCHOOL_ID)).thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdInAndSchoolId(eq(List.of(1L)), eq(SCHOOL_ID)))
                .thenReturn(List.of(mathEntry));
        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of(mathMark));

        AcademicSession session = session(50L, "2025-2026", LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(session));
        when(temporalMembershipResolver.realizedEnrollmentSegmentsForSession(SCHOOL_ID, STUDENT_ID, 50L))
                .thenReturn(new StudentTemporalMembershipResolver.SessionResolution(
                        sessionView(session), StudentTemporalMembershipResolver.CoverageClassification.CONFLICT,
                        List.of(), null, false, "Multiple realized enrollment segments overlap"));

        when(examConfigRepository.findBySessionAndClassNameAndSchoolId("2025-2026", "9", SCHOOL_ID))
                .thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(List.of(mathEntry));
        lenient().when(studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(eq(List.of(10L)), eq(SCHOOL_ID)))
                .thenReturn(List.of(mathMark));

        List<ExamResultDTO> results = service.getStudentResults(STUDENT_ID, "2025-2026");

        // The existing mark remains factual (mark authority) despite the enrollment conflict.
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTotalMarksObtained()).isEqualTo(70.0);
    }

    @Test
    void noSessionResultsIncludeAPriorSessionsClassDiscoveredViaEnrollment() {
        Student student = promotedStudent(); // current className = "10"
        when(studentService.getStudent(STUDENT_ID)).thenReturn(Optional.of(student));
        when(examConfigRepository.findByClassNameAndSchoolId("10", SCHOOL_ID)).thenReturn(List.of());
        when(studentMarkRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(List.of());

        StudentEnrollment closedRow = new StudentEnrollment();
        closedRow.setSchoolId(SCHOOL_ID);
        closedRow.setStudentId(STUDENT_ID);
        closedRow.setAcademicSessionId(50L);
        closedRow.setClassId(9L);
        closedRow.setClassNameSnapshot("9");
        closedRow.setStatus(StudentEnrollmentStatus.CLOSED);
        closedRow.setEffectiveFrom(LocalDate.of(2025, 4, 1));
        closedRow.setEffectiveUntil(LocalDate.of(2026, 3, 31));
        when(studentEnrollmentRepository.findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(SCHOOL_ID, STUDENT_ID))
                .thenReturn(List.of(closedRow));
        AcademicSession session = session(50L, "2025-2026", LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        when(academicSessionRepository.findByIdAndSchoolId(50L, SCHOOL_ID)).thenReturn(Optional.of(session));

        ExamConfig class9Exam = examConfig(1L, "2025-2026", "9", "Half Yearly");
        ExamSubjectEntry mathEntry = entry(10L, 1L, "Math", 100);
        when(examConfigRepository.findBySessionAndClassNameAndSchoolId("2025-2026", "9", SCHOOL_ID))
                .thenReturn(List.of(class9Exam));
        when(examSubjectEntryRepository.findByExamConfigIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(List.of(mathEntry));
        when(studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(eq(STUDENT_ID), anyList(), eq(SCHOOL_ID)))
                .thenReturn(List.of());
        lenient().when(studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(eq(List.of(10L)), eq(SCHOOL_ID)))
                .thenReturn(List.of());

        List<ExamResultDTO> results = service.getStudentResults(STUDENT_ID, null);

        // The current class ("10") has zero exams, but the prior class-9 exam (discovered via
        // realized enrollment, never a mark) is still surfaced — promotion never erases it.
        assertThat(results).extracting(ExamResultDTO::getClassName).containsExactly("9");
        verify(examConfigRepository, never()).findBySessionAndSchoolId(any(), any());
    }
}
