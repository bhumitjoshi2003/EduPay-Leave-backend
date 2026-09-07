package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.indraacademy.ias_management.service.StudentTemporalMembershipResolver.CoverageClassification.*;
import static com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE;
import static com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentTemporalMembershipResolverTest {

    private static final long SCHOOL = 1L;
    private static final long SESSION = 2L;
    private static final String STUDENT = "S1";
    private static final LocalDate SESSION_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate SESSION_END = LocalDate.of(2027, 3, 31);

    @Mock private StudentRepository studentRepository;
    @Mock private AcademicSessionRepository sessionRepository;
    @Mock private StudentEnrollmentRepository enrollmentRepository;

    private StudentTemporalMembershipResolver resolver;
    private AcademicSession session;

    @BeforeEach
    void setUp() {
        resolver = new StudentTemporalMembershipResolver(
                studentRepository, sessionRepository, enrollmentRepository);
        Student student = new Student();
        student.setStudentId(STUDENT);
        student.setSchoolId(SCHOOL);
        lenient().when(studentRepository.findByStudentIdAndSchoolId(STUDENT, SCHOOL))
                .thenReturn(Optional.of(student));
        session = new AcademicSession();
        session.setId(SESSION);
        session.setSchoolId(SCHOOL);
        session.setLabel("2026-2027");
        session.setStartDate(SESSION_START);
        session.setEndDate(SESSION_END);
        lenient().when(sessionRepository.findByIdAndSchoolId(SESSION, SCHOOL)).thenReturn(Optional.of(session));
    }

    @Test
    void activeAndClosedSegmentsAreEffectiveOnInclusiveBoundaries() {
        StudentEnrollment active = enrollment(10L, ACTIVE, LocalDate.of(2026, 4, 1), null, 9L, 90L);
        StudentEnrollment closed = enrollment(11L, CLOSED, LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 8, 10), 9L, 90L);
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT))
                .thenReturn(List.of(active));
        when(enrollmentRepository.findRealizedEffectiveEnrollments(SCHOOL, STUDENT, SESSION,
                LocalDate.of(2026, 4, 1))).thenReturn(List.of(active));

        var activeResult = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL, STUDENT, SESSION, LocalDate.of(2026, 4, 1));
        assertThat(activeResult.classification()).isEqualTo(ENROLLMENT_BACKED);
        assertThat(activeResult.segment().status()).isEqualTo(ACTIVE);

        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT)).thenReturn(List.of(closed));
        when(enrollmentRepository.findRealizedEffectiveEnrollments(SCHOOL, STUDENT, SESSION,
                closed.getEffectiveFrom())).thenReturn(List.of(closed));
        when(enrollmentRepository.findRealizedEffectiveEnrollments(SCHOOL, STUDENT, SESSION,
                closed.getEffectiveUntil())).thenReturn(List.of(closed));

        assertThat(resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL, STUDENT, SESSION, closed.getEffectiveFrom()).segment().status()).isEqualTo(CLOSED);
        assertThat(resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL, STUDENT, SESSION, closed.getEffectiveUntil()).segment().enrollmentId()).isEqualTo(11L);
    }

    @Test
    void plannedAndCancelledNeverBecomeFactualMembershipOrAdoptionBoundary() {
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT)).thenReturn(List.of());
        LocalDate date = LocalDate.of(2026, 9, 1);
        when(enrollmentRepository.findRealizedEffectiveEnrollments(SCHOOL, STUDENT, SESSION, date))
                .thenReturn(List.of());

        var result = resolver.resolveEffectiveRealizedEnrollment(SCHOOL, STUDENT, SESSION, date);

        assertThat(result.classification()).isEqualTo(LEGACY_UNCOVERED);
        assertThat(result.adoptionBoundary()).isNull();
        assertThat(result.legacyFallbackPermitted()).isTrue();
        verify(enrollmentRepository, never()).findEffectiveEnrollment(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void beforeAdoptionIsLegacyButExitReadmissionGapIsAuthoritative() {
        LocalDate adoption = LocalDate.of(2026, 6, 1);
        StudentEnrollment first = enrollment(10L, CLOSED, adoption,
                LocalDate.of(2026, 8, 10), 9L, 90L);
        StudentEnrollment readmitted = enrollment(11L, ACTIVE,
                LocalDate.of(2026, 9, 1), null, 10L, 100L);
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT))
                .thenReturn(List.of(first, readmitted));
        when(enrollmentRepository.findRealizedEffectiveEnrollments(eq(SCHOOL), eq(STUDENT), eq(SESSION), any()))
                .thenReturn(List.of());

        var before = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL, STUDENT, SESSION, LocalDate.of(2026, 5, 31));
        var gap = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL, STUDENT, SESSION, LocalDate.of(2026, 8, 20));

        assertThat(before.classification()).isEqualTo(LEGACY_UNCOVERED);
        assertThat(before.legacyFallbackPermitted()).isTrue();
        assertThat(gap.classification()).isEqualTo(AUTHORITATIVE_GAP);
        assertThat(gap.legacyFallbackPermitted()).isFalse();
    }

    @Test
    void rangePartitionsClassAndSectionTransitionsAndPreservesGap() {
        StudentEnrollment classNineA = enrollment(10L, CLOSED,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), 9L, 91L);
        StudentEnrollment classTenA = enrollment(11L, CLOSED,
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 20), 10L, 101L);
        StudentEnrollment classTenB = enrollment(12L, ACTIVE,
                LocalDate.of(2026, 8, 23), null, 10L, 102L);
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT))
                .thenReturn(List.of(classNineA, classTenA, classTenB));
        when(enrollmentRepository.findRealizedOverlappingRange(SCHOOL, STUDENT, SESSION,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of(classNineA, classTenA, classTenB));

        var result = resolver.resolveRealizedEnrollmentRange(SCHOOL, STUDENT, SESSION,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(result.classification()).isEqualTo(ENROLLMENT_BACKED);
        assertThat(result.intersections()).hasSize(3);
        assertThat(result.intersections().get(0).intersectedTo()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(result.intersections().get(1).segment().classId()).isEqualTo(10L);
        assertThat(result.intersections().get(2).segment().sectionId()).isEqualTo(102L);
        assertThat(result.uncoveredIntervals()).singleElement().satisfies(gap -> {
            assertThat(gap.from()).isEqualTo(LocalDate.of(2026, 8, 21));
            assertThat(gap.to()).isEqualTo(LocalDate.of(2026, 8, 22));
            assertThat(gap.classification()).isEqualTo(AUTHORITATIVE_GAP);
            assertThat(gap.legacyFallbackPermitted()).isFalse();
        });
    }

    @Test
    void promotionBetweenSessionsKeepsEachTenantSessionIndependent() {
        long priorSession = 3L;
        AcademicSession prior = new AcademicSession();
        prior.setId(priorSession);
        prior.setSchoolId(SCHOOL);
        prior.setLabel("2025-2026");
        prior.setStartDate(LocalDate.of(2025, 4, 1));
        prior.setEndDate(LocalDate.of(2026, 3, 31));
        when(sessionRepository.findByIdAndSchoolId(priorSession, SCHOOL)).thenReturn(Optional.of(prior));
        StudentEnrollment old = enrollment(20L, CLOSED, prior.getStartDate(), prior.getEndDate(), 9L, 91L);
        old.setAcademicSessionId(priorSession);
        StudentEnrollment current = enrollment(21L, ACTIVE, SESSION_START, null, 10L, 101L);
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT)).thenReturn(List.of(old, current));
        when(enrollmentRepository.findRealizedByStudentAndSession(SCHOOL, STUDENT, priorSession))
                .thenReturn(List.of(old));
        when(enrollmentRepository.findRealizedByStudentAndSession(SCHOOL, STUDENT, SESSION))
                .thenReturn(List.of(current));

        assertThat(resolver.realizedEnrollmentSegmentsForSession(SCHOOL, STUDENT, priorSession)
                .segments()).extracting(StudentTemporalMembershipResolver.Segment::classId).containsExactly(9L);
        assertThat(resolver.realizedEnrollmentSegmentsForSession(SCHOOL, STUDENT, SESSION)
                .segments()).extracting(StudentTemporalMembershipResolver.Segment::classId).containsExactly(10L);
    }

    @Test
    void overlappingDirtyResultsFailClosedAndReturnNoSegments() {
        StudentEnrollment first = enrollment(10L, CLOSED, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), 9L, 91L);
        StudentEnrollment second = enrollment(11L, ACTIVE, LocalDate.of(2026, 8, 10),
                null, 10L, 101L);
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT)).thenReturn(List.of(first, second));
        when(enrollmentRepository.findRealizedOverlappingRange(SCHOOL, STUDENT, SESSION,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of(first, second));

        var result = resolver.resolveRealizedEnrollmentRange(SCHOOL, STUDENT, SESSION,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(result.classification()).isEqualTo(CONFLICT);
        assertThat(result.intersections()).isEmpty();
        assertThat(result.legacyFallbackPermitted()).isFalse();
    }

    @Test
    void repositoryResultOutsideRequestedTenantContextFailsClosed() {
        StudentEnrollment wrongTenant = enrollment(10L, ACTIVE,
                LocalDate.of(2026, 8, 1), null, 9L, 91L);
        wrongTenant.setSchoolId(999L);
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT)).thenReturn(List.of());
        when(enrollmentRepository.findRealizedEffectiveEnrollments(
                SCHOOL, STUDENT, SESSION, LocalDate.of(2026, 8, 1)))
                .thenReturn(List.of(wrongTenant));

        var result = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL, STUDENT, SESSION, LocalDate.of(2026, 8, 1));

        assertThat(result.classification()).isEqualTo(CONFLICT);
        assertThat(result.segment()).isNull();
        assertThat(result.legacyFallbackPermitted()).isFalse();
    }

    @Test
    void dateResolutionRejectsAmbiguousSessionAndCrossTenantSession() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        when(sessionRepository.findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                SCHOOL, date, date)).thenReturn(List.of(session, session));

        assertThatThrownBy(() -> resolver.resolveEffectiveRealizedEnrollment(SCHOOL, STUDENT, date))
                .isInstanceOf(StudentTemporalMembershipResolver.TemporalMembershipConflictException.class);
        when(sessionRepository.findByIdAndSchoolId(999L, SCHOOL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL, STUDENT, 999L, date)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void resolverPerformsNoWrites() {
        when(enrollmentRepository.findRealizedHistory(SCHOOL, STUDENT)).thenReturn(List.of());
        when(enrollmentRepository.findRealizedByStudentAndSession(SCHOOL, STUDENT, SESSION))
                .thenReturn(List.of());

        resolver.realizedEnrollmentSegmentsForSession(SCHOOL, STUDENT, SESSION);

        verify(enrollmentRepository, never()).save(any());
        verify(enrollmentRepository, never()).saveAll(any());
        verify(enrollmentRepository, never()).delete(any());
        verify(studentRepository, never()).save(any());
        verify(sessionRepository).findByIdAndSchoolId(SESSION, SCHOOL);
    }

    @Test
    void sessionLabelsAreResolvedAsStoredAndTenantScoped() {
        when(sessionRepository.findBySchoolIdAndLabel(SCHOOL, "2026-2027"))
                .thenReturn(Optional.of(session));

        var result = resolver.resolveTenantSession(SCHOOL, "2026-2027");

        assertThat(result.id()).isEqualTo(SESSION);
        assertThat(result.label()).isEqualTo("2026-2027");
        verify(sessionRepository).findBySchoolIdAndLabel(SCHOOL, "2026-2027");
    }

    private StudentEnrollment enrollment(long id, StudentEnrollmentStatus status,
                                         LocalDate from, LocalDate until,
                                         long classId, Long sectionId) {
        StudentEnrollment row = new StudentEnrollment();
        row.setId(id);
        row.setSchoolId(SCHOOL);
        row.setStudentId(STUDENT);
        row.setAcademicSessionId(SESSION);
        row.setClassId(classId);
        row.setClassNameSnapshot("Class " + classId);
        row.setSectionId(sectionId);
        row.setSectionNameSnapshot(sectionId == null ? null : "Section " + sectionId);
        row.setStatus(status);
        row.setEffectiveFrom(from);
        row.setEffectiveUntil(until);
        return row;
    }
}
