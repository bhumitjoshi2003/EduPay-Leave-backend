package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.repository.DeviceTokenRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTokenControllerTest {

    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private AuthService authService;
    @Mock private SecurityUtil securityUtil;
    @InjectMocks private DeviceTokenController controller;

    @BeforeEach
    void authenticatedUser() {
        when(authService.getUserId()).thenReturn("teacher-1");
        when(securityUtil.getSchoolId()).thenReturn(2L);
    }

    @Test
    void removesOwnTokenUsingAuthenticatedUserAndTenant() {
        when(deviceTokenRepository.deleteByTokenAndUserIdAndSchoolId("device-token", "teacher-1", 2L))
                .thenReturn(1L);

        var response = controller.removeToken(Map.of("token", "device-token"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(deviceTokenRepository)
                .deleteByTokenAndUserIdAndSchoolId("device-token", "teacher-1", 2L);
        verify(deviceTokenRepository, never()).deleteByToken("device-token");
    }

    @Test
    void cannotDeleteAnotherUsersToken() {
        when(deviceTokenRepository.deleteByTokenAndUserIdAndSchoolId("other-user-token", "teacher-1", 2L))
                .thenReturn(0L);

        controller.removeToken(Map.of("token", "other-user-token"));

        verify(deviceTokenRepository)
                .deleteByTokenAndUserIdAndSchoolId("other-user-token", "teacher-1", 2L);
        verify(deviceTokenRepository, never()).deleteByToken("other-user-token");
    }

    @Test
    void cannotDeleteTokenFromAnotherSchool() {
        when(deviceTokenRepository.deleteByTokenAndUserIdAndSchoolId("school-3-token", "teacher-1", 2L))
                .thenReturn(0L);

        controller.removeToken(Map.of("token", "school-3-token"));

        verify(deviceTokenRepository)
                .deleteByTokenAndUserIdAndSchoolId("school-3-token", "teacher-1", 2L);
        verify(deviceTokenRepository, never()).deleteByToken("school-3-token");
    }
}
