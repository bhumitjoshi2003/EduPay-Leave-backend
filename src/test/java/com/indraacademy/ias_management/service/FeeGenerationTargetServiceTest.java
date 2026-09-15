package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeeGenerationTargetServiceTest {
    static final Long SCHOOL = 1L, SESSION = 10L, CLASS_9 = 20L, CLASS_10 = 21L, SECTION_A = 30L;

    @Mock AcademicSessionRepository sessions;
    @Mock AcademicSessionService sessionService;
    @Mock StudentEnrollmentRepository enrollments;
    @Mock StudentRepository students;
    @Mock SchoolClassRepository classes;
    @Mock SectionRepository sections;
    @Mock FeeCalculationService calculationService;
    @Mock StudentFeesRepository studentFeesRepository;
    @Mock StudentFeesLineItemRepository lineItems;
    @Mock StudentOneTimeFeeChargedRepository oneTime;
    @Mock StudentTransportFeeAssignmentRepository transport;
    @Mock AuditService auditService;
    @Mock SecurityUtil securityUtil;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;

    FeeGenerationTargetService service;
    AcademicSession session;

    @BeforeEach void setup() {
        service = new FeeGenerationTargetService(sessions, sessionService, enrollments, students, classes, sections,
                calculationService, studentFeesRepository, lineItems, oneTime, transport, auditService, securityUtil, transactionManager);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        session = session();
        lenient().when(sessions.findByIdAndSchoolId(SESSION, SCHOOL)).thenReturn(Optional.of(session));
        lenient().when(classes.findByIdAndSchoolId(CLASS_9, SCHOOL)).thenReturn(Optional.of(schoolClass(CLASS_9, "9")));
        lenient().when(classes.findByIdAndSchoolId(CLASS_10, SCHOOL)).thenReturn(Optional.of(schoolClass(CLASS_10, "10")));
        lenient().when(sections.findByIdAndSchoolId(SECTION_A, SCHOOL)).thenReturn(Optional.of(section(SECTION_A, CLASS_9, "A")));
        lenient().when(sessionService.academicMonthToDate(eq(session), anyInt()))
                .thenAnswer(i -> LocalDate.of(2026, 7, 1).plusMonths((Integer) i.getArgument(1) - 1));
        lenient().when(calculationService.validateFeeConfiguration(eq(SCHOOL), eq("2026-2027"), anyString()))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        lenient().when(oneTime.findFeeHeadIdBySchoolIdAndStudentId(anyLong(), anyString())).thenReturn(Set.of());
        lenient().when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(anyString(), anyLong(), anyString()))
                .thenReturn(List.of());
        lenient().when(transport.effectiveOn(anyLong(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        lenient().when(calculationService.computeMonthSnapshot(anyLong(), anyString(), anyString(), anyString(),
                        anyInt(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(BigDecimal.valueOf(1000), BigDecimal.ZERO,
                        BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED, List.of()));
    }

    // ─── candidate discovery ────────────────────────────────────────────────────────────

    @Test void previewIncludesPlannedAndActiveButExcludesCancelledClosedAndAmbiguousStudents() {
        when(enrollments.findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(SCHOOL, SESSION))
                .thenReturn(List.of(
                        enrollment(1L, "S-PLANNED", StudentEnrollmentStatus.PLANNED, CLASS_9, null),
                        enrollment(2L, "S-ACTIVE", StudentEnrollmentStatus.ACTIVE, CLASS_9, null),
                        enrollment(3L, "S-CANCELLED", StudentEnrollmentStatus.CANCELLED, CLASS_9, null),
                        enrollment(4L, "S-CLOSED", StudentEnrollmentStatus.CLOSED, CLASS_9, null),
                        enrollment(5L, "S-AMBIGUOUS", StudentEnrollmentStatus.PLANNED, CLASS_9, null),
                        enrollment(6L, "S-AMBIGUOUS", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        stubStudent("S-PLANNED"); stubStudent("S-ACTIVE");

        List<StudentPreviewRow> rows = service.preview(SESSION, null, null);

        assertThat(rows).extracting(StudentPreviewRow::studentId).containsExactlyInAnyOrder("S-PLANNED", "S-ACTIVE");
    }

    @Test void previewMarksPlannedEnrollmentWithInformationalWarning() {
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.PLANNED, CLASS_9, null)));
        stubStudent("S1");

        StudentPreviewRow row = service.preview(SESSION, null, "S1").getFirst();

        assertThat(row.warnings()).anyMatch(w -> w.contains("not yet active"));
        assertThat(row.eligible()).isTrue();
    }

    @Test void previewComputesIdenticalTotalsForPlannedAndActiveEnrollmentInSameClass() {
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S-P", SESSION))
                .thenReturn(List.of(enrollment(1L, "S-P", StudentEnrollmentStatus.PLANNED, CLASS_9, null)));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S-A", SESSION))
                .thenReturn(List.of(enrollment(2L, "S-A", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        stubStudent("S-P"); stubStudent("S-A");

        BigDecimal plannedTotal = service.preview(SESSION, null, "S-P").getFirst().totalDue();
        BigDecimal activeTotal = service.preview(SESSION, null, "S-A").getFirst().totalDue();

        assertThat(plannedTotal).isEqualByComparingTo(activeTotal);
    }

    @Test void previewBlocksWhenNoFeeRuleConfiguredForTargetClass() {
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        stubStudent("S1");
        when(calculationService.validateFeeConfiguration(SCHOOL, "2026-2027", "9"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.fail("No FeeStructureRule configured"));

        StudentPreviewRow row = service.preview(SESSION, null, "S1").getFirst();

        assertThat(row.eligible()).isFalse();
        assertThat(row.blockingErrors()).contains("No FeeStructureRule configured");
        assertThat(row.months()).isEmpty();
    }

    @Test void previewShowsAlreadyGeneratedMonthsAndDoesNotRecomputeThem() {
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        stubStudent("S1");
        StudentFees existing = new StudentFees();
        existing.setBaseAmountDue(BigDecimal.valueOf(500)); existing.setBusFeeDue(BigDecimal.ZERO); existing.setDiscountAmount(BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth("S1", SCHOOL, "2026-2027", 1)).thenReturn(existing);

        StudentPreviewRow row = service.preview(SESSION, null, "S1").getFirst();

        assertThat(row.alreadyGeneratedMonths()).contains(1);
        assertThat(row.months().getFirst().alreadyGenerated()).isTrue();
        verify(calculationService, never()).computeMonthSnapshot(anyLong(), anyString(), anyString(), anyString(), eq(1),
                anyBoolean(), any(), any(), any(), any());
    }

    @Test void previewWarnsWhenNoTransportAssignmentCoversTheTargetSession() {
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        stubStudent("S1");

        StudentPreviewRow row = service.preview(SESSION, null, "S1").getFirst();

        assertThat(row.warnings()).anyMatch(w -> w.contains("No transport assignment recorded"));
    }

    @Test void previewDoesNotBlockGenerationSolelyForMissingTransportAssignment() {
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        stubStudent("S1");

        StudentPreviewRow row = service.preview(SESSION, null, "S1").getFirst();

        assertThat(row.eligible()).isTrue();
        assertThat(row.blockingErrors()).isEmpty();
    }

    @Test void previewFlagsRepeatingClassWhenPriorSegmentHadTheSameClass() {
        StudentEnrollment target = enrollment(2L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null);
        StudentEnrollment prior = enrollment(1L, "S1", StudentEnrollmentStatus.CLOSED, CLASS_9, null);
        prior.setEffectiveFrom(LocalDate.of(2025, 7, 1));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(target));
        when(enrollments.findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(SCHOOL, "S1"))
                .thenReturn(List.of(prior, target));
        stubStudent("S1");

        StudentPreviewRow row = service.preview(SESSION, null, "S1").getFirst();

        assertThat(row.repeatingSameClass()).isTrue();
    }

    @Test void previewLeavesRepeatingClassNullWhenNoPriorSegmentExists() {
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.PLANNED, CLASS_9, null)));
        when(enrollments.findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.PLANNED, CLASS_9, null)));
        stubStudent("S1");

        StudentPreviewRow row = service.preview(SESSION, null, "S1").getFirst();

        assertThat(row.repeatingSameClass()).isNull();
    }

    // ─── generate ───────────────────────────────────────────────────────────────────────

    @Test void generateSetsClassIdFromEnrollmentNotFromStudentProjection() {
        Student student = student("S1", CLASS_10); // Student projection deliberately stale/different
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(100L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        when(students.findByStudentIdAndSchoolId("S1", SCHOOL)).thenReturn(Optional.of(student));

        service.generate(request(List.of(decision("S1", 100L, CLASS_9))), "127.0.0.1");

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, times(12)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(fee -> assertThat(fee.getClassId()).isEqualTo(CLASS_9));
    }

    @Test void generateProducesGeneratedOutcomeOnFirstRun() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(100L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        when(students.findByStudentIdAndSchoolId("S1", SCHOOL)).thenReturn(Optional.of(student("S1", CLASS_9)));

        List<StudentGenerationResult> results = service.generate(request(List.of(decision("S1", 100L, CLASS_9))), "ip");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.GENERATED);
        assertThat(results.getFirst().generatedMonths()).isEqualTo(12);
        assertThat(results.getFirst().skippedMonths()).isZero();
    }

    @Test void generateReturnsAlreadyGeneratedWhenAllMonthsExist() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(100L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        when(students.findByStudentIdAndSchoolId("S1", SCHOOL)).thenReturn(Optional.of(student("S1", CLASS_9)));
        for (int m = 1; m <= 12; m++) {
            when(studentFeesRepository.findByStudentIdAndSchoolIdAndAcademicSessionIdAndMonth("S1", SCHOOL, SESSION, m)).thenReturn(new StudentFees());
        }

        List<StudentGenerationResult> results = service.generate(request(List.of(decision("S1", 100L, CLASS_9))), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.ALREADY_GENERATED);
        assertThat(results.getFirst().generatedMonths()).isZero();
        verify(studentFeesRepository, never()).save(any());
    }

    @Test void generateReturnsPartiallyGeneratedWhenSomeMonthsAlreadyExist() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(100L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        when(students.findByStudentIdAndSchoolId("S1", SCHOOL)).thenReturn(Optional.of(student("S1", CLASS_9)));
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndAcademicSessionIdAndMonth("S1", SCHOOL, SESSION, 1)).thenReturn(new StudentFees());

        List<StudentGenerationResult> results = service.generate(request(List.of(decision("S1", 100L, CLASS_9))), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.PARTIALLY_GENERATED);
        assertThat(results.getFirst().generatedMonths()).isEqualTo(11);
        assertThat(results.getFirst().skippedMonths()).isEqualTo(1);
    }

    @Test void generateReturnsEnrollmentChangedWhenTargetEnrollmentWasCancelled() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(100L, "S1", StudentEnrollmentStatus.CANCELLED, CLASS_9, null)));

        List<StudentGenerationResult> results = service.generate(request(List.of(decision("S1", 100L, CLASS_9))), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);
        verify(studentFeesRepository, never()).save(any());
    }

    @Test void generateReturnsEnrollmentChangedWhenTargetClassNoLongerMatchesPreview() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(100L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_10, null))); // corrected since preview

        List<StudentGenerationResult> results = service.generate(request(List.of(decision("S1", 100L, CLASS_9))), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);
        assertThat(results.getFirst().message()).containsIgnoringCase("class has changed");
        verify(studentFeesRepository, never()).save(any());
    }

    @Test void generateReturnsEnrollmentChangedWhenExpectedEnrollmentNoLongerExists() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1")).thenReturn(List.of());

        List<StudentGenerationResult> results = service.generate(request(List.of(decision("S1", 999L, CLASS_9))), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);
    }

    @Test void generateReturnsNoRuleConfiguredWithoutWritingAnything() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "S1"))
                .thenReturn(List.of(enrollment(100L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        when(calculationService.validateFeeConfiguration(SCHOOL, "2026-2027", "9"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.fail("No rule"));

        List<StudentGenerationResult> results = service.generate(request(List.of(decision("S1", 100L, CLASS_9))), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.NO_RULE_CONFIGURED);
        verify(studentFeesRepository, never()).save(any());
    }

    @Test void oneStudentFailureNeverBlocksAnotherStudentsGeneration() {
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "BAD")).thenThrow(new RuntimeException("boom"));
        when(enrollments.findAllHistoryForUpdate(SCHOOL, "GOOD"))
                .thenReturn(List.of(enrollment(200L, "GOOD", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));
        when(students.findByStudentIdAndSchoolId("GOOD", SCHOOL)).thenReturn(Optional.of(student("GOOD", CLASS_9)));

        List<StudentGenerationResult> results = service.generate(
                request(List.of(decision("BAD", 100L, CLASS_9), decision("GOOD", 200L, CLASS_9))), "ip");

        assertThat(results).extracting(StudentGenerationResult::studentId).containsExactlyInAnyOrder("BAD", "GOOD");
        assertThat(results.stream().filter(r -> r.studentId().equals("BAD")).findFirst().orElseThrow().outcome())
                .isEqualTo(GenerationOutcome.FAILED);
        assertThat(results.stream().filter(r -> r.studentId().equals("GOOD")).findFirst().orElseThrow().outcome())
                .isEqualTo(GenerationOutcome.GENERATED);
    }

    @Test void generationNeverTouchesPaymentOrAllocationRepositories() {
        assertThat(List.of(FeeGenerationTargetService.class.getDeclaredFields()))
                .extracting(java.lang.reflect.Field::getType)
                .noneMatch(t -> t.getSimpleName().contains("Payment") || t.getSimpleName().contains("Allocation") || t.getSimpleName().contains("Refund"));
    }

    // ─── E5C read-only drift ───

    @Test void matchingPlannedAndActiveEnrollmentsAreClean() {
        when(studentFeesRepository.findBySchoolIdAndYear(SCHOOL, "2026-2027"))
                .thenReturn(fees("P", CLASS_9, "9", 12, "A", CLASS_9, "9", 12));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "P", SESSION))
                .thenReturn(List.of(enrollment(1L, "P", StudentEnrollmentStatus.PLANNED, CLASS_9, SECTION_A)));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "A", SESSION))
                .thenReturn(List.of(enrollment(2L, "A", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));

        assertThat(service.targetDrift(SESSION, null, null))
                .extracting(TargetDriftRow::driftStatus)
                .containsExactlyInAnyOrder(DriftStatus.CLEAN, DriftStatus.CLEAN);
    }

    @Test void correctedClassIsClassMismatchButSectionOnlyCorrectionIsClean() {
        when(studentFeesRepository.findBySchoolIdAndYear(SCHOOL, "2026-2027"))
                .thenReturn(fees("CLASS", CLASS_9, "9", 12, "SECTION", CLASS_9, "9", 12));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "CLASS", SESSION))
                .thenReturn(List.of(enrollment(1L, "CLASS", StudentEnrollmentStatus.PLANNED, CLASS_10, null)));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "SECTION", SESSION))
                .thenReturn(List.of(enrollment(2L, "SECTION", StudentEnrollmentStatus.PLANNED, CLASS_9, SECTION_A)));

        List<TargetDriftRow> rows = service.targetDrift(SESSION, null, null);
        assertThat(rows.stream().filter(r -> r.studentId().equals("CLASS")).findFirst().orElseThrow().driftStatus())
                .isEqualTo(DriftStatus.CLASS_MISMATCH);
        assertThat(rows.stream().filter(r -> r.studentId().equals("SECTION")).findFirst().orElseThrow().driftStatus())
                .isEqualTo(DriftStatus.CLEAN);
    }

    @Test void cancelledAndPartialGenerationAreReportedWithoutWrites() {
        when(studentFeesRepository.findBySchoolIdAndYear(SCHOOL, "2026-2027"))
                .thenReturn(fees("C", CLASS_9, "9", 12, "P", CLASS_9, "9", 3));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "C", SESSION))
                .thenReturn(List.of(enrollment(1L, "C", StudentEnrollmentStatus.CANCELLED, CLASS_9, null)));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "P", SESSION))
                .thenReturn(List.of(enrollment(2L, "P", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));

        List<TargetDriftRow> rows = service.targetDrift(SESSION, null, null);

        assertThat(rows).extracting(TargetDriftRow::driftStatus)
                .containsExactlyInAnyOrder(DriftStatus.CANCELLED_TARGET_WITH_FEES, DriftStatus.PARTIAL_GENERATION);
        assertThat(rows.stream().filter(r -> r.studentId().equals("P")).findFirst().orElseThrow().missingMonths())
                .containsExactly(4, 5, 6, 7, 8, 9, 10, 11, 12);
        verify(studentFeesRepository, never()).save(any());
        verify(enrollments, never()).save(any());
        verify(lineItems, never()).save(any());
        verify(oneTime, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void noApplicableEnrollmentIsInformationalButUncoveredLegacyRowsAreOmitted() {
        when(studentFeesRepository.findBySchoolIdAndYear(SCHOOL, "2026-2027"))
                .thenReturn(fees("CLOSED", CLASS_9, "9", 12, "LEGACY", CLASS_9, "9", 12));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "CLOSED", SESSION))
                .thenReturn(List.of(enrollment(1L, "CLOSED", StudentEnrollmentStatus.CLOSED, CLASS_9, null)));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "LEGACY", SESSION))
                .thenReturn(List.of());

        assertThat(service.targetDrift(SESSION, null, null))
                .singleElement().satisfies(row -> {
                    assertThat(row.studentId()).isEqualTo("CLOSED");
                    assertThat(row.driftStatus()).isEqualTo(DriftStatus.NO_TARGET_ENROLLMENT);
                });
    }

    @Test void driftLookupIsTenantScopedAndHonorsFilters() {
        when(studentFeesRepository.findBySchoolIdAndYear(SCHOOL, "2026-2027"))
                .thenReturn(fees("S1", CLASS_9, "9", 12));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION))
                .thenReturn(List.of(enrollment(1L, "S1", StudentEnrollmentStatus.ACTIVE, CLASS_9, null)));

        assertThat(service.targetDrift(SESSION, CLASS_10, "S1")).isEmpty();
        verify(studentFeesRepository).findBySchoolIdAndYear(SCHOOL, "2026-2027");
        verify(enrollments).findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL, "S1", SESSION);
    }

    // ─── fixtures ───────────────────────────────────────────────────────────────────────

    private AcademicSession session() {
        AcademicSession s = new AcademicSession(); s.setId(SESSION); s.setSchoolId(SCHOOL);
        s.setLabel("2026-2027"); s.setStartDate(LocalDate.of(2026, 7, 1)); s.setEndDate(LocalDate.of(2027, 6, 30));
        return s;
    }
    private SchoolClass schoolClass(Long id, String name) {
        SchoolClass c = new SchoolClass(); c.setId(id); c.setSchoolId(SCHOOL); c.setName(name); return c;
    }
    private Section section(Long id, Long classId, String name) {
        Section s = new Section(); s.setId(id); s.setSchoolId(SCHOOL); s.setClassId(classId); s.setName(name); return s;
    }
    private StudentEnrollment enrollment(Long id, String studentId, StudentEnrollmentStatus status, Long classId, Long sectionId) {
        StudentEnrollment e = new StudentEnrollment();
        e.setId(id); e.setSchoolId(SCHOOL); e.setStudentId(studentId); e.setAcademicSessionId(SESSION);
        e.setClassId(classId); e.setClassNameSnapshot(classId.equals(CLASS_9) ? "9" : "10");
        e.setSectionId(sectionId); e.setStatus(status); e.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        return e;
    }
    private Student student(String studentId, Long classId) {
        Student s = new Student(); s.setStudentId(studentId); s.setSchoolId(SCHOOL);
        s.setName("Name-" + studentId); s.setClassId(classId); s.setTakesBus(false); s.setDistance(0.0);
        return s;
    }
    private void stubStudent(String studentId) {
        lenient().when(students.findByStudentIdAndSchoolId(studentId, SCHOOL)).thenReturn(Optional.of(student(studentId, CLASS_9)));
    }
    private GenerationRequest request(List<GenerationDecision> decisions) { return new GenerationRequest(SESSION, decisions); }
    private GenerationDecision decision(String studentId, Long enrollmentId, Long classId) {
        return new GenerationDecision(studentId, enrollmentId, classId);
    }
    private List<StudentFees> fees(Object... groups) {
        List<StudentFees> result = new java.util.ArrayList<>();
        for (int i = 0; i < groups.length; i += 4) {
            String studentId = (String) groups[i]; Long classId = (Long) groups[i + 1];
            String className = (String) groups[i + 2]; int count = (Integer) groups[i + 3];
            for (int month = 1; month <= count; month++) {
                StudentFees fee = new StudentFees(); fee.setSchoolId(SCHOOL); fee.setStudentId(studentId);
                fee.setClassId(classId); fee.setClassName(className); fee.setYear("2026-2027"); fee.setMonth(month);
                result.add(fee);
            }
        }
        return result;
    }
}
