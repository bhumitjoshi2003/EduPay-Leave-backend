package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;

import static com.indraacademy.ias_management.service.StudentTemporalMembershipResolver.CoverageClassification.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL coverage for the read-only E6B temporal resolver. Synthetic fixtures use
 * negative identifiers and every test transaction rolls back. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StudentTemporalMembershipResolver.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentTemporalMembershipResolverPostgresIT {

    private static final long SCHOOL_1 = -98101L;
    private static final long SCHOOL_2 = -98102L;
    private static final long SESSION_PRIOR = -98201L;
    private static final long SESSION_CURRENT = -98202L;
    private static final long SESSION_OTHER_TENANT = -98203L;
    private static final long CLASS_9 = -98301L;
    private static final long CLASS_10 = -98302L;
    private static final long CLASS_OTHER = -98303L;
    private static final long SECTION_A = -98401L;
    private static final long SECTION_B = -98402L;
    private static final long SECTION_OTHER = -98403L;
    private static final long SECTION_10_A = -98404L;
    private static final String STUDENT = "E6B-PG-S1";
    private static final String LEGACY_STUDENT = "E6B-PG-LEGACY";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StudentTemporalMembershipResolver resolver;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void fixtures() {
        insertSchool(SCHOOL_1, "e6b-resolver-one");
        insertSchool(SCHOOL_2, "e6b-resolver-two");
        insertSession(SESSION_PRIOR, SCHOOL_1, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        insertSession(SESSION_CURRENT, SCHOOL_1, "2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertSession(SESSION_OTHER_TENANT, SCHOOL_2, "2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertClass(CLASS_9, SCHOOL_1, "9");
        insertClass(CLASS_10, SCHOOL_1, "10");
        insertClass(CLASS_OTHER, SCHOOL_2, "Other");
        insertSection(SECTION_A, SCHOOL_1, CLASS_9, "A");
        insertSection(SECTION_10_A, SCHOOL_1, CLASS_10, "A");
        insertSection(SECTION_B, SCHOOL_1, CLASS_10, "B");
        insertSection(SECTION_OTHER, SCHOOL_2, CLASS_OTHER, "Z");
        insertStudent(STUDENT, SCHOOL_1, CLASS_10, "10");
        insertStudent(LEGACY_STUDENT, SCHOOL_1, CLASS_9, "9");
    }

    @Test
    void activeClosedInclusivePromotionAndDateOwnedSessionResolution() {
        insertClosed(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        insertActive(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_B, LocalDate.of(2026, 4, 1));

        var priorFirst = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, LocalDate.of(2025, 4, 1));
        var priorLast = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, SESSION_PRIOR, LocalDate.of(2026, 3, 31));
        var current = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, SESSION_CURRENT, LocalDate.of(2026, 4, 1));

        assertThat(priorFirst.classification()).isEqualTo(ENROLLMENT_BACKED);
        assertThat(priorFirst.segment().status().name()).isEqualTo("CLOSED");
        assertThat(priorLast.segment().classNameSnapshot()).isEqualTo("9");
        assertThat(current.segment().status().name()).isEqualTo("ACTIVE");
        assertThat(current.segment().classNameSnapshot()).isEqualTo("10");
    }

    @Test
    void classAndSectionTransitionsPartitionRangeAndExposeAuthoritativeGap() {
        insertClosed(STUDENT, SESSION_CURRENT, CLASS_9, SECTION_A,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "CLASS_CHANGE");
        insertClosed(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10_A,
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 20), "SECTION_CHANGE");
        insertActive(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_B, LocalDate.of(2026, 8, 23));

        var result = resolver.resolveRealizedEnrollmentRange(SCHOOL_1, STUDENT, SESSION_CURRENT,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(result.intersections()).hasSize(3);
        assertThat(result.intersections().get(0).intersectedTo()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(result.intersections().get(1).segment().classId()).isEqualTo(CLASS_10);
        assertThat(result.intersections().get(2).segment().sectionId()).isEqualTo(SECTION_B);
        assertThat(result.uncoveredIntervals()).singleElement().satisfies(gap -> {
            assertThat(gap.from()).isEqualTo(LocalDate.of(2026, 8, 21));
            assertThat(gap.to()).isEqualTo(LocalDate.of(2026, 8, 22));
            assertThat(gap.classification()).isEqualTo(AUTHORITATIVE_GAP);
            assertThat(gap.legacyFallbackPermitted()).isFalse();
        });
        assertThat(resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, SESSION_CURRENT, LocalDate.of(2026, 8, 21))
                .classification()).isEqualTo(AUTHORITATIVE_GAP);
    }

    @Test
    void plannedAndCancelledAreIgnoredAndDoNotEstablishAdoption() {
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from) " +
                        "VALUES (?,?,?,?,?,?,?,'PLANNED',DATE '2027-01-01')",
                SCHOOL_1, STUDENT, SESSION_CURRENT, CLASS_10, "10", SECTION_B, "B");
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,'CANCELLED',DATE '2026-09-01',DATE '2026-09-01','CANCELLED_BEFORE_START')",
                SCHOOL_1, STUDENT, SESSION_CURRENT, CLASS_10, "10", SECTION_B, "B");

        var result = resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, SESSION_CURRENT, LocalDate.of(2026, 9, 1));

        assertThat(result.classification()).isEqualTo(LEGACY_UNCOVERED);
        assertThat(result.adoptionBoundary()).isNull();
        assertThat(result.legacyFallbackPermitted()).isTrue();
        assertThat(resolver.realizedEnrollmentSegmentsForSession(SCHOOL_1, STUDENT, SESSION_CURRENT)
                .segments()).isEmpty();
    }

    @Test
    void beforeFirstRealizedAndZeroHistoryAreLegacyUncovered() {
        insertActive(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_B, LocalDate.of(2026, 9, 1));

        assertThat(resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, SESSION_CURRENT, LocalDate.of(2026, 8, 31))
                .classification()).isEqualTo(LEGACY_UNCOVERED);
        assertThat(resolver.earliestRealizedEnrollmentDate(SCHOOL_1, STUDENT))
                .contains(LocalDate.of(2026, 9, 1));
        assertThat(resolver.earliestRealizedEnrollmentDate(SCHOOL_1, LEGACY_STUDENT)).isEmpty();
        assertThat(resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, LEGACY_STUDENT, SESSION_CURRENT, LocalDate.of(2026, 10, 1))
                .classification()).isEqualTo(LEGACY_UNCOVERED);
    }

    @Test
    void tenantIsolationAndReadOnlySignaturesHold() {
        insertActive(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_B, LocalDate.of(2026, 4, 1));
        TableSignature before = signatures();

        assertThatThrownBy(() -> resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, SESSION_OTHER_TENANT, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(java.util.NoSuchElementException.class);
        resolver.resolveEffectiveRealizedEnrollment(
                SCHOOL_1, STUDENT, SESSION_CURRENT, LocalDate.of(2026, 8, 1));
        resolver.resolveRealizedEnrollmentRange(SCHOOL_1, STUDENT, SESSION_CURRENT,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        resolver.realizedEnrollmentSegmentsForSession(SCHOOL_1, STUDENT, SESSION_CURRENT);
        resolver.earliestRealizedEnrollmentDate(SCHOOL_1, STUDENT);

        assertThat(signatures()).isEqualTo(before);
    }

    private TableSignature signatures() {
        return new TableSignature(count("student"), count("student_enrollment"),
                count("student_fees"), count("payment"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day) " +
                "VALUES (?,true,CURRENT_TIMESTAMP,?,'TRIAL',?,4,8)", id, slug, slug);
    }

    private void insertSession(long id, long schoolId, String label, LocalDate from, LocalDate to) {
        jdbc.update("INSERT INTO academic_session " +
                        "(id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,?,?,?,false,CURRENT_TIMESTAMP)",
                id, schoolId, label, from, to);
    }

    private void insertClass(long id, long schoolId, String name) {
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,?,true,false)",
                id, schoolId, name);
    }

    private void insertSection(long id, long schoolId, long classId, String name) {
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,?,true)",
                id, schoolId, classId, name);
    }

    private void insertStudent(String id, long schoolId, long classId, String className) {
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name) VALUES (?,?,'ACTIVE',?,?)",
                id, schoolId, classId, className);
    }

    private void insertActive(String studentId, long sessionId, long classId, long sectionId, LocalDate from) {
        insertEnrollment(studentId, sessionId, classId, sectionId, "ACTIVE", from, null, null);
    }

    private void insertClosed(String studentId, long sessionId, long classId, long sectionId,
                              LocalDate from, LocalDate to, String reason) {
        insertEnrollment(studentId, sessionId, classId, sectionId, "CLOSED", from, to, reason);
    }

    private void insertEnrollment(String studentId, long sessionId, long classId, long sectionId,
                                  String status, LocalDate from, LocalDate to, String reason) {
        String className = classId == CLASS_9 ? "9" : "10";
        String sectionName = sectionId == SECTION_A || sectionId == SECTION_10_A ? "A" : "B";
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                SCHOOL_1, studentId, sessionId, classId, className, sectionId, sectionName,
                status, from, to, reason);
    }

    private record TableSignature(long students, long enrollments, long fees, long payments) {}
}
