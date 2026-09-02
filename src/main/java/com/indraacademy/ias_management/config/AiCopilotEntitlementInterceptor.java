package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.service.EntitlementService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies subscription feature gates at the shared API boundary. Frontend route/menu
 * checks are UX only; this interceptor is the authoritative protection against callers
 * invoking a disabled paid feature directly.
 */
@Component
public class AiCopilotEntitlementInterceptor implements HandlerInterceptor {

    private final EntitlementService entitlementService;
    private final SecurityUtil securityUtil;

    public AiCopilotEntitlementInterceptor(EntitlementService entitlementService,
                                           SecurityUtil securityUtil) {
        this.entitlementService = entitlementService;
        this.securityUtil = securityUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // CORS preflight carries no authenticated school context, and an async
        // StreamingResponseBody dispatch must not repeat the check after the response
        // is already committed. The initial authenticated REQUEST is authoritative.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        String featureKey = featureFor(request.getRequestURI(), request.getMethod());
        if (featureKey != null) {
            entitlementService.requireFeature(securityUtil.getSchoolId(), featureKey);
        }
        return true;
    }

    static String featureFor(String path, String method) {
        if (path == null) return null;

        if (path.startsWith("/api/ai/") || path.startsWith("/api/knowledge-base")) return "AI_COPILOT";
        if (path.startsWith("/api/audit")) return "AUDIT_LOGS";

        if (path.equals("/api/students/bulk") || path.equals("/api/students/bulk/template")
                || path.equals("/api/teachers/bulk") || path.equals("/api/teachers/bulk/template")) {
            return "BULK_IMPORT";
        }

        if (path.startsWith("/api/student-fees/reminders/")) return "FEE_REMINDERS";
        if (path.startsWith("/api/payments")) return "PAYMENT_COLLECTION";
        if (path.startsWith("/api/student-fees") || path.startsWith("/api/fee-workflow") || path.startsWith("/api/fee-structure")
                || path.startsWith("/api/bus-fees") || path.startsWith("/api/invoices")
                || path.startsWith("/api/fee-heads") || path.startsWith("/api/fee-payments")
                || path.startsWith("/api/credit-notes") || path.startsWith("/api/fee-rules")
                || path.startsWith("/api/student-fee-configs")) return "FEE_MANAGEMENT";

        if (path.startsWith("/api/exams") || path.startsWith("/api/marks")
                || path.startsWith("/api/student-stream") || path.startsWith("/api/elective-enrollment")
                || path.startsWith("/api/assessment-groups") || path.startsWith("/api/subjects")
                || path.startsWith("/api/streams") || path.startsWith("/api/optional-groups")
                || path.startsWith("/api/optional-subjects")) {
            return "EXAM_MARKS";
        }

        // Public report-card verification must remain usable without a school session.
        if (path.startsWith("/api/report-card-templates") || path.startsWith("/api/report-cards")) {
            return path.startsWith("/api/public/") ? null : "REPORT_CARD";
        }
        if (path.equals("/api/school/report-card-header")) return "REPORT_CARD";

        // These three endpoints power the paid analytics screen. /stats is deliberately
        // excluded because it also supplies the always-available admin dashboard cards.
        if (path.equals("/api/dashboard/fee-trend") || path.equals("/api/dashboard/class-stats")
                || path.equals("/api/dashboard/attendance-trend")) return "ANALYTICS";

        return null;

    }
}
