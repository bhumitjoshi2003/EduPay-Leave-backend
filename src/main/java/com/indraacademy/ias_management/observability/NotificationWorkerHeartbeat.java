package com.indraacademy.ias_management.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fire-and-forget Better Stack heartbeat for NotificationDeliveryWorker.poll().
 * Disabled unless BETTERSTACK_NOTIFICATION_WORKER_HEARTBEAT_URL is configured;
 * delivery is dispatched async with a short timeout and every failure is
 * swallowed here so a monitoring outage can never delay or break the worker
 * itself. No student/school/user/payment/notification data is ever sent —
 * this is a bare GET to a caller-supplied URL, and that URL is never logged.
 */
@Component
public class NotificationWorkerHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorkerHeartbeat.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final String heartbeatUrl;
    private final HttpClient httpClient;

    public NotificationWorkerHeartbeat(
            @Value("${betterstack.notification-worker.heartbeat-url:}") String heartbeatUrl) {
        this.heartbeatUrl = heartbeatUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    public void reportSuccess() {
        if (heartbeatUrl == null || heartbeatUrl.isBlank()) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(heartbeatUrl))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("Notification worker heartbeat delivery failed: {}", ex.getClass().getSimpleName());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Notification worker heartbeat could not be dispatched: {}", e.getClass().getSimpleName());
        }
    }
}
