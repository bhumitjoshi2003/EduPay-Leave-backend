package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AttendanceRow;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentAttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Absence emails: only explicit ABSENT rows on the school's own local date, never approved-leave absences. */
@ExtendWith(MockitoExtension.class)
class AttendanceEmailSchedulerTest {

    private static final long SCHOOL = 7L;
    private static final LocalDate LOCAL_TODAY = LocalDate.of(2026, 9, 25);

    @Mock private StudentAttendanceRepository rows;
    @Mock private StudentRepository students;
    @Mock private SchoolRepository schools;
    @Mock private AttendanceService attendanceService;
    @Mock private EmailService emailService;
    @InjectMocks private AttendanceEmailScheduler scheduler;

    private final School school = new School();

    @BeforeEach
    void setUp() {
        // 20:00 UTC on the 24th is already the 25th in the school's zone.
        ReflectionTestUtils.setField(scheduler, "clock", Clock.fixed(Instant.parse("2026-09-24T20:00:00Z"), ZoneOffset.UTC));
        school.setId(SCHOOL);
        school.setName("Test School");
        when(schools.findById(SCHOOL)).thenReturn(Optional.of(school));
        when(attendanceService.schoolToday(school)).thenReturn(LOCAL_TODAY);
    }

    @Test
    void emailsOnlyUnexplainedAbsencesOnTheSchoolLocalDate() {
        when(rows.findRowsWithStatusOnDates(eq(AttendanceStatus.ABSENT), anyCollection())).thenReturn(List.of(
                absent("S-ABSENT", LOCAL_TODAY),
                absent("S-LEAVE", LOCAL_TODAY),
                absent("S-YESTERDAY", LOCAL_TODAY.minusDays(1))));
        when(attendanceService.approvedLeaveKeys(SCHOOL, LOCAL_TODAY, LOCAL_TODAY))
                .thenReturn(Set.of(AttendanceService.leaveKey("S-LEAVE", LOCAL_TODAY)));
        when(students.findByStudentIdAndSchoolId("S-ABSENT", SCHOOL)).thenReturn(Optional.of(student("S-ABSENT", StudentStatus.ACTIVE)));

        scheduler.sendAttendanceEmails();

        verify(emailService).sendHtmlEmail(eq(EmailPurpose.NOTIFICATION), eq("S-ABSENT@example.test"), contains("Absence"), anyString());
        verify(students, never()).findByStudentIdAndSchoolId(eq("S-LEAVE"), anyLong());
        verify(students, never()).findByStudentIdAndSchoolId(eq("S-YESTERDAY"), anyLong());
        verifyNoMoreInteractions(emailService);
    }

    @Test
    void inactiveStudentsAreNotEmailed() {
        when(rows.findRowsWithStatusOnDates(eq(AttendanceStatus.ABSENT), anyCollection()))
                .thenReturn(List.of(absent("S-LEFT", LOCAL_TODAY)));
        when(attendanceService.approvedLeaveKeys(SCHOOL, LOCAL_TODAY, LOCAL_TODAY)).thenReturn(Set.of());
        when(students.findByStudentIdAndSchoolId("S-LEFT", SCHOOL)).thenReturn(Optional.of(student("S-LEFT", StudentStatus.WITHDRAWN)));

        scheduler.sendAttendanceEmails();

        verifyNoInteractions(emailService);
    }

    private static AttendanceRow absent(String studentId, LocalDate date) {
        return new AttendanceRow(1L, SCHOOL, studentId, date, AttendanceStatus.ABSENT, 3L, null);
    }

    private static Student student(String id, StudentStatus status) {
        Student s = new Student();
        s.setStudentId(id);
        s.setName(id);
        s.setStatus(status);
        s.setEmail(id + "@example.test");
        return s;
    }
}
