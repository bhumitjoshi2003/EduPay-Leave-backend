package com.indraacademy.ias_management.config;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.Hint;
import io.sentry.protocol.SentryException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class SentryConfig {
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "service", "operation", "exception.class", "request_id", "environment", "release");

    @Value("${sentry.dsn:}") private String dsn;
    @Value("${sentry.environment:production}") private String environment;
    @Value("${sentry.release:}") private String release;

    @PostConstruct
    void initialize() {
        if (dsn == null || dsn.isBlank()) return;
        Sentry.init(options -> {
            options.setDsn(dsn);
            options.setEnvironment(environment);
            if (release != null && !release.isBlank()) options.setRelease(release);
            options.setSendDefaultPii(false);
            options.setTracesSampleRate(0.0);
            options.setBeforeSend(this::scrub);
        });
    }

    SentryEvent scrub(SentryEvent event, Hint hint) {
        event.setRequest(null);
        event.setUser(null);
        event.setThrowable(null);
        event.setBreadcrumbs(null);
        event.setExtras(java.util.Map.of());
        event.getContexts().entrySet().clear();
        event.getTags().keySet().removeIf(key -> !ALLOWED_TAGS.contains(key));
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue("Unexpected application error");
            }
        }
        if (event.getMessage() != null) event.getMessage().setMessage("Unexpected application error");
        return event;
    }
}
