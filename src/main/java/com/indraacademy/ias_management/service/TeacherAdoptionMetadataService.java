package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherAdoptionMetadataRequest;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

@Service
public class TeacherAdoptionMetadataService {
    private final UserRepository users;
    private final SecurityUtil security;
    private final Clock clock;

    public TeacherAdoptionMetadataService(UserRepository users, SecurityUtil security, Clock clock) {
        this.users = users;
        this.security = security;
        this.clock = clock;
    }

    @Transactional
    public void reportAndroidVersion(TeacherAdoptionMetadataRequest request) {
        User user = currentTeacher();
        user.setClientPlatform("ANDROID");
        user.setAppVersionName(request.appVersionName().trim());
        user.setAppVersionCode(request.appVersionCode());
        user.setClientReportedAt(clock.instant());
        users.save(user);
    }

    @Transactional
    public void completeOnboarding() {
        User user = currentTeacher();
        if (user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(clock.instant());
            users.save(user);
        }
    }

    private User currentTeacher() {
        return users.findByUserIdAndSchoolIdAndActiveTrue(security.getUsername(), security.getSchoolId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher account not found"));
    }
}
