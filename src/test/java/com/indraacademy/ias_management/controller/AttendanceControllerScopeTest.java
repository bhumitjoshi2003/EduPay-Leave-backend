package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.AbsenceChargeService;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Read-side access rules of the Attendance V2 summary endpoints. */
@ExtendWith(MockitoExtension.class)
class AttendanceControllerScopeTest {

    @Mock private AttendanceService attendanceService;
    @Mock private AbsenceChargeService absenceChargeService;
    @Mock private AuthService authService;
    @Mock private StudentRepository studentRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private TeacherClassScopeService teacherClassScopeService;
    @InjectMocks private AttendanceController controller;

    @Test
    void studentsAndParentsCannotReadClassSummariesOrAbsentees() {
        when(authService.getRole()).thenReturn("STUDENT", "PARENT", "STUDENT", "PARENT");
        assertThat(controller.getClassAttendanceSummary("8", "month", 9, 2026, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.getClassAttendanceSummary("8", "month", 9, 2026, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.getConsecutiveAbsentees("8", 3, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.getConsecutiveAbsentees("8", 3, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(attendanceService);
    }

    @Test
    void teacherClassSummaryIsForcedToTheirOwnSection() {
        when(authService.getRole()).thenReturn("TEACHER");
        when(authService.getUserId()).thenReturn("T1");
        when(securityUtil.getSchoolId()).thenReturn(4L);
        when(teacherClassScopeService.authorizeAndScopeToClass("TEACHER", "T1", 4L, "8", 99L)).thenReturn(ScopedAccess.allow(11L));
        when(attendanceService.getClassSummary("8", "month", 9, 2026, null, 11L)).thenReturn(List.of());

        assertThat(controller.getClassAttendanceSummary("8", "month", 9, 2026, null, 99L).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(attendanceService).getClassSummary("8", "month", 9, 2026, null, 11L);
    }

    @Test
    void teacherIsDeniedAnotherClass() {
        when(authService.getRole()).thenReturn("TEACHER");
        when(authService.getUserId()).thenReturn("T1");
        when(securityUtil.getSchoolId()).thenReturn(4L);
        when(teacherClassScopeService.authorizeAndScopeToClass(any(), any(), any(), eq("9"), isNull()))
                .thenReturn(ScopedAccess.deny("Not your class"));

        assertThat(controller.getConsecutiveAbsentees("9", 3, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(attendanceService);
    }

    @Test
    void studentCanOnlyReadOwnFiguresAndFeeCount() {
        when(authService.getRole()).thenReturn("STUDENT");
        when(authService.getUserId()).thenReturn("S1");
        assertThat(controller.getStudentAttendanceSummary("S2", "month", 9, 2026, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.getTotalUnappliedLeaveCount("S2", "2026-2027").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        when(absenceChargeService.countChargeable("S1", "2026-2027")).thenReturn(2L);
        assertThat(controller.getTotalUnappliedLeaveCount("S1", "2026-2027").getBody()).isEqualTo(2L);
    }

    @Test
    void teacherCannotReadAStudentOutsideTheirSection() {
        when(authService.getRole()).thenReturn("TEACHER");
        when(authService.getUserId()).thenReturn("T1");
        when(securityUtil.getSchoolId()).thenReturn(4L);
        Student other = new Student();
        other.setClassName("9");
        other.setSectionId(20L);
        when(studentRepository.findByStudentIdAndSchoolId("S9", 4L)).thenReturn(Optional.of(other));
        when(teacherClassScopeService.authorizeAndScopeToStudent("TEACHER", "T1", 4L, "9", 20L))
                .thenReturn(ScopedAccess.deny("Not your student"));

        assertThat(controller.getDailyAttendance("S9", 9, 2026).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(attendanceService);
    }

    @Test
    void unreadableSubmissionIsABadRequestNotAServerError() {
        var response = controller.handleUnreadableBody(new org.springframework.http.converter.HttpMessageNotReadableException(
                "Cannot deserialize value of type AttendanceStatus from String \"HALF_DAY\"",
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message",
                "Invalid attendance request. Each student's status must be PRESENT or ABSENT.");
    }
}
