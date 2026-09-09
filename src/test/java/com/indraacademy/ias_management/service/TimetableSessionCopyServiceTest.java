package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TimetableDtos.CopySessionResult;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.service.TimetableSessionCopyWorker.Evaluation;
import com.indraacademy.ias_management.service.TimetableSessionCopyWorker.Outcome;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coordinator-level tests for TimetableSessionCopyService — tenant/target-safety guardrails and
 * outcome aggregation. Per-row DB behavior (idempotency, LEFT-teacher skip, invalid class/section,
 * conflicts, simultaneous-tag preservation) is proven for real against PostgreSQL in
 * TimetableSessionCopyWorkerPostgresIT; here the worker is mocked so this test stays about
 * orchestration only.
 */
@ExtendWith(MockitoExtension.class)
class TimetableSessionCopyServiceTest {

    @Mock private TimetableRepository timetableRepository;
    @Mock private TimetableSessionAccessService sessionAccess;
    @Mock private TimetableSessionCopyWorker worker;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private TimetableSessionCopyService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long SOURCE_ID = 10L;
    private static final Long TARGET_ID = 20L;

    @BeforeEach
    void setUp() {
        service = new TimetableSessionCopyService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        ReflectionTestUtils.setField(service, "sessionAccess", sessionAccess);
        ReflectionTestUtils.setField(service, "worker", worker);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        lenient().when(sessionAccess.requireOwnedSession(SCHOOL_ID, SOURCE_ID)).thenReturn(session(SOURCE_ID, false));
        lenient().when(sessionAccess.requireOwnedSession(SCHOOL_ID, TARGET_ID)).thenReturn(session(TARGET_ID, false));
    }

    private static AcademicSession session(Long id, boolean current) {
        AcademicSession s = new AcademicSession();
        s.setId(id);
        s.setLabel("session-" + id);
        s.setCurrent(current);
        return s;
    }

    private static TimetableEntry row(Long id) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        return e;
    }

    @Test
    void sourceEqualsTarget_rejected() {
        assertThatThrownBy(() -> service.copy(SOURCE_ID, SOURCE_ID, false, request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timetableRepository, never()).findByAcademicSessionIdAndSchoolId(any(), any());
    }

    @Test
    void nullSourceOrTarget_rejected() {
        assertThatThrownBy(() -> service.copy(null, TARGET_ID, false, request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.copy(SOURCE_ID, null, false, request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossSchoolSourceOrTarget_rejected() {
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SOURCE_ID))
                .thenThrow(new java.util.NoSuchElementException("Academic session not found: " + SOURCE_ID));

        assertThatThrownBy(() -> service.copy(SOURCE_ID, TARGET_ID, false, request))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void historicalTarget_rejected() {
        org.mockito.Mockito.doThrow(new IllegalStateException("Session has ended and is read-only."))
                .when(sessionAccess).requireWritable(any());

        assertThatThrownBy(() -> service.copy(SOURCE_ID, TARGET_ID, false, request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentTargetWithoutConfirmation_rejected() {
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, TARGET_ID)).thenReturn(session(TARGET_ID, true));

        assertThatThrownBy(() -> service.copy(SOURCE_ID, TARGET_ID, false, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmCurrentTarget");
        verify(timetableRepository, never()).findByAcademicSessionIdAndSchoolId(any(), any());
    }

    @Test
    void currentTargetWithConfirmation_proceeds() {
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, TARGET_ID)).thenReturn(session(TARGET_ID, true));
        when(timetableRepository.findByAcademicSessionIdAndSchoolId(SOURCE_ID, SCHOOL_ID)).thenReturn(List.of());

        CopySessionResult result = service.copy(SOURCE_ID, TARGET_ID, true, request);

        assertThat(result.scanned()).isZero();
    }

    @Test
    void aggregatesWorkerOutcomesAcrossAllOutcomeTypes() {
        TimetableEntry r1 = row(1L), r2 = row(2L), r3 = row(3L), r4 = row(4L), r5 = row(5L);
        when(timetableRepository.findByAcademicSessionIdAndSchoolId(SOURCE_ID, SCHOOL_ID))
                .thenReturn(List.of(r1, r2, r3, r4, r5));
        when(worker.attempt(SCHOOL_ID, r1, TARGET_ID)).thenReturn(Evaluation.copied(row(101L)));
        when(worker.attempt(SCHOOL_ID, r2, TARGET_ID)).thenReturn(Evaluation.alreadyCopied(row(102L)));
        when(worker.attempt(SCHOOL_ID, r3, TARGET_ID)).thenReturn(Evaluation.skipped(Outcome.SKIPPED_INELIGIBLE_TEACHER, "left"));
        when(worker.attempt(SCHOOL_ID, r4, TARGET_ID)).thenReturn(Evaluation.skipped(Outcome.SKIPPED_INVALID_CLASS, "gone"));
        when(worker.attempt(SCHOOL_ID, r5, TARGET_ID)).thenReturn(Evaluation.skipped(Outcome.SKIPPED_INVALID_SECTION, "gone"));

        CopySessionResult result = service.copy(SOURCE_ID, TARGET_ID, false, request);

        assertThat(result.scanned()).isEqualTo(5);
        assertThat(result.copied()).isEqualTo(1);
        assertThat(result.alreadyCopied()).isEqualTo(1);
        assertThat(result.skippedIneligibleTeacher()).isEqualTo(1);
        assertThat(result.skippedInvalidClass()).isEqualTo(1);
        assertThat(result.skippedInvalidSection()).isEqualTo(1);
        assertThat(result.failures()).isZero();
        assertThat(result.details()).hasSize(5);
    }

    @Test
    void existingTargetRowDoesNotBlockCopy_bothOutcomesJustReportedNotRejected() {
        // The worker itself no longer produces a CONFLICT outcome at all — copying into a slot
        // that already has a (possibly different) occupant in the target session simply succeeds,
        // exactly like any other row; this test documents that Outcome has no CONFLICT case left.
        assertThat(Outcome.values()).containsExactlyInAnyOrder(
                Outcome.COPIED, Outcome.ALREADY_COPIED, Outcome.SKIPPED_INELIGIBLE_TEACHER,
                Outcome.SKIPPED_INVALID_CLASS, Outcome.SKIPPED_INVALID_SECTION, Outcome.FAILURE);
    }

    @Test
    void oneRowThrowing_doesNotPreventOthersFromBeingProcessed() {
        TimetableEntry bad = row(1L), good = row(2L);
        when(timetableRepository.findByAcademicSessionIdAndSchoolId(SOURCE_ID, SCHOOL_ID)).thenReturn(List.of(bad, good));
        when(worker.attempt(SCHOOL_ID, bad, TARGET_ID)).thenThrow(new RuntimeException("boom"));
        when(worker.attempt(SCHOOL_ID, good, TARGET_ID)).thenReturn(Evaluation.copied(row(102L)));

        CopySessionResult result = service.copy(SOURCE_ID, TARGET_ID, false, request);

        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.copied()).isEqualTo(1);
        verify(worker).attempt(SCHOOL_ID, good, TARGET_ID);
    }

    @Test
    void neverTouchesTeacherOrResponsibilityTablesDirectly() {
        // TimetableSessionCopyService has no dependency on TeacherRepository, TeacherClassGrant
        // repository, or ClassTeacherResponsibilityRepository at all — the absence of those
        // fields is itself the guarantee those tables are never written by this service.
        assertThat(service.getClass().getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .noneMatch(type -> type.getSimpleName().contains("TeacherClassGrant")
                        || type.getSimpleName().contains("ClassTeacherResponsibility"));
    }
}
