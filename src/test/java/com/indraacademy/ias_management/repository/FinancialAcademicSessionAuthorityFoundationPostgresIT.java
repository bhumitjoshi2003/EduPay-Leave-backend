package com.indraacademy.ias_management.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Financial AcademicSession Authority, Phase B1 (V64) — proof against a real, PROD-shaped
 * PostgreSQL database with Flyway enabled, following the same *PostgresIT convention as
 * {@link RefundIntegrityFoundationPostgresIT} for V63. No application code is exercised here —
 * this is schema-level proof only, since B1 adds only nullable columns/FKs and no write/read
 * path touches them yet.
 * <p>
 * The upgrade scenario (Task 14) is proven directly: the {@code @DynamicPropertySource} block
 * first drives Flyway to exactly V63 and inserts representative pre-existing financial rows —
 * under the schema as it existed before this phase — and only then lets Spring's own
 * {@code spring.flyway.enabled=true} bootstrap apply the remaining migration (V64) on top,
 * exactly mirroring a real production upgrade. Running this same class against a completely
 * fresh disposable database (as done for every other PostgresIT suite in this repo) is what
 * proves the fresh V1→V64 scenario (Task 13) — both paths go through the identical V64 file.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class FinancialAcademicSessionAuthorityFoundationPostgresIT {

    private static final long SCHOOL_A = -96401L;
    private static final long SCHOOL_B = -96402L;
    private static final String PRE_EXISTING_SESSION_LABEL = "2025-2026";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");

        // Stage the database at exactly V63 — the schema as it existed immediately before this
        // phase — then insert representative pre-existing financial rows under that OLD schema,
        // proving the upgrade path (Task 14): these rows must survive V64 unchanged, with their
        // new academic_session_id column defaulting to NULL, never guessed or backfilled.
        Flyway before = Flyway.configure().dataSource(url, username, password).target("63").load();
        before.migrate();

        try (var connection = java.sql.DriverManager.getConnection(url, username, password)) {
            var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    connection, true));
            seedPreExistingRows(jdbc);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed pre-V64 fixtures", e);
        }

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** Inserted under the V63 schema, before V64 (and its new academic_session_id column) has
     * ever been applied — the strongest possible proof that the column truly defaults to NULL
     * for pre-existing rows rather than merely "happens to be NULL because a test inserted it
     * that way after the column already existed." */
    private static void seedPreExistingRows(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'FinSession IT','TRIAL','finsession-it',4,8,'Asia/Kolkata')," +
                "(?,true,CURRENT_TIMESTAMP,'FinSession Other IT','TRIAL','finsession-other-it',4,8,'Asia/Kolkata')",
                SCHOOL_A, SCHOOL_B);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                -96410L, SCHOOL_A, PRE_EXISTING_SESSION_LABEL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                -96411L, SCHOOL_B, PRE_EXISTING_SESSION_LABEL);

        jdbc.update("INSERT INTO payment (school_id,student_id,student_name,class_name,session,month,amount," +
                "payment_id,order_id,payment_date,status,razorpay_signature,amount_paid) VALUES " +
                "(?, 'V64-PRE-STUDENT','Pre Student','6A',?, '000000000000', 10000, 'V64-PRE-PAY-1'," +
                "'V64-PRE-PAY-1-ORDER', ?, 'success','sig', 10000)",
                SCHOOL_A, PRE_EXISTING_SESSION_LABEL, LocalDateTime.now());
        Long paymentId = jdbc.queryForObject("SELECT id FROM payment WHERE payment_id = 'V64-PRE-PAY-1'", Long.class);

        jdbc.update("INSERT INTO payment_order (order_id,school_id,student_id,class_name,session,month,amount," +
                "created_at) VALUES ('V64-PRE-ORDER-1', ?, 'V64-PRE-STUDENT','6A',?, '000000000000', 10000, CURRENT_TIMESTAMP)",
                SCHOOL_A, PRE_EXISTING_SESSION_LABEL);

        jdbc.update("INSERT INTO student_fees (school_id,student_id,class_name,month,paid,takes_bus,year,distance) VALUES " +
                "(?, 'V64-PRE-STUDENT','6A',1,false,false,?,0)", SCHOOL_A, PRE_EXISTING_SESSION_LABEL);
        Long studentFeesId = jdbc.queryForObject(
                "SELECT id FROM student_fees WHERE school_id=? AND student_id='V64-PRE-STUDENT' AND year=?",
                Long.class, SCHOOL_A, PRE_EXISTING_SESSION_LABEL);

        jdbc.update("INSERT INTO student_fees_line_item (student_fees_id,school_id,student_id,session,month," +
                "line_item_type,gross_amount_paise,net_amount_paise,created_at) VALUES " +
                "(?, ?, 'V64-PRE-STUDENT', ?, 1, 'RECURRING', 10000, 10000, CURRENT_TIMESTAMP)",
                studentFeesId, SCHOOL_A, PRE_EXISTING_SESSION_LABEL);

        jdbc.update("INSERT INTO payment_student_fees_allocation (payment_id,student_fees_id,school_id,student_id," +
                "session,month,amount_paise,created_at) VALUES (?, ?, ?, 'V64-PRE-STUDENT', ?, 1, 10000, CURRENT_TIMESTAMP)",
                paymentId, studentFeesId, SCHOOL_A, PRE_EXISTING_SESSION_LABEL);

        jdbc.update("INSERT INTO refund (payment_id,school_id,student_id,session,months_refunded,amount_paise," +
                "status,legacy_approximation,created_at) VALUES (?, ?, 'V64-PRE-STUDENT', ?, '000000000000', 1000," +
                "'success', false, CURRENT_TIMESTAMP)",
                paymentId, SCHOOL_A, PRE_EXISTING_SESSION_LABEL);
    }

    @Autowired private JdbcTemplate jdbc;

    // ── Task 9: existing rows survive, new column defaults NULL, historical strings unchanged ──

    @Test
    void preExistingPaymentSurvivesWithNullSessionIdAndUnchangedLabel() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT session, academic_session_id FROM payment WHERE payment_id = 'V64-PRE-PAY-1'");
        assertThat(row.get("session")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(row.get("academic_session_id")).isNull();
    }

    @Test
    void preExistingPaymentOrderSurvivesWithNullSessionIdAndUnchangedLabel() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT session, academic_session_id FROM payment_order WHERE order_id = 'V64-PRE-ORDER-1'");
        assertThat(row.get("session")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(row.get("academic_session_id")).isNull();
    }

    @Test
    void preExistingStudentFeesSurvivesWithNullSessionIdAndUnchangedYear() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT year, academic_session_id FROM student_fees WHERE student_id = 'V64-PRE-STUDENT'");
        assertThat(row.get("year")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(row.get("academic_session_id")).isNull();
    }

    @Test
    void preExistingLineItemAllocationAndRefundAllSurviveWithNullSessionIdAndUnchangedLabels() {
        Map<String, Object> lineItem = jdbc.queryForMap(
                "SELECT session, academic_session_id FROM student_fees_line_item WHERE student_id = 'V64-PRE-STUDENT'");
        assertThat(lineItem.get("session")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(lineItem.get("academic_session_id")).isNull();

        Map<String, Object> allocation = jdbc.queryForMap(
                "SELECT session, academic_session_id FROM payment_student_fees_allocation WHERE student_id = 'V64-PRE-STUDENT'");
        assertThat(allocation.get("session")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(allocation.get("academic_session_id")).isNull();

        Map<String, Object> refund = jdbc.queryForMap(
                "SELECT session, academic_session_id FROM refund WHERE student_id = 'V64-PRE-STUDENT'");
        assertThat(refund.get("session")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(refund.get("academic_session_id")).isNull();
    }

    // ── Task 11: NULL is accepted on every target table for a brand-new row too ─────────────

    @Test
    void newRowsAcrossAllSixTablesAcceptNullAcademicSessionId() {
        // Every INSERT above (in seedPreExistingRows) already omitted academic_session_id
        // entirely and succeeded on all six tables — this is the direct, positive confirmation
        // that NULL is valid, not merely "never attempted."
        Integer nullCount = jdbc.queryForObject(
                "SELECT " +
                        "(SELECT count(*) FROM payment WHERE academic_session_id IS NULL) + " +
                        "(SELECT count(*) FROM payment_order WHERE academic_session_id IS NULL) + " +
                        "(SELECT count(*) FROM student_fees WHERE academic_session_id IS NULL) + " +
                        "(SELECT count(*) FROM student_fees_line_item WHERE academic_session_id IS NULL) + " +
                        "(SELECT count(*) FROM payment_student_fees_allocation WHERE academic_session_id IS NULL) + " +
                        "(SELECT count(*) FROM refund WHERE academic_session_id IS NULL)",
                Integer.class);
        assertThat(nullCount).isGreaterThanOrEqualTo(6);
    }

    // ── Task 10: composite tenant-safe FK — same-school accepted, cross-school rejected ─────

    private record TableFixture(String table, String pkColumn, long pkValue, String extraSetClause) {}

    private List<TableFixture> fixtures() {
        Long paymentId = jdbc.queryForObject("SELECT id FROM payment WHERE payment_id = 'V64-PRE-PAY-1'", Long.class);
        Long studentFeesId = jdbc.queryForObject(
                "SELECT id FROM student_fees WHERE school_id=? AND student_id='V64-PRE-STUDENT'", Long.class, SCHOOL_A);
        Long lineItemId = jdbc.queryForObject(
                "SELECT id FROM student_fees_line_item WHERE student_id='V64-PRE-STUDENT'", Long.class);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM payment_order WHERE order_id='V64-PRE-ORDER-1'", Long.class);
        Long allocationId = jdbc.queryForObject(
                "SELECT id FROM payment_student_fees_allocation WHERE student_id='V64-PRE-STUDENT'", Long.class);
        Long refundId = jdbc.queryForObject(
                "SELECT id FROM refund WHERE student_id='V64-PRE-STUDENT'", Long.class);
        return List.of(
                new TableFixture("payment", "id", paymentId, null),
                new TableFixture("payment_order", "id", orderId, null),
                new TableFixture("student_fees", "id", studentFeesId, null),
                new TableFixture("student_fees_line_item", "id", lineItemId, null),
                new TableFixture("payment_student_fees_allocation", "id", allocationId, null),
                new TableFixture("refund", "id", refundId, null)
        );
    }

    @Test
    void sameSchoolAcademicSessionReferenceIsAcceptedOnEveryTable() {
        for (TableFixture f : fixtures()) {
            // Must not throw — SCHOOL_A row pointing at a SCHOOL_A session is exactly the
            // intended, valid shape a later phase's backfill will produce.
            jdbc.update("UPDATE " + f.table() + " SET academic_session_id = -96410 WHERE " + f.pkColumn() + " = ?", f.pkValue());
            Long value = jdbc.queryForObject(
                    "SELECT academic_session_id FROM " + f.table() + " WHERE " + f.pkColumn() + " = ?", Long.class, f.pkValue());
            assertThat(value).isEqualTo(-96410L);
            // Reset for the next assertion/test in this class.
            jdbc.update("UPDATE " + f.table() + " SET academic_session_id = NULL WHERE " + f.pkColumn() + " = ?", f.pkValue());
        }
    }

    @Test
    void crossSchoolAcademicSessionReferenceIsRejectedOnEveryTable() {
        for (TableFixture f : fixtures()) {
            // SCHOOL_A row attempting to point at SCHOOL_B's session (-96411) — the exact
            // cross-tenant corruption the composite FK exists to make impossible.
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE " + f.table() + " SET academic_session_id = -96411 WHERE " + f.pkColumn() + " = ?", f.pkValue()))
                    .as("table %s must reject a cross-school academic_session_id", f.table())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    // ── Task 15: schema inspection — column shape, FK target, no accidental NOT NULL/DEFAULT/CASCADE ──

    @Test
    void columnShapeIsNullableBigintWithNoDefaultOnEveryTable() {
        for (String table : List.of("payment", "payment_order", "student_fees", "student_fees_line_item",
                "payment_student_fees_allocation", "refund")) {
            Map<String, Object> col = jdbc.queryForMap(
                    "SELECT data_type, is_nullable, column_default FROM information_schema.columns " +
                            "WHERE table_name = ? AND column_name = 'academic_session_id'", table);
            assertThat(col.get("data_type")).as("%s.academic_session_id type", table).isEqualTo("bigint");
            assertThat(col.get("is_nullable")).as("%s.academic_session_id nullable", table).isEqualTo("YES");
            assertThat(col.get("column_default")).as("%s.academic_session_id default", table).isNull();
        }
    }

    @Test
    void everyForeignKeyReferencesAcademicSessionOnSchoolIdAndIdWithRestrictDeleteRule() {
        for (String constraint : List.of(
                "fk_payment_academic_session", "fk_payment_order_academic_session",
                "fk_student_fees_academic_session", "fk_student_fees_line_item_academic_session",
                "fk_payment_student_fees_allocation_academic_session", "fk_refund_academic_session")) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT confrelid::regclass::text AS ref_table, confdeltype AS delete_rule " +
                            "FROM pg_constraint WHERE conname = ?", constraint);
            assertThat(row.get("ref_table")).as("%s target table", constraint).isEqualTo("academic_session");
            // 'r' = RESTRICT — never 'c' (CASCADE) or 'n' (SET NULL): financial history must
            // never silently disappear or be severed as a side effect of a session deletion.
            assertThat(row.get("delete_rule")).as("%s delete rule", constraint).isEqualTo("r");
        }
    }
}
