package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.UserSessionDto;
import com.indraacademy.ias_management.entity.UserSession;
import com.indraacademy.ias_management.repository.UserSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * One row per login (see UserSession). Every operation here is scoped to a single
 * session or a single user's own sessions — refreshing or revoking one session must
 * never touch another, which is exactly the guarantee the old users.refresh_token_id
 * (a single column shared by every login) could not make.
 */
@Service
public class UserSessionService {

    @Autowired private UserSessionRepository repository;
    @Autowired private Clock clock;

    @Transactional
    public UserSession createSession(String userId, String rawRefreshTokenId, Instant expiresAt,
                                      String userAgent, String ipAddress) {
        UserSession session = new UserSession();
        session.setUserId(userId);
        session.setRefreshTokenHash(hash(rawRefreshTokenId));
        Instant now = clock.instant();
        session.setCreatedAt(now);
        session.setLastUsedAt(now);
        session.setExpiresAt(expiresAt);
        session.setUserAgent(truncate(userAgent, 255));
        session.setIpAddress(truncate(ipAddress, 64));
        return repository.save(session);
    }

    /** Resolves the still-usable (not revoked, not expired) session for a raw refresh
     * token, or empty if it has been revoked/expired/never existed — the exact cases
     * that must reject a refresh attempt. */
    @Transactional(readOnly = true)
    public Optional<UserSession> resolveActive(String rawRefreshTokenId) {
        Instant now = clock.instant();
        return repository.findByRefreshTokenHash(hash(rawRefreshTokenId))
                .filter(s -> s.getRevokedAt() == null)
                .filter(s -> s.getExpiresAt().isAfter(now));
    }

    /** Rotates the SAME session row onto a new refresh token — the session's identity
     * (its id, its history) is preserved; only the token material changes. This is
     * what makes rotation safe to add here: it was already the existing per-user
     * behavior (every refresh already rotated the single global JTI), so scoping it
     * to one row instead of one user changes only the blast radius, not the shape of
     * the guarantee an already-issued refresh token has.
     * <p>
     * Returns false — and leaves the row completely untouched — if session's own hash
     * no longer matches what the caller last read (i.e. a concurrent refresh already
     * won and rotated it first, or it was revoked in the meantime). Callers MUST treat
     * false as "this refresh attempt is rejected", never as a partial success: exactly
     * one of two concurrent refreshes presenting the same token may ever return true. */
    @Transactional
    public boolean rotate(UserSession session, String newRawRefreshTokenId, Instant newExpiresAt,
                           String userAgent, String ipAddress) {
        int updated = repository.rotateIfCurrentHashMatches(
                session.getId(), session.getRefreshTokenHash(), hash(newRawRefreshTokenId),
                newExpiresAt, clock.instant(), truncate(userAgent, 255), truncate(ipAddress, 64));
        return updated == 1;
    }

    /** Revokes exactly the one session a raw refresh token belongs to (logout) —
     * never any other session for the same user. A no-op (not an error) if the token
     * is unknown/already revoked, matching logout's existing best-effort behavior. */
    @Transactional
    public void revokeByRawToken(String rawRefreshTokenId) {
        repository.findByRefreshTokenHash(hash(rawRefreshTokenId)).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(clock.instant());
                repository.save(session);
            }
        });
    }

    /** Revokes one specific session by id, but ONLY if it belongs to callerUserId —
     * returns false (never throws, never reveals whether the id belongs to someone
     * else) otherwise, so a user's session endpoints can never expose or revoke
     * another user's session. */
    @Transactional
    public boolean revokeOwnSession(Long sessionId, String callerUserId) {
        Optional<UserSession> found = repository.findById(sessionId);
        if (found.isEmpty() || !found.get().getUserId().equals(callerUserId)) {
            return false;
        }
        UserSession session = found.get();
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(clock.instant());
            repository.save(session);
        }
        return true;
    }

    /** Revokes every active session for a user — used for "log out everywhere" and
     * for the existing forced-logout policy on deactivation/password-reset flows
     * (previously implemented by nulling the single users.refresh_token_id column). */
    @Transactional
    public int revokeAllForUser(String userId) {
        return repository.revokeAllForUser(userId, clock.instant());
    }

    /** Revokes every active session for a user EXCEPT the one matching
     * currentSessionHash — "log out all other sessions". Falls back to revoking all
     * of them if the caller's own current session cannot be identified (e.g. no
     * refresh-token cookie present), which is still safe: it only ever touches the
     * calling user's own sessions. */
    @Transactional
    public int revokeAllOthers(String userId, String currentSessionHash) {
        if (currentSessionHash == null) {
            return repository.revokeAllForUser(userId, clock.instant());
        }
        return repository.revokeAllForUserExceptHash(userId, currentSessionHash, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<UserSessionDto> listActiveSessions(String userId, String currentSessionHash) {
        Instant now = clock.instant();
        return repository.findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(userId).stream()
                .filter(s -> s.getExpiresAt().isAfter(now))
                .map(s -> UserSessionDto.from(s, s.getRefreshTokenHash().equals(currentSessionHash)))
                .toList();
    }

    /** SHA-256 hex digest — same convention as PasswordResetService#hashToken. Public
     * so AuthController can compute the current request's session hash (to identify
     * "isCurrent" / exclude it from "revoke others") without duplicating the digest
     * logic or ever needing to store/compare the raw token itself. */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash refresh token", e);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
