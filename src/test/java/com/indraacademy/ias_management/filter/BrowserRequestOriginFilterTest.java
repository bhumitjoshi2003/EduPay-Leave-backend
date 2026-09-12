package com.indraacademy.ias_management.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BrowserRequestOriginFilterTest {
    private BrowserRequestOriginFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BrowserRequestOriginFilter();
        ReflectionTestUtils.setField(filter, "baseDomain", "edunexify.co.in");
        ReflectionTestUtils.setField(filter, "frontendUrl", "https://edunexify.co.in");
        ReflectionTestUtils.setField(filter, "additionalOrigins",
                "capacitor://localhost,http://localhost:4200,https://localhost");
    }

    @Test
    void matchingSchoolOriginAndSlugIsAllowed() throws Exception {
        var request = unsafe("https://alpha.edunexify.co.in", "alpha");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void siblingSchoolCannotClaimAnotherSlug() throws Exception {
        var request = unsafe("https://alpha.edunexify.co.in", "beta");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
    }

    @Test
    void externalBrowserOriginIsRejected() throws Exception {
        var request = unsafe("https://evil.example", "alpha");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
    }

    @Test
    void capacitorAndNonBrowserClientsRemainSupported() throws Exception {
        for (String origin : new String[]{"capacitor://localhost", null}) {
            var request = unsafe(origin, "alpha");
            var response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
    }

    private MockHttpServletRequest unsafe(String origin, String slug) {
        var request = new MockHttpServletRequest("POST", "/api/students");
        if (origin != null) request.addHeader("Origin", origin);
        request.addHeader("X-School-Slug", slug);
        return request;
    }
}
