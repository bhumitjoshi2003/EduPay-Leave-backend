package com.indraacademy.ias_management.filter;

import com.indraacademy.ias_management.repository.RolePermissionRepository;
import com.indraacademy.ias_management.service.UserSessionService;
import com.indraacademy.ias_management.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Immediate session revocation (the "revoked session still usable" fix): an access
 * token is no longer sufficient on its own — the user_session it names via its
 * sessionId claim must still exist, belong to the same user, and be neither revoked
 * nor expired. Uses a REAL JwtUtil (real RSA keypair, real signing/parsing) so
 * signature/claim behavior is genuinely exercised, mocking only the external
 * dependencies (UserDetailsService, RolePermissionRepository, UserSessionService).
 */
class JwtAuthFilterTest {

    private JwtAuthFilter filter;
    private JwtUtil jwtUtil;
    private UserDetailsService userDetailsService;
    private RolePermissionRepository rolePermissionRepository;
    private UserSessionService userSessionService;
    private KeyPair keyPair;

    private static final String USER_ID = "T1";
    private static final Long SESSION_ID = 42L;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "privateKey", Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        ReflectionTestUtils.setField(jwtUtil, "publicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiryMinutes", 60L);

        userDetailsService = mock(UserDetailsService.class);
        rolePermissionRepository = mock(RolePermissionRepository.class);
        userSessionService = mock(UserSessionService.class);

        filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(filter, "userDetailsService", userDetailsService);
        ReflectionTestUtils.setField(filter, "rolePermissionRepository", rolePermissionRepository);
        ReflectionTestUtils.setField(filter, "userSessionService", userSessionService);

        UserDetails userDetails = new User(USER_ID, "irrelevant", true, true, true, true, List.of());
        lenient().when(userDetailsService.loadUserByUsername(USER_ID)).thenReturn(userDetails);
        lenient().when(rolePermissionRepository.findPermissionKeysByRoleAndSchool(any(), any())).thenReturn(List.of());

