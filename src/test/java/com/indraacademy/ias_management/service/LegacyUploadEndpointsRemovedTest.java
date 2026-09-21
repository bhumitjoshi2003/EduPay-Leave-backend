package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.controller.AdminController;
import com.indraacademy.ias_management.controller.SchoolController;
import com.indraacademy.ias_management.controller.StudentController;
import com.indraacademy.ias_management.controller.TeacherController;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 regression guard: proves the retired local-disk multipart upload
 * endpoints/methods are genuinely gone from the codebase — not just unused — so a future change
 * can't silently reintroduce a second, parallel persistent-upload architecture alongside the
 * direct-to-object-storage flow (FileUploadRequestController/FileUploadRequestService). Every
 * category's ONLY remaining persistent upload path is upload-request -> presigned PUT ->
 * complete.
 *
 * <p>Uses reflection rather than an HTTP call because an unauthenticated MockMvc request can't
 * distinguish "no such mapping" from "mapping exists but requires auth" — both return 401 from
 * Spring Security before MVC ever resolves a handler. Absence of the Java method itself is the
 * stronger, unambiguous guarantee.
 */
class LegacyUploadEndpointsRemovedTest {

    @Test
    void teacherController_hasNoLegacyPhotoUploadMethod() {
        assertThatThrownBy(() -> TeacherController.class.getMethod("uploadTeacherPhoto", String.class, MultipartFile.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void studentController_hasNoLegacyPhotoUploadMethod() {
        assertThatThrownBy(() -> StudentController.class.getMethod("uploadStudentPhoto", String.class, MultipartFile.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void adminController_hasNoLegacyPhotoUploadMethod() {
        assertThatThrownBy(() -> AdminController.class.getMethod("uploadAdminPhoto", String.class, MultipartFile.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void schoolController_hasNoLegacyLogoOrHeaderUploadMethod() {
        assertThatThrownBy(() -> SchoolController.class.getMethod("uploadLogo", MultipartFile.class, jakarta.servlet.http.HttpServletRequest.class))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> SchoolController.class.getMethod("uploadReportCardHeader", MultipartFile.class, jakarta.servlet.http.HttpServletRequest.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void schoolController_stillHasTheExplicitRemoveReportCardHeaderEndpoint() throws NoSuchMethodException {
        // The DELETE remove action is part of the CURRENT architecture (Phase 2's
        // Object-Storage-aware removeReportCardHeader) and must NOT have been swept away
        // alongside the two retired upload methods above.
        Method method = SchoolController.class.getMethod("removeReportCardHeader", jakarta.servlet.http.HttpServletRequest.class);
        org.assertj.core.api.Assertions.assertThat(method).isNotNull();
    }

    @Test
    void personalMediaController_classNoLongerExists() {
        assertThatThrownBy(() -> Class.forName("com.indraacademy.ias_management.controller.PersonalMediaController"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void fileStorageServiceAndControllerNoLongerExist() {
        assertThatThrownBy(() -> Class.forName("com.indraacademy.ias_management.service.FileStorageService"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.indraacademy.ias_management.controller.FileUploadController"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void fileStoragePropertiesNoLongerExists() {
        assertThatThrownBy(() -> Class.forName("com.indraacademy.ias_management.config.FileStorageProperties"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
