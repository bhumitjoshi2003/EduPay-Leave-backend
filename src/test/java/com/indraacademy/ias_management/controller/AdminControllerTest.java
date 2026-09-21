package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.service.AdminService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Only covers getAdmin()'s object-storage photo resolution — AdminService.getAdminById already
 * enforces the same-school/SUPER_ADMIN-any-school tenant isolation this delegates to (see
 * AdminServiceTest, if present, for that logic's own coverage); this test proves the added photo
 * swap correctly delegates to ObjectStorageService.resolveDisplayUrl.
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private AdminService adminService;
    @Mock private AuthService authService;
    @Mock private ObjectStorageService objectStorageService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController();
        ReflectionTestUtils.setField(controller, "adminService", adminService);
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "objectStorageService", objectStorageService);
    }

    @Test
    void getAdmin_legacyLocalDiskPhoto_leftCompletelyUntouched() {
        Admin admin = new Admin();
        admin.setAdminId("A1");
        admin.setPhotoUrl("/uploads/admin-photos/A1.jpg");
        when(adminService.getAdminById("A1")).thenReturn(Optional.of(admin));
        when(objectStorageService.resolveDisplayUrl("/uploads/admin-photos/A1.jpg"))
                .thenReturn("/uploads/admin-photos/A1.jpg");

        var response = controller.getAdmin("A1");

        assertThat(response.getBody().getPhotoUrl()).isEqualTo("/uploads/admin-photos/A1.jpg");
    }

    @Test
    void getAdmin_objectStorageKeyPhoto_resolvedToFreshPresignedUrl() {
        Admin admin = new Admin();
        admin.setAdminId("A1");
        admin.setPhotoUrl("schools/1/admins/A1/profile/uuid.jpg");
        when(adminService.getAdminById("A1")).thenReturn(Optional.of(admin));
        when(objectStorageService.resolveDisplayUrl("schools/1/admins/A1/profile/uuid.jpg"))
                .thenReturn("https://storage.example/signed-get-url");

        var response = controller.getAdmin("A1");

        assertThat(response.getBody().getPhotoUrl()).isEqualTo("https://storage.example/signed-get-url");
    }

    @Test
    void getAdmin_notFound_doesNotAttemptPhotoResolution() {
        when(adminService.getAdminById("MISSING")).thenReturn(Optional.empty());

        var response = controller.getAdmin("MISSING");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
