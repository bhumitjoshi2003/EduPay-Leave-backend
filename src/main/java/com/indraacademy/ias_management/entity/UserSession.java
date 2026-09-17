package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One independent, revocable login session — one row per successful login. Never
 * shared between sessions: refreshing or logging out one row must never affect any
 * other row for the same user (see UserSessionService). refreshTokenHash is
 * SHA-256(raw refresh token JTI) — the raw token itself is never persisted anywhere.
 * userAgent/ipAddress are display metadata only, never used to identify a session or
 * make an authorization decision.
 */
@Entity
@Table(name = "user_session")
@Data
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;
}
