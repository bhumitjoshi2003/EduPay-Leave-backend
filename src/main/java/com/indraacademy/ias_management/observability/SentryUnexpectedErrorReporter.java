package com.indraacademy.ias_management.observability;

import com.indraacademy.ias_management.filter.RequestIdFilter;
import io.sentry.Sentry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class SentryUnexpectedErrorReporter implements UnexpectedErrorReporter {
    @Override
    public void report(String operation, Throwable failure) {
        if (!Sentry.isEnabled()) return;
        Sentry.withScope(scope -> {
            scope.setTag("service", "backend");
            scope.setTag("operation", safeOperation(operation));
            scope.setTag("exception.class", failure.getClass().getSimpleName());
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null) scope.setTag("request_id", requestId);
            Sentry.captureException(failure);
        });
    }

    private String safeOperation(String operation) {
        return operation != null && operation.matches("[A-Za-z0-9._-]{1,80}") ? operation : "unknown";
    }
}
