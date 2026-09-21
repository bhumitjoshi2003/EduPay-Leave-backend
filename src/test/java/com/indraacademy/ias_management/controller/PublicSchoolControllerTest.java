package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.service.ObjectStorageService;
import com.indraacademy.ias_management.service.PlanService;
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
 * Only covers getSchoolBySlug's object-storage logo resolution — this is a public, unauthenticated
 * endpoint (login-screen branding), so the presigned GET URL it generates must come purely from
 * this backend's own object-storage credentials, never anything derived from a caller session.
 */
@ExtendWith(MockitoExtension.class)
class PublicSchoolControllerTest {

    @Mock private SchoolRepository schoolRepository;
    @Mock private PlanService planService;
    @Mock private ObjectStorageService objectStorageService;

    private PublicSchoolController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicSchoolController();
        ReflectionTestUtils.setField(controller, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(controller, "planService", planService);
        ReflectionTestUtils.setField(controller, "objectStorageService", objectStorageService);
    }

    private School activeSchool(String logoUrl) {
        School s = new School();
        s.setSlug("myschool");
        s.setActive(true);
        s.setLogoUrl(logoUrl);
        return s;
    }

    @Test
    void getSchoolBySlug_objectStorageLogo_resolvedToFreshPresignedUrl() {
        when(schoolRepository.findBySlug("myschool")).thenReturn(Optional.of(activeSchool("schools/1/school/logo/uuid.png")));
        when(objectStorageService.resolveDisplayUrl("schools/1/school/logo/uuid.png"))
                .thenReturn("https://storage.example/logo-get-url");

        var response = controller.getSchoolBySlug("myschool");

        assertThat(response.getBody().getLogoUrl()).isEqualTo("https://storage.example/logo-get-url");
    }

    @Test
    void getSchoolBySlug_legacyLocalDiskLogo_leftCompletelyUntouched() {
        when(schoolRepository.findBySlug("myschool")).thenReturn(Optional.of(activeSchool("/uploads/school-logos/1.png")));
        when(objectStorageService.resolveDisplayUrl("/uploads/school-logos/1.png"))
                .thenReturn("/uploads/school-logos/1.png");

        var response = controller.getSchoolBySlug("myschool");

        assertThat(response.getBody().getLogoUrl()).isEqualTo("/uploads/school-logos/1.png");
    }

    @Test
    void getSchoolBySlug_unknownSlug_returnsNotFound() {
        when(schoolRepository.findBySlug("unknown")).thenReturn(Optional.empty());

        var response = controller.getSchoolBySlug("unknown");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
