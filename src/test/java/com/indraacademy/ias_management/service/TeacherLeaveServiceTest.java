package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherLeaveApplyRequest;
import com.indraacademy.ias_management.dto.TeacherLeaveResponse;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherLeave;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers TeacherLeaveService's apply/updateStatus/cancel — mirrors LeaveServiceTest's coverage of
 * the same guard shapes (locked read, same-status-repeat rejection, tenant check), plus the
 * self-cancel rule specific to teacher leave (own request, only while PENDING).
 */
@ExtendWith(MockitoExtension.class)
class TeacherLeaveServiceTest {

    @Mock private TeacherLeaveRepository teacherLeaveRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private HttpServletRequest request;

    private TeacherLeaveService service;

    private static final Long SCHOOL_ID = 2L;
    private static final Long LEAVE_ID = 1L;
    private static final String TEACHER_ID = "T1";

    @BeforeEach
    void setUp() {
        service = new TeacherLeaveService();
        ReflectionTestUtils.setField(service, "teacherLeaveRepository", teacherLeaveRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn(TEACHER_ID);
        lenient().when(securityUtil.getRole()).thenReturn("TEACHER");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    private TeacherLeave leave(LeaveStatus status) {
        TeacherLeave l = new TeacherLeave();
        l.setId(LEAVE_ID);
        l.setSchoolId(SCHOOL_ID);
        l.setTeacherId(TEACHER_ID);
        l.setTeacherName("Test Teacher");
        l.setStartDate(LocalDate.of(2026, 8, 20));
        l.setEndDate(LocalDate.of(2026, 8, 22));
        l.setReason("Test reason");
        l.setStatus(status);
        return l;
    }

    private Teacher teacher() {
        Teacher t = new Teacher();
        t.setTeacherId(TEACHER_ID);
        t.setSchoolId(SCHOOL_ID);
        t.setName("Test Teacher");
        return t;
    }

    // ---- applyLeave ----

    @Test
    void applyLeave_persistsAsPending_andResolvesTeacherIdFromSecurityContext_neverFromInput() {
        TeacherLeaveApplyRequest req = new TeacherLeaveApplyRequest();
        req.setStartDate(LocalDate.of(2026, 8, 20));
        req.setEndDate(LocalDate.of(2026, 8, 22));
        req.setReason("Family event");

        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher()));
        when(teacherLeaveRepository.save(any(TeacherLeave.class))).thenAnswer(inv -> {
            TeacherLeave saved = inv.getArgument(0);
            saved.setId(LEAVE_ID);
            return saved;
        });

        TeacherLeaveResponse response = service.applyLeave(req, request);

        assertThat(response.getStatus()).isEqualTo(LeaveStatus.PENDING);
        assertThat(response.getTeacherId()).isEqualTo(TEACHER_ID);
        verify(teacherLeaveRepository).save(argThat(l -> l.getTeacherId().equals(TEACHER_ID) && l.getSchoolId().equals(SCHOOL_ID)));
        verify(auditService).log(eq(TEACHER_ID), anyString(), eq("APPLY_TEACHER_LEAVE"), eq("TeacherLeave"), anyString(), any(), anyString(), anyString());
    }

