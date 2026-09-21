package com.indraacademy.ias_management.filter;

import com.indraacademy.ias_management.service.SlugResolutionService;
import com.indraacademy.ias_management.util.SchoolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Regression for the same "object storage upload auth bug" fix applied to JwtAuthFilter:
 * isPublicPath() used to exempt the entire /api/files/ prefix (path.startsWith), which would
 * have silently skipped tenant validation for FileUploadRequestController's upload-request and
 * complete endpoints too — they only share a URL prefix with the one legacy endpoint the
 * exemption was meant for, /api/files/uploadEventImage. Fixed to match only that exact path,
 * mirroring SecurityConfig's own requestMatchers("/api/files/uploadEventImage").
 */
class TenantValidationFilterTest {

    private TenantValidationFilter filter;
    private SlugResolutionService slugResolutionService;

    @BeforeEach
    void setUp() {
        filter = new TenantValidationFilter();
        slugResolutionService = mock(SlugResolutionService.class);
        ReflectionTestUtils.setField(filter, "slugResolutionService", slugResolutionService);
        ReflectionTestUtils.setField(filter, "baseDomain", "edunexify.co.in");
        ReflectionTestUtils.setField(filter, "validationEnabled", true);
        SchoolContext.clear();
    }

    @AfterEach
    void tearDown() {
        SchoolContext.clear();
    }

    @Test
    void uploadRequestPath_isNoLongerExemptFromTenantValidation() throws Exception {
        // JWT says schoolId=1, but the caller's X-School-Slug resolves to schoolId=2 —
        // a mismatch that isPublicPath() previously hid entirely for this path.
        SchoolContext.set(1L);
        when(slugResolutionService.resolveSlugToSchoolId("otherschool")).thenReturn(2L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/upload-request");
        request.setServerName("edunexify.co.in"); // root domain -> no subdomain, falls back to header
        request.addHeader("X-School-Slug", "otherschool");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void completePath_isNoLongerExemptFromTenantValidation() throws Exception {
        SchoolContext.set(1L);
        when(slugResolutionService.resolveSlugToSchoolId("otherschool")).thenReturn(2L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/complete");
        request.setServerName("edunexify.co.in");
        request.addHeader("X-School-Slug", "otherschool");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void uploadRequestPath_matchingSchool_proceeds() throws Exception {
        SchoolContext.set(1L);
        when(slugResolutionService.resolveSlugToSchoolId("myschool")).thenReturn(1L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/upload-request");
        request.setServerName("edunexify.co.in");
        request.addHeader("X-School-Slug", "myschool");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void retiredUploadEventImagePath_isNoLongerExemptFromTenantValidationEither() throws Exception {
        // Same retirement as JwtAuthFilterTest's equivalent — /api/files/uploadEventImage no
        // longer has any special-case exemption in isPublicPath(), so a mismatched slug/schoolId
        // must now be caught for it exactly like any other authenticated path (404 for the
        // now-nonexistent controller happens downstream, at dispatch — irrelevant to this filter).
        SchoolContext.set(1L);
        when(slugResolutionService.resolveSlugToSchoolId("otherschool")).thenReturn(2L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/uploadEventImage");
        request.setServerName("edunexify.co.in");
        request.addHeader("X-School-Slug", "otherschool");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }
}
