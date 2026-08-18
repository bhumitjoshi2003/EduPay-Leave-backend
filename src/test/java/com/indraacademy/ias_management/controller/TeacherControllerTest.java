package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.service.TeacherService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherControllerTest {

    @Mock private TeacherService teacherService;
    @Mock private HttpServletRequest request;

    private TeacherController controller;

    @BeforeEach
    void setUp() {
        controller = new TeacherController();
        ReflectionTestUtils.setField(controller, "teacherService", teacherService);
    }

    @Test
    void updateTeacherUsesPathIdEvenWhenBodyContainsDifferentId() {
        Teacher existing = new Teacher();
        existing.setTeacherId("T1");
        existing.setEmail("teacher@test.com");
        Teacher body = new Teacher();
        body.setTeacherId("OTHER");
        body.setEmail("teacher@test.com");

        when(teacherService.getTeacher("T1")).thenReturn(Optional.of(existing));
        when(teacherService.updateTeacher(any(Teacher.class), any())).thenAnswer(inv -> inv.getArgument(0));

        controller.updateTeacher("T1", body, request);

        assertThat(body.getTeacherId()).isEqualTo("T1");
        verify(teacherService).updateTeacher(body, request);
    }

    @Test
    void teacherPhotoUploadIsAdminOnly() throws Exception {
        Method method = TeacherController.class.getMethod(
                "uploadTeacherPhoto", String.class, org.springframework.web.multipart.MultipartFile.class);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }
}
