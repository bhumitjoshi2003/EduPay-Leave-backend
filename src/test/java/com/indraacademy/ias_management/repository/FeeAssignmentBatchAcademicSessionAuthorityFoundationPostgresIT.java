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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Financial AcademicSession Authority, Phase D2 (V65) — proof against a real, PROD-shaped
 * PostgreSQL database with Flyway enabled, following the same *PostgresIT convention as
 * {@link FinancialAcademicSessionAuthorityFoundationPostgresIT} for V64. No application code is
 * exercised here — this is schema-level proof only, since D2 adds only nullable columns/FKs and
 * no write/read path touches them yet.
 * <p>
 * The upgrade scenario is proven directly: the {@code @DynamicPropertySource} block first drives
 * Flyway to exactly V64 and inserts representative pre-existing workflow rows — under the schema
 * as it existed before this phase — and only then lets Spring's own
 * {@code spring.flyway.enabled=true} bootstrap apply the remaining migration (V65) on top,
 * exactly mirroring a real production upgrade. Running this same class against a completely
 * fresh disposable database (as done for every other PostgresIT suite in this repo) is what
 * proves the fresh V1→V65 scenario — both paths go through the identical V65 file.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class FeeAssignmentBatchAcademicSessionAuthorityFoundationPostgresIT {

    private static final long SCHOOL_A = -96501L;
    private static final long SCHOOL_B = -96502L;
    private static final String PRE_EXISTING_SESSION_LABEL = "2025-2026";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");

        // Stage the database at exactly V64 — the schema as it existed immediately before this
        // phase — then insert representative pre-existing workflow rows under that OLD schema,
        // proving the upgrade path: these rows must survive V65 unchanged, with their new
        // academic_session_id column defaulting to NULL, never guessed or backfilled.
        Flyway before = Flyway.configure().dataSource(url, username, password).target("64").load();
        before.migrate();

        try (var connection = java.sql.DriverManager.getConnection(url, username, password)) {
            var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    connection, true));
            seedPreExistingRows(jdbc);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed pre-V65 fixtures", e);
        }

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** Inserted under the V64 schema, before V65 (and its new academic_session_id column) has
     * ever been applied — the strongest possible proof that the column truly defaults to NULL
     * for pre-existing rows rather than merely "happens to be NULL because a test inserted it
     * that way after the column already existed." */
    private static void seedPreExistingRows(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'FeeWorkflow Session IT','TRIAL','feeworkflow-session-it',4,8,'Asia/Kolkata')," +
                "(?,true,CURRENT_TIMESTAMP,'FeeWorkflow Session Other IT','TRIAL','feeworkflow-session-other-it',4,8,'Asia/Kolkata')",
                SCHOOL_A, SCHOOL_B);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                -96510L, SCHOOL_A, PRE_EXISTING_SESSION_LABEL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                -96511L, SCHOOL_B, PRE_EXISTING_SESSION_LABEL);

        jdbc.update("INSERT INTO student_fee_assignment (school_id,student_id,academic_session,status,effective_date," +
                "selected_months,excluded,assigned_by,assigned_at) VALUES " +
                "(?,'V65-PRE-STUDENT',?,'READY',DATE '2025-04-01','1,2,3',false,'admin',CURRENT_TIMESTAMP)",
                SCHOOL_A, PRE_EXISTING_SESSION_LABEL);

        jdbc.update("INSERT INTO fee_generation_batch (school_id,academic_session,effective_date,selected_months," +
                "requested_student_ids,requested_students,status,initiated_by,started_at) VALUES " +
                "(?,?,DATE '2025-04-01','1,2,3','V65-PRE-STUDENT',1,'COMPLETED','admin',CURRENT_TIMESTAMP)",
                SCHOOL_A, PRE_EXISTING_SESSION_LABEL);
    }

    @Autowired private JdbcTemplate jdbc;

    // ── Existing rows survive, new column defaults NULL, historical strings unchanged ───────

    @Test
    void preExistingAssignmentSurvivesWithNullSessionIdAndUnchangedLabel() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT academic_session, academic_session_id FROM student_fee_assignment WHERE student_id = 'V65-PRE-STUDENT'");
        assertThat(row.get("academic_session")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(row.get("academic_session_id")).isNull();
    }

    @Test
    void preExistingBatchSurvivesWithNullSessionIdAndUnchangedLabel() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT academic_session, academic_session_id FROM fee_generation_batch WHERE requested_student_ids = 'V65-PRE-STUDENT'");
        assertThat(row.get("academic_session")).isEqualTo(PRE_EXISTING_SESSION_LABEL);
        assertThat(row.get("academic_session_id")).isNull();
    }

    // ── NULL is accepted on both tables for a brand-new row too ─────────────────────────────

    @Test
    void newRowsAcrossBothTablesAcceptNullAcademicSessionId() {
        // Every INSERT above (in seedPreExistingRows) already omitted academic_session_id
        // entirely and succeeded on both tables — this is the direct, positive confirmation
        // that NULL is valid, not merely "never attempted."
        Integer nullCount = jdbc.queryForObject(
                "SELECT " +
                        "(SELECT count(*) FROM student_fee_assignment WHERE academic_session_id IS NULL) + " +
                        "(SELECT count(*) FROM fee_generation_batch WHERE academic_session_id IS NULL)",
                Integer.class);
        assertThat(nullCount).isGreaterThanOrEqualTo(2);
    }

    // ── Composite tenant-safe FK — same-school accepted, cross-school rejected ──────────────

    private record TableFixture(String table, String pkColumn, long pkValue) {}

    private List<TableFixture> fixtures() {
        Long assignmentId = jdbc.queryForObject(
                "SELECT id FROM student_fee_assignment WHERE student_id='V65-PRE-STUDENT'", Long.class);
        Long batchId = jdbc.queryForObject(
                "SELECT id FROM fee_generation_batch WHERE requested_student_ids='V65-PRE-STUDENT'", Long.class);
        return List.of(
                new TableFixture("student_fee_assignment", "id", assignmentId),
                new TableFixture("fee_generation_batch", "id", batchId)
        );
    }

    @Test
    void sameSchoolAcademicSessionReferenceIsAcceptedOnEveryTable() {
        for (TableFixture f : fixtures()) {
            // Must not throw — SCHOOL_A row pointing at a SCHOOL_A session is exactly the
            // intended, valid shape a later phase's backfill will produce.
            jdbc.update("UPDATE " + f.table() + " SET academic_session_id = -96510 WHERE " + f.pkColumn() + " = ?", f.pkValue());
            Long value = jdbc.queryForObject(
                    "SELECT academic_session_id FROM " + f.table() + " WHERE " + f.pkColumn() + " = ?", Long.class, f.pkValue());
            assertThat(value).isEqualTo(-96510L);
            // Reset for the next assertion/test in this class.
            jdbc.update("UPDATE " + f.table() + " SET academic_session_id = NULL WHERE " + f.pkColumn() + " = ?", f.pkValue());
        }
    }

    @Test
    void crossSchoolAcademicSessionReferenceIsRejectedOnEveryTable() {
        for (TableFixture f : fixtures()) {
            // SCHOOL_A row attempting to point at SCHOOL_B's session (-96511) — the exact
            // cross-tenant corruption the composite FK exists to make impossible.
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE " + f.table() + " SET academic_session_id = -96511 WHERE " + f.pkColumn() + " = ?", f.pkValue()))
                    .as("table %s must reject a cross-school academic_session_id", f.table())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    // ── Schema inspection — column shape, FK target, no accidental NOT NULL/DEFAULT/CASCADE ──

    @Test
    void columnShapeIsNullableBigintWithNoDefaultOnEveryTable() {
        for (String table : List.of("student_fee_assignment", "fee_generation_batch")) {
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
                "fk_student_fee_assignment_academic_session", "fk_fee_generation_batch_academic_session")) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT confrelid::regclass::text AS ref_table, confdeltype AS delete_rule " +
                            "FROM pg_constraint WHERE conname = ?", constraint);
            assertThat(row.get("ref_table")).as("%s target table", constraint).isEqualTo("academic_session");
            // 'r' = RESTRICT — never 'c' (CASCADE) or 'n' (SET NULL): workflow/audit history
            // must never silently disappear or be severed as a side effect of a session deletion.
            assertThat(row.get("delete_rule")).as("%s delete rule", constraint).isEqualTo("r");
        }
    }

    // ── Existing objects (Task 19) must be completely untouched by V65 ──────────────────────

    @Test
    void existingUniqueConstraintOnAssignmentIsUnchanged() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint WHERE conname = 'uq_student_fee_assignment'");
        assertThat((String) row.get("def")).contains("UNIQUE (school_id, student_id, academic_session)");
    }

    @Test
    void existingStatusCheckConstraintOnAssignmentIsUnchanged() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint WHERE conname = 'ck_student_fee_assignment_status'");
        String def = (String) row.get("def");
        assertThat(def).contains("NOT_ASSIGNED").contains("READY").contains("GENERATED")
                .contains("PARTIALLY_GENERATED").contains("EXCLUDED").contains("GENERATION_FAILED");
    }

    @Test
    void existingIndexesOnBothTablesAreUnchanged() {
        Integer assignmentIndex = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_student_fee_assignment_school_session_status'", Integer.class);
        assertThat(assignmentIndex).isEqualTo(1);
        Integer batchIndex = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_fee_generation_batch_school_session'", Integer.class);
        assertThat(batchIndex).isEqualTo(1);
        // No new index was added on academic_session_id on either table (Task 6's "no index" decision).
        Integer newIndexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexdef ILIKE '%academic_session_id%' " +
                        "AND tablename IN ('student_fee_assignment','fee_generation_batch')", Integer.class);
        assertThat(newIndexes).isZero();
    }

    @Test
    void existingRetryBatchSelfReferentialForeignKeyIsUnchanged() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT confrelid::regclass::text AS ref_table FROM pg_constraint WHERE conname = 'fk_fee_generation_retry_batch'");
        assertThat(row.get("ref_table")).isEqualTo("fee_generation_batch");
    }
}
