package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.indraacademy.ias_management.entity.Teacher;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in that date of birth is mandatory for a newly-created teacher — it is the
 * only source of the DOB-derived initial login password (see AuthController.registerUser
 * and TeacherBulkImportService), so a teacher created without one must never reach the
 * database.
 */
@ExtendWith(MockitoExtension.class)
class TeacherServiceTest {

    @Mock private TeacherRepository teacherRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private EntitlementService entitlementService;
    @Mock private HttpServletRequest request;

    private TeacherService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new TeacherService();
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "entitlementService", entitlementService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().registerModule(new JavaTimeModule()));

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(teacherRepository.save(any(Teacher.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Teacher newTeacher(String teacherId, LocalDate dob) {
        Teacher t = new Teacher();
        t.setTeacherId(teacherId);
        t.setName("Test Teacher");
        t.setDob(dob);
        return t;
    }

    @Test
    void addTeacherWithValidDobSucceeds() {
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(teacherRepository.existsById("T1")).thenReturn(false);

        Teacher saved = service.addTeacher(newTeacher("T1", LocalDate.of(1985, 3, 20)), request);

        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);
        verify(teacherRepository).save(any(Teacher.class));
    }

    @Test
    void addTeacherWithoutDobIsRejected() {
        assertThatThrownBy(() -> service.addTeacher(newTeacher("T1", null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Date of birth is required");

        verify(teacherRepository, never()).save(any());
    }

    @Test
    void duplicateTeacherIdWithinSchoolIsRejected() {
        Teacher existing = newTeacher("T1", LocalDate.of(1985, 3, 20));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.addTeacher(newTeacher("T1", LocalDate.of(1985, 3, 20)), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(teacherRepository, never()).save(any());
    }
}
