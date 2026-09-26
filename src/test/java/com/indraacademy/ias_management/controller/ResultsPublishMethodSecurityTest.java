package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.service.ExamConfigService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Publishing results is ADMIN-only, enforced by real Spring method security on the controller
 * proxy (not just the annotation text): a TEACHER call is rejected before the service is reached.
 */
@SpringJUnitConfig(ResultsPublishMethodSecurityTest.Config.class)
class ResultsPublishMethodSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean ExamController examController() { return new ExamController(); }
    }

    @Autowired ExamController controller;
    @MockBean ExamConfigService examConfigService;
    @MockBean SecurityUtil securityUtil;
    @MockBean TeacherClassScopeService teacherClassScopeService;

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @BeforeEach
    void reset() { clearInvocations(examConfigService); }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void teacherCannotPublishOrUnpublish() {
        as("TEACHER");
        assertThatThrownBy(() -> controller.publishResults(7L, new MockHttpServletRequest())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.unpublishResults(7L, new MockHttpServletRequest())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(examConfigService);
    }

    @Test
    void otherNonAdminRolesCannotPublish() {
        for (String role : List.of("SUB_ADMIN", "SUPER_ADMIN", "STUDENT", "PARENT")) {
            as(role);
            assertThatThrownBy(() -> controller.publishResults(7L, new MockHttpServletRequest()))
                    .as(role).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(examConfigService);
    }

    @Test
    void adminCanPublishAndUnpublish() {
        as("ADMIN");
        when(examConfigService.publishResults(eq(7L), any())).thenReturn(new ExamConfig());
        when(examConfigService.unpublishResults(eq(7L), any())).thenReturn(new ExamConfig());
        assertThat(controller.publishResults(7L, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.unpublishResults(7L, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(200);
        verify(examConfigService).publishResults(eq(7L), any());
        verify(examConfigService).unpublishResults(eq(7L), any());
    }
}
