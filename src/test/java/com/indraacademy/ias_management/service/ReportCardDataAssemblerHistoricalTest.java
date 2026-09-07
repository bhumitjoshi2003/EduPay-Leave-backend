package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ReportCardTemplate;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.ReportCardTemplateRepository;
import com.indraacademy.ias_management.repository.ReportCardTemplateSectionRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Phase E6E: Historical Report Card correctness after promotion.
 *
 * ReportCardDataAssembler.assemble() used to stamp the DTO with student.getClassName() /
 * student.getSectionName() (the student's CURRENT class/section) and compute attendance against
 * a guessed Jan-1-to-today date range. It now resolves the historical class via
 * MarkService.resolveHistoricalClassNamesForSession (E6D — marks union realized enrollment),
 * refuses to arbitrarily pick a class when genuinely ambiguous (requires an explicit classId
 * instead), resolves the historical SECTION from E6B's realized enrollment segments, and computes
 * attendance against the real AcademicSession's own date range.
 */
@ExtendWith(MockitoExtension.class)
class ReportCardDataAssemblerHistoricalTest {

    @Mock private StudentRepository studentRepo;
    @Mock private SchoolRepository schoolRepo;
    @Mock private ReportCardTemplateRepository templateRepo;
    @Mock private ReportCardTemplateSectionRepository sectionRepo;
    @Mock private AcademicSessionRepository sessionRepo;
    @Mock private AttendanceService attendanceService;
    @Mock private MarkService markService;
    @Mock private WeightageCalculationEngine weightageEngine;
    @Mock private ReportCardTemplateService templateService;
    @Mock private RemarksService remarksService;
    @Mock private SecurityUtil securityUtil;
    @Mock private StudentTemporalMembershipResolver temporalMembershipResolver;
    @Mock private SchoolClassRepository schoolClassRepo;

    private ReportCardDataAssembler assembler;

    private static final Long SCHOOL_ID = 4L;
    private static final String STUDENT_ID = "S1";
    private static final Long TEMPLATE_ID = 100L;

    @BeforeEach
    void setUp() {
        assembler = new ReportCardDataAssembler();
        ReflectionTestUtils.setField(assembler, "studentRepo", studentRepo);
        ReflectionTestUtils.setField(assembler, "schoolRepo", schoolRepo);
        ReflectionTestUtils.setField(assembler, "templateRepo", templateRepo);
        ReflectionTestUtils.setField(assembler, "sectionRepo", sectionRepo);
        ReflectionTestUtils.setField(assembler, "sessionRepo", sessionRepo);
        ReflectionTestUtils.setField(assembler, "attendanceService", attendanceService);
        ReflectionTestUtils.setField(assembler, "markService", markService);
        ReflectionTestUtils.setField(assembler, "weightageEngine", weightageEngine);
        ReflectionTestUtils.setField(assembler, "templateService", templateService);
        ReflectionTestUtils.setField(assembler, "remarksService", remarksService);
        ReflectionTestUtils.setField(assembler, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(assembler, "temporalMembershipResolver", temporalMembershipResolver);
        ReflectionTestUtils.setField(assembler, "schoolClassRepo", schoolClassRepo);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        // No AcademicSession fixtures by default — resolveHistoricalSection's enrollment lookup
        // gracefully finds nothing (Optional.empty()) and falls back to the pre-E6E legacy
        // section rule, exactly matching a school with no E6B enrollment data. When a test DOES
        // stub an AcademicSession (for attendance date resolution) but doesn't care about
        // section, default the resolver call to a harmless empty/legacy-uncovered result rather
        // than an unstubbed null triggering the (correctly-handled, but noisy) NPE fallback path.
        lenient().when(temporalMembershipResolver.realizedEnrollmentSegmentsForSession(anyLong(), anyString(), anyLong()))
                .thenReturn(new StudentTemporalMembershipResolver.SessionResolution(
                        null, StudentTemporalMembershipResolver.CoverageClassification.LEGACY_UNCOVERED,
                        java.util.List.of(), null, true, null));
    }

    private Student promotedStudent() {
        Student s = new Student();
        s.setStudentId(STUDENT_ID);
        s.setName("Promoted Pat");
        s.setSchoolId(SCHOOL_ID);
        s.setClassName("10"); // CURRENT class, post-promotion
        s.setSectionName("B"); // CURRENT section
        s.setSectionId(20L);
        return s;
    }

    // ─── resolveHistoricalContext (used by ReportCardController's publication check) ───

    @Test
    void resolveHistoricalContextDelegatesToMarkServiceEvidence() {
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(promotedStudent()));
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2025-2026", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9"));
        when(schoolClassRepo.findBySchoolIdAndName(SCHOOL_ID, "9")).thenReturn(Optional.empty());

        var result = assembler.resolveHistoricalContext(STUDENT_ID, "2025-2026", null);

        assertThat(result.className()).isEqualTo("9");
    }

    @Test
    void resolveHistoricalContextThrowsWhenStudentNotFound() {
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assembler.resolveHistoricalContext(STUDENT_ID, "2025-2026", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void resolveHistoricalContextThrowsNotFoundWhenNoHistoricalEvidenceExistsAtAll() {
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(promotedStudent()));
        // An authoritative gap with zero marks and zero enrollment for this session.
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2025-2026", SCHOOL_ID, "10"))
                .thenReturn(Set.of());

        assertThatThrownBy(() -> assembler.resolveHistoricalContext(STUDENT_ID, "2025-2026", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void ambiguousMultiClassContextIsNeverArbitrarilyChosen() {
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(promotedStudent()));
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2026-2027", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9", "10"));

        assertThatThrownBy(() -> assembler.resolveHistoricalContext(STUDENT_ID, "2026-2027", null))
                .isInstanceOf(ReportCardDataAssembler.ReportCardContextAmbiguousException.class);
    }

    @Test
    void explicitClassIdResolvesAGenuineAmbiguity() {
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(promotedStudent()));
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2026-2027", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9", "10"));
        SchoolClass class9 = new SchoolClass();
        class9.setId(9L);
        class9.setName("9");
        when(schoolClassRepo.findByIdAndSchoolId(9L, SCHOOL_ID)).thenReturn(Optional.of(class9));

        var result = assembler.resolveHistoricalContext(STUDENT_ID, "2026-2027", 9L);

        assertThat(result.className()).isEqualTo("9");
    }

    @Test
    void classIdNotMatchingAnyCandidateIsRejected() {
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(promotedStudent()));
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2026-2027", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9", "10"));
        SchoolClass wrongClass = new SchoolClass();
        wrongClass.setId(11L);
        wrongClass.setName("11");
        when(schoolClassRepo.findByIdAndSchoolId(11L, SCHOOL_ID)).thenReturn(Optional.of(wrongClass));

        assertThatThrownBy(() -> assembler.resolveHistoricalContext(STUDENT_ID, "2026-2027", 11L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── assemble(): class/section/attendance historical correctness ───

    private void stubCommonAssembleDependencies(String session) {
        School school = new School();
        school.setName("Test School");
        school.setGradingSystem("CBSE");
        when(schoolRepo.findById(SCHOOL_ID)).thenReturn(Optional.of(school));

        ReportCardTemplate template = new ReportCardTemplate();
        template.setId(TEMPLATE_ID);
        template.setSchoolId(SCHOOL_ID);
        template.setAssessmentGroupId(50L);
        when(templateRepo.findByIdAndSchoolId(TEMPLATE_ID, SCHOOL_ID)).thenReturn(Optional.of(template));
        when(templateService.getTemplate(TEMPLATE_ID)).thenReturn(new ReportCardTemplateDTO());

        WeightedGroupResultDTO weighted = new WeightedGroupResultDTO(
                50L, "Group", "GROUP_BASED", 85.0, null, null, null, null, 1);
        when(weightageEngine.computeForStudent(STUDENT_ID, 50L, session)).thenReturn(weighted);

        lenient().when(sectionRepo.findByTemplateIdAndSectionType(eq(TEMPLATE_ID), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void reportCardAfterPromotionShowsHistoricalClassNineNotCurrentClassTen() {
        Student student = promotedStudent(); // current className = "10"
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        stubCommonAssembleDependencies("2025-2026");
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2025-2026", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9"));

        ReportCardDataDTO dto = assembler.assemble(STUDENT_ID, TEMPLATE_ID, "2025-2026");

        assertThat(dto.getClassName()).isEqualTo("9"); // NOT "10"
    }

    @Test
    void reportCardSectionShownWhenNoPromotionHasHappenedSinceThisSession() {
        Student student = promotedStudent(); // current className = "10", section "B"
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        stubCommonAssembleDependencies("2026-2027");
        // Resolved historical class equals the CURRENT class: no promotion since this session.
        // No AcademicSession/enrollment fixtures — legacy section rule applies.
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2026-2027", SCHOOL_ID, "10"))
                .thenReturn(Set.of("10"));

        ReportCardDataDTO dto = assembler.assemble(STUDENT_ID, TEMPLATE_ID, "2026-2027");

        assertThat(dto.getClassName()).isEqualTo("10");
        assertThat(dto.getSectionName()).isEqualTo("B");
    }

    @Test
    void reportCardSectionLeftUnsetWhenHistoricalClassCanBeResolvedButSectionCannot() {
        Student student = promotedStudent(); // current className = "10", section "B"
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        stubCommonAssembleDependencies("2025-2026");
        // Resolved historical class ("9") differs from the student's CURRENT class ("10") —
        // a promotion has happened since this session, and no enrollment data exists to
        // establish the real historical section, so it must be left unset rather than guessed.
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2025-2026", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9"));

        ReportCardDataDTO dto = assembler.assemble(STUDENT_ID, TEMPLATE_ID, "2025-2026");

        assertThat(dto.getClassName()).isEqualTo("9");
        assertThat(dto.getSectionName()).isNull();
    }

    @Test
    void reportCardSectionComesFromRealizedEnrollmentSegmentWhenAvailable() {
        Student student = promotedStudent(); // current className = "10", current section "B"
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        stubCommonAssembleDependencies("2025-2026");
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2025-2026", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9"));

        AcademicSession session2526 = new AcademicSession();
        session2526.setId(77L);
        session2526.setStartDate(LocalDate.of(2025, 4, 1));
        session2526.setEndDate(LocalDate.of(2026, 3, 31));
        when(sessionRepo.findBySchoolIdAndLabel(SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(session2526));

        StudentTemporalMembershipResolver.Segment historicalSegment = new StudentTemporalMembershipResolver.Segment(
                200L, SCHOOL_ID, STUDENT_ID, 77L, 9L, "9", 900L, "A",
                StudentEnrollmentStatus.CLOSED, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        when(temporalMembershipResolver.realizedEnrollmentSegmentsForSession(SCHOOL_ID, STUDENT_ID, 77L))
                .thenReturn(new StudentTemporalMembershipResolver.SessionResolution(
                        new StudentTemporalMembershipResolver.Session(77L, SCHOOL_ID, "2025-2026",
                                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31)),
                        StudentTemporalMembershipResolver.CoverageClassification.ENROLLMENT_BACKED,
                        java.util.List.of(historicalSegment), LocalDate.of(2025, 4, 1), false, null));

        ReportCardDataDTO dto = assembler.assemble(STUDENT_ID, TEMPLATE_ID, "2025-2026");

        assertThat(dto.getClassName()).isEqualTo("9");
        assertThat(dto.getSectionName()).isEqualTo("A"); // from the enrollment segment, NOT live "B"
    }

    @Test
    void reportCardHistoricalAttendanceDelegatesToSharedAttendanceServiceForTheHistoricalSessionDates() {
        Student student = promotedStudent();
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        stubCommonAssembleDependencies("2025-2026");
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2025-2026", SCHOOL_ID, "10"))
                .thenReturn(Set.of("9"));

        com.indraacademy.ias_management.entity.ReportCardTemplateSection attendanceSection =
                new com.indraacademy.ias_management.entity.ReportCardTemplateSection();
        attendanceSection.setEnabled(true);
        when(sectionRepo.findByTemplateIdAndSectionType(TEMPLATE_ID, "ATTENDANCE"))
                .thenReturn(Optional.of(attendanceSection));

        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);
        AcademicSession session2526 = new AcademicSession();
        session2526.setId(77L);
        session2526.setStartDate(start);
        session2526.setEndDate(end);
        when(sessionRepo.findBySchoolIdAndLabel(SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(session2526));

        AttendanceSummaryDTO summary = new AttendanceSummaryDTO();
        summary.setTotalWorkingDays(200);
        summary.setDaysPresent(190);
        summary.setAttendancePercentage(95.0);
        // The session ended in the past ("2026-03-31"), so the assembler's future-date cap does
        // not clamp it — the historical session's own end date must be used verbatim.
        when(attendanceService.getStudentAttendanceForDateRange(eq(STUDENT_ID), eq(start), eq(end)))
                .thenReturn(summary);

        ReportCardDataDTO dto = assembler.assemble(STUDENT_ID, TEMPLATE_ID, "2025-2026");

        assertThat(dto.getAttendance()).isNotNull();
        assertThat(dto.getAttendance().getWorkingDays()).isEqualTo(200);
        assertThat(dto.getAttendance().getPresentDays()).isEqualTo(190);
        assertThat(dto.getAttendance().getPercentage()).isEqualTo(95.0);
    }

    @Test
    void missingAcademicSessionForAttendanceFailsClearlyRatherThanGuessingADateRange() {
        Student student = promotedStudent();
        when(studentRepo.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        stubCommonAssembleDependencies("2099-2100");
        when(markService.resolveHistoricalClassNamesForSession(STUDENT_ID, "2099-2100", SCHOOL_ID, "10"))
                .thenReturn(Set.of("10"));

        com.indraacademy.ias_management.entity.ReportCardTemplateSection attendanceSection =
                new com.indraacademy.ias_management.entity.ReportCardTemplateSection();
        attendanceSection.setEnabled(true);
        when(sectionRepo.findByTemplateIdAndSectionType(TEMPLATE_ID, "ATTENDANCE"))
                .thenReturn(Optional.of(attendanceSection));
        when(sessionRepo.findBySchoolIdAndLabel(SCHOOL_ID, "2099-2100")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assembler.assemble(STUDENT_ID, TEMPLATE_ID, "2099-2100"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