        SecurityContextHolder.clearContext();
    }

    private String accessToken(String userId, Long sessionId) {
        var builder = Jwts.builder()
                .setSubject(userId)
                .claim("role", "TEACHER")
                .claim("userId", userId)
                .claim("schoolId", 1L)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60000));
        if (sessionId != null) builder.claim("sessionId", sessionId);
        return builder.signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256).compact();
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/students");
        request.setCookies(new jakarta.servlet.http.Cookie("accessToken", token));
        return request;
    }

    @Test
    void activeSession_isAuthenticated_andRequestProceeds() throws Exception {
        when(userSessionService.isActiveForUser(SESSION_ID, USER_ID)).thenReturn(true);

        MockHttpServletRequest request = requestWithToken(accessToken(USER_ID, SESSION_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull(); // filterChain.doFilter was reached
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(USER_ID);
        assertThat(response.getStatus()).isEqualTo(200); // MockHttpServletResponse default, never set to 401
    }

    @Test
    void missingSessionId_legacyToken_isRejectedWithUnauthorized() throws Exception {
        MockHttpServletRequest request = requestWithToken(accessToken(USER_ID, null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull(); // request never reached the rest of the chain
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userSessionService, never()).isActiveForUser(any(), any());
    }

    @Test
    void sessionNotFound_isRejectedWithUnauthorized() throws Exception {
        when(userSessionService.isActiveForUser(SESSION_ID, USER_ID)).thenReturn(false);

        MockHttpServletRequest request = requestWithToken(accessToken(USER_ID, SESSION_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void revokedSession_isRejectedWithUnauthorized() throws Exception {
        // isActiveForUser's own contract already folds "revoked" into false — this test
        // proves the filter honors that false verdict exactly like "not found".
        when(userSessionService.isActiveForUser(SESSION_ID, USER_ID)).thenReturn(false);

        MockHttpServletRequest request = requestWithToken(accessToken(USER_ID, SESSION_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesTheTokensOwnUserIdToTheSessionCheck_neverSomeOtherValue() throws Exception {
        // The filter must ask "is session SESSION_ID active FOR EXACTLY THIS TOKEN'S
        // OWN userId" — never a hardcoded value, never some other principal. The actual
        // cross-user rejection logic (a session row that legitimately belongs to a
        // different user must never match) lives in UserSessionService and is proven
        // there (UserSessionServiceTest, UserSessionServicePostgresIT); this test only
        // proves the filter wires the token's own claims through unmodified.
        when(userSessionService.isActiveForUser(eq(SESSION_ID), eq(USER_ID))).thenReturn(true);

        MockHttpServletRequest request = requestWithToken(accessToken(USER_ID, SESSION_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        verify(userSessionService).isActiveForUser(SESSION_ID, USER_ID);
        verify(userSessionService, never()).isActiveForUser(eq(SESSION_ID), argThat(u -> !USER_ID.equals(u)));
    }

    @Test
    void sessionLookupThrows_failsClosed_neverAuthenticates() throws Exception {
        when(userSessionService.isActiveForUser(any(), any())).thenThrow(new RuntimeException("DB unavailable"));

        MockHttpServletRequest request = requestWithToken(accessToken(USER_ID, SESSION_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void publicRoute_bypassesAuthEntirely_regardlessOfSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(userSessionService);
    }

    // ─── Regression: "object storage upload auth bug" ──────────────────────────────
    // Root cause was path.startsWith("/api/files/") in the bypass block above, which silently
    // skipped JWT parsing for FileUploadRequestController's upload-request/complete endpoints
    // too (they only share a URL prefix with the one legacy endpoint the bypass was meant for,
    // /api/files/uploadEventImage). SecurityContextHolder's authentication was NEVER set for
    // these paths, so every caller — including a genuinely valid, active session — fell through
    // to Spring Security's anonymous principal and was denied 401 by @PreAuthorize("isAuthenticated()"),
    // regardless of whether the token presented was fresh (post-refresh) or stale. Fixed by
    // matching only the exact legacy path, mirroring SecurityConfig's own requestMatchers(...).

    @Test
    void uploadRequestPath_withValidActiveSession_authenticatesAndProceeds() throws Exception {
        when(userSessionService.isActiveForUser(SESSION_ID, USER_ID)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/upload-request");
        request.setCookies(new jakarta.servlet.http.Cookie("accessToken", accessToken(USER_ID, SESSION_ID)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(USER_ID);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void completePath_withValidActiveSession_authenticatesAndProceeds() throws Exception {
        when(userSessionService.isActiveForUser(SESSION_ID, USER_ID)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/complete");
        request.setCookies(new jakarta.servlet.http.Cookie("accessToken", accessToken(USER_ID, SESSION_ID)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void uploadRequestPath_revokedSession_isRejectedWithUnauthorized() throws Exception {
        // Proves the endpoint is genuinely subject to the same session-revocation enforcement as
        // any other authenticated route now — not merely "reached", but actually validated.
        when(userSessionService.isActiveForUser(SESSION_ID, USER_ID)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/upload-request");
        request.setCookies(new jakarta.servlet.http.Cookie("accessToken", accessToken(USER_ID, SESSION_ID)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void uploadRequestPath_noAccessTokenCookieAtAll_reachesChainWithNoAuthentication() throws Exception {
        // No cookies at all: this filter defers entirely (same as it always has for any path) —
        // it's Spring Security's own anonymous-principal handling plus
        // @PreAuthorize("isAuthenticated()") downstream that produces the eventual 401 for a
        // genuinely unauthenticated caller, proven separately at the full-stack level in
        // FileUploadRequestSecurityTest. This test only proves THIS filter never fabricates an
        // authentication for a request that presents no credentials whatsoever.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/upload-request");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userSessionService);
    }

    @Test
    void legacyUploadEventImagePath_stillBypassesAuthFilterEntirely() throws Exception {
        // The ONE legacy endpoint the /api/files/ prefix exemption was ever meant to cover
        // (SecurityConfig's own exact-match permitAll) must remain untouched by the fix.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/files/uploadEventImage");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(userSessionService);
    }
}
