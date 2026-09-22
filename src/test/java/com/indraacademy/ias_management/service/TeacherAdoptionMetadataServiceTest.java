package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherAdoptionMetadataRequest;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherAdoptionMetadataServiceTest {
    @Mock UserRepository users;
    @Mock SecurityUtil security;
    TeacherAdoptionMetadataService service;
    User teacher;
    Instant now = Instant.parse("2026-09-22T03:30:00Z");

    @BeforeEach void setup() {
        service = new TeacherAdoptionMetadataService(users, security, Clock.fixed(now, ZoneOffset.UTC));
        teacher = new User(); teacher.setUserId("T1"); teacher.setSchoolId(7L); teacher.setActive(true);
        when(security.getUsername()).thenReturn("T1"); when(security.getSchoolId()).thenReturn(7L);
        when(users.findByUserIdAndSchoolIdAndActiveTrue("T1", 7L)).thenReturn(Optional.of(teacher));
    }

    @Test void reportsLatestVersionForAuthenticatedTeacherOnly() {
        service.reportAndroidVersion(new TeacherAdoptionMetadataRequest(" 1.3.0 ", 13));
        assertThat(teacher.getClientPlatform()).isEqualTo("ANDROID");
        assertThat(teacher.getAppVersionName()).isEqualTo("1.3.0");
        assertThat(teacher.getAppVersionCode()).isEqualTo(13);
        assertThat(teacher.getClientReportedAt()).isEqualTo(now);
        verify(users).save(teacher);
    }

    @Test void completionIsIdempotentAndKeepsOriginalTimestamp() {
        service.completeOnboarding(); service.completeOnboarding();
        assertThat(teacher.getOnboardingCompletedAt()).isEqualTo(now);
        verify(users, times(1)).save(teacher);
    }
}
