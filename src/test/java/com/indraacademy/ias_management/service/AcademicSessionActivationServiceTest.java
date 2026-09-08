package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AcademicSessionDto;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationApplyResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationState;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.SessionActivationOutcome;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase G: "Make Current" now bridges to class-teacher activation in one step. Proves the
 * orchestration itself — sequencing, the already-current no-op, and exception propagation on a
 * failed activation — without re-testing ClassTeacherActivationService's own diff/replacement
 * logic (already covered exhaustively in ClassTeacherActivationServiceTest).
 */
@ExtendWith(MockitoExtension.class)
class AcademicSessionActivationServiceTest {

    @Mock private TimetableSessionAccessService sessionAccess;
    @Mock private ClassTeacherActivationService activationService;
    @Mock private AcademicSessionService academicSessionService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private AcademicSessionActivationService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long TARGET_SESSION_ID = 2L;

    @BeforeEach
    void setUp() {
        service = new AcademicSessionActivationService();
        ReflectionTestUtils.setField(service, "sessionAccess", sessionAccess);
        ReflectionTestUtils.setField(service, "activationService", activationService);
        ReflectionTestUtils.setField(service, "academicSessionService", academicSessionService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    private AcademicSession session(Long id, boolean current) {
        AcademicSession s = new AcademicSession();
        s.setId(id);
        s.setSchoolId(SCHOOL_ID);
        s.setCurrent(current);
        return s;
    }

    private AcademicSessionDto dto(Long id, boolean current) {
        return new AcademicSessionDto(id, "S" + id, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), current);
    }

    private ActivationApplyResult applyResult(int applied, int cleared, int unchanged) {
        return new ActivationApplyResult(TARGET_SESSION_ID, ActivationState.APPLIED_IN_SYNC, null, "admin",
                applied, cleared, unchanged, 0, 0, List.of());
    }

    @Test
    void makesFutureSessionCurrent_thenActivatesItsConfiguration() {
        AcademicSession target = session(TARGET_SESSION_ID, false);
        when(sessionAccess.lockOwnedSession(SCHOOL_ID, TARGET_SESSION_ID)).thenReturn(target);
        when(academicSessionService.setCurrentSession(TARGET_SESSION_ID)).thenReturn(dto(TARGET_SESSION_ID, true));
        when(activationService.apply(request)).thenReturn(applyResult(3, 1, 2));

        SessionActivationOutcome outcome = service.setCurrentSessionAndActivate(TARGET_SESSION_ID, request);

        assertThat(outcome.activationPerformed()).isTrue();
        assertThat(outcome.session().isCurrent()).isTrue();
        assertThat(outcome.activation().applied()).isEqualTo(3);
        assertThat(outcome.activation().cleared()).isEqualTo(1);
        verify(academicSessionService).setCurrentSession(TARGET_SESSION_ID);
        verify(activationService).apply(request);
    }

    @Test
    void alreadyCurrentSession_isAGenuineNoOp_doesNotSwitchOrActivate() {
        AcademicSession target = session(TARGET_SESSION_ID, true);
        when(sessionAccess.lockOwnedSession(SCHOOL_ID, TARGET_SESSION_ID)).thenReturn(target);
        when(academicSessionService.toDto(target)).thenReturn(dto(TARGET_SESSION_ID, true));

        SessionActivationOutcome outcome = service.setCurrentSessionAndActivate(TARGET_SESSION_ID, request);

        assertThat(outcome.activationPerformed()).isFalse();
        assertThat(outcome.activation()).isNull();
        verify(academicSessionService, never()).setCurrentSession(any());
        verify(activationService, never()).apply(any());
    }

    @Test
    void activationFailure_propagatesOutOfTheOrchestrationCall() {
        AcademicSession target = session(TARGET_SESSION_ID, false);
        when(sessionAccess.lockOwnedSession(SCHOOL_ID, TARGET_SESSION_ID)).thenReturn(target);
        when(academicSessionService.setCurrentSession(TARGET_SESSION_ID)).thenReturn(dto(TARGET_SESSION_ID, true));
        when(activationService.apply(request)).thenThrow(new IllegalStateException("Teacher disappeared during activation"));

        // The exception must propagate uncaught — that is what triggers Spring's @Transactional
        // rollback of the session switch in production (proven against a real transaction in
        // AcademicSessionActivationPostgresIT).
        assertThatThrownBy(() -> service.setCurrentSessionAndActivate(TARGET_SESSION_ID, request))
                .isInstanceOf(IllegalStateException.class);
        verify(academicSessionService, times(1)).setCurrentSession(TARGET_SESSION_ID);
    }

    @Test
    void surfacesIneligibleTeacherCountsWithoutThrowing() {
        AcademicSession target = session(TARGET_SESSION_ID, false);
        when(sessionAccess.lockOwnedSession(SCHOOL_ID, TARGET_SESSION_ID)).thenReturn(target);
        when(academicSessionService.setCurrentSession(TARGET_SESSION_ID)).thenReturn(dto(TARGET_SESSION_ID, true));
        ActivationApplyResult resultWithIssues = new ActivationApplyResult(TARGET_SESSION_ID, ActivationState.APPLIED_IN_SYNC,
                null, "admin", 0, 0, 0, 2, 1, List.of());
        when(activationService.apply(request)).thenReturn(resultWithIssues);

        SessionActivationOutcome outcome = service.setCurrentSessionAndActivate(TARGET_SESSION_ID, request);

        assertThat(outcome.activationPerformed()).isTrue();
        assertThat(outcome.activation().ineligibleTeacher()).isEqualTo(2);
        assertThat(outcome.activation().invalidClassOrSection()).isEqualTo(1);
    }

    @Test
    void tenantOwnershipIsValidatedBeforeAnyWrite() {
        when(sessionAccess.lockOwnedSession(SCHOOL_ID, TARGET_SESSION_ID))
                .thenThrow(new java.util.NoSuchElementException("Academic session not found: " + TARGET_SESSION_ID));

        assertThatThrownBy(() -> service.setCurrentSessionAndActivate(TARGET_SESSION_ID, request))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(academicSessionService, never()).setCurrentSession(any());
        verify(activationService, never()).apply(any());
    }
}
