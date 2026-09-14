package com.indraacademy.ias_management.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestId_returnsItAndCleansMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/plans");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNotBlank());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[A-Za-z0-9-]{36}");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void propagatesValidRequestIdAndReplacesInvalidValue() throws Exception {
        MockHttpServletRequest valid = new MockHttpServletRequest("GET", "/api/public/plans");
        valid.addHeader(RequestIdFilter.HEADER, "client_req-12345");
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        filter.doFilter(valid, validResponse, (req, res) -> { });
        assertThat(validResponse.getHeader(RequestIdFilter.HEADER)).isEqualTo("client_req-12345");

        MockHttpServletRequest invalid = new MockHttpServletRequest("GET", "/api/public/plans");
        invalid.addHeader(RequestIdFilter.HEADER, "email@example.com\nunsafe");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        filter.doFilter(invalid, invalidResponse, (req, res) -> { });
        assertThat(invalidResponse.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("email@example.com\nunsafe");
    }

    @Test
    void cleansMdcWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fail");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> { throw new RuntimeException("boom"); });
        } catch (Exception ignored) {
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
