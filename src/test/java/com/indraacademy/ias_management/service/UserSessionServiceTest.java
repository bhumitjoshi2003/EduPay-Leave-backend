package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.UserSession;
import com.indraacademy.ias_management.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Mockito coverage for UserSessionService's own logic (filtering, ownership checks,
 * the fallback in revokeAllOthers) — the actual persistence guarantees (does a
 * rotate/revoke on one row really leave sibling rows untouched in the database) are
 * proven separately in UserSessionServicePostgresIT against a real Postgres instance.
 */
@ExtendWith(MockitoExtension.class)
class UserSessionServiceTest {

    @Mock private UserSessionRepository repository;

    private UserSessionService service;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new UserSessionService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void hash_isDeterministic_andNeverEqualsTheRawToken() {
        String raw = "some-raw-refresh-token-jti";
        String h1 = service.hash(raw);
        String h2 = service.hash(raw);

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(raw);
        assertThat(h1).hasSize(64); // SHA-256 hex
    }

    @Test
    void hash_differentInputs_produceDifferentHashes() {
        assertThat(service.hash("token-a")).isNotEqualTo(service.hash("token-b"));
    }

    @Test
    void createSession_persistsOnlyTheHash_neverTheRawToken() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createSession("U1", "raw-jti", NOW.plusSeconds(3600), "Mozilla/5.0", "1.2.3.4");

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(repository).save(captor.capture());
        UserSession saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("U1");
        assertThat(saved.getRefreshTokenHash()).isEqualTo(service.hash("raw-jti"));
        assertThat(saved.getRefreshTokenHash()).isNotEqualTo("raw-jti");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getLastUsedAt()).isEqualTo(NOW);
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getIpAddress()).isEqualTo("1.2.3.4");
    }

    @Test
    void resolveActive_returnsEmpty_whenNoSessionMatchesTheHash() {
        when(repository.findByRefreshTokenHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.resolveActive("unknown-jti")).isEmpty();
    }

    @Test
    void resolveActive_returnsEmpty_whenSessionIsRevoked() {
        UserSession session = new UserSession();
        session.setRevokedAt(NOW.minusSeconds(10));
        session.setExpiresAt(NOW.plusSeconds(3600));
        when(repository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(session));

        assertThat(service.resolveActive("jti")).isEmpty();
    }

    @Test
    void resolveActive_returnsEmpty_whenSessionIsExpired() {
        UserSession session = new UserSession();
        session.setRevokedAt(null);
        session.setExpiresAt(NOW.minusSeconds(1)); // already expired
        when(repository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(session));

        assertThat(service.resolveActive("jti")).isEmpty();
    }

    @Test
    void resolveActive_returnsSession_whenNotRevokedAndNotExpired() {
        UserSession session = new UserSession();
        session.setRevokedAt(null);
        session.setExpiresAt(NOW.plusSeconds(3600));
        when(repository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(session));

        assertThat(service.resolveActive("jti")).contains(session);
    }

    @Test
    void revokeOwnSession_refusesToRevoke_whenSessionBelongsToAnotherUser() {
        UserSession session = new UserSession();
        session.setUserId("OTHER_USER");
        when(repository.findById(42L)).thenReturn(Optional.of(session));

        boolean result = service.revokeOwnSession(42L, "CALLING_USER");

        assertThat(result).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void revokeOwnSession_returnsFalse_whenSessionDoesNotExist() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.revokeOwnSession(99L, "U1")).isFalse();
    }

    @Test
    void revokeOwnSession_revokesAndSaves_whenCallerOwnsTheSession() {
        UserSession session = new UserSession();
        session.setUserId("U1");
        session.setRevokedAt(null);
        when(repository.findById(7L)).thenReturn(Optional.of(session));

        boolean result = service.revokeOwnSession(7L, "U1");

        assertThat(result).isTrue();
        assertThat(session.getRevokedAt()).isEqualTo(NOW);
        verify(repository).save(session);
    }

    @Test
    void revokeAllOthers_fallsBackToRevokeAll_whenCurrentSessionHashIsUnknown() {
        when(repository.revokeAllForUser("U1", NOW)).thenReturn(3);

        int count = service.revokeAllOthers("U1", null);

        assertThat(count).isEqualTo(3);
        verify(repository).revokeAllForUser("U1", NOW);
        verify(repository, never()).revokeAllForUserExceptHash(anyString(), anyString(), any());
    }

    @Test
    void revokeAllOthers_excludesTheCurrentSessionHash_whenKnown() {
        when(repository.revokeAllForUserExceptHash("U1", "current-hash", NOW)).thenReturn(2);

        int count = service.revokeAllOthers("U1", "current-hash");

        assertThat(count).isEqualTo(2);
        verify(repository).revokeAllForUserExceptHash("U1", "current-hash", NOW);
        verify(repository, never()).revokeAllForUser(anyString(), any());
    }

    @Test
    void listActiveSessions_excludesExpiredRows_andMarksTheMatchingHashAsCurrent() {
        UserSession active = new UserSession();
        active.setId(1L);
        active.setRefreshTokenHash("hash-a");
        active.setExpiresAt(NOW.plusSeconds(60));

        UserSession expired = new UserSession();
        expired.setId(2L);
        expired.setRefreshTokenHash("hash-b");
        expired.setExpiresAt(NOW.minusSeconds(60));

        when(repository.findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc("U1"))
                .thenReturn(List.of(active, expired));

        var dtos = service.listActiveSessions("U1", "hash-a");

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).getId()).isEqualTo(1L);
        assertThat(dtos.get(0).isCurrent()).isTrue();
    }

    // ─── rotate: atomic compare-and-swap, exactly one concurrent caller may win ───

    @Test
    void rotate_returnsTrue_whenTheCompareAndSwapUpdatesExactlyOneRow() {
        UserSession session = new UserSession();
        session.setId(1L);
        session.setRefreshTokenHash("old-hash");
        when(repository.rotateIfCurrentHashMatches(eq(1L), eq("old-hash"), anyString(), any(), any(), any(), any()))
                .thenReturn(1);

        boolean result = service.rotate(session, "new-raw-jti", NOW.plusSeconds(3600), "UA", "1.2.3.4");

        assertThat(result).isTrue();
    }

    @Test
    void rotate_returnsFalse_whenTheHashNoLongerMatches_neverThrows() {
        // Simulates having lost a concurrent-refresh race: another caller already
        // rotated this session between when this caller read it and now.
        UserSession session = new UserSession();
        session.setId(1L);
        session.setRefreshTokenHash("stale-hash");
        when(repository.rotateIfCurrentHashMatches(eq(1L), eq("stale-hash"), anyString(), any(), any(), any(), any()))
                .thenReturn(0);

        boolean result = service.rotate(session, "new-raw-jti", NOW.plusSeconds(3600), "UA", "1.2.3.4");

        assertThat(result).isFalse();
    }

    @Test
    void rotate_comparesAgainstTheSessionsOwnHashAtCallTime_notSomeOtherValue() {
        UserSession session = new UserSession();
        session.setId(9L);
        session.setRefreshTokenHash("exact-current-hash");
        when(repository.rotateIfCurrentHashMatches(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.rotate(session, "new-raw-jti", NOW.plusSeconds(3600), null, null);

        verify(repository).rotateIfCurrentHashMatches(
                eq(9L), eq("exact-current-hash"), eq(service.hash("new-raw-jti")),
                eq(NOW.plusSeconds(3600)), eq(NOW), isNull(), isNull());
    }

    // ─── isActiveForUser: the access-token session-validity check (JwtAuthFilter) ──

    @Test
    void isActiveForUser_true_whenSessionExists_belongsToUser_notRevoked_notExpired() {
        UserSession session = new UserSession();
        session.setId(5L);
        session.setUserId("U1");
        session.setRevokedAt(null);
        session.setExpiresAt(NOW.plusSeconds(60));
        when(repository.findById(5L)).thenReturn(java.util.Optional.of(session));

        assertThat(service.isActiveForUser(5L, "U1")).isTrue();
    }

    @Test
    void isActiveForUser_false_whenSessionBelongsToADifferentUser() {
        // The exact cross-user defense-in-depth case: a real session that genuinely
        // belongs to "OTHER_USER" must never be reported active for "U1", even though
        // the row itself is otherwise perfectly valid (not revoked, not expired).
        UserSession session = new UserSession();
        session.setId(5L);
        session.setUserId("OTHER_USER");
        session.setRevokedAt(null);
        session.setExpiresAt(NOW.plusSeconds(60));
        when(repository.findById(5L)).thenReturn(java.util.Optional.of(session));

        assertThat(service.isActiveForUser(5L, "U1")).isFalse();
    }

    @Test
    void isActiveForUser_false_whenSessionDoesNotExist() {
        when(repository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThat(service.isActiveForUser(99L, "U1")).isFalse();
    }

    @Test
    void isActiveForUser_false_whenRevoked() {
        UserSession session = new UserSession();
        session.setId(5L);
        session.setUserId("U1");
        session.setRevokedAt(NOW.minusSeconds(1));
        session.setExpiresAt(NOW.plusSeconds(60));
        when(repository.findById(5L)).thenReturn(java.util.Optional.of(session));

        assertThat(service.isActiveForUser(5L, "U1")).isFalse();
    }

    @Test
    void isActiveForUser_false_whenExpired() {
        UserSession session = new UserSession();
        session.setId(5L);
        session.setUserId("U1");
        session.setRevokedAt(null);
        session.setExpiresAt(NOW.minusSeconds(1));
        when(repository.findById(5L)).thenReturn(java.util.Optional.of(session));

        assertThat(service.isActiveForUser(5L, "U1")).isFalse();
    }

    @Test
    void isActiveForUser_false_whenSessionIdOrUserIdIsNull_neverThrows() {
        assertThat(service.isActiveForUser(null, "U1")).isFalse();
        assertThat(service.isActiveForUser(5L, null)).isFalse();
        verifyNoInteractions(repository);
    }
}
