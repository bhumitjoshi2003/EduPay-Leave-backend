package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.StudentEnrollmentBackfillReport;
import com.indraacademy.ias_management.dto.StudentEnrollmentBulkBackfillReport;
import com.indraacademy.ias_management.service.StudentEnrollmentBackfillService;
import com.indraacademy.ias_management.service.StudentEnrollmentBulkBackfillService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentEnrollmentMaintenanceControllerTest {

    @Mock StudentEnrollmentBackfillService backfillService;
    @Mock StudentEnrollmentBulkBackfillService bulkBackfillService;
    @Mock SecurityUtil securityUtil;

    private StudentEnrollmentMaintenanceController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentEnrollmentMaintenanceController(backfillService, bulkBackfillService, securityUtil);
    }

    @Test
    void bulkEndpointIsRestrictedToSuperAdminOnly() throws NoSuchMethodException {
        Method method = StudentEnrollmentMaintenanceController.class.getMethod(
                "backfillActiveSchools", boolean.class, LocalDate.class, List.class);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        // Exactly hasRole('SUPER_ADMIN') — not hasAnyRole — so an ADMIN-role JWT cannot satisfy
        // this expression and Spring Security rejects the call with 403 before the method body runs.
        assertThat(authorization.value()).isEqualTo("hasRole('SUPER_ADMIN')");
    }

    @Test
    void perSchoolEndpointRemainsAdminOnlyAndUnchanged() throws NoSuchMethodException {
        Method method = StudentEnrollmentMaintenanceController.class.getMethod(
                "backfillCurrentState", boolean.class, LocalDate.class);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void perSchoolEndpointStillResolvesSchoolFromCallerJwt() {
        LocalDate asOf = LocalDate.of(2026, 9, 7);
        when(securityUtil.getSchoolId()).thenReturn(42L);
        when(backfillService.backfillForSchool(42L, asOf, true))
                .thenReturn(new StudentEnrollmentBackfillReport(
                        42L, asOf, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()));

        controller.backfillCurrentState(true, asOf);

        verify(backfillService).backfillForSchool(42L, asOf, true);
    }

    @Test
    void bulkEndpointDelegatesToOrchestratorWithGivenParametersAndDefaultsAsOfDateToToday() {
        StudentEnrollmentBulkBackfillReport report = new StudentEnrollmentBulkBackfillReport(
                LocalDate.now(), true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        when(bulkBackfillService.backfillActiveSchools(LocalDate.now(), true, null)).thenReturn(report);

        StudentEnrollmentBulkBackfillReport result = controller.backfillActiveSchools(true, null, null);

        assertThat(result).isSameAs(report);
        verify(bulkBackfillService).backfillActiveSchools(LocalDate.now(), true, null);
    }

    @Test
    void bulkEndpointForwardsExplicitAsOfDateAndSchoolIdsUnchanged() {
        LocalDate asOf = LocalDate.of(2026, 9, 1);
        List<Long> schoolIds = List.of(10L, 20L);
        StudentEnrollmentBulkBackfillReport report = new StudentEnrollmentBulkBackfillReport(
                asOf, false, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        when(bulkBackfillService.backfillActiveSchools(asOf, false, schoolIds)).thenReturn(report);

        StudentEnrollmentBulkBackfillReport result = controller.backfillActiveSchools(false, asOf, schoolIds);

        assertThat(result).isSameAs(report);
        verify(bulkBackfillService).backfillActiveSchools(asOf, false, schoolIds);
    }

    @Test
    void bulkEndpointDoesNotConsultCallerSchoolContext() {
        // The bulk endpoint is cross-tenant by design; it must never scope via the caller's own
        // schoolId the way the per-school endpoint does.
        controller.backfillActiveSchools(true, LocalDate.now(), null);

        org.mockito.Mockito.verifyNoInteractions(securityUtil);
    }
}
