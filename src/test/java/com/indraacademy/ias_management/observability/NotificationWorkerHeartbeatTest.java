package com.indraacademy.ias_management.observability;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NotificationWorkerHeartbeatTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void missingUrlIsANoOpAndNeverAttemptsDelivery() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = startServer(hits, null);
        NotificationWorkerHeartbeat heartbeat = new NotificationWorkerHeartbeat("");

        heartbeat.reportSuccess();

        Thread.sleep(200);
        assertThat(hits.get()).isZero();
    }

    @Test
    void successfulCycleSendsHeartbeat() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicInteger hits = new AtomicInteger();
        server = startServer(hits, received);
        String url = "http://localhost:" + server.getAddress().getPort() + "/heartbeat";
        NotificationWorkerHeartbeat heartbeat = new NotificationWorkerHeartbeat(url);

        heartbeat.reportSuccess();

        assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void unreachableUrlDoesNotThrow() {
        // Nothing listening here — connection should be refused near-instantly.
        NotificationWorkerHeartbeat heartbeat = new NotificationWorkerHeartbeat("http://localhost:1/heartbeat");

        assertThatCode(heartbeat::reportSuccess).doesNotThrowAnyException();
    }

    @Test
    void malformedUrlDoesNotThrow() {
        NotificationWorkerHeartbeat heartbeat = new NotificationWorkerHeartbeat("not a url");

        assertThatCode(heartbeat::reportSuccess).doesNotThrowAnyException();
    }

    private HttpServer startServer(AtomicInteger hits, CountDownLatch latch) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext("/heartbeat", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            if (latch != null) latch.countDown();
        });
        s.start();
        return s;
    }
}
