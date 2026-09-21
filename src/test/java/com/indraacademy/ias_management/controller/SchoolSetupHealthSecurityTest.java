package com.indraacademy.ias_management.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SchoolSetupHealthSecurityTest {
    @Test
    void setupHealthIsRestrictedToAdminOnly() throws Exception {
        Method endpoint = SchoolController.class.getMethod("getSetupHealth");
        PreAuthorize authorization = endpoint.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
        // hasRole('ADMIN') is a single-role check — SUB_ADMIN, SUPER_ADMIN, TEACHER, STUDENT and
        // PARENT are all denied by construction, not by an enumerated exclusion list that could
        // drift out of sync with the Role constants. See SchoolSetupHealthEndToEndSecurityTest
        // for the full-stack proof that unauthenticated callers actually get 401 at runtime.
    }
}
