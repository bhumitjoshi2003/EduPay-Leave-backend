package com.indraacademy.ias_management.util;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the pwdChangeRequired claim used to enforce a restricted first-login
 * session (see JwtAuthFilter's allowlist and AuthController.login).
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "privateKey", Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        ReflectionTestUtils.setField(jwtUtil, "publicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiryMinutes", 15L);
    }

    private String tokenWithClaim(Object pwdChangeRequired) {
        JwtBuilder builder = Jwts.builder()
                .setSubject("U1")
                .claim("userId", "U1")
                .claim("role", "STUDENT")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60000));
        if (pwdChangeRequired != null) {
            builder.claim("pwdChangeRequired", pwdChangeRequired);
        }
        return builder.signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256).compact();
    }

    @Test
    void extractPasswordChangeRequired_true() {
        assertThat(jwtUtil.extractPasswordChangeRequired(tokenWithClaim(true))).isTrue();
    }

    @Test
    void extractPasswordChangeRequired_false() {
        assertThat(jwtUtil.extractPasswordChangeRequired(tokenWithClaim(false))).isFalse();
    }

    @Test
    void extractPasswordChangeRequired_absentClaim_defaultsFalse() {
        // A normal (non-restricted) session's access token never carries this claim.
        assertThat(jwtUtil.extractPasswordChangeRequired(tokenWithClaim(null))).isFalse();
    }
}
