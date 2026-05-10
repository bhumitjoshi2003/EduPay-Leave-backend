package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.filter.JwtAuthFilter;
import com.indraacademy.ias_management.filter.TenantValidationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    @Autowired
    private TenantValidationFilter tenantValidationFilter;

    @Value("${frontend.url}")
    private String frontendUrl;

    /**
     * Comma-separated list of additional CORS origins, typically for mobile/dev clients.
     * Default covers the Capacitor app origin and localhost dev servers.
     * Override in application.properties via cors.additional-origins=...
     */
    @Value("${cors.additional-origins:capacitor://localhost,http://localhost,https://localhost}")
    private String additionalOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();

                    // Wildcard subdomain pattern for all school subdomains (*.edunexify.co.in).
                    // allowedOriginPatterns supports wildcards; allowedOrigins does not.
                    config.addAllowedOriginPattern("https://*.edunexify.co.in");
                    config.addAllowedOriginPattern("http://*.edunexify.co.in");

                    // Root domain (super-admin, marketing site, dev)
                    config.addAllowedOriginPattern(frontendUrl);

                    // Additional origins: Capacitor Android app, localhost dev servers
                    Arrays.stream(additionalOrigins.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(config::addAllowedOriginPattern);

                    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(List.of("*"));
                    config.setAllowCredentials(true);
                    return config;
                }))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login",
                                "/api/auth/reset-password",
                                "/api/auth/request-password-reset",
                                "/api/auth/refresh-token",
                                "/api/public/**",
                                "/actuator/health").permitAll()
                        .requestMatchers("/api/uploads/events/images/**").permitAll()
                        .requestMatchers("/api/uploads/student-photos/**").permitAll()
                        .requestMatchers("/api/uploads/teacher-photos/**").permitAll()
                        .requestMatchers("/api/uploads/admin-photos/**").permitAll()
                        .requestMatchers("/api/files/uploadEventImage").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/demo-requests").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantValidationFilter, JwtAuthFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Unauthorized\"}");
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}