package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceInsightsDtos.ClassInsights;
import com.indraacademy.ias_management.dto.AttendanceInsightsDtos.MonthTrend;
import com.indraacademy.ias_management.dto.AttendanceInsightsDtos.RecentDay;
import com.indraacademy.ias_management.dto.AttendanceInsightsDtos.StudentInsights;
import com.indraacademy.ias_management.dto.AttendanceInsightsDtos.StudentRow;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Real-PostgreSQL coverage for Attendance Insights (Phase 1). The clock is fixed so the school's
 * "today" (Asia/Kolkata) is Friday 2026-09-25; the current session is 2026-2027. Rows are
 * explicit Attendance V2 rows written through {@link AttendanceV2Fixtures}.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceInsightsService.class, AttendanceService.class, AcademicSessionService.class,
        TimetableSessionAccessService.class, AttendanceInsightsPostgresIT.FixedClock.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AttendanceInsightsPostgresIT {

    static final long SCHOOL = -96001L, OTHER_SCHOOL = -96002L, SESSION = -96003L, OTHER_SESSION = -96004L;
    static final long CLASS_8 = -96010L, CLASS_9 = -96011L, OTHER_CLASS = -96012L;
    static final long SECTION_A = -96020L, SECTION_B = -96021L;
    static final String A1 = "INS-A1", A2 = "INS-A2", A3 = "INS-A3", B1 = "INS-B1", P1 = "INS-P1";
    static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-24T20:00:00Z"), ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired AttendanceInsightsService insights;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;
    @MockBean TeacherClassScopeService classScope;

    /** The 20 most recent weekdays up to TODAY, oldest first (31 Aug … 25 Sep 2026). */
    static List<LocalDate> twentyDays() {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = TODAY; days.size() < 20; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) days.add(0, d);
        }
        return days;
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,working_days) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'Insights IT','TRIAL','insights-it',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY')," +
                "(?,true,CURRENT_TIMESTAMP,'Insights Other','TRIAL','insights-other',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY')",
                SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)," +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)", SESSION, SCHOOL, OTHER_SESSION, OTHER_SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'8',true,false),(?,?,'9',true,false),(?,?,'8',true,false)",
                CLASS_8, SCHOOL, CLASS_9, SCHOOL, OTHER_CLASS, OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true),(?,?,?,'B',true)",
                SECTION_A, SCHOOL, CLASS_8, SECTION_B, SCHOOL, CLASS_8);
        student(A1, "Aarav", CLASS_8, "8", SECTION_A, "A");
        student(A2, "Bina", CLASS_8, "8", SECTION_A, "A");
        student(A3, "Chetan", CLASS_8, "8", SECTION_A, "A");
        student(B1, "Divya", CLASS_8, "8", SECTION_B, "B");
        student(P1, "Esha", CLASS_9, "9", null, null);

        List<LocalDate> days = twentyDays();
        LocalDate leaveDay = LocalDate.of(2026, 9, 15), unexplained = LocalDate.of(2026, 9, 1);
        for (LocalDate d : days) {
            // A1: 18 present, 2 absent (15 Sep on approved leave, 1 Sep unexplained) -> 90%.
            mark(A1, CLASS_8, SECTION_A, d, d.equals(leaveDay) || d.equals(unexplained) ? "ABSENT" : "PRESENT");
        }
        // A2: only the last 4 days: PRESENT then 3 ABSENT (current streak 3) -> 25%.
        List<LocalDate> last4 = days.subList(16, 20);
        mark(A2, CLASS_8, SECTION_A, last4.get(0), "PRESENT");
        for (LocalDate d : last4.subList(1, 4)) mark(A2, CLASS_8, SECTION_A, d, "ABSENT");
        // A3: enrolled, nothing recorded. B1: 2 absences in section B (streak 2).
        mark(B1, CLASS_8, SECTION_B, days.get(18), "ABSENT");
        mark(B1, CLASS_8, SECTION_B, days.get(19), "ABSENT");
        mark(P1, CLASS_9, null, days.get(19), "PRESENT");
        jdbc.update("INSERT INTO leaves (school_id,student_id,student_name,class_name,leave_date,reason,applied_date,status) VALUES " +
                "(?,?,?, '8',?, 'Unwell',CURRENT_TIMESTAMP,'APPROVED'),(?,?,?, '8',?, 'Unwell',CURRENT_TIMESTAMP,'PENDING')",
                SCHOOL, A1, "Aarav", leaveDay.toString(), SCHOOL, A1, "Aarav", unexplained.toString());

        when(security.getSchoolId()).thenReturn(SCHOOL);
        when(security.getUsername()).thenReturn("INS-TEACHER");
    }

    // ─── Student ─────────────────────────────────────────────────────────

    @Test
    void studentInsightsUseTheV2FormulaWithApprovedLeaveStillAbsent() {
        StudentInsights s = insights.studentInsights(A1);

        assertThat(s.sessionLabel()).isEqualTo("2026-2027");
        assertThat(s.to()).isEqualTo(TODAY);
        assertThat(s.submittedDays()).isEqualTo(20);
        assertThat(s.present()).isEqualTo(18);
        assertThat(s.absent()).isEqualTo(2);
        assertThat(s.approvedLeave()).isEqualTo(1);          // pending leave on 1 Sep does not count
        assertThat(s.percentage()).isEqualTo(90.0);          // 18 / 20, denominator not reduced
        assertThat(s.lowAttendance()).isFalse();
        assertThat(s.currentAbsenceStreak()).isZero();
    }

    @Test
    void monthlyTrendOnlyHasMonthsWithSubmittedDays() {
        List<MonthTrend> trend = insights.studentInsights(A1).monthlyTrend();

        assertThat(trend).extracting(MonthTrend::label).containsExactly("Aug 2026", "Sep 2026");
        assertThat(trend.get(0).submittedDays()).isEqualTo(1);
        assertThat(trend.get(0).percentage()).isEqualTo(100.0);
        assertThat(trend.get(1).submittedDays()).isEqualTo(19);
        assertThat(trend.get(1).present()).isEqualTo(17);
        assertThat(trend.get(1).approvedLeave()).isEqualTo(1);
        assertThat(trend.get(1).percentage()).isEqualTo(89.5);
    }

    @Test
    void recentHistoryIsTheLastTenSubmittedDaysNewestFirstWithLeaveContext() {
        List<RecentDay> recent = insights.studentInsights(A1).recent();

        assertThat(recent).hasSize(10);
        assertThat(recent.get(0).date()).isEqualTo(TODAY);
        assertThat(recent).extracting(RecentDay::date).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(recent).filteredOn(r -> r.status().equals("ABSENT")).singleElement().satisfies(r -> {
            assertThat(r.date()).isEqualTo(LocalDate.of(2026, 9, 15));
            assertThat(r.approvedLeave()).isTrue();
        });
    }

    @Test
    void lowAttendanceAndStreakFlagsAndEmptyState() {
        StudentInsights low = insights.studentInsights(A2);
        assertThat(low.percentage()).isEqualTo(25.0);
        assertThat(low.lowAttendance()).isTrue();
        assertThat(low.currentAbsenceStreak()).isEqualTo(3);

        StudentInsights none = insights.studentInsights(A3);
        assertThat(none.submittedDays()).isZero();
        assertThat(none.percentage()).isZero();
        assertThat(none.lowAttendance()).isFalse();       // no data is not "low attendance"
        assertThat(none.monthlyTrend()).isEmpty();
        assertThat(none.recent()).isEmpty();
    }

    // ─── Teacher ─────────────────────────────────────────────────────────

    @Test
    void teacherSeesOnlyTheirOwnSectionWithWeightedFiguresLowFirst() {
        when(classScope.resolveOwnScope("INS-TEACHER", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope("8", SECTION_A, false));

        ClassInsights c = insights.teacherClassInsights();

        assertThat(c.classId()).isEqualTo(CLASS_8);
        assertThat(c.sectionId()).isEqualTo(SECTION_A);
        assertThat(c.totalStudents()).isEqualTo(3);
        assertThat(c.students()).extracting(StudentRow::studentId).containsExactly(A2, A1, A3).doesNotContain(B1);
        assertThat(c.submittedRecords()).isEqualTo(24);
        assertThat(c.classPercentage()).isEqualTo(79.2);    // (18 + 1) / (20 + 4), not the mean of 90% and 25%
        assertThat(c.belowThresholdCount()).isEqualTo(1);
        assertThat(c.consecutiveAbsenceCount()).isEqualTo(1);
        assertThat(c.students().get(0).currentAbsenceStreak()).isEqualTo(3);
        assertThat(c.students().get(1).approvedLeave()).isEqualTo(1);
    }

    @Test
    void teacherWithoutAClassOrWithAnAmbiguousSectionIsDenied() {
        when(classScope.resolveOwnScope("INS-TEACHER", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));
        assertThatThrownBy(() -> insights.teacherClassInsights()).isInstanceOf(AccessDeniedException.class);

        when(classScope.resolveOwnScope("INS-TEACHER", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope("8", null, true));
        assertThatThrownBy(() -> insights.teacherClassInsights()).isInstanceOf(AccessDeniedException.class);
    }

    // ─── Admin ───────────────────────────────────────────────────────────

    @Test
    void adminWholeClassAndSingleSection() {
        ClassInsights whole = insights.adminClassInsights(CLASS_8, null);
        assertThat(whole.totalStudents()).isEqualTo(4);
        assertThat(whole.students()).extracting(StudentRow::studentId).containsExactly(B1, A2, A1, A3);
        assertThat(whole.classPercentage()).isEqualTo(73.1);   // 19 / 26
        assertThat(whole.belowThresholdCount()).isEqualTo(2);
        assertThat(whole.consecutiveAbsenceCount()).isEqualTo(1);  // B1's streak of 2 is below the threshold of 3
        assertThat(whole.students()).filteredOn(r -> r.studentId().equals(B1)).singleElement()
                .satisfies(r -> assertThat(r.sectionName()).isEqualTo("B"));

        ClassInsights sectionB = insights.adminClassInsights(CLASS_8, SECTION_B);
        assertThat(sectionB.sectionName()).isEqualTo("B");
        assertThat(sectionB.students()).extracting(StudentRow::studentId).containsExactly(B1);
        assertThat(sectionB.classPercentage()).isZero();

        ClassInsights plain = insights.adminClassInsights(CLASS_9, null);
        assertThat(plain.students()).extracting(StudentRow::studentId).containsExactly(P1);
        assertThat(plain.classPercentage()).isEqualTo(100.0);
    }

    @Test
    void adminCannotReachAnotherSchoolOrAMismatchedSection() {
        assertThatThrownBy(() -> insights.adminClassInsights(OTHER_CLASS, null)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> insights.adminClassInsights(CLASS_9, SECTION_A)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> insights.studentInsights("NOT-HERE")).isInstanceOf(NoSuchElementException.class);
    }

    // ─── Performance ─────────────────────────────────────────────────────

    @Test
    void classInsightsQueryCountDoesNotGrowWithStudents() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        // Baseline already has both a PRESENT and an ABSENT row, so every optional lookup
        // (e.g. approved leave for absences) is exercised in both measurements.
        student("INS-EXTRA-X", "Extra X", CLASS_9, "9", null, null);
        mark("INS-EXTRA-X", CLASS_9, null, TODAY, "ABSENT");
        em.flush(); em.clear();
        stats.clear();
        insights.adminClassInsights(CLASS_9, null);
        long withTwo = stats.getPrepareStatementCount();

        for (int i = 0; i < 12; i++) {
            String id = "INS-EXTRA-" + i;
            student(id, "Extra " + i, CLASS_9, "9", null, null);
            mark(id, CLASS_9, null, TODAY, i % 2 == 0 ? "PRESENT" : "ABSENT");
        }
        em.flush(); em.clear();
        stats.clear();
        ClassInsights big = insights.adminClassInsights(CLASS_9, null);

        assertThat(big.totalStudents()).isEqualTo(14);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(withTwo);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void student(String id, String name, long classId, String className, Long sectionId, String sectionName) {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,section_name,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,?,?,?,DATE '2026-04-01')", id, SCHOOL, name, classId, className, sectionId, sectionName);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id," +
                "section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'ACTIVE',DATE '2026-04-01')",
                SCHOOL, id, SESSION, classId, className, sectionId, sectionName);
    }

    private void mark(String studentId, long classId, Long sectionId, LocalDate date, String status) {
        AttendanceV2Fixtures.mark(jdbc, SCHOOL, studentId, classId, sectionId, date, status);
    }
}
