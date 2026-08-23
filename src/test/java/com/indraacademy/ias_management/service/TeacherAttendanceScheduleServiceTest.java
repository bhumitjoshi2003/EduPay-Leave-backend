package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherAttendanceScheduleRequest;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherAttendanceSchedule;
import com.indraacademy.ias_management.repository.TeacherAttendanceScheduleRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherAttendanceScheduleServiceTest {
    @Mock private TeacherAttendanceScheduleRepository repository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private AuditService auditService;
    @Mock private HttpServletRequest request;

    private TeacherAttendanceScheduleService service;

    @BeforeEach
    void setUp() {
        service = new TeacherAttendanceScheduleService(repository, teacherRepository, securityUtil, auditService);
        lenient().when(securityUtil.getSchoolId()).thenReturn(2L);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T1", 2L))
                .thenReturn(Optional.of(new Teacher()));
        lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void customChangeClosesPreviousScheduleAndPreservesFutureBoundary() {
        TeacherAttendanceSchedule previous = schedule("CUSTOM", "MONDAY,TUESDAY", LocalDate.of(2026, 4, 1), null);
        TeacherAttendanceSchedule future = schedule("SCHOOL", null, LocalDate.of(2027, 1, 1), null);
        when(repository.findByTeacherIdAndSchoolIdOrderByEffectiveFromAsc("T1", 2L))
                .thenReturn(List.of(previous, future));

        TeacherAttendanceScheduleRequest change = new TeacherAttendanceScheduleRequest();
        change.setScheduleType("custom");
        change.setWorkingDays("SATURDAY, THURSDAY,THURSDAY");
        change.setEffectiveFrom(LocalDate.of(2026, 8, 1));

        var response = service.change("T1", change, request);

        assertThat(previous.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(response.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(response.getWorkingDays()).isEqualTo("THURSDAY,SATURDAY");
        verify(repository, times(2)).save(any());
    }

    @Test
    void schoolScheduleFallsBackToCurrentSchoolDaysWhileCustomScheduleOverridesThem() {
        TeacherAttendanceSchedule custom = schedule(
                "CUSTOM", "THURSDAY,FRIDAY,SATURDAY", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31));
        TeacherAttendanceSchedule school = schedule("SCHOOL", null, LocalDate.of(2026, 11, 1), null);
        Map<String, List<TeacherAttendanceSchedule>> schedules = Map.of("T1", List.of(custom, school));
        String schoolDays = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY";

        assertThat(service.workingDaysFor("T1", LocalDate.of(2026, 9, 1), schoolDays, schedules))
                .isEqualTo("THURSDAY,FRIDAY,SATURDAY");
        assertThat(service.workingDaysFor("T1", LocalDate.of(2026, 12, 1), schoolDays, schedules))
                .isEqualTo(schoolDays);
        assertThat(service.workingDaysFor("T2", LocalDate.of(2026, 9, 1), schoolDays, schedules))
                .isEqualTo(schoolDays);
    }

    private TeacherAttendanceSchedule schedule(String type, String days, LocalDate from, LocalDate to) {
        TeacherAttendanceSchedule schedule = new TeacherAttendanceSchedule();
        schedule.setSchoolId(2L);
        schedule.setTeacherId("T1");
        schedule.setScheduleType(type);
        schedule.setWorkingDays(days);
        schedule.setEffectiveFrom(from);
        schedule.setEffectiveTo(to);
        return schedule;
    }
}
