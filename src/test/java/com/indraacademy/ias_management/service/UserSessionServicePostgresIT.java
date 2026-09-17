package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.UserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres proof of the actual multi-session guarantee no Mockito test can honestly make:
 * that a genuine, committed row for one login is completely unaffected by what happens to a
 * different login's row in the database — the exact property the old single users.refresh_token_id
 * column could never have (see the migration/entity comments for the full root-cause writeup).
 * Follows the same *PostgresIT convention as PaymentPricingConfigPostgresIT (DataJpaTest + real
 * Postgres, gated by DB_URL, the real ClockConfig bean, TestTransaction ended immediately so
 * each service call is a genuinely committing transaction).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserSessionService.class, com.indraacademy.ias_management.config.ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class UserSessionServicePostgresIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserSessionService userSessionService;

    private static final String USER_A = "usit-user-a";
    private static final String USER_B = "usit-user-b";

    @BeforeEach
    void endTestManagedTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        jdbc.update("INSERT INTO users (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", USER_A);
        jdbc.update("INSERT INTO users (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", USER_B);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM user_session WHERE user_id IN (?, ?)", USER_A, USER_B);
        jdbc.update("DELETE FROM users WHERE user_id IN (?, ?)", USER_A, USER_B);
    }

    @Test
    void loginOnBrowserA_thenLoginOnBrowserB_bothRemainIndependentlyValid() {
        userSessionService.createSession(USER_A, "raw-A", Instant.now().plusSeconds(3600), "Chrome", "1.1.1.1");
        userSessionService.createSession(USER_A, "raw-B", Instant.now().plusSeconds(3600), "Brave", "1.1.1.1");

        assertThat(userSessionService.resolveActive("raw-A")).isPresent();
        assertThat(userSessionService.resolveActive("raw-B")).isPresent();
    }

    @Test
    void refreshingSessionA_neverAffectsSessionB() {
        userSessionService.createSession(USER_A, "raw-A", Instant.now().plusSeconds(3600), null, null);
        userSessionService.createSession(USER_A, "raw-B", Instant.now().plusSeconds(3600), null, null);

        UserSession sessionA = userSessionService.resolveActive("raw-A").orElseThrow();
        userSessionService.rotate(sessionA, "raw-A2", Instant.now().plusSeconds(7200), "Chrome", "2.2.2.2");

        assertThat(userSessionService.resolveActive("raw-A")).isEmpty(); // old token is dead
        assertThat(userSessionService.resolveActive("raw-A2")).isPresent(); // same session, new token
        assertThat(userSessionService.resolveActive("raw-B")).isPresent(); // completely untouched
    }

    @Test
    void loggingOutSessionA_neverAffectsSessionB() {
        userSessionService.createSession(USER_A, "raw-A", Instant.now().plusSeconds(3600), null, null);
        userSessionService.createSession(USER_A, "raw-B", Instant.now().plusSeconds(3600), null, null);

        userSessionService.revokeByRawToken("raw-A");

        assertThat(userSessionService.resolveActive("raw-A")).isEmpty();
        assertThat(userSessionService.resolveActive("raw-B")).isPresent();
    }

    @Test
    void revokedSession_cannotBeUsedToRefresh() {
        userSessionService.createSession(USER_A, "raw-A", Instant.now().plusSeconds(3600), null, null);
        userSessionService.revokeByRawToken("raw-A");

        assertThat(userSessionService.resolveActive("raw-A")).isEmpty();
    }

    @Test
    void logoutAll_revokesEveryOneOfThreeSessions() {
        userSessionService.createSession(USER_A, "raw-A", Instant.now().plusSeconds(3600), null, null);
        userSessionService.createSession(USER_A, "raw-B", Instant.now().plusSeconds(3600), null, null);
        userSessionService.createSession(USER_A, "raw-C", Instant.now().plusSeconds(3600), null, null);

        int revoked = userSessionService.revokeAllForUser(USER_A);

        assertThat(revoked).isEqualTo(3);
        assertThat(userSessionService.resolveActive("raw-A")).isEmpty();
        assertThat(userSessionService.resolveActive("raw-B")).isEmpty();
        assertThat(userSessionService.resolveActive("raw-C")).isEmpty();
    }

    @Test
    void expiredSession_isRejected_evenThoughNeverExplicitlyRevoked() {
        userSessionService.createSession(USER_A, "raw-expired", Instant.now().minusSeconds(5), null, null);

        assertThat(userSessionService.resolveActive("raw-expired")).isEmpty();
    }

    @Test
    void oneUser_cannotRevokeAnotherUsersSession() {
        UserSession sessionA = userSessionService.createSession(USER_A, "raw-A", Instant.now().plusSeconds(3600), null, null);

        boolean revoked = userSessionService.revokeOwnSession(sessionA.getId(), USER_B);

        assertThat(revoked).isFalse();
        // Untouched — still resolvable, proving the cross-user attempt did nothing.
        assertThat(userSessionService.resolveActive("raw-A")).isPresent();
    }

    /** Release-critical: two requests racing to refresh the SAME still-valid refresh
     * token must never both succeed. rotate()'s atomic compare-and-swap UPDATE (see
     * UserSessionRepository#rotateIfCurrentHashMatches) is the only thing standing
     * between this and a real vulnerability — a Mockito test cannot honestly prove
     * this, since the whole guarantee rests on genuine Postgres row-level locking and
     * WHERE-clause re-evaluation across two REAL concurrent transactions/connections.
     * Calling the same @Transactional service method from two threads is sufficient
     * to get two separate connections here — Spring's transaction binding is
     * thread-local, so no raw-JDBC lock orchestration is needed the way a
     * deliberately-blocking test (e.g. PaymentSettlementServicePostgresIT's FOR
     * UPDATE tests) requires; the CAS itself never blocks, it just loses cleanly. */
    @Test
    void concurrentRefresh_ofTheSameToken_exactlyOneRotationSucceeds() throws Exception {
        UserSession session = userSessionService.createSession(
                USER_A, "raw-R1", Instant.now().plusSeconds(3600), null, null);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        CompletableFuture<Boolean> callerA = CompletableFuture.supplyAsync(() -> {
            bothReady.countDown();
            await(go);
            return userSessionService.rotate(session, "raw-R2-from-A", Instant.now().plusSeconds(7200), "Chrome", "1.1.1.1");
        });
        CompletableFuture<Boolean> callerB = CompletableFuture.supplyAsync(() -> {
            bothReady.countDown();
            await(go);
            return userSessionService.rotate(session, "raw-R2-from-B", Instant.now().plusSeconds(7200), "Brave", "2.2.2.2");
        });

        assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown(); // release both at essentially the same instant

        boolean resultA = callerA.get(10, TimeUnit.SECONDS);
        boolean resultB = callerB.get(10, TimeUnit.SECONDS);

        // Exactly one caller wins — never both, never neither.
        assertThat(resultA ^ resultB).as("exactly one of the two concurrent refreshes must succeed").isTrue();

        // The original (pre-race) token is dead either way.
        assertThat(userSessionService.resolveActive("raw-R1")).isEmpty();
        // Exactly one successor token is resolvable — the winner's — never both, never neither.
        long resolvableSuccessors = java.util.stream.Stream.of("raw-R2-from-A", "raw-R2-from-B")
                .filter(raw -> userSessionService.resolveActive(raw).isPresent())
                .count();
        assertThat(resolvableSuccessors).isEqualTo(1);
        // The resolvable one matches whichever caller's rotate() actually returned true.
        assertThat(userSessionService.resolveActive("raw-R2-from-A").isPresent()).isEqualTo(resultA);
        assertThat(userSessionService.resolveActive("raw-R2-from-B").isPresent()).isEqualTo(resultB);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    void revokeAllOthers_keepsCurrentSession_revokesOnlyTheRest() {
        userSessionService.createSession(USER_A, "raw-current", Instant.now().plusSeconds(3600), null, null);
        userSessionService.createSession(USER_A, "raw-other-1", Instant.now().plusSeconds(3600), null, null);
        userSessionService.createSession(USER_A, "raw-other-2", Instant.now().plusSeconds(3600), null, null);

        String currentHash = userSessionService.hash("raw-current");
        int revoked = userSessionService.revokeAllOthers(USER_A, currentHash);

        assertThat(revoked).isEqualTo(2);
        assertThat(userSessionService.resolveActive("raw-current")).isPresent();
        assertThat(userSessionService.resolveActive("raw-other-1")).isEmpty();
        assertThat(userSessionService.resolveActive("raw-other-2")).isEmpty();
    }
}
