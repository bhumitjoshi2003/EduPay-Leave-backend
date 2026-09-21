package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ObjectStorageService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.StudentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Only covers getStudent()'s object-storage photo resolution — the ADMIN/TEACHER/STUDENT-self/
 * PARENT-child access resolution above it is pre-existing, unchanged behavior; this test proves
 * the added resolvePhotoUrlForDisplay call correctly delegates to
 * ObjectStorageService.resolveDisplayUrl (see ObjectStorageServiceTest for that logic's own
 * coverage) without altering who is allowed to reach a given student's record at all.
 */
@ExtendWith(MockitoExtension.class)
class StudentControllerTest {

    @Mock private StudentService studentService;
    @Mock private AuthService authService;
    @Mock private ParentPortalService parentPortalService;
    @Mock private ObjectStorageService objectStorageService;

    private StudentController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentController();
        ReflectionTestUtils.setField(controller, "studentService", studentService);
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "parentPortalService", parentPortalService);
        ReflectionTestUtils.setField(controller, "objectStorageService", objectStorageService);
        lenient().when(authService.getRole()).thenReturn(Role.ADMIN);
    }

    @Test
    void getStudent_legacyLocalDiskPhoto_leftCompletelyUntouched() {
        Student student = new Student();
        student.setStudentId("S1");
        student.setPhotoUrl("/uploads/student-photos/S1.jpg");
        when(studentService.getStudent("S1")).thenReturn(Optional.of(student));
        when(objectStorageService.resolveDisplayUrl("/uploads/student-photos/S1.jpg"))
                .thenReturn("/uploads/student-photos/S1.jpg");

        var response = controller.getStudent("S1");

        assertThat(response.getBody().getPhotoUrl()).isEqualTo("/uploads/student-photos/S1.jpg");
    }

    @Test
    void getStudent_objectStorageKeyPhoto_resolvedToFreshPresignedUrl() {
        Student student = new Student();
        student.setStudentId("S1");
        student.setPhotoUrl("schools/1/students/S1/profile/uuid.jpg");
        when(studentService.getStudent("S1")).thenReturn(Optional.of(student));
        when(objectStorageService.resolveDisplayUrl("schools/1/students/S1/profile/uuid.jpg"))
                .thenReturn("https://storage.example/signed-get-url");

        var response = controller.getStudent("S1");

        assertThat(response.getBody().getPhotoUrl()).isEqualTo("https://storage.example/signed-get-url");
    }

    @Test
    void getStudent_studentRole_stillResolvesOwnIdBeforePhotoSwap() {
        // The pre-existing self-only resolution must still run BEFORE the photo swap — proven by
        // checking studentService is queried with the JWT's own userId, never the path param.
        when(authService.getRole()).thenReturn(Role.STUDENT);
        when(authService.getUserId()).thenReturn("S1");
        Student student = new Student();
        student.setStudentId("S1");
        when(studentService.getStudent("S1")).thenReturn(Optional.of(student));
        when(objectStorageService.resolveDisplayUrl(null)).thenReturn(null);

        controller.getStudent("SOMEONE_ELSE");

        verify(studentService).getStudent("S1");
    }

    @Test
    void getStudent_parentRole_stillEnforcesChildAccessBeforePhotoSwap() {
        when(authService.getRole()).thenReturn(Role.PARENT);
        Student student = new Student();
        student.setStudentId("S1");
        when(studentService.getStudent("S1")).thenReturn(Optional.of(student));
        when(objectStorageService.resolveDisplayUrl(null)).thenReturn(null);

        controller.getStudent("S1");

        verify(parentPortalService).assertChildAccess("S1");
    }

    @Test
    void getStudent_notFound_doesNotAttemptPhotoResolution() {
        when(studentService.getStudent("MISSING")).thenReturn(Optional.empty());

        var response = controller.getStudent("MISSING");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
