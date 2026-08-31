package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the atomic-counter mechanism (the exact SQL in IdSequenceCounterRepository —
 * {@code INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING}) is genuinely race-free under
 * real concurrent load against the real Postgres engine — not a mocked-concurrency test,
 * which can only prove that Java code compiles under concurrent calls, never that a specific
 * SQL statement is actually safe under a real database's own locking semantics.
 *
 * <p>Deliberately bypasses Spring/JPA entirely: this test's only job is to prove the raw SQL
 * statement is race-free under Postgres itself, so plain JDBC connections in a thread pool is
 * both simpler and more direct evidence than booting the full application context through
 * Hibernate's query executor would be.
 *
 * <p>Uses a dedicated, obviously-not-real role_prefix ("ZZTESTCONC") so this can never
 * collide with or consume real stu/emp/par sequence numbers, and cleans up its own row
 * afterward regardless of outcome — this runs against the shared dev database, not a
 * disposable one.
 *
 * <p>Skipped (not failed) when DB_URL isn't set in the environment — this is the same
 * variable name / value the running backend already uses (see run-backend.sh), so exporting
 * it before running this class is what "real" means here, deliberately not something the
 * routine unit test suite depends on.
 */
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class IdSequenceCounterConcurrencyTest {

    private static final String TEST_ROLE_PREFIX = "ZZTESTCONC";

    private static final String UPSERT_SQL = """
            INSERT INTO id_sequence_counter (role_prefix, year_code, next_seq)
            VALUES (?, ?, 10001)
            ON CONFLICT (role_prefix, year_code)
            DO UPDATE SET next_seq = id_sequence_counter.next_seq + 1
            RETURNING next_seq
            """;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM id_sequence_counter WHERE role_prefix = ?")) {
            ps.setString(1, TEST_ROLE_PREFIX);
            ps.executeUpdate();
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
    }

    private long nextSequence(int yearCode) throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, TEST_ROLE_PREFIX);
            ps.setInt(2, yearCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void concurrentCallers_neverReceiveTheSameSequenceNumber() throws Exception {
        int yearCode = 26;
        int threads = 40;
        int callsPerThread = 25;
        int totalCalls = threads * callsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1); // all threads fire together, maximizing real contention
        Set<Long> seenValues = ConcurrentHashMap.newKeySet();
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        long value = nextSequence(yearCode);
                        if (!seenValues.add(value)) {
                            // A duplicate would mean the atomic upsert is NOT actually race-free —
                            // this is the one outcome this whole test exists to rule out.
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        startGate.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(60, TimeUnit.SECONDS);

        assertThat(finished).as("all threads completed within the timeout").isTrue();
        assertThat(errors.get()).as("no duplicate sequence numbers and no thread-level errors").isZero();
        assertThat(seenValues).hasSize(totalCalls);
        // Every value generated for a fresh (role, year) pair starting empty should land in
        // [10001, 10001 + totalCalls - 1] with none skipped — no gaps expected on the pure
        // success path (gaps are only an accepted consequence of a LATER failure elsewhere in
        // account creation, which never happens in this test — see IdGeneratorService's
        // Javadoc for that trade-off).
        assertThat(seenValues).containsExactlyInAnyOrderElementsOf(
                java.util.stream.LongStream.rangeClosed(10001, 10000 + totalCalls).boxed().toList());
    }
}
