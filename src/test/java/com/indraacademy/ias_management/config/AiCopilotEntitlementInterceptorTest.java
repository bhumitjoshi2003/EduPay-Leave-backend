package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.service.EntitlementService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCopilotEntitlementInterceptorTest {

    private EntitlementService entitlementService;
    private SecurityUtil securityUtil;
    private AiCopilotEntitlementInterceptor interceptor;

    @BeforeEach
    void setUp() {
        entitlementService = mock(EntitlementService.class);
        securityUtil = mock(SecurityUtil.class);
        interceptor = new AiCopilotEntitlementInterceptor(entitlementService, securityUtil);
    }

    @Test
    void requiresAiCopilotFeatureForCurrentSchool() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/ai/chat");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(securityUtil.getSchoolId()).thenReturn(42L);

        boolean allowed = interceptor.preHandle(request, null, null);

        assertThat(allowed).isTrue();
        verify(entitlementService).requireFeature(42L, "AI_COPILOT");
    }

    @Test
    void mapsPaidApiFamiliesToTheirCanonicalFeatureKeys() {
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/payments/history", "GET")).isEqualTo("PAYMENT_COLLECTION");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/student-stream/bulk", "POST")).isEqualTo("EXAM_MARKS");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/assessment-groups", "GET")).isEqualTo("EXAM_MARKS");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/report-cards/pdf", "GET")).isEqualTo("REPORT_CARD");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/students/bulk", "POST")).isEqualTo("BULK_IMPORT");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/student-fees/reminders/send", "POST")).isEqualTo("FEE_REMINDERS");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/dashboard/class-stats", "GET")).isEqualTo("ANALYTICS");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/audit", "GET")).isEqualTo("AUDIT_LOGS");
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/attendance", "GET")).isNull();
        assertThat(AiCopilotEntitlementInterceptor.featureFor("/api/public/verify-rc", "GET")).isNull();
    }

    @Test
    void allowsCorsPreflightWithoutAnEntitlementLookup() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("OPTIONS");

        assertThat(interceptor.preHandle(request, null, null)).isTrue();
        verify(entitlementService, never()).requireFeature(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
