package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    @Autowired private JwtUtil jwtUtil;

    private Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(jwtUtil.getPublicKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTokenString(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7);
    }

    public String getUserIdFromToken(String authorizationHeader) {
        String token = extractTokenString(authorizationHeader);
        if (token == null) return null;

        Claims claims = parseToken(token);
        return (claims != null) ? claims.get("userId", String.class) : null;
    }

    public String getRoleFromToken(String authorizationHeader) {
        String token = extractTokenString(authorizationHeader);
        if (token == null) return null;

        Claims claims = parseToken(token);
        return (claims != null) ? claims.get("role", String.class) : null;
    }

    public java.util.Map<String, String> getUserIdAndRoleFromToken(String authorizationHeader) {
        java.util.Map<String, String> claimsMap = new java.util.HashMap<>();
        String token = extractTokenString(authorizationHeader);
        if (token == null) return claimsMap;

        Claims claims = parseToken(token);
        if (claims != null) {
            claimsMap.put("userId", claims.get("userId", String.class));
            claimsMap.put("role", claims.get("role", String.class));
        }
        return claimsMap;
    }
}