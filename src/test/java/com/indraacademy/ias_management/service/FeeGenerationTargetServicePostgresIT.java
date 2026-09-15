package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
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
import jakarta.persistence.EntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeeGenerationTargetService.class, FeeCalculationService.class, AcademicSessionService.class,
        FeeGenerationTargetServicePostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class FeeGenerationTargetServicePostgresIT {
    static final long SCHOOL = -97001, OTHER_SCHOOL = -97002;
    static final long SESSION = -97003, SOURCE_SESSION = -97004;
    static final long CLASS_9 = -97005, CLASS_10 = -97006, CLASS_NORULE = -97007, OTHER_CLASS = -97012;
    static final long SECTION_A = -97008, SECTION_B = -97009, SECTION_C = -97010;
    static final long FEE_HEAD = -97011;
    static final String SESSION_LABEL = "2026-2027", SOURCE_LABEL = "2025-2026";

    /** computeMonthSnapshot's dueMonths JSON must be really parsed (not mocked) — a mocked
     *  ObjectMapper.readValue returns null, which NPEs FeeCalculationService.appliesThisAcademicMonth. */
    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired FeeGenerationTargetService service;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        r.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @BeforeEach void fixtures() throws Exception {
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");

        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'FeeGen Target IT','TRIAL','feegen-target-it',7,8,'Asia/Kolkata')," +
                "(?,true,CURRENT_TIMESTAMP,'Other FeeGen Target IT','TRIAL','other-feegen-target-it',7,8,'Asia/Kolkata')", SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-07-01',DATE '2026-06-30',false,CURRENT_TIMESTAMP)," +
                "(?,?,?,DATE '2026-07-01',DATE '2027-06-30',false,CURRENT_TIMESTAMP)," +
                "(?,?,?,DATE '2026-07-01',DATE '2027-06-30',false,CURRENT_TIMESTAMP)",
                SOURCE_SESSION, SCHOOL, SOURCE_LABEL, SESSION, SCHOOL, SESSION_LABEL, SESSION + 1000, OTHER_SCHOOL, SESSION_LABEL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES " +
                "(?,?,'9',true,1,false),(?,?,'10',true,2,false),(?,?,'11',true,3,false),(?,?,'9',true,1,false)",
                CLASS_9, SCHOOL, CLASS_10, SCHOOL, CLASS_NORULE, SCHOOL, OTHER_CLASS, OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES " +
                "(?,?,?,'A',true),(?,?,?,'B',true),(?,?,?,'C',true)",
                SECTION_A, SCHOOL, CLASS_9, SECTION_B, SCHOOL, CLASS_10, SECTION_C, SCHOOL, CLASS_9);
        jdbc.update("INSERT INTO fee_head (id,active,code,created_at,display_order,due_months,frequency,name,is_optional,is_refundable,school_id) VALUES " +
                "(?,true,'TUITION',CURRENT_TIMESTAMP,1,'[1,2,3,4,5,6,7,8,9,10,11,12]','MONTHLY','Tuition Fee',false,false,?)", FEE_HEAD, SCHOOL);
        jdbc.update("INSERT INTO fee_structure_rule (id,amount,class_name,created_at,effective_from,school_id,academic_session_id,fee_head_id,class_id) VALUES " +
                "(?,100000,'9',CURRENT_TIMESTAMP,DATE '2026-07-01',?,?,?,?)", FEE_HEAD - 1, SCHOOL, SESSION, FEE_HEAD, CLASS_9);
        jdbc.update("INSERT INTO fee_structure_rule (id,amount,class_name,created_at,effective_from,school_id,academic_session_id,fee_head_id,class_id) VALUES " +
                "(?,120000,'10',CURRENT_TIMESTAMP,DATE '2026-07-01',?,?,?,?)", FEE_HEAD - 2, SCHOOL, SESSION, FEE_HEAD, CLASS_10);
        jdbc.update("INSERT INTO bus_fees (id,academic_year,fees,min_distance,max_distance,school_id) VALUES " +
                "(?,?,200,0,10,?)", FEE_HEAD - 3, SESSION_LABEL, SCHOOL);
    }

    private void insertStudent(String id, long schoolId, long classId, Long sectionId, boolean takesBus, double distance) {
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,takes_bus,distance,joining_date) " +
                "VALUES (?,?,'ACTIVE',?,'x',?,'x',?,?,DATE '2026-07-01')", id, schoolId, classId, sectionId, takesBus, distance);
    }
    private void insertEnrollment(String studentId, long schoolId, long sessionId, String status, long classId, Long sectionId, LocalDate from) {
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) " +
                "VALUES (?,?,?,?,'x',?,?,?,?)", schoolId, studentId, sessionId, classId, sectionId, sectionId == null ? null : "x", status, from);
    }
    private void closeEnrollment(String studentId, long sessionId, LocalDate until) {
        jdbc.update("UPDATE student_enrollment SET status='CLOSED', effective_until=?, closure_reason='SESSION_COMPLETED' WHERE student_id=? AND academic_session_id=?", until, studentId, sessionId);
    }
    private Long enrollmentIdOf(String studentId, long sessionId) {
        return jdbc.queryForObject("SELECT id FROM student_enrollment WHERE student_id=? AND academic_session_id=?", Long.class, studentId, sessionId);
    }
    private int feeRowCount(String studentId) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?", Integer.class, SCHOOL, studentId, SESSION_LABEL);
        return c == null ? 0 : c;
    }
    private void assertZeroFinancialSideEffects(String studentId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=? AND student_id=?", Integer.class, SCHOOL, studentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_student_fees_allocation WHERE student_id=?", Integer.class, studentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM allocation_refund WHERE student_fees_id IN (SELECT id FROM student_fees WHERE school_id=? AND student_id=?)",
                Integer.class, SCHOOL, studentId)).isZero();
    }

    @Test void plannedEnrollmentGeneratesTwelveMonthsFromEnrollmentClassNotStudentProjection() {
        insertStudent("STU-PLANNED", SCHOOL, CLASS_9, SECTION_A, false, 0); // Student projection deliberately stale (class 9)
        insertEnrollment("STU-PLANNED", SCHOOL, SESSION, "PLANNED", CLASS_10, SECTION_B, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-PLANNED", SESSION);

        List<StudentGenerationResult> results = service.generate(genRequest(decision("STU-PLANNED", enrollmentId, CLASS_10)), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.GENERATED);
        assertThat(feeRowCount("STU-PLANNED")).isEqualTo(12);
        List<Long> classIds = jdbc.query("SELECT class_id FROM student_fees WHERE school_id=? AND student_id=?",
                (rs, i) -> rs.getLong("class_id"), SCHOOL, "STU-PLANNED");
        assertThat(classIds).allMatch(id -> id == CLASS_10); // from enrollment, never the stale class-9 Student projection
        assertZeroFinancialSideEffects("STU-PLANNED");
    }

    /** Financial AcademicSession Authority, Phase B3/B4: every generated StudentFees row and
     * its line items must carry the target session's real id, not merely its label. */
    @Test void generationPopulatesAcademicSessionIdFromTargetSession() {
        insertStudent("STU-SESSIONID", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-SESSIONID", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-SESSIONID", SESSION);

        List<StudentGenerationResult> results = service.generate(genRequest(decision("STU-SESSIONID", enrollmentId, CLASS_9)), "ip");

        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.GENERATED);
        List<Long> sessionIds = jdbc.query("SELECT academic_session_id FROM student_fees WHERE school_id=? AND student_id=?",
                (rs, i) -> rs.getLong("academic_session_id"), SCHOOL, "STU-SESSIONID");
        assertThat(sessionIds).allMatch(id -> id == SESSION);
        List<Long> lineItemSessionIds = jdbc.query(
                "SELECT academic_session_id FROM student_fees_line_item WHERE school_id=? AND student_id=?",
                (rs, i) -> rs.getLong("academic_session_id"), SCHOOL, "STU-SESSIONID");
        assertThat(lineItemSessionIds).isNotEmpty().allMatch(id -> id == SESSION);
    }

    @Test void activeEnrollmentGeneratesIdenticalAmountsToPlannedInSameClass() {
        insertStudent("STU-PLANNED2", SCHOOL, CLASS_10, SECTION_B, false, 0);
        insertStudent("STU-ACTIVE", SCHOOL, CLASS_10, SECTION_B, false, 0);
        insertEnrollment("STU-PLANNED2", SCHOOL, SESSION, "PLANNED", CLASS_10, SECTION_B, LocalDate.of(2026, 7, 1));
        insertEnrollment("STU-ACTIVE", SCHOOL, SESSION, "ACTIVE", CLASS_10, SECTION_B, LocalDate.of(2026, 7, 1));

        StudentPreviewRow plannedPreview = service.preview(SESSION, null, "STU-PLANNED2").getFirst();
        StudentPreviewRow activePreview = service.preview(SESSION, null, "STU-ACTIVE").getFirst();

        assertThat(plannedPreview.totalDue()).isEqualByComparingTo(activePreview.totalDue());
        assertThat(plannedPreview.warnings()).anyMatch(w -> w.contains("not yet active"));
        assertThat(activePreview.warnings()).noneMatch(w -> w.contains("not yet active"));
    }

    @Test void detainSameClassIsExposedAsRepeatingClassAndGeneratesNormally() {
        insertStudent("STU-DETAIN", SCHOOL, CLASS_9, SECTION_A, false, 0);
        // ck_student_enrollment_lifecycle requires effective_until/closure_reason to already be
        // set on any CLOSED row at insert time — insert ACTIVE first, then close it, rather than
        // inserting directly as CLOSED with those columns null.
        insertEnrollment("STU-DETAIN", SCHOOL, SOURCE_SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2025, 7, 1));
        closeEnrollment("STU-DETAIN", SOURCE_SESSION, LocalDate.of(2026, 6, 30));
        insertEnrollment("STU-DETAIN", SCHOOL, SESSION, "PLANNED", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-DETAIN", SESSION);

        StudentPreviewRow preview = service.preview(SESSION, null, "STU-DETAIN").getFirst();
        assertThat(preview.repeatingSameClass()).isTrue();

        List<StudentGenerationResult> results = service.generate(genRequest(decision("STU-DETAIN", enrollmentId, CLASS_9)), "ip");
        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.GENERATED);
    }

    @Test void cancelledTargetEnrollmentNeverAppearsInPreviewOrProducesFees() {
        insertStudent("STU-CANCELLED", SCHOOL, CLASS_10, SECTION_B, false, 0);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                "VALUES (?,?,?,?,?,?,?,'CANCELLED',DATE '2026-07-01',DATE '2026-07-01','CANCELLED_BEFORE_START')",
                SCHOOL, "STU-CANCELLED", SESSION, CLASS_10, "x", SECTION_B, "x");
        Long enrollmentId = enrollmentIdOf("STU-CANCELLED", SESSION);

        List<StudentPreviewRow> preview = service.preview(SESSION, null, "STU-CANCELLED");
        assertThat(preview).isEmpty();

        List<StudentGenerationResult> results = service.generate(genRequest(decision("STU-CANCELLED", enrollmentId, CLASS_10)), "ip");
        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);
        assertThat(feeRowCount("STU-CANCELLED")).isZero();
    }

    @Test void correctedEnrollmentAfterPreviewInvalidatesTheStalePreviewAsAConflict() throws Exception {
        insertStudent("STU-CORRECTED", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-CORRECTED", SCHOOL, SESSION, "PLANNED", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-CORRECTED", SESSION);
        StudentPreviewRow preview = service.preview(SESSION, null, "STU-CORRECTED").getFirst();
        assertThat(preview.targetClassId()).isEqualTo(CLASS_9);
        // Commit the fixtures + preview's transaction, then correct the enrollment to CLASS_10 as
        // a genuinely separate, already-committed write — exactly like a real correctPlannedEnrollment
        // request landing between an admin's preview and their generate click. Mixing this raw
        // correction into the SAME transaction as the JPA reads would hide behind Hibernate's
        // first-level cache and never exercise the real staleness path.
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            jdbc.update("UPDATE student_enrollment SET class_id=?, class_name_snapshot='x', section_id=NULL, section_name_snapshot=NULL WHERE id=?", CLASS_10, enrollmentId);

            List<StudentGenerationResult> results = service.generate(
                    genRequest(decision("STU-CORRECTED", enrollmentId, preview.targetClassId())), "ip"); // stale expectedTargetClassId=CLASS_9

            assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);
            assertThat(feeRowCount("STU-CORRECTED")).isZero();
        } finally {
            cleanupCommittedFixtures();
        }
    }

    @Test void sectionChangeAloneDoesNotAlterFeeClassAuthority() {
        insertStudent("STU-SECTION", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-SECTION", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_C, LocalDate.of(2026, 7, 1)); // different section, same class

        StudentPreviewRow preview = service.preview(SESSION, null, "STU-SECTION").getFirst();

        assertThat(preview.targetClassId()).isEqualTo(CLASS_9);
        assertThat(preview.targetClassName()).isEqualTo("9");
        assertThat(preview.blockingErrors()).isEmpty();
    }

    @Test void missingFeeRuleBlocksGenerationWithoutWritingAnything() {
        insertStudent("STU-NORULE", SCHOOL, CLASS_NORULE, null, false, 0);
        insertEnrollment("STU-NORULE", SCHOOL, SESSION, "PLANNED", CLASS_NORULE, null, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-NORULE", SESSION);

        StudentPreviewRow preview = service.preview(SESSION, null, "STU-NORULE").getFirst();
        assertThat(preview.eligible()).isFalse();
        assertThat(preview.blockingErrors()).isNotEmpty();

        List<StudentGenerationResult> results = service.generate(genRequest(decision("STU-NORULE", enrollmentId, CLASS_NORULE)), "ip");
        assertThat(results.getFirst().outcome()).isEqualTo(GenerationOutcome.NO_RULE_CONFIGURED);
        assertThat(feeRowCount("STU-NORULE")).isZero();
    }

    @Test void discountConfigReducesGeneratedAmount() {
        insertStudent("STU-DISCOUNT", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-DISCOUNT", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        jdbc.update("INSERT INTO student_fee_config (config_type,created_at,school_id,student_id,valid_from,value,academic_session_id,fee_head_id) VALUES " +
                "('DISCOUNT_PERCENT',CURRENT_TIMESTAMP,?,?,DATE '2026-07-01',50,?,?)", SCHOOL, "STU-DISCOUNT", SESSION, FEE_HEAD);
        insertStudent("STU-NODISCOUNT", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-NODISCOUNT", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));

        BigDecimal discounted = service.preview(SESSION, null, "STU-DISCOUNT").getFirst().totalDue();
        BigDecimal full = service.preview(SESSION, null, "STU-NODISCOUNT").getFirst().totalDue();

        assertThat(discounted).isLessThan(full);
    }

    @Test void transportAssignmentIsUsedWhenPresentForTheTargetSession() {
        insertStudent("STU-TRANSPORT", SCHOOL, CLASS_9, SECTION_A, false, 0); // Student projection says no bus
        insertEnrollment("STU-TRANSPORT", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        jdbc.update("INSERT INTO student_transport_fee_assignment (school_id,student_id,academic_session,enabled,distance,effective_from,reason,changed_by,created_at) VALUES " +
                "(?,?,?,true,5,DATE '2026-07-01','test','admin',CURRENT_TIMESTAMP)", SCHOOL, "STU-TRANSPORT", SESSION_LABEL);

        StudentPreviewRow preview = service.preview(SESSION, null, "STU-TRANSPORT").getFirst();

        assertThat(preview.months().getFirst().busFeeDue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(preview.warnings()).noneMatch(w -> w.contains("No transport assignment"));
    }

    @Test void missingTransportAssignmentFallsBackToStudentProjectionWithAWarning() {
        insertStudent("STU-NOTRANSPORT", SCHOOL, CLASS_9, SECTION_A, true, 3.0);
        insertEnrollment("STU-NOTRANSPORT", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));

        StudentPreviewRow preview = service.preview(SESSION, null, "STU-NOTRANSPORT").getFirst();

        assertThat(preview.months().getFirst().busFeeDue()).isGreaterThan(BigDecimal.ZERO); // fallback used student's own bus status
        assertThat(preview.warnings()).anyMatch(w -> w.contains("No transport assignment recorded"));
        assertThat(preview.eligible()).isTrue(); // never blocked solely for missing transport assignment
    }

    @Test void alreadyGeneratedMonthsAreSkippedOnRerunAndMissingOnesAreFilledIn() {
        insertStudent("STU-RERUN", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-RERUN", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-RERUN", SESSION);

        StudentGenerationResult first = service.generate(genRequest(decision("STU-RERUN", enrollmentId, CLASS_9)), "ip").getFirst();
        assertThat(first.outcome()).isEqualTo(GenerationOutcome.GENERATED);
        assertThat(feeRowCount("STU-RERUN")).isEqualTo(12);

        StudentGenerationResult rerun = service.generate(genRequest(decision("STU-RERUN", enrollmentId, CLASS_9)), "ip").getFirst();
        assertThat(rerun.outcome()).isEqualTo(GenerationOutcome.ALREADY_GENERATED);
        assertThat(feeRowCount("STU-RERUN")).isEqualTo(12); // never duplicated
    }

    @Test void partialGenerationRerunFillsOnlyTheMissingMonths() {
        insertStudent("STU-PARTIAL", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-PARTIAL", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-PARTIAL", SESSION);
        jdbc.update("INSERT INTO student_fees (school_id,student_id,class_id,class_name,month,year,paid,manually_paid,takes_bus,distance,base_amount_due,bus_fee_due,discount_amount) VALUES " +
                "(?,?,?,?,1,?,false,false,false,0,100000,0,0)", SCHOOL, "STU-PARTIAL", CLASS_9, "9", SESSION_LABEL);

        StudentGenerationResult result = service.generate(genRequest(decision("STU-PARTIAL", enrollmentId, CLASS_9)), "ip").getFirst();

        assertThat(result.outcome()).isEqualTo(GenerationOutcome.PARTIALLY_GENERATED);
        assertThat(result.generatedMonths()).isEqualTo(11);
        assertThat(result.skippedMonths()).isEqualTo(1);
        assertThat(feeRowCount("STU-PARTIAL")).isEqualTo(12);
    }

    @Test void concurrentDuplicateGenerationNeverDuplicatesAMonth() throws Exception {
        insertStudent("STU-CONCURRENT", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-CONCURRENT", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-CONCURRENT", SESSION);
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<List<StudentGenerationResult>> call = () -> {
                start.await(10, TimeUnit.SECONDS);
                return service.generate(genRequest(decision("STU-CONCURRENT", enrollmentId, CLASS_9)), "ip");
            };
            Future<List<StudentGenerationResult>> a = pool.submit(call), b = pool.submit(call);
            start.countDown();
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);

            Integer count = jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                    Integer.class, SCHOOL, "STU-CONCURRENT", SESSION_LABEL);
            assertThat(count).isEqualTo(12);
            for (int m = 1; m <= 12; m++) {
                Integer perMonth = jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=? AND month=?",
                        Integer.class, SCHOOL, "STU-CONCURRENT", SESSION_LABEL, m);
                assertThat(perMonth).as("month %d", m).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow(); cleanupCommittedFixtures();
        }
    }

    @Test void oneStudentFailureIsIsolatedFromAnotherStudentInTheSameBatch() {
        insertStudent("STU-OK", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-OK", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long okId = enrollmentIdOf("STU-OK", SESSION);

        List<StudentGenerationResult> results = service.generate(new GenerationRequest(SESSION, List.of(
                decision("STU-MISSING", 999999L, CLASS_9), // no such student/enrollment at all
                decision("STU-OK", okId, CLASS_9))), "ip");

        assertThat(results).extracting(StudentGenerationResult::studentId).containsExactlyInAnyOrder("STU-MISSING", "STU-OK");
        assertThat(results.stream().filter(r -> r.studentId().equals("STU-MISSING")).findFirst().orElseThrow().outcome())
                .isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);
        assertThat(results.stream().filter(r -> r.studentId().equals("STU-OK")).findFirst().orElseThrow().outcome())
                .isEqualTo(GenerationOutcome.GENERATED);
        assertThat(feeRowCount("STU-OK")).isEqualTo(12);
    }

    @Test void tenantIsolationNeverExposesOrGeneratesAcrossSchools() {
        long otherSessionId = SESSION + 1000;
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,joining_date) VALUES (?,?,'ACTIVE',?,?,DATE '2026-07-01')",
                "STU-OTHER", OTHER_SCHOOL, OTHER_CLASS, "9");
        insertEnrollment("STU-OTHER", OTHER_SCHOOL, otherSessionId, "ACTIVE", OTHER_CLASS, null, LocalDate.of(2026, 7, 1));
        // Fee rules/classes only exist under SCHOOL — a same-labeled session query scoped to SCHOOL must never see the other school's student.
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL);

        List<StudentPreviewRow> preview = service.preview(SESSION, null, null);

        assertThat(preview).extracting(StudentPreviewRow::studentId).doesNotContain("STU-OTHER");
    }

    @Test void generationProducesZeroPaymentAllocationOrRefundMutation() {
        insertStudent("STU-ZEROFIN", SCHOOL, CLASS_9, SECTION_A, false, 0);
        insertEnrollment("STU-ZEROFIN", SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        Long enrollmentId = enrollmentIdOf("STU-ZEROFIN", SESSION);

        service.generate(genRequest(decision("STU-ZEROFIN", enrollmentId, CLASS_9)), "ip");

        assertZeroFinancialSideEffects("STU-ZEROFIN");
    }

    @Test void targetDriftClassifiesPersistedPostgresEvidenceAndPerformsZeroWrites() {
        for (String id : List.of("DRIFT-PLANNED", "DRIFT-ACTIVE", "DRIFT-CLASS", "DRIFT-CANCEL", "DRIFT-PARTIAL", "DRIFT-LEGACY")) {
            insertStudent(id, SCHOOL, CLASS_9, SECTION_A, false, 0);
        }
        insertEnrollment("DRIFT-PLANNED", SCHOOL, SESSION, "PLANNED", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        for (String id : List.of("DRIFT-ACTIVE", "DRIFT-CLASS", "DRIFT-CANCEL", "DRIFT-PARTIAL")) {
            insertEnrollment(id, SCHOOL, SESSION, "ACTIVE", CLASS_9, SECTION_A, LocalDate.of(2026, 7, 1));
        }
        jdbc.update("UPDATE student_enrollment SET class_name_snapshot='9', section_name_snapshot='A' WHERE school_id=? AND academic_session_id=?", SCHOOL, SESSION);
        for (String id : List.of("DRIFT-PLANNED", "DRIFT-ACTIVE", "DRIFT-CLASS", "DRIFT-CANCEL", "DRIFT-PARTIAL")) {
            service.generate(genRequest(decision(id, enrollmentIdOf(id, SESSION), CLASS_9)), "ip");
        }
        jdbc.update("UPDATE student_enrollment SET class_id=?,class_name_snapshot='10',section_id=?,section_name_snapshot='B' WHERE student_id='DRIFT-CLASS'", CLASS_10, SECTION_B);
        jdbc.update("UPDATE student_enrollment SET status='CANCELLED',effective_until=DATE '2026-07-01',closure_reason='CANCELLED_BEFORE_START' WHERE student_id='DRIFT-CANCEL'");
        jdbc.update("DELETE FROM student_fees_line_item WHERE school_id=? AND student_id='DRIFT-PARTIAL' AND month>3", SCHOOL);
        jdbc.update("DELETE FROM student_fees WHERE school_id=? AND student_id='DRIFT-PARTIAL' AND month>3", SCHOOL);
        jdbc.update("INSERT INTO student_fees (school_id,student_id,class_id,class_name,month,year,paid,manually_paid,takes_bus,distance,base_amount_due,bus_fee_due,discount_amount) VALUES (?,?,?,?,1,?,false,false,false,0,1,0,0)", SCHOOL, "DRIFT-LEGACY", CLASS_9, "9", SESSION_LABEL);
        entityManager.clear();

        List<Integer> before = List.of(
                jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=?", Integer.class, SCHOOL),
                jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=?", Integer.class, SCHOOL),
                jdbc.queryForObject("SELECT count(*) FROM student_fees_line_item WHERE school_id=?", Integer.class, SCHOOL),
                jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=?", Integer.class, SCHOOL));

        List<TargetDriftRow> rows = service.targetDrift(SESSION, null, null);

        assertThat(rows).extracting(TargetDriftRow::studentId).doesNotContain("DRIFT-LEGACY");
        assertThat(rows).filteredOn(r -> r.studentId().equals("DRIFT-PLANNED") || r.studentId().equals("DRIFT-ACTIVE"))
                .extracting(TargetDriftRow::driftStatus).containsOnly(DriftStatus.CLEAN);
        assertThat(rows).filteredOn(r -> r.studentId().equals("DRIFT-CLASS")).extracting(TargetDriftRow::driftStatus).containsOnly(DriftStatus.CLASS_MISMATCH);
        assertThat(rows).filteredOn(r -> r.studentId().equals("DRIFT-CANCEL")).extracting(TargetDriftRow::driftStatus).containsOnly(DriftStatus.CANCELLED_TARGET_WITH_FEES);
        assertThat(rows).filteredOn(r -> r.studentId().equals("DRIFT-PARTIAL")).singleElement()
                .satisfies(r -> { assertThat(r.driftStatus()).isEqualTo(DriftStatus.PARTIAL_GENERATION); assertThat(r.missingMonths()).hasSize(9); });
        assertThat(List.of(
                jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=?", Integer.class, SCHOOL),
                jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=?", Integer.class, SCHOOL),
                jdbc.queryForObject("SELECT count(*) FROM student_fees_line_item WHERE school_id=?", Integer.class, SCHOOL),
                jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=?", Integer.class, SCHOOL))).isEqualTo(before);
    }

    private GenerationDecision decision(String studentId, Long enrollmentId, Long classId) {
        return new GenerationDecision(studentId, enrollmentId, classId);
    }
    private GenerationRequest genRequest(GenerationDecision... decisions) {
        return new GenerationRequest(SESSION, List.of(decisions));
    }
    private void cleanupCommittedFixtures() {
        // student_fees_line_item FK-references both student_fees_id and fee_head_id and must be
        // deleted before either parent. Scoped by school_id alone was found to occasionally leave
        // rows behind for this test specifically (its concurrent-generation scenario commits from
        // separate worker-thread transactions, outside this method's own visibility guarantees) —
        // matched transitively via student_fees/fee_head ownership as well, so every line item
        // connected to this school's sentinel fixture is removed regardless of why school_id alone
        // didn't catch it.
        jdbc.update("DELETE FROM student_fees_line_item WHERE school_id=? " +
                "OR student_fees_id IN (SELECT id FROM student_fees WHERE school_id=?) " +
                "OR fee_head_id IN (SELECT id FROM fee_head WHERE school_id=?)", SCHOOL, SCHOOL, SCHOOL);
        jdbc.update("DELETE FROM student_fees WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_one_time_fee_charged WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_fee_config WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_transport_fee_assignment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM fee_structure_rule WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM fee_head WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM bus_fees WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id IN (?,?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id IN (?,?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id IN (?,?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?,?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?,?)", SCHOOL, OTHER_SCHOOL);
    }
}
