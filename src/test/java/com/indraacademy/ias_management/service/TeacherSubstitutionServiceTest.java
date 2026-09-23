package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherSubstitutionServiceTest {
    @Mock TeacherSubstitutionRepository substitutions;
    @Mock TimetableRepository timetables;
    @Mock TeacherRepository teachers;
    @Mock TeacherLeaveRepository leaves;
    @Mock TeacherAttendanceRepository attendance;
    @Mock TimetableSessionAccessService sessions;
    @Mock SecurityUtil security;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock PermissionService permissions;
    @Mock HttpServletRequest http;

    TeacherSubstitutionService service;

    // 2026-09-24 is a Thursday.
    static final LocalDate DATE = LocalDate.of(2026, 9, 24);
    static final Long SCHOOL_ID = 1L;
    static final Long SESSION_ID = 10L;

    TimetableEntry entry;
    Teacher substituteTeacher;

    @BeforeEach
    void setup() {
        service = new TeacherSubstitutionService(substitutions, timetables, teachers, leaves, attendance,
                sessions, security, audit, events, permissions);

        lenient().when(security.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(security.getUsername()).thenReturn("A1");
        lenient().when(security.getRole()).thenReturn(Role.ADMIN);
        lenient().when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(permissions.getPermissionKeysForRole(anyString(), anyLong()))
                .thenReturn(List.of("TIMETABLE_EDIT"));

        entry = new TimetableEntry();
        entry.setId(100L);
        entry.setSchoolId(SCHOOL_ID);
        entry.setAcademicSessionId(SESSION_ID);
        entry.setDay(Day.THURSDAY);
        entry.setPeriodNumber(3);
        entry.setClassName("X");
        entry.setSectionName("A");
        entry.setSubjectName("Maths");
        entry.setStartTime("09:10");
        entry.setEndTime("09:50");
        entry.setTeacherId("T1");
        entry.setTeacherName("Mr Original");

        substituteTeacher = new Teacher();
        substituteTeacher.setTeacherId("T2");
        substituteTeacher.setSchoolId(SCHOOL_ID);
        substituteTeacher.setName("Ms Substitute");
        substituteTeacher.setStatus(TeacherStatus.ACTIVE);

        AcademicSession session = new AcademicSession();
        session.setId(SESSION_ID);
        lenient().when(sessions.currentSessionOrNull(SCHOOL_ID)).thenReturn(session);
        lenient().when(sessions.lockOwnedSession(eq(SCHOOL_ID), eq(SESSION_ID))).thenReturn(session);

        TeacherLeave onLeave = new TeacherLeave();
        onLeave.setTeacherId("T1");
        lenient().when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of(onLeave));
        lenient().when(attendance.findBySchoolIdAndDate(eq(SCHOOL_ID), any())).thenReturn(List.of());

        lenient().when(timetables.findByIdAndSchoolId(100L, SCHOOL_ID)).thenReturn(Optional.of(entry));
        lenient().when(timetables.lockById(100L, SCHOOL_ID)).thenReturn(Optional.of(entry));
        lenient().when(timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(eq(SESSION_ID), anyString(), eq(SCHOOL_ID)))
                .thenReturn(List.of());
        lenient().when(timetables.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of(entry));

        lenient().when(teachers.findByTeacherIdAndSchoolId("T2", SCHOOL_ID)).thenReturn(Optional.of(substituteTeacher));
        lenient().when(teachers.findByStatusAndSchoolId(TeacherStatus.ACTIVE, SCHOOL_ID)).thenReturn(List.of(substituteTeacher));

        lenient().when(substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(
                eq(SCHOOL_ID), eq(DATE), eq(100L), eq(TeacherSubstitutionStatus.ACTIVE))).thenReturn(Optional.empty());
        lenient().when(substitutions.findBySchoolIdAndDateAndStatus(SCHOOL_ID, DATE, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(List.of());
        lenient().when(substitutions.saveAndFlush(any())).thenAnswer(inv -> {
            TeacherSubstitution s = inv.getArgument(0);
            if (s.getId() == null) s.setId(500L);
            return s;
        });
    }

    private UpsertRequest request() {
        return new UpsertRequest(100L, DATE, "T2");
    }

    // ─── Authorization ───────────────────────────────────────────────────

    @Test
    void adminCanAssign() {
        Assignment result = service.assign(request(), http);
        assertThat(result.substituteTeacherId()).isEqualTo("T2");
        assertThat(result.status()).isEqualTo(TeacherSubstitutionStatus.ACTIVE);
    }

    @Test
    void permittedSubAdminCanAssign() {
        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        when(permissions.getPermissionKeysForRole(Role.SUB_ADMIN, SCHOOL_ID)).thenReturn(List.of("TIMETABLE_EDIT"));
        Assignment result = service.assign(request(), http);
        assertThat(result.substituteTeacherId()).isEqualTo("T2");
    }

    @Test
    void unauthorizedSubAdminIsDenied() {
        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        when(permissions.getPermissionKeysForRole(Role.SUB_ADMIN, SCHOOL_ID)).thenReturn(List.of("TIMETABLE_VIEW"));
        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permission");
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void permissionLookupFailureDeniesTheSubAdminMutation() {
        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        when(permissions.getPermissionKeysForRole(Role.SUB_ADMIN, SCHOOL_ID)).thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permission");
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void adminMutationSucceedsEvenIfPermissionLookupWouldFail() {
        when(security.getRole()).thenReturn(Role.ADMIN);
        Assignment result = service.assign(request(), http);
        assertThat(result.substituteTeacherId()).isEqualTo("T2");
        verify(permissions, never()).getPermissionKeysForRole(anyString(), anyLong());
    }

    // ─── Tenant isolation ────────────────────────────────────────────────

    @Test
    void changeIsDeniedForASubstitutionInAnotherSchool() {
        TeacherSubstitution other = new TeacherSubstitution();
        other.setId(999L);
        other.setSchoolId(2L); // different school than the caller's SCHOOL_ID
        when(substitutions.findById(999L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.change(999L, new ChangeRequest("T2"), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void cancelIsDeniedForASubstitutionInAnotherSchool() {
        TeacherSubstitution other = new TeacherSubstitution();
        other.setId(999L);
        other.setSchoolId(2L);
        when(substitutions.findById(999L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.cancel(999L, http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    // ─── Conflict rules ──────────────────────────────────────────────────

    @Test
    void originalTeacherCannotSubstituteThemselves() {
        UpsertRequest self = new UpsertRequest(100L, DATE, "T1");
        assertThatThrownBy(() -> service.assign(self, http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot cover their own period");
    }

    @Test
    void substituteWithATimetableConflictIsRejected() {
        TimetableEntry conflicting = new TimetableEntry();
        conflicting.setDay(Day.THURSDAY);
        conflicting.setPeriodNumber(3);
        when(timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T2", SCHOOL_ID))
                .thenReturn(List.of(conflicting));

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already teaching");
    }

    @Test
    void substituteAlreadyCoveringAnotherClassSamePeriodIsRejected() {
        TeacherSubstitution alreadyCovering = new TeacherSubstitution();
        alreadyCovering.setId(1L);
        alreadyCovering.setSubstituteTeacherId("T2");
        alreadyCovering.setPeriodNumber(3);
        when(substitutions.findBySchoolIdAndDateAndStatus(SCHOOL_ID, DATE, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(List.of(alreadyCovering));

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already teaching");
    }

    @Test
    void duplicateActiveSubstitutionForSamePeriodIsRejected() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        when(substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(
                SCHOOL_ID, DATE, 100L, TeacherSubstitutionStatus.ACTIVE)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already has an active substitute");
    }

    @Test
    void originalTeacherMustActuallyBeUnavailable() {
        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(attendance.findBySchoolIdAndDate(eq(SCHOOL_ID), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not unavailable");
    }

    // ─── Change / cancel behavior ────────────────────────────────────────

    @Test
    void changeReplacesTheSubstituteAndNotifiesBothTeachers() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(SESSION_ID);
        existing.setDate(DATE);
        existing.setTimetableEntryId(100L);
        existing.setSubstituteTeacherId("T2");
        existing.setStatus(TeacherSubstitutionStatus.ACTIVE);

        Teacher replacement = new Teacher();
        replacement.setTeacherId("T3");
        replacement.setSchoolId(SCHOOL_ID);
        replacement.setName("Mr Replacement");
        replacement.setStatus(TeacherStatus.ACTIVE);

        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(teachers.findByTeacherIdAndSchoolId("T3", SCHOOL_ID)).thenReturn(Optional.of(replacement));

        Assignment result = service.change(1L, new ChangeRequest("T3"), http);

        assertThat(result.substituteTeacherId()).isEqualTo("T3");
        verify(events, times(2)).publishEvent(any(TeacherSubstitutionNotificationEvent.class));
    }

    @Test
    void changeToTheSameTeacherIsRejected() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setSubstituteTeacherId("T2");
        existing.setStatus(TeacherSubstitutionStatus.ACTIVE);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.change(1L, new ChangeRequest("T2"), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void cancelMarksTheSubstitutionCancelledAndNotifiesTheSubstitute() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(SESSION_ID);
        existing.setSubstituteTeacherId("T2");
        existing.setStatus(TeacherSubstitutionStatus.ACTIVE);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        Assignment result = service.cancel(1L, http);

        assertThat(result.status()).isEqualTo(TeacherSubstitutionStatus.CANCELLED);
        verify(events, times(1)).publishEvent(any(TeacherSubstitutionNotificationEvent.class));
    }

    @Test
    void cancellingAnAlreadyCancelledSubstitutionIsRejected() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setStatus(TeacherSubstitutionStatus.CANCELLED);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.cancel(1L, http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already cancelled");
    }

    // ─── Post-commit notification event ─────────────────────────────────

    @Test
    void assignPublishesExactlyOnePostCommitNotificationEvent() {
        service.assign(request(), http);
        verify(events, times(1)).publishEvent(any(TeacherSubstitutionNotificationEvent.class));
    }

    // ─── Teacher's own assignments ("mine") ─────────────────────────────

    @Test
    void teacherSeesTheirOwnCoverClassesForTheDate() {
        TeacherSubstitution mine = new TeacherSubstitution();
        mine.setId(1L);
        mine.setSubstituteTeacherId("T2");
        mine.setPeriodNumber(3);
        when(security.getUsername()).thenReturn("T2");
        when(substitutions.findBySchoolIdAndSubstituteTeacherIdAndDateAndStatusOrderByPeriodNumberAsc(
                SCHOOL_ID, "T2", DATE, TeacherSubstitutionStatus.ACTIVE)).thenReturn(List.of(mine));

        List<Assignment> result = service.mine(DATE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).substituteTeacherId()).isEqualTo("T2");
    }
}
