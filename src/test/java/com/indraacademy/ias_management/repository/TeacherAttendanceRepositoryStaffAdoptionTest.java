package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.TeacherAttendance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, against a real database (not a mock), the two guarantees Staff Adoption depends on
 * that a Mockito-based service test structurally cannot demonstrate: that MAX(check_in_time)
 * genuinely selects the latest row, and that markedByAdmin = true rows are excluded entirely —
 * a teacher whose only attendance is admin-marked must produce NO result, not a false one.
 *
 * Scoped to just the TeacherAttendance entity, mirroring LeaveConcurrencyIT /
 * ReleaseNoteRepositoryUniquenessTest's established pattern.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = TeacherAttendance.class)
@EnableJpaRepositories(basePackageClasses = TeacherAttendanceRepository.class)
class TeacherAttendanceRepositoryStaffAdoptionTest {

    @Autowired private TeacherAttendanceRepository repository;

    @Test
    void teacherWithOnlyAdminMarkedAttendanceProducesNoResult() {
        attendance("T1", 1L, LocalDate.of(2026, 9, 20),
                LocalDateTime.of(2026, 9, 20, 8, 0), true);

        List<TeacherAttendanceRepository.LastTeacherCheckIn> result =
                repository.findLastSelfCheckInBySchoolIdAndTeacherIds(1L, List.of("T1"));

        assertThat(result).isEmpty();
    }

    @Test
    void selectsTheLatestSelfCheckInAcrossMultipleDays_ignoringAnyAdminMarkedRows() {
        attendance("T1", 1L, LocalDate.of(2026, 9, 18),
                LocalDateTime.of(2026, 9, 18, 8, 5), false);
        attendance("T1", 1L, LocalDate.of(2026, 9, 21),
                LocalDateTime.of(2026, 9, 21, 8, 10), false);
        // Most recent by date, but admin-marked — must not be picked even though it's newest.
        attendance("T1", 1L, LocalDate.of(2026, 9, 22),
                LocalDateTime.of(2026, 9, 22, 9, 0), true);

        List<TeacherAttendanceRepository.LastTeacherCheckIn> result =
                repository.findLastSelfCheckInBySchoolIdAndTeacherIds(1L, List.of("T1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTeacherId()).isEqualTo("T1");
        assertThat(result.get(0).getLastAttendanceAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 8, 10));
    }

    @Test
    void aggregatesIndependentlyPerTeacherWithNoCrossContamination() {
        attendance("T1", 1L, LocalDate.of(2026, 9, 20),
                LocalDateTime.of(2026, 9, 20, 8, 0), false);
        attendance("T2", 1L, LocalDate.of(2026, 9, 21),
                LocalDateTime.of(2026, 9, 21, 8, 30), false);

        List<TeacherAttendanceRepository.LastTeacherCheckIn> result =
                repository.findLastSelfCheckInBySchoolIdAndTeacherIds(1L, List.of("T1", "T2"));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TeacherAttendanceRepository.LastTeacherCheckIn::getTeacherId)
                .containsExactlyInAnyOrder("T1", "T2");
    }

    @Test
    void attendanceFromAnotherSchoolIsNeverIncluded() {
        attendance("T1", 1L, LocalDate.of(2026, 9, 20),
                LocalDateTime.of(2026, 9, 20, 8, 0), false);
        attendance("T1", 2L, LocalDate.of(2026, 9, 21),
                LocalDateTime.of(2026, 9, 21, 8, 0), false);

        List<TeacherAttendanceRepository.LastTeacherCheckIn> result =
                repository.findLastSelfCheckInBySchoolIdAndTeacherIds(1L, List.of("T1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLastAttendanceAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 8, 0));
    }

    private void attendance(String teacherId, Long schoolId, LocalDate date,
                            LocalDateTime checkInTime, boolean markedByAdmin) {
        TeacherAttendance a = new TeacherAttendance();
        a.setTeacherId(teacherId);
        a.setSchoolId(schoolId);
        a.setDate(date);
        a.setCheckInTime(checkInTime);
        a.setStatus("ON_TIME");
        a.setMarkedByAdmin(markedByAdmin);
        repository.saveAndFlush(a);
    }
}
