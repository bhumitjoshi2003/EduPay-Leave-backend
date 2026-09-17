package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    List<UserSession> findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(String userId);

    /** Atomic compare-and-swap: rotates a session's refresh token ONLY if its hash is
     * still exactly oldHash at the moment this UPDATE actually runs. Under Postgres's
     * default READ COMMITTED isolation, a concurrent UPDATE targeting the same row
     * blocks on the row lock, then — once the first writer commits — re-evaluates its
     * own WHERE clause against the now-current row. So of two concurrent refreshes
     * presenting the same (soon-to-be-stale) token, at most one ever matches oldHash
     * and returns 1; the other sees a hash that no longer matches and returns 0. This
     * is what makes "exactly one concurrent refresh succeeds" true without any
     * explicit lock statement or in-memory synchronization. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.refreshTokenHash = :newHash, s.expiresAt = :newExpiresAt, " +
            "s.lastUsedAt = :now, s.userAgent = COALESCE(:userAgent, s.userAgent), " +
            "s.ipAddress = COALESCE(:ipAddress, s.ipAddress) " +
            "WHERE s.id = :id AND s.refreshTokenHash = :oldHash AND s.revokedAt IS NULL")
    int rotateIfCurrentHashMatches(@Param("id") Long id, @Param("oldHash") String oldHash,
                                    @Param("newHash") String newHash, @Param("newExpiresAt") Instant newExpiresAt,
                                    @Param("now") Instant now, @Param("userAgent") String userAgent,
                                    @Param("ipAddress") String ipAddress);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") String userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now " +
            "WHERE s.userId = :userId AND s.revokedAt IS NULL AND s.refreshTokenHash <> :currentHash")
    int revokeAllForUserExceptHash(@Param("userId") String userId, @Param("currentHash") String currentHash,
                                    @Param("now") Instant now);
}
