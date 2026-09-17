package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.UserSession;

import java.time.Instant;

/** Read-only view of a UserSession for the "Active Sessions" listing — never exposes
 * the refresh token hash or any other user's data (see AuthController#listSessions). */
public class UserSessionDto {
    private Long id;
    private String userAgent;
    private String ipAddress;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;
    private boolean current;

    public static UserSessionDto from(UserSession session, boolean current) {
        UserSessionDto dto = new UserSessionDto();
        dto.id = session.getId();
        dto.userAgent = session.getUserAgent();
        dto.ipAddress = session.getIpAddress();
        dto.createdAt = session.getCreatedAt();
        dto.lastUsedAt = session.getLastUsedAt();
        dto.expiresAt = session.getExpiresAt();
        dto.current = current;
        return dto;
    }

    public Long getId() { return id; }
    public String getUserAgent() { return userAgent; }
    public String getIpAddress() { return ipAddress; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isCurrent() { return current; }
}
