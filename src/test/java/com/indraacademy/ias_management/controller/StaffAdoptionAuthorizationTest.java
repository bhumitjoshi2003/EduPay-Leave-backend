package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.StaffAdoptionResponse;
import com.indraacademy.ias_management.service.StaffAdoptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(StaffAdoptionAuthorizationTest.Config.class)
class StaffAdoptionAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean StaffAdoptionService service() { return mock(StaffAdoptionService.class); }
        @Bean StaffAdoptionController controller(StaffAdoptionService service) { return new StaffAdoptionController(service); }
    }

    @Autowired StaffAdoptionController controller;
    @Autowired StaffAdoptionService service;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void adminCanAccess() {
        authenticate("ADMIN");
        when(service.getStaffAdoption()).thenReturn(new StaffAdoptionResponse(
                new StaffAdoptionResponse.Summary(0, 0, 0, 0, 0), List.of()));
        assertThatCode(() -> controller.getStaffAdoption()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEACHER", "STUDENT", "PARENT", "SUB_ADMIN", "SUPER_ADMIN"})
    void nonAdminsCannotAccess(String role) {
        authenticate(role);
        assertThatThrownBy(() -> controller.getStaffAdoption())
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void anonymousCannotAccess() {
        assertThatThrownBy(() -> controller.getStaffAdoption())
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test", "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
