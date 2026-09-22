package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.StaffAdoptionReminderPreviewResponse;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderRequest;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderSendResponse;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderType;
import com.indraacademy.ias_management.dto.StaffAdoptionResponse;
import com.indraacademy.ias_management.service.StaffAdoptionReminderService;
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

/** Mirrors {@link StaffAdoptionAuthorizationTest}: reminders share the same
 *  {@code /api/admin/staff-adoption} controller and ADMIN-only class-level authorization. */
@SpringJUnitConfig(StaffAdoptionReminderAuthorizationTest.Config.class)
class StaffAdoptionReminderAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean StaffAdoptionService service() { return mock(StaffAdoptionService.class); }
        @Bean StaffAdoptionReminderService reminderService() { return mock(StaffAdoptionReminderService.class); }
        @Bean StaffAdoptionController controller(StaffAdoptionService service, StaffAdoptionReminderService reminderService) {
            return new StaffAdoptionController(service, reminderService);
        }
    }

    @Autowired StaffAdoptionController controller;
    @Autowired StaffAdoptionReminderService reminderService;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void adminCanPreviewAndSendReminders() {
        authenticate("ADMIN");
        when(reminderService.preview(StaffAdoptionReminderType.NOT_STARTED))
                .thenReturn(new StaffAdoptionReminderPreviewResponse(0, List.of()));
        when(reminderService.send(StaffAdoptionReminderType.NOT_STARTED))
                .thenReturn(new StaffAdoptionReminderSendResponse(StaffAdoptionReminderType.NOT_STARTED, 0, 0, 0));

        assertThatCode(() -> controller.previewReminder(StaffAdoptionReminderType.NOT_STARTED)).doesNotThrowAnyException();
        assertThatCode(() -> controller.sendReminder(new StaffAdoptionReminderRequest(StaffAdoptionReminderType.NOT_STARTED)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEACHER", "STUDENT", "PARENT", "SUB_ADMIN", "SUPER_ADMIN"})
    void nonAdminsCannotPreviewOrSendReminders(String role) {
        authenticate(role);
        assertThatThrownBy(() -> controller.previewReminder(StaffAdoptionReminderType.NOT_STARTED))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> controller.sendReminder(new StaffAdoptionReminderRequest(StaffAdoptionReminderType.NOT_STARTED)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void anonymousCannotPreviewOrSendReminders() {
        assertThatThrownBy(() -> controller.previewReminder(StaffAdoptionReminderType.NOT_STARTED))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThatThrownBy(() -> controller.sendReminder(new StaffAdoptionReminderRequest(StaffAdoptionReminderType.NOT_STARTED)))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test", "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
