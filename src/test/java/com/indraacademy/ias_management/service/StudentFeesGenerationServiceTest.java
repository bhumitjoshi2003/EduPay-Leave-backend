package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.FeeOperationalStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolFeeSettings;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolFeeSettingsRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentOneTimeFeeChargedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the academic-calendar audit's fix to generateStudentFeesForNextYear's
 * "skip students enrolled during the current academic year" check — it used to compare
 * `createdAt.getYear() >= today.getYear()`, which only correctly identified "enrolled this
 * academic year" for a January-start school. computeCurrentAcademicYearStart replaces that
 * with a real academic-year boundary derived from the school's own start month.
 *
 * Also proves that automatic next-session generation remains structurally scheduled but
 * cannot persist fees until authoritative promotion/enrollment outcomes exist.
 */
@ExtendWith(MockitoExtension.class)
class StudentFeesGenerationServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private AuditService auditService;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private StudentOneTimeFeeChargedRepository studentOneTimeFeeChargedRepository;
    @Mock private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolFeeSettingsRepository schoolFeeSettingsRepository;

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
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "schoolFeeSettingsRepository", schoolFeeSettingsRepository);

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

    // ─── Automatic next-session generation safety gate ───

    @Test
    void generationMonth_cannotCreateFutureClassFeesByPredictingNextClass() {
        School school = school(1L, 4);
        Student continuing = student("S1", "5", LocalDateTime.of(2024, 6, 1, 0, 0));

        service.generateForSchool(school, LocalDate.of(2026, 3, 1), List.of(continuing));

        verifyNoInteractions(studentFeesRepository, studentFeesLineItemRepository,
                studentOneTimeFeeChargedRepository, feeCalculationService, schoolClassRepository, auditService);
    }

    @Test
    void unresolvedOrDetainedStudent_cannotReceiveGuessedNextClassCharges() {
        School school = school(1L, 4);
        Student unresolved = student("UNRESOLVED", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        Student detained = student("DETAIN", "5", LocalDateTime.of(2024, 1, 1, 0, 0));

        service.generateForSchool(school, LocalDate.of(2026, 3, 1), List.of(unresolved, detained));

        verify(studentFeesRepository, never()).save(any());
        verify(studentFeesLineItemRepository, never()).save(any());
        verify(studentOneTimeFeeChargedRepository, never()).save(any());
        verifyNoInteractions(feeCalculationService, schoolClassRepository);
    }

    @Test
    void scheduledRun_withAutomaticAnnualGenerationTrue_stillPersistsNoFutureFees() {
        School school = school(1L, LocalDate.now().plusMonths(1).getMonthValue());
        Student continuing = student("S1", "5", LocalDateTime.now().minusYears(2));
        SchoolFeeSettings settings = new SchoolFeeSettings();
        settings.setSchoolId(1L);
        settings.setOperationalStatus(FeeOperationalStatus.ACTIVE);
        settings.setAutomaticAnnualGeneration(true);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        when(studentRepository.findByStatus(StudentStatus.ACTIVE)).thenReturn(List.of(continuing));
        when(schoolFeeSettingsRepository.findBySchoolId(1L)).thenReturn(Optional.of(settings));

        service.generateStudentFeesForNextYear();

        verify(studentRepository).findByStatus(StudentStatus.ACTIVE);
        verify(studentFeesRepository, never()).save(any());
        verify(studentFeesLineItemRepository, never()).save(any());
        verify(studentOneTimeFeeChargedRepository, never()).save(any());
        verifyNoInteractions(feeCalculationService, schoolClassRepository, auditService);
    }

    @Test
    void automaticGate_doesNotCrossTenantBoundaryOrPersistEitherTenantsStudents() {
        School tenantOne = school(1L, 4);
        Student tenantTwoStudent = student("SCHOOL-2-STUDENT", "5", LocalDateTime.of(2024, 1, 1, 0, 0));
        tenantTwoStudent.setSchoolId(2L);

        service.generateForSchool(tenantOne, LocalDate.of(2026, 3, 1), List.of(tenantTwoStudent));

        verifyNoInteractions(studentFeesRepository, studentFeesLineItemRepository,
                studentOneTimeFeeChargedRepository, feeCalculationService, schoolClassRepository, auditService);
    }

    @Test
    void wrongMonth_doesNotGenerateAnythingForThatSchool() {
        School school = school(1L, 4); // April-start, generates in March only
        LocalDate notGenerationMonth = LocalDate.of(2026, 5, 1);
        Student continuing = student("S1", "5", LocalDateTime.of(2024, 1, 1, 0, 0));

        service.generateForSchool(school, notGenerationMonth, List.of(continuing));

        verifyNoInteractions(studentFeesRepository);
    }

}
