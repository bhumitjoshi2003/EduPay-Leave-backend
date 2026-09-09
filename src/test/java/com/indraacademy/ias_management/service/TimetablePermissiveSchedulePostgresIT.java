package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.TimetableDtos.TimetableEntryRequest;
import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Proves the new product rule end to end, through the real {@link TimetableService} against a
 * real PostgreSQL database with Flyway (including V58) applied: the timetable is a permissive
 * schedule record — the application never adjudicates whether multiple assignments in the same
 * school/session/class/section/day/period are logically correct, and any number of rows may
 * coexist there, including the same subject and/or the same teacher. Ownership (a TEACHER may
 * only update/delete their own row) is still fully enforced.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TimetableService.class, TimetableSessionAccessService.class, TeacherClassScopeService.class,
        com.indraacademy.ias_management.config.ClockConfig.class,
        TimetablePermissiveSchedulePostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class TimetablePermissiveSchedulePostgresIT {

    private static final long SCHOOL = -97001L;
    private static final long SESSION = -97101L;
    private static final long CLASS_11_SCIENCE = -97201L;
    private static final String TEACHER_A = "TPS-A", TEACHER_B = "TPS-B", TEACHER_C = "TPS-C",
            TEACHER_D = "TPS-D", TEACHER_E = "TPS-E";

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TimetableService timetableService;
    @MockBean private SecurityUtil securityUtil;
    @MockBean private AuditService auditService;

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void seedFixtures() {
        jdbc.update("INSERT INTO school (id, active, created_at, name, plan, slug, academic_year_start_month, periods_per_day) " +
                "VALUES (?, true, CURRENT_TIMESTAMP, 'tps-school', 'TRIAL', 'tps-school', 4, 8)", SCHOOL);
        jdbc.update("INSERT INTO academic_session (id, created_at, is_current, start_date, end_date, label, school_id) " +
                "VALUES (?, CURRENT_TIMESTAMP, true, DATE '2026-04-01', DATE '2027-03-31', 'TPS-SESSION', ?)", SESSION, SCHOOL);
        jdbc.update("INSERT INTO school_class (id, active, name, school_id, stream_eligible) " +
                "VALUES (?, true, '11 Science', ?, false)", CLASS_11_SCIENCE, SCHOOL);
        for (String teacherId : List.of(TEACHER_A, TEACHER_B, TEACHER_C, TEACHER_D, TEACHER_E)) {
            jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                    teacherId, SCHOOL, "Teacher " + teacherId);
        }

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        // Every TEACHER caller in this test already teaches CLASS_11_SCIENCE in the current
        // session by the time they act (each row created first bootstraps the next check), so no
        // TeacherClassScopeService/grant relationship needs to be mocked beyond ownership.
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM timetable_entry WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM teacher WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM school WHERE id = ?", SCHOOL);
    }

    private TimetableEntryRequest req(String subject, String teacherId) {
        return new TimetableEntryRequest(SESSION, CLASS_11_SCIENCE, null, Day.TUESDAY, 4, "11:00", "12:00", subject, teacherId);
    }

    @Test
    void fiveTeachersSameClassDaySlot_mathMathBiologyPhysicsMath_allFivePersist() {
        TimetableEntry a = timetableService.create(req("Mathematics", TEACHER_A), "ADMIN", "admin1", request);
        TimetableEntry b = timetableService.create(req("Mathematics", TEACHER_B), "ADMIN", "admin1", request);
        TimetableEntry c = timetableService.create(req("Biology", TEACHER_C), "ADMIN", "admin1", request);
        TimetableEntry d = timetableService.create(req("Physics", TEACHER_D), "ADMIN", "admin1", request);
        TimetableEntry e = timetableService.create(req("Mathematics", TEACHER_E), "ADMIN", "admin1", request);

        assertThat(List.of(a, b, c, d, e)).extracting(TimetableEntry::getId).doesNotHaveDuplicates();

        List<TimetableEntry> slot = timetableRepository.findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                SESSION, CLASS_11_SCIENCE, Day.TUESDAY, 4, SCHOOL);
        assertThat(slot).hasSize(5);
        assertThat(slot).extracting(TimetableEntry::getTeacherId)
                .containsExactlyInAnyOrder(TEACHER_A, TEACHER_B, TEACHER_C, TEACHER_D, TEACHER_E);
        assertThat(slot).extracting(TimetableEntry::getSubjectName)
                .containsExactlyInAnyOrder("Mathematics", "Mathematics", "Biology", "Physics", "Mathematics");
    }

    @Test
    void teacherB_canEditAndDeleteOnlyOwnRow_notTheOtherFour() {
        TimetableEntry a = timetableService.create(req("Mathematics", TEACHER_A), "ADMIN", "admin1", request);
        TimetableEntry b = timetableService.create(req("Mathematics", TEACHER_B), "ADMIN", "admin1", request);
        TimetableEntry c = timetableService.create(req("Biology", TEACHER_C), "ADMIN", "admin1", request);
        TimetableEntry d = timetableService.create(req("Physics", TEACHER_D), "ADMIN", "admin1", request);
        TimetableEntry eRow = timetableService.create(req("Mathematics", TEACHER_E), "ADMIN", "admin1", request);

        // Teacher B can edit their own row.
        TimetableEntry updated = timetableService.update(b.getId(), req("Mathematics (Advanced)", TEACHER_B),
                "TEACHER", TEACHER_B, request);
        assertThat(updated.getSubjectName()).isEqualTo("Mathematics (Advanced)");

        // Teacher B cannot edit or delete any of A/C/D/E's rows.
        for (TimetableEntry other : List.of(a, c, d, eRow)) {
            assertThatThrownBy(() -> timetableService.update(other.getId(), req("Hacked", TEACHER_B), "TEACHER", TEACHER_B, request))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> timetableService.delete(other.getId(), SESSION, "TEACHER", TEACHER_B, request))
                    .isInstanceOf(SecurityException.class);
        }

        // Teacher B can delete their own row.
        timetableService.delete(b.getId(), SESSION, "TEACHER", TEACHER_B, request);

        List<TimetableEntry> remaining = timetableRepository.findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                SESSION, CLASS_11_SCIENCE, Day.TUESDAY, 4, SCHOOL);
        assertThat(remaining).extracting(TimetableEntry::getId)
                .containsExactlyInAnyOrder(a.getId(), c.getId(), d.getId(), eRow.getId());
    }

    @Test
    void admin_canUpdateAndDeleteAnyRowRegardlessOfOwner() {
        TimetableEntry a = timetableService.create(req("Mathematics", TEACHER_A), "ADMIN", "admin1", request);
        TimetableEntry b = timetableService.create(req("Mathematics", TEACHER_B), "ADMIN", "admin1", request);

        TimetableEntry updated = timetableService.update(a.getId(), req("Mathematics (Revised)", TEACHER_A), request);
        assertThat(updated.getSubjectName()).isEqualTo("Mathematics (Revised)");

        timetableService.delete(b.getId(), SESSION, request);

        List<TimetableEntry> remaining = timetableRepository.findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                SESSION, CLASS_11_SCIENCE, Day.TUESDAY, 4, SCHOOL);
        assertThat(remaining).extracting(TimetableEntry::getId).containsExactly(a.getId());
    }

    @Test
    void sameTeacher_twoDifferentSubjectsAtTheExactSameClassDayPeriodTime_bothPersist() {
        // If the new rule truly has no overlap restriction, a single teacher may even be
        // double-booked with themselves across two different subjects in the identical slot —
        // the application no longer decides that is a conflict; that is the admin's job.
        TimetableEntry math = timetableService.create(req("Mathematics", TEACHER_A), "ADMIN", "admin1", request);
        TimetableEntry biology = timetableService.create(req("Biology", TEACHER_A), "ADMIN", "admin1", request);

        assertThat(math.getId()).isNotEqualTo(biology.getId());
        List<TimetableEntry> slot = timetableRepository.findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                SESSION, CLASS_11_SCIENCE, Day.TUESDAY, 4, SCHOOL);
        assertThat(slot).hasSize(2);
        assertThat(slot).allMatch(row -> TEACHER_A.equals(row.getTeacherId()));
        assertThat(slot).extracting(TimetableEntry::getSubjectName).containsExactlyInAnyOrder("Mathematics", "Biology");
    }
}
