package com.indraacademy.ias_management.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SubAdminDashboardContractSecurityTest {

    @Test
    void mixedDomainDashboardStatsRemainAdminAndSuperAdminOnly() {
        PreAuthorize authorization = DashboardController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }

    @Test
    void staffTodaySummaryRemainsAdminOnly() throws Exception {
        Method endpoint = TeacherAttendanceController.class.getMethod("getTodaySummary");
        PreAuthorize authorization = endpoint.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }
}
