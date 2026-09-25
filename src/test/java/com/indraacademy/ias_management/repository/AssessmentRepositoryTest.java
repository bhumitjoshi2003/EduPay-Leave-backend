package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Assessment;
import com.indraacademy.ias_management.entity.AssessmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Real SQL (H2) for student scoping, date windows and the reminder claim. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = Assessment.class)
class AssessmentRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired AssessmentRepository assessments;

    static final long SCHOOL = 1L;
    static final long SESSION = 11L;
    static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    Long tomorrowId;

    @BeforeEach
    void seed() {
        tomorrowId = save("Tomorrow A", SCHOOL, SESSION, 8L, 3L, TODAY.plusDays(1), LocalTime.of(10, 0)).getId();
        save("Today whole class", SCHOOL, SESSION, 8L, null, TODAY, null);
        save("Next week B", SCHOOL, SESSION, 8L, 4L, TODAY.plusDays(7), null);
        save("Last week A", SCHOOL, SESSION, 8L, 3L, TODAY.minusDays(7), null);
        save("October A", SCHOOL, SESSION, 8L, 3L, LocalDate.of(2026, 10, 5), null);
        save("Class 9", SCHOOL, SESSION, 9L, 5L, TODAY.plusDays(1), null);
        save("Other school", 2L, SESSION, 8L, 3L, TODAY.plusDays(1), null);
        save("Old session", SCHOOL, 10L, 8L, 3L, TODAY.plusDays(1), null);
        em.flush();
    }

    @Test
    void upcomingIsTheSectionPlusWholeClassFromTodaySoonestFirst() {
        assertThat(assessments.findForClassFrom(SCHOOL, SESSION, 8L, 3L, TODAY, PageRequest.of(0, 50)))
                .extracting(Assessment::getTitle).containsExactly("Today whole class", "Tomorrow A", "October A");
    }

    @Test
    void aStudentWithoutASectionSeesOnlyWholeClassAssessments() {
        assertThat(assessments.findForClassFrom(SCHOOL, SESSION, 8L, null, TODAY.minusDays(30), PageRequest.of(0, 50)))
                .extracting(Assessment::getTitle).containsExactly("Today whole class");
    }

    @Test
    void monthAndPastWindows() {
        assertThat(assessments.findForClassBetween(SCHOOL, SESSION, 8L, 3L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .extracting(Assessment::getTitle).containsExactly("Last week A", "Today whole class", "Tomorrow A");
        assertThat(assessments.findForClassBefore(SCHOOL, SESSION, 8L, 3L, TODAY, PageRequest.of(0, 50)))
                .extracting(Assessment::getTitle).containsExactly("Last week A");
    }

    @Test
    void teacherCandidatesAreOwnOrInRelatedClassesOfThisSchoolAndSessionEachOnce() {
        Assessment own = save("Own class 12", SCHOOL, SESSION, 12L, null, TODAY.plusDays(2), null);
        own.setCreatedByUserId("T9");
        em.persistAndFlush(own);
        assertThat(assessments.findTeacherCandidatesFrom(SCHOOL, SESSION, "T9", java.util.List.of(9L), TODAY, PageRequest.of(0, 100)))
                .extracting(Assessment::getTitle).containsExactly("Class 9", "Own class 12");
        assertThat(assessments.findTeacherCandidatesFrom(SCHOOL, SESSION, "T9", java.util.List.of(-1L), TODAY, PageRequest.of(0, 100)))
                .extracting(Assessment::getTitle).containsExactly("Own class 12");
        assertThat(assessments.findTeacherCandidatesBefore(SCHOOL, SESSION, "nobody", java.util.List.of(8L), TODAY, PageRequest.of(0, 100)))
                .extracting(Assessment::getTitle).containsExactly("Last week A");
    }

    @Test
    void theReminderClaimSucceedsOnceAndOnlyForTheSameDate() {
        Instant now = Instant.parse("2026-09-23T11:30:00Z");
        assertThat(assessments.findReminderCandidates(TODAY.plusDays(1), TODAY.plusDays(1), PageRequest.of(0, 100)))
                .extracting(Assessment::getTitle).contains("Tomorrow A", "Class 9", "Other school");

        assertThat(assessments.markReminderSent(tomorrowId, TODAY.plusDays(2), now)).as("rescheduled").isZero();
        assertThat(assessments.markReminderSent(tomorrowId, TODAY.plusDays(1), now)).isEqualTo(1);
        assertThat(assessments.markReminderSent(tomorrowId, TODAY.plusDays(1), now)).as("second claim").isZero();
        assertThat(assessments.findReminderCandidates(TODAY.plusDays(1), TODAY.plusDays(1), PageRequest.of(0, 100)))
                .extracting(Assessment::getTitle).doesNotContain("Tomorrow A");

        assertThat(assessments.releaseReminderClaim(tomorrowId, now.plusSeconds(1))).as("someone else's claim").isZero();
        assertThat(assessments.releaseReminderClaim(tomorrowId, now)).isEqualTo(1);
        assertThat(assessments.markReminderSent(tomorrowId, TODAY.plusDays(1), now)).as("retry after release").isEqualTo(1);
    }

    @Test
    void aDeletedAssessmentCannotBeClaimed() {
        assessments.deleteById(tomorrowId);
        em.flush();
        assertThat(assessments.markReminderSent(tomorrowId, TODAY.plusDays(1), Instant.now())).isZero();
    }

    private Assessment save(String title, long school, long session, long classId, Long sectionId, LocalDate date, LocalTime start) {
        Assessment a = new Assessment();
        a.setSchoolId(school);
        a.setAcademicSessionId(session);
        a.setCreatedByUserId("T1");
        a.setCreatedByRole("TEACHER");
        a.setAssessmentType(AssessmentType.CLASS_TEST);
        a.setClassId(classId);
        a.setClassName(String.valueOf(classId));
        a.setSectionId(sectionId);
        a.setSubjectName("Science");
        a.setTitle(title);
        a.setAssessmentDate(date);
        a.setStartTime(start);
        a.setSyllabus("Ch 1");
        return em.persist(a);
    }
}
