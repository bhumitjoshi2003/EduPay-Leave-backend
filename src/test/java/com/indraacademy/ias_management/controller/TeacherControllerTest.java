package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.service.ObjectStorageService;
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
import java.net.URL;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherControllerTest {

    @Mock private TeacherService teacherService;
    @Mock private ObjectStorageService objectStorageService;
    @Mock private HttpServletRequest request;

    private TeacherController controller;

    @BeforeEach
    void setUp() {
        controller = new TeacherController();
        ReflectionTestUtils.setField(controller, "teacherService", teacherService);
        ReflectionTestUtils.setField(controller, "objectStorageService", objectStorageService);
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

    // ─── Object-storage photo resolution on read (backward-compatible with legacy paths) ────

    @Test
    void getTeacher_legacyLocalDiskPhoto_leftCompletelyUntouched() {
        Teacher teacher = new Teacher();
        teacher.setTeacherId("T1");
        teacher.setPhotoUrl("/uploads/teacher-photos/T1.jpg");
        when(teacherService.getTeacher("T1")).thenReturn(Optional.of(teacher));

        var response = controller.getTeacher("T1");

        assertThat(response.getBody().getPhotoUrl()).isEqualTo("/uploads/teacher-photos/T1.jpg");
        verify(objectStorageService, never()).createPresignedDownloadUrl(any());
    }

    @Test
    void getTeacher_objectStorageKeyPhoto_resolvedToFreshPresignedUrl() throws Exception {
        Teacher teacher = new Teacher();
        teacher.setTeacherId("T1");
        teacher.setPhotoUrl("schools/1/teachers/T1/profile/uuid.jpg");
        when(teacherService.getTeacher("T1")).thenReturn(Optional.of(teacher));
        when(objectStorageService.createPresignedDownloadUrl("schools/1/teachers/T1/profile/uuid.jpg"))
                .thenReturn(new URL("https://storage.example/signed-get-url"));

        var response = controller.getTeacher("T1");

        assertThat(response.getBody().getPhotoUrl()).isEqualTo("https://storage.example/signed-get-url");
    }

    @Test
    void getTeacher_noPhoto_doesNotAttemptToResolveAnything() {
        Teacher teacher = new Teacher();
        teacher.setTeacherId("T1");
        teacher.setPhotoUrl(null);
        when(teacherService.getTeacher("T1")).thenReturn(Optional.of(teacher));

        var response = controller.getTeacher("T1");

        assertThat(response.getBody().getPhotoUrl()).isNull();
        verify(objectStorageService, never()).createPresignedDownloadUrl(any());
    }

    @Test
    void getTeacher_notFound_doesNotAttemptPhotoResolution() {
        when(teacherService.getTeacher("MISSING")).thenReturn(Optional.empty());

        var response = controller.getTeacher("MISSING");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(objectStorageService, never()).createPresignedDownloadUrl(any());
    }
}