    @Test
    void applyLeave_rejectsEndDateBeforeStartDate() {
        TeacherLeaveApplyRequest req = new TeacherLeaveApplyRequest();
        req.setStartDate(LocalDate.of(2026, 8, 22));
        req.setEndDate(LocalDate.of(2026, 8, 20));
        req.setReason("Bad range");

        assertThatThrownBy(() -> service.applyLeave(req, request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(teacherLeaveRepository);
    }

    @Test
    void applyLeave_rejectsBlankReason() {
        TeacherLeaveApplyRequest req = new TeacherLeaveApplyRequest();
        req.setStartDate(LocalDate.of(2026, 8, 20));
        req.setEndDate(LocalDate.of(2026, 8, 22));
        req.setReason("   ");

        assertThatThrownBy(() -> service.applyLeave(req, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- updateStatus ----

    @Test
    void updateStatus_pendingToApproved_succeeds() {
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.PENDING)));
        when(teacherLeaveRepository.save(any(TeacherLeave.class))).thenAnswer(inv -> inv.getArgument(0));

        TeacherLeaveResponse updated = service.updateStatus(LEAVE_ID, LeaveStatus.APPROVED, request);

        assertThat(updated.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(notificationService).createAutoGeneratedIndividualNotification(
                anyString(), anyString(), eq("Teacher_Leave_Status_APPROVED"), eq(TEACHER_ID), anyString(), anyString());
    }

    /** Same "block only true repeats" policy as student Leave — a double-approve is a no-op error. */
    @Test
    void updateStatus_reapprovingAnAlreadyApprovedLeave_isRejectedAsARepeat() {
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.APPROVED)));

        assertThatThrownBy(() -> service.updateStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(InvalidLeaveStatusTransitionException.class);

        verify(teacherLeaveRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    /** Reversal (APPROVED -> REJECTED) stays legal, mirroring student Leave's confirmed policy. */
    @Test
    void updateStatus_approvedToRejected_stillSucceeds_theReversalCase() {
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.APPROVED)));
        when(teacherLeaveRepository.save(any(TeacherLeave.class))).thenAnswer(inv -> inv.getArgument(0));

        TeacherLeaveResponse updated = service.updateStatus(LEAVE_ID, LeaveStatus.REJECTED, request);

        assertThat(updated.getStatus()).isEqualTo(LeaveStatus.REJECTED);
    }

    @Test
    void updateStatus_tenantMismatch_rejected() {
        TeacherLeave otherSchool = leave(LeaveStatus.PENDING);
        otherSchool.setSchoolId(999L);
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(otherSchool));

        assertThatThrownBy(() -> service.updateStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(SecurityException.class);

        verify(teacherLeaveRepository, never()).save(any());
    }

    @Test
    void updateStatus_usesTheLockedFinder_notThePlainFindById() {
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.PENDING)));
        when(teacherLeaveRepository.save(any(TeacherLeave.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(LEAVE_ID, LeaveStatus.APPROVED, request);

        verify(teacherLeaveRepository).findByIdForUpdate(LEAVE_ID);
        verify(teacherLeaveRepository, never()).findById(any());
    }

    @Test
    void updateStatus_missingLeave_throwsNoSuchElement() {
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ---- cancelLeave ----

    @Test
    void cancelLeave_ownPendingRequest_succeeds() {
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.PENDING)));

        service.cancelLeave(LEAVE_ID, request);

        verify(teacherLeaveRepository).deleteById(LEAVE_ID);
        verify(auditService).log(eq(TEACHER_ID), eq("TEACHER"), eq("CANCEL_TEACHER_LEAVE"), eq("TeacherLeave"), anyString(), anyString(), any(), anyString());
    }

    @Test
    void cancelLeave_notOwnRequest_rejectedForTeacherRole() {
        TeacherLeave someoneElses = leave(LeaveStatus.PENDING);
        someoneElses.setTeacherId("T2");
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.cancelLeave(LEAVE_ID, request))
                .isInstanceOf(SecurityException.class);

        verify(teacherLeaveRepository, never()).deleteById(any());
    }

    @Test
    void cancelLeave_alreadyApproved_rejectedForTeacherRole_mustGoThroughAdmin() {
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.APPROVED)));

        assertThatThrownBy(() -> service.cancelLeave(LEAVE_ID, request))
                .isInstanceOf(IllegalStateException.class);

        verify(teacherLeaveRepository, never()).deleteById(any());
    }

    @Test
    void cancelLeave_adminCanCancelAnyStatus_unrestrictedPrecedent() {
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.APPROVED)));

        service.cancelLeave(LEAVE_ID, request);

        verify(teacherLeaveRepository).deleteById(LEAVE_ID);
    }

    @Test
    void cancelLeave_usesTheLockedFinder_notThePlainFindByIdAndSchoolId() {
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.PENDING)));

        service.cancelLeave(LEAVE_ID, request);

        verify(teacherLeaveRepository).findByIdForUpdate(LEAVE_ID);
        verify(teacherLeaveRepository, never()).findByIdAndSchoolId(any(), any());
    }

    @Test
    void cancelLeave_tenantMismatch_rejected() {
        TeacherLeave otherSchool = leave(LeaveStatus.PENDING);
        otherSchool.setSchoolId(999L);
        when(teacherLeaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(otherSchool));

        assertThatThrownBy(() -> service.cancelLeave(LEAVE_ID, request))
                .isInstanceOf(SecurityException.class);

        verify(teacherLeaveRepository, never()).deleteById(any());
    }
}
