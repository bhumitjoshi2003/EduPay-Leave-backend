package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.StaffAdoptionResponse;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.TeacherAttendanceRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.repository.UserSessionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffAdoptionServiceTest {
    @Mock TeacherRepository teachers;
    @Mock UserRepository users;
    @Mock UserSessionRepository sessions;
    @Mock TeacherAttendanceRepository attendance;
    @Mock SecurityUtil security;
    private StaffAdoptionService service;

    @BeforeEach
    void setUp() {
        service = new StaffAdoptionService(teachers, users, sessions, attendance, security);
        when(security.getSchoolId()).thenReturn(42L);
    }

    @Test
    void emptySchoolReturnsZeroSummaryWithoutAggregateQueries() {
        when(teachers.findBySchoolId(42L)).thenReturn(List.of());

        StaffAdoptionResponse response = service.getStaffAdoption();

        assertThat(response.summary().totalTeachers()).isZero();
        assertThat(response.teachers()).isEmpty();
        verifyNoInteractions(users, sessions, attendance);
    }

    @Test
    void classifiesStartedNeverStartedPendingDisabledAndSelfAttendanceWithoutDuplicates() {
        Teacher started = teacher("T1", "Asha", TeacherStatus.ACTIVE);
        Teacher notStarted = teacher("T2", "Bina", TeacherStatus.ACTIVE);
        Teacher pending = teacher("T3", "Charu", TeacherStatus.ACTIVE);
        Teacher disabled = teacher("T4", "Divya", TeacherStatus.LEFT);
        when(teachers.findBySchoolId(42L)).thenReturn(List.of(disabled, pending, notStarted, started));
        when(users.findBySchoolIdAndRoleAndUserIdIn(eq(42L), eq("TEACHER"), anyList()))
                .thenReturn(List.of(user("T1", true), user("T2", true), user("T4", true)));
        Instant lastActive = Instant.parse("2026-09-20T08:00:00Z");
        UserSessionRepository.LastActivity activity = activity("T1", lastActive);
        when(sessions.findLastActivityByUserIds(anyList())).thenReturn(List.of(activity));
        LocalDateTime lastCheckIn = LocalDateTime.of(2026, 9, 21, 8, 15);
        TeacherAttendanceRepository.LastTeacherCheckIn checkIn = checkIn("T1", lastCheckIn);
        when(attendance.findLastSelfCheckInBySchoolIdAndTeacherIds(eq(42L), anyList()))
                .thenReturn(List.of(checkIn));

        StaffAdoptionResponse response = service.getStaffAdoption();

        assertThat(response.teachers()).extracting(StaffAdoptionResponse.TeacherRow::teacherId)
                .containsExactly("T1", "T2", "T3", "T4");
        assertThat(row(response, "T1").accountStatus()).isEqualTo("STARTED");
        assertThat(row(response, "T1").lastActiveAt()).isEqualTo(lastActive);
        assertThat(row(response, "T1").hasUsedAttendance()).isTrue();
        assertThat(row(response, "T1").lastAttendanceAt()).isEqualTo(lastCheckIn);
        assertThat(row(response, "T2").accountStatus()).isEqualTo("NOT_STARTED");
        assertThat(row(response, "T2").lastActiveAt()).isNull();
        assertThat(row(response, "T3").accountStatus()).isEqualTo("ACCOUNT_PENDING");
        assertThat(row(response, "T4").accountStatus()).isEqualTo("DISABLED");
        assertThat(response.summary()).isEqualTo(new StaffAdoptionResponse.Summary(4, 1, 2, 1, 1));

        verify(teachers).findBySchoolId(42L);
        verify(users).findBySchoolIdAndRoleAndUserIdIn(eq(42L), eq("TEACHER"), anyList());
        verify(sessions).findLastActivityByUserIds(anyList());
        verify(attendance).findLastSelfCheckInBySchoolIdAndTeacherIds(eq(42L), anyList());
        verifyNoMoreInteractions(teachers, users, sessions, attendance);
    }

    @Test
    void inactiveUserIsDisabledEvenWhenTeacherDomainRowIsActive() {
        when(teachers.findBySchoolId(42L)).thenReturn(List.of(teacher("T1", "Asha", TeacherStatus.ACTIVE)));
        when(users.findBySchoolIdAndRoleAndUserIdIn(eq(42L), eq("TEACHER"), anyList()))
                .thenReturn(List.of(user("T1", false)));
        when(sessions.findLastActivityByUserIds(anyList())).thenReturn(List.of());
        when(attendance.findLastSelfCheckInBySchoolIdAndTeacherIds(eq(42L), anyList())).thenReturn(List.of());

        StaffAdoptionResponse response = service.getStaffAdoption();

        assertThat(row(response, "T1").accountStatus()).isEqualTo("DISABLED");
        assertThat(response.summary().disabledTeachers()).isEqualTo(1);
    }

    private Teacher teacher(String id, String name, TeacherStatus status) {
        Teacher teacher = new Teacher();
        teacher.setTeacherId(id); teacher.setName(name); teacher.setStatus(status); teacher.setSchoolId(42L);
        return teacher;
    }

    private User user(String id, boolean active) {
        User user = new User(); user.setUserId(id); user.setRole("TEACHER"); user.setSchoolId(42L); user.setActive(active);
        return user;
    }

    private UserSessionRepository.LastActivity activity(String id, Instant at) {
        UserSessionRepository.LastActivity result = mock(UserSessionRepository.LastActivity.class);
        when(result.getUserId()).thenReturn(id); when(result.getLastActiveAt()).thenReturn(at); return result;
    }

    private TeacherAttendanceRepository.LastTeacherCheckIn checkIn(String id, LocalDateTime at) {
        TeacherAttendanceRepository.LastTeacherCheckIn result = mock(TeacherAttendanceRepository.LastTeacherCheckIn.class);
        when(result.getTeacherId()).thenReturn(id); when(result.getLastAttendanceAt()).thenReturn(at); return result;
    }

    private StaffAdoptionResponse.TeacherRow row(StaffAdoptionResponse response, String id) {
        return response.teachers().stream().filter(row -> row.teacherId().equals(id)).findFirst().orElseThrow();
    }
}
