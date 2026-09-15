package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ManualPaymentRequest;
import com.indraacademy.ias_management.entity.Payment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Financial AcademicSession Authority, Phase B3/B4 — real-Postgres proof that
 * {@link StudentFeesService#recordManualPayment} resolves the client-supplied session label to
 * an authoritative {@code AcademicSession} row (never persisting an unchecked string), rejects
 * an unresolvable label outright, and that the resulting {@code Payment}/allocation rows carry
 * the resolved session's real id — plus that this resolution is genuinely tenant-scoped (two
 * schools sharing the same session label must never cross-resolve).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentFeesService.class, AcademicSessionService.class, FeeCalculationService.class,
        AuditService.class, com.indraacademy.ias_management.util.SecurityUtil.class,
        StudentFeesServiceSessionAuthorityPostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentFeesServiceSessionAuthorityPostgresIT {

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    private static final long SCHOOL_A = -98501L;
    private static final long SCHOOL_B = -98502L;
    private static final String LABEL = "2025-2026";

    @MockBean private BusinessNotificationService businessNotifications;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StudentFeesService service;

    private long sessionAId;
    private long sessionBId;

    private void seed() {
        jdbc.update("INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) VALUES " +
                        "(?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                SCHOOL_A, LABEL);
        sessionAId = jdbc.queryForObject("SELECT id FROM academic_session WHERE school_id=? AND label=?", Long.class, SCHOOL_A, LABEL);
        jdbc.update("INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) VALUES " +
                        "(?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                SCHOOL_B, LABEL);
        sessionBId = jdbc.queryForObject("SELECT id FROM academic_session WHERE school_id=? AND label=?", Long.class, SCHOOL_B, LABEL);
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    private void insertStudentFees(long schoolId, String studentId, int month) {
        jdbc.update("INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, discount_amount, snapshot_status) " +
                        "VALUES (?, ?, '6A', ?, false, false, ?, 0, false, 0, 1000, 0, 0, 'COMPUTED')",
                schoolId, studentId, month, LABEL);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_student_fees_allocation WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM payment WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM student_fees WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
    }

    /** SecurityUtil is a real bean here (not mocked, matching this repo's *PostgresIT
     * convention for services whose own tenant-scoping logic is exactly what's under test) —
     * it reads from a thread-bound SchoolContext rather than a JWT in this test environment. */
    private void actingAsSchool(long schoolId) {
        com.indraacademy.ias_management.util.SchoolContext.set(schoolId);
    }

    @Test
    void manualPayment_resolvesSessionAndPopulatesAcademicSessionIdOnPaymentAndAllocation() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFees(SCHOOL_A, "STU-MANUAL-A", 1);

        ManualPaymentRequest request = manualRequest("STU-MANUAL-A", LABEL, "100000000000", new BigDecimal("1000"));
        Payment saved = service.recordManualPayment(request, "127.0.0.1");

        assertThat(saved.getAcademicSessionId()).isEqualTo(sessionAId);
        assertThat(jdbc.queryForObject("SELECT academic_session_id FROM payment WHERE id=?", Long.class, saved.getId()))
                .isEqualTo(sessionAId);
        assertThat(jdbc.queryForObject("SELECT academic_session_id FROM payment_student_fees_allocation WHERE payment_id=?",
                Long.class, saved.getId())).isEqualTo(sessionAId);
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    @Test
    void manualPayment_unresolvableSessionLabel_rejectsWithoutCreatingAnyRow() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFees(SCHOOL_A, "STU-MANUAL-BAD", 1);

        ManualPaymentRequest request = manualRequest("STU-MANUAL-BAD", "2099-2100", "100000000000", new BigDecimal("1000"));

        assertThatThrownBy(() -> service.recordManualPayment(request, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcademicSession not found");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE student_id='STU-MANUAL-BAD'", Integer.class))
                .isZero();
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    /** Tenant safety: School A and School B both have a session literally labeled "2025-2026"
     * (allowed — AcademicSession.label is unique per school, not globally). A School-A manual
     * payment must resolve School A's session id, never School B's, even though the labels are
     * byte-identical strings. */
    @Test
    void manualPayment_sameLabelDifferentSchool_resolvesOnlyItsOwnSchoolsSession() {
        seed();
        actingAsSchool(SCHOOL_B);
        insertStudentFees(SCHOOL_B, "STU-MANUAL-B", 1);

        ManualPaymentRequest request = manualRequest("STU-MANUAL-B", LABEL, "100000000000", new BigDecimal("1000"));
        Payment saved = service.recordManualPayment(request, "127.0.0.1");

        assertThat(saved.getAcademicSessionId()).isEqualTo(sessionBId);
        assertThat(saved.getAcademicSessionId()).isNotEqualTo(sessionAId);
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    private ManualPaymentRequest manualRequest(String studentId, String session, String monthSelection, BigDecimal amount) {
        ManualPaymentRequest request = new ManualPaymentRequest();
        request.setStudentId(studentId);
        request.setStudentName("IT Student");
        request.setClassName("6A");
        request.setSession(session);
        request.setMonthSelectionString(monthSelection);
        request.setAmountReceived(amount);
        request.setPaymentMode("CASH");
        return request;
    }
}
