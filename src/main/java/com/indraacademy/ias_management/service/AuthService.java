package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Autowired private JwtUtil jwtUtil;

    private Claims parseToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            log.warn("Attempted to parse a null or empty token string.");
            return null;
        }

        try {
            Jws<Claims> claimsJws = Jwts.parserBuilder()
                    .setSigningKey(jwtUtil.getPublicKey())
                    .build()
                    .parseClaimsJws(token);

            log.debug("Token parsed successfully.");
            return claimsJws.getBody();

        } catch (SignatureException e) {
            log.warn("JWT Signature validation failed for token: {}", token, e);
        } catch (ExpiredJwtException e) {
            log.warn("JWT is expired for token: {}", token, e);
        } catch (MalformedJwtException e) {
            log.warn("JWT is malformed: {}", token, e);
        } catch (UnsupportedJwtException e) {
            log.warn("JWT format is unsupported: {}", token, e);
        } catch (IllegalArgumentException e) {
            log.error("JWT string is null or empty when passed to parser: {}", token, e);
        } catch (JwtException e) {
            log.error("Generic JWT exception occurred for token: {}", token, e);
        }
        return null;
    }

    private String extractTokenString(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.trim().isEmpty()) {
            log.debug("Authorization header is null or empty.");
            return null;
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            log.warn("Authorization header does not start with 'Bearer ': {}", authorizationHeader);
            return null;
        }

        String token = authorizationHeader.substring(7);
        if (token.trim().isEmpty()) {
            log.warn("Extracted token string is empty after removing 'Bearer '.");
            return null;
        }
        return token;
    }

    public String getUserIdFromToken(String authorizationHeader) {
        log.info("Attempting to extract userId from authorization header.");
        String token = extractTokenString(authorizationHeader);
        if (token == null) {
            return null;
        }

        Claims claims = parseToken(token);
        String userId = (claims != null) ? claims.get("userId", String.class) : null;

        if (userId != null) {
            log.info("Successfully extracted userId: {}", userId);
        } else {
            log.debug("Could not extract valid userId claim.");
        }
        return userId;
    }

    public String getRoleFromToken(String authorizationHeader) {
        log.info("Attempting to extract role from authorization header.");
        String token = extractTokenString(authorizationHeader);
        if (token == null) {
            return null;
        }

        Claims claims = parseToken(token);
        String role = (claims != null) ? claims.get("role", String.class) : null;

        if (role != null) {
            log.info("Successfully extracted role: {}", role);
        } else {
            log.debug("Could not extract valid role claim.");
        }
        return role;
    }

    public Map<String, String> getUserIdAndRoleFromToken(String authorizationHeader) {
        log.info("Attempting to extract userId and role from authorization header.");

        String token = extractTokenString(authorizationHeader);
        if (token == null) {
            return Collections.emptyMap();
        }

        Claims claims = parseToken(token);

        if (claims != null) {
            Map<String, String> claimsMap = new HashMap<>();

            String userId = claims.get("userId", String.class);
            String role = claims.get("role", String.class);

            if (userId != null) {
                claimsMap.put("userId", userId);
            } else {
                log.warn("Token parsed but 'userId' claim was missing or null.");
            }

            if (role != null) {
                claimsMap.put("role", role);
            } else {
                log.warn("Token parsed but 'role' claim was missing or null.");
            }

            log.info("Successfully extracted claims. UserID present: {}, Role present: {}", claimsMap.containsKey("userId"), claimsMap.containsKey("role"));
            return claimsMap;
        }

        log.debug("Token could not be parsed, returning empty map.");
        return Collections.emptyMap();
    }
}