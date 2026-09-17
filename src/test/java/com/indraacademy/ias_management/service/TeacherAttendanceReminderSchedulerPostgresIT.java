package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres proof of the four guarantees a Mockito test cannot honestly make for this
 * feature: (1) the notifications table's tenant-scoped idempotency unique index genuinely
 * collapses two scheduler ticks into one row, (2) a real teacher_attendance row suppresses the
 * reminder via the real batch query, (3) a real APPROVED teacher_leave row does the same, and
 * (4) two schools' processing is genuinely independent in the same database. Follows the
 * established *PostgresIT convention (DataJpaTest + real Postgres, gated by DB_URL, TestTransaction
 * ended immediately so the scheduler's own @Transactional-free writes are genuinely committed).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TeacherAttendanceReminderScheduler.class, TeacherAttendanceScheduleService.class,
        BusinessNotificationService.class, NotificationPublisher.class, NotificationPublicationTransaction.class,
        NotificationRecipientResolver.class, NotificationChannelPolicyResolver.class,
        AuditService.class, com.indraacademy.ias_management.util.SecurityUtil.class,
        com.indraacademy.ias_management.config.ClockConfig.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class TeacherAttendanceReminderSchedulerPostgresIT {

    // Never-real, distinctive negative ids so this suite can never collide with real data or
    // other tests' fixtures in the shared test database.
    private static final long SCHOOL_A = -77001L;
    private static final long SCHOOL_B = -77002L;
    private static final String TEACHER_A = "tars-teacher-a";
    private static final String TEACHER_B = "tars-teacher-b";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    // ParentPortalService is a real dependency of NotificationRecipientResolver's constructor
    // but is never invoked on the DIRECT_USER path this scheduler uses — mocked purely to
    // satisfy DI without pulling in an unrelated feature's own dependency chain.
    @MockBean private com.indraacademy.ias_management.service.ParentPortalService parentPortalService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TeacherAttendanceReminderScheduler scheduler;

    @BeforeEach
    void seedFixtures() {
        TestTransaction.flagForCommit();
        TestTransaction.end();

        cleanupCommittedFixtures();

        seedSchool(SCHOOL_A, "Asia/Kolkata", LocalTime.of(7, 45));
        seedTeacher(SCHOOL_A, TEACHER_A);

        // Reminder time is due "now" in Asia/Kolkata — set the shared clock accordingly.
        // Individual tests may override for a second school with a different timezone.
        useClockAt(TODAY, LocalTime.of(7, 47), "Asia/Kolkata");
    }

    @AfterEach
    void cleanup() {
        cleanupCommittedFixtures();
    }

    private void cleanupCommittedFixtures() {
        jdbc.update("DELETE FROM notifications WHERE school_id IN (?, ?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM teacher_leave WHERE school_id IN (?, ?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM teacher_attendance WHERE school_id IN (?, ?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM school_holidays WHERE school_id IN (?, ?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM users WHERE school_id IN (?, ?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM teacher WHERE school_id IN (?, ?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM school WHERE id IN (?, ?)", SCHOOL_A, SCHOOL_B);
    }

    private void seedSchool(long schoolId, String timezone, LocalTime reminderTime) {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month," +
                        "periods_per_day,timezone,working_days,teacher_attendance_reminder_enabled," +
                        "teacher_attendance_reminder_time) VALUES " +
                        "(?,true,CURRENT_TIMESTAMP,?,'TRIAL',?,4,8,?," +
                        "'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY',true,?)",
                schoolId, "TARS School " + schoolId, "tars-school-" + schoolId, timezone, reminderTime);
    }

    private void seedTeacher(long schoolId, String teacherId) {
        jdbc.update("INSERT INTO teacher (teacher_id,school_id,name,status) VALUES (?,?,?,'ACTIVE')",
                teacherId, schoolId, "Teacher " + teacherId);
        jdbc.update("INSERT INTO users (user_id,school_id,role,active,password) VALUES (?,?,'TEACHER',true,'x')",
                teacherId, schoolId);
    }

    private void useClockAt(LocalDate date, LocalTime time, String zone) {
        Clock fixed = Clock.fixed(ZonedDateTime.of(date, time, ZoneId.of(zone)).toInstant(), ZoneId.of("UTC"));
        ReflectionTestUtils.setField(scheduler, "clock", fixed);
    }

    private String idempotencyKey(long schoolId, String teacherId) {
        return "teacher-attendance-reminder:" + schoolId + ":" + TODAY + ":" + teacherId;
    }

    private int notificationCount(long schoolId, String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE school_id = ? AND idempotency_key = ?",
                Integer.class, schoolId, key);
        return count == null ? 0 : count;
    }

    @Test
    void duplicateSchedulerExecution_producesExactlyOneReminder() {
        scheduler.sendTeacherAttendanceReminders();
        scheduler.sendTeacherAttendanceReminders();
        scheduler.sendTeacherAttendanceReminders();

        assertThat(notificationCount(SCHOOL_A, idempotencyKey(SCHOOL_A, TEACHER_A))).isEqualTo(1);
    }

    @Test
    void existingAttendanceRecord_suppressesTheReminder() {
        jdbc.update("INSERT INTO teacher_attendance (teacher_id,school_id,date,status,method) VALUES " +
                "(?,?,?,'ON_TIME','GPS')", TEACHER_A, SCHOOL_A, TODAY);

        scheduler.sendTeacherAttendanceReminders();

        assertThat(notificationCount(SCHOOL_A, idempotencyKey(SCHOOL_A, TEACHER_A))).isZero();
    }

    @Test
    void approvedLeaveCoveringToday_suppressesTheReminder() {
        jdbc.update("INSERT INTO teacher_leave (school_id,teacher_id,teacher_name,start_date,end_date,reason," +
                        "status,applied_date) VALUES (?,?,?,?,?,'Sick leave','APPROVED',CURRENT_TIMESTAMP)",
                SCHOOL_A, TEACHER_A, "Teacher " + TEACHER_A, TODAY.minusDays(1), TODAY.plusDays(1));

        scheduler.sendTeacherAttendanceReminders();

        assertThat(notificationCount(SCHOOL_A, idempotencyKey(SCHOOL_A, TEACHER_A))).isZero();
    }

    @Test
    void twoSchools_areProcessedIndependently_oneEligibleOneAlreadyMarked() {
        seedSchool(SCHOOL_B, "Asia/Kolkata", LocalTime.of(7, 45));
        seedTeacher(SCHOOL_B, TEACHER_B);
        // School B's teacher has already marked attendance — must not receive a reminder,
        // while School A's teacher (no attendance) still does, in the same scheduler run.
        jdbc.update("INSERT INTO teacher_attendance (teacher_id,school_id,date,status,method) VALUES " +
                "(?,?,?,'ON_TIME','GPS')", TEACHER_B, SCHOOL_B, TODAY);

        scheduler.sendTeacherAttendanceReminders();

        assertThat(notificationCount(SCHOOL_A, idempotencyKey(SCHOOL_A, TEACHER_A))).isEqualTo(1);
        assertThat(notificationCount(SCHOOL_B, idempotencyKey(SCHOOL_B, TEACHER_B))).isZero();
    }
}
