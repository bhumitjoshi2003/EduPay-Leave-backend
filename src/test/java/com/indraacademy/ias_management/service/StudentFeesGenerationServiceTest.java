package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.entity.StudentOneTimeFeeCharged;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentOneTimeFeeChargedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the academic-calendar audit's fix to generateStudentFeesForNextYear's
 * "skip students enrolled during the current academic year" check — it used to compare
 * `createdAt.getYear() >= today.getYear()`, which only correctly identified "enrolled this
 * academic year" for a January-start school. computeCurrentAcademicYearStart replaces that
 * with a real academic-year boundary derived from the school's own start month.
 *
 * Also covers sub-phase 1b: generateForSchool (the extracted, directly-testable per-school
 * body of generateStudentFeesForNextYear) actually snapshotting base_amount_due/bus_fee_due/
 * discount_amount/amount_rule_snapshot per row for a continuing student, using
 * FeeCalculationService as the sole calculation source and never appliesAtJoin (continuing
 * students are never a "first row").
 */
@ExtendWith(MockitoExtension.class)
class StudentFeesGenerationServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private AuditService auditService;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private StudentOneTimeFeeChargedRepository studentOneTimeFeeChargedRepository;
    @Mock private StudentFeesLineItemRepository studentFeesLineItemRepository;

    private StudentFeesGenerationService service;

    @BeforeEach
    void setUp() {
        service = new StudentFeesGenerationService();
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "studentOneTimeFeeChargedRepository", studentOneTimeFeeChargedRepository);
        ReflectionTestUtils.setField(service, "studentFeesLineItemRepository", studentFeesLineItemRepository);

        lenient().when(schoolClassRepository.findBySchoolIdAndActiveOrderByDisplayOrderAsc(anyLong(), eq(true)))
                .thenReturn(List.of()); // falls back to DEFAULT_CLASS_SEQUENCE
        lenient().when(feeCalculationService.academicMonthStart(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(LocalDate.of(2025, 1, 1));
        lenient().when(feeCalculationService.validateFeeConfiguration(any(), any(), any()))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        lenient().when(feeCalculationService.computeMonthSnapshot(
                        any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        java.math.BigDecimal.valueOf(2000, 2), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        "{}", List.of(), SnapshotStatus.COMPUTED, List.of()));
    }

    private School school(Long id, int startMonth) {
        School s = new School();
        s.setId(id);
        s.setActive(true);
        s.setAcademicYearStartMonth(startMonth);
        return s;
    }

    private Student student(String id, String className, LocalDateTime createdAt) {
        Student s = new Student();
        s.setStudentId(id);
        s.setClassName(className);
        s.setCreatedAt(createdAt);
        s.setSchoolId(1L);
        s.setTakesBus(false);
        return s;
    }

    @Test
    void aprilStartSchool_boundaryIsApril1() {
        // Generation runs in March (the month before April) for an April-start school.
        LocalDate today = LocalDate.of(2026, 3, 1);
        LocalDate boundary = service.computeCurrentAcademicYearStart(today, 4);
        assertThat(boundary).isEqualTo(LocalDate.of(2025, 4, 1));
    }

    @Test
    void januaryStartSchool_boundaryIsJanuary1SameYear() {
        // Generation runs in December for a January-start school.
        LocalDate today = LocalDate.of(2026, 12, 1);
        LocalDate boundary = service.computeCurrentAcademicYearStart(today, 1);
        assertThat(boundary).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void julyStartSchool_boundaryIsJuly1() {
        // Generation runs in June for a July-start school.
        LocalDate today = LocalDate.of(2026, 6, 1);
        LocalDate boundary = service.computeCurrentAcademicYearStart(today, 7);
        assertThat(boundary).isEqualTo(LocalDate.of(2025, 7, 1));
    }

    @Test
    void decemberStartSchool_boundaryIsDecember1PriorYear() {
        // Generation runs in November for a December-start school.
        LocalDate today = LocalDate.of(2026, 11, 1);
        LocalDate boundary = service.computeCurrentAcademicYearStart(today, 12);
        assertThat(boundary).isEqualTo(LocalDate.of(2025, 12, 1));
    }

    @Test
    void studentEnrolledJustAfterBoundary_isConsideredThisAcademicYear() {
        // April-start school, generation month (March 2026). A student who enrolled on
        // April 2nd 2025 — the day after the CURRENT academic year started — must be
        // treated as "enrolled this academic year" (skipped here, picked up next cycle).
        LocalDate today = LocalDate.of(2026, 3, 1);
        LocalDate boundary = service.computeCurrentAcademicYearStart(today, 4);
        LocalDate enrolledAt = LocalDate.of(2025, 4, 2);
        assertThat(enrolledAt.isBefore(boundary)).isFalse(); // NOT before -> skip (correct: new this year)
    }

    @Test
    void studentEnrolledJustBeforeBoundary_isConsideredAGenuineContinuingStudent() {
        // Same April-start school. A student who enrolled on March 31 2025 — the day
        // BEFORE the current academic year started — is a genuine continuing student from
        // the PRIOR academic year and must NOT be skipped.
        LocalDate today = LocalDate.of(2026, 3, 1);
        LocalDate boundary = service.computeCurrentAcademicYearStart(today, 4);
        LocalDate enrolledAt = LocalDate.of(2025, 3, 31);
        assertThat(enrolledAt.isBefore(boundary)).isTrue(); // before -> do NOT skip
    }

    @Test
    void decemberStartSchool_regressionCaseFromAudit_studentEnrolledInJanuaryIsNotWronglySkippedForever() {
        // This is the exact bug scenario the audit found: for a December-start school, the
        // OLD `createdAt.getYear() >= today.getYear()` check had an 11-month blind spot. A
        // student enrolled in January 2026 (well into the CURRENT academic year, which
        // started December 2025) generates fees in November 2026 for the NEXT year
        // (academic year starting December 2026). The old check compared calendar years
        // (2026 >= 2026) and happened to skip them correctly here by coincidence, but the
        // real point is the boundary is now derived from the school's actual start month,
        // not calendar-year equality — verified directly:
        LocalDate today = LocalDate.of(2026, 11, 1);
        LocalDate boundary = service.computeCurrentAcademicYearStart(today, 12);
        assertThat(boundary).isEqualTo(LocalDate.of(2025, 12, 1));

        LocalDate enrolledJanuary2026 = LocalDate.of(2026, 1, 15);
        // Enrolled well after the academic year's real start (Dec 2025) -> correctly
        // recognized as "this academic year" -> skipped (fees already exist through Nov 2026).
        assertThat(enrolledJanuary2026.isBefore(boundary)).isFalse();
    }

    // ─── generateForSchool — sub-phase 1b: continuing students get a snapshotted row ─────

    @Test
    void continuingStudent_getsTwelveRowsAllUsingTheNormalScheduleNeverAppliesAtJoin() {
        School school = school(1L, 4); // April-start
        LocalDate today = LocalDate.of(2026, 3, 1); // generation month for April-start
        Student continuing = student("S1", "5", LocalDateTime.of(2024, 6, 1, 0, 0)); // enrolled long ago

        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S1"), any(), eq(1L))).thenReturn(false);

        service.generateForSchool(school, today, List.of(continuing));

        ArgumentCaptor<StudentFees> savedCaptor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, times(12)).save(savedCaptor.capture());
        List<StudentFees> saved = savedCaptor.getAllValues();
        assertThat(saved).extracting(StudentFees::getMonth).containsExactly(1,2,3,4,5,6,7,8,9,10,11,12);
        assertThat(saved).allSatisfy(sf -> {
            assertThat(sf.getBaseAmountDue()).isNotNull();
            assertThat(sf.getAmountComputedAt()).isNotNull();
            assertThat(sf.getAmountRuleSnapshot()).isEqualTo("{}");
            assertThat(sf.getClassName()).isEqualTo("6"); // promoted from class 5 for the next session
        });

        ArgumentCaptor<Boolean> isFirstRowCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(feeCalculationService, times(12)).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), isFirstRowCaptor.capture(), any(), any(), any(), any());
        assertThat(isFirstRowCaptor.getAllValues()).as("a continuing student's rows are never a 'first row'")
                .containsOnly(false);
    }

    @Test
    void januaryStartSchool_generatesInDecember() {
        School school = school(2L, 1);
        LocalDate decemberOfPriorYear = LocalDate.of(2026, 12, 1);
        Student continuing = student("S2", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S2"), any(), eq(2L))).thenReturn(false);

        service.generateForSchool(school, decemberOfPriorYear, List.of(continuing));

        verify(studentFeesRepository, times(12)).save(any());
    }

    @Test
    void julyStartSchool_generatesInJune() {
        School school = school(3L, 7);
        LocalDate june = LocalDate.of(2026, 6, 1);
        Student continuing = student("S3", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S3"), any(), eq(3L))).thenReturn(false);

        service.generateForSchool(school, june, List.of(continuing));

        verify(studentFeesRepository, times(12)).save(any());
    }

    @Test
    void decemberStartSchool_generatesInNovember() {
        School school = school(4L, 12);
        LocalDate november = LocalDate.of(2026, 11, 1);
        Student continuing = student("S4", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S4"), any(), eq(4L))).thenReturn(false);

        service.generateForSchool(school, november, List.of(continuing));

        verify(studentFeesRepository, times(12)).save(any());
    }

    @Test
    void wrongMonth_doesNotGenerateAnythingForThatSchool() {
        School school = school(1L, 4); // April-start, generates in March only
        LocalDate notGenerationMonth = LocalDate.of(2026, 5, 1);
        Student continuing = student("S1", "5", LocalDateTime.of(2024, 1, 1, 0, 0));

        service.generateForSchool(school, notGenerationMonth, List.of(continuing));

        verifyNoInteractions(studentFeesRepository);
    }

    @Test
    void oneTimeFeeHeadsNewlyChargedDuringGeneration_arePersistedToTheDedupTable() {
        School school = school(1L, 4);
        LocalDate today = LocalDate.of(2026, 3, 1);
        Student continuing = student("S1", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S1"), any(), eq(1L))).thenReturn(false);

        // Month 1 charges a ONE_TIME fee head (id=5); every other month charges nothing new.
        when(feeCalculationService.computeMonthSnapshot(
                        eq(1L), any(), any(), eq("S1"), eq(1), eq(false), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        java.math.BigDecimal.valueOf(1000000, 2), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        "{}", List.of(5L), SnapshotStatus.COMPUTED, List.of()));

        service.generateForSchool(school, today, List.of(continuing));

        ArgumentCaptor<StudentOneTimeFeeCharged> chargedCaptor = ArgumentCaptor.forClass(StudentOneTimeFeeCharged.class);
        verify(studentOneTimeFeeChargedRepository, times(1)).save(chargedCaptor.capture());
        assertThat(chargedCaptor.getValue().getFeeHeadId()).isEqualTo(5L);
        assertThat(chargedCaptor.getValue().getStudentId()).isEqualTo("S1");
        assertThat(chargedCaptor.getValue().getSchoolId()).isEqualTo(1L);
    }

    @Test
    void lineItemsFromTheSnapshot_arePersistedLinkedToEachGeneratedRowsRealId() {
        School school = school(1L, 4);
        LocalDate today = LocalDate.of(2026, 3, 1);
        Student continuing = student("S1", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S1"), any(), eq(1L))).thenReturn(false);
        when(studentFeesRepository.save(any(StudentFees.class))).thenAnswer(inv -> {
            StudentFees f = inv.getArgument(0);
            ReflectionTestUtils.setField(f, "id", 8000L + f.getMonth());
            return f;
        });
        // Month 1 has a real fee-head line item; every other month's default stub (set up in
        // setUp()) returns an empty line-item list, so only month 1 should reach the repository.
        when(feeCalculationService.computeMonthSnapshot(
                        eq(1L), any(), any(), eq("S1"), eq(1), eq(false), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        java.math.BigDecimal.valueOf(200000, 2), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        "{}", List.of(), SnapshotStatus.COMPUTED,
                        List.of(new FeeCalculationService.LineItemSnapshot(
                                "FEE_HEAD", 33L, "TUITION", "Tuition Fee", "MONTHLY", 200000L, 0L, null))));

        service.generateForSchool(school, today, List.of(continuing));

        ArgumentCaptor<StudentFeesLineItem> liCaptor = ArgumentCaptor.forClass(StudentFeesLineItem.class);
        verify(studentFeesLineItemRepository, times(1)).save(liCaptor.capture());
        StudentFeesLineItem saved = liCaptor.getValue();
        assertThat(saved.getStudentFeesId()).isEqualTo(8001L); // month 1's generated row id
        assertThat(saved.getFeeHeadName()).isEqualTo("Tuition Fee");
        assertThat(saved.getSchoolId()).isEqualTo(1L);
        assertThat(saved.getStudentId()).isEqualTo("S1");
        assertThat(saved.getMonth()).isEqualTo(1);
    }

    @Test
    void alreadyGeneratedStudent_isNotTouchedAgainRegardlessOfCurrentRuleState() {
        // The idempotency guard (existsByStudentIdAndYearAndSchoolId) is what makes "future
        // rule/config changes don't retroactively alter an already-generated row" true in
        // practice: a double-run of this job (or a rerun after an admin changes pricing)
        // must never call save() for a student whose rows already exist.
        School school = school(1L, 4);
        LocalDate today = LocalDate.of(2026, 3, 1);
        Student alreadyGenerated = student("S1", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S1"), any(), eq(1L))).thenReturn(true);

        service.generateForSchool(school, today, List.of(alreadyGenerated));

        verify(studentFeesRepository, never()).save(any());
        verifyNoInteractions(feeCalculationService);
    }

    @Test
    void newlyEnrolledStudentThisAcademicYear_isSkippedEntirely() {
        School school = school(1L, 4);
        LocalDate today = LocalDate.of(2026, 3, 1); // current academic year started April 2025
        Student justEnrolled = student("S1", "5", LocalDateTime.of(2025, 9, 1, 0, 0)); // enrolled Sept 2025

        service.generateForSchool(school, today, List.of(justEnrolled));

        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void graduatingStudent_isSkippedEntirely() {
        School school = school(1L, 4);
        LocalDate today = LocalDate.of(2026, 3, 1);
        Student graduating = student("S1", "12", LocalDateTime.of(2020, 1, 1, 0, 0)); // last class in the sequence

        service.generateForSchool(school, today, List.of(graduating));

        verify(studentFeesRepository, never()).save(any());
    }

    // ─── Hardening: never persist a snapshot when we cannot confidently calculate it ─────

    @Test
    void invalidFeeConfiguration_studentIsSkippedEntirely_noPartialRowsCreated() {
        School school = school(1L, 4);
        LocalDate today = LocalDate.of(2026, 3, 1);
        Student continuing = student("S1", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(eq("S1"), any(), eq(1L))).thenReturn(false);
        when(feeCalculationService.validateFeeConfiguration(eq(1L), any(), eq("6")))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.fail("No FeeStructureRule configured"));

        service.generateForSchool(school, today, List.of(continuing));

        verify(studentFeesRepository, never()).save(any());
        verify(feeCalculationService, never()).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any());
        verify(studentOneTimeFeeChargedRepository, never()).save(any());
    }

    @Test
    void invalidConfigForOneClass_doesNotBlockGenerationForAnotherValidClassInTheSameSchool() {
        School school = school(1L, 4);
        LocalDate today = LocalDate.of(2026, 3, 1);
        // Different current classes -> different next classes ("5"->"6", "7"->"8") so
        // validateFeeConfiguration can be stubbed distinctly per resulting class.
        Student inBadClass = student("BAD1", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        Student inGoodClass = student("GOOD1", "7", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId(any(), any(), eq(1L))).thenReturn(false);
        when(feeCalculationService.validateFeeConfiguration(eq(1L), any(), eq("6")))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.fail("No rules for class 6"));
        when(feeCalculationService.validateFeeConfiguration(eq(1L), any(), eq("8")))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());

        service.generateForSchool(school, today, List.of(inBadClass, inGoodClass));

        verify(studentFeesRepository, never()).save(argThat(sf -> "BAD1".equals(sf.getStudentId())));
        verify(studentFeesRepository, times(12)).save(argThat(sf -> "GOOD1".equals(sf.getStudentId())));
    }
}
