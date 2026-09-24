package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.HomeworkClasswork;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real SQL (H2) for student visibility scoping and class/section recipient resolution. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = HomeworkClasswork.class)
class HomeworkClassworkRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired HomeworkClassworkRepository works;
    @Autowired StudentEnrollmentRepository enrollments;
    @Autowired HomeworkClassworkAttachmentRepository attachmentRepo;

    static final long SCHOOL = 1L;
    static final long SESSION = 11L;
    static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    @BeforeEach
    void seed() {
        // Class 8: section A (3), section B (4), whole-class (null); plus class 9 and another school.
        work(SCHOOL, SESSION, 8L, 3L, "Science", TODAY, "hw A", TODAY.plusDays(1));
        work(SCHOOL, SESSION, 8L, 4L, "Maths", TODAY, "hw B", TODAY.plusDays(2));
        work(SCHOOL, SESSION, 8L, null, "Assembly", TODAY, null, null);
        work(SCHOOL, SESSION, 9L, 5L, "English", TODAY, "hw 9", TODAY.plusDays(1));
        work(2L, SESSION, 8L, 3L, "Other school", TODAY, "x", TODAY.plusDays(1));
        work(SCHOOL, 10L, 8L, 3L, "Old session", TODAY, "x", TODAY.plusDays(1));
        work(SCHOOL, SESSION, 8L, 3L, "History", TODAY.minusDays(3), "old hw", TODAY.minusDays(1));
        work(SCHOOL, SESSION, 8L, 3L, "Geography", TODAY.minusDays(1), "due later", TODAY.plusDays(5));
        em.flush();
    }

    @Test
    void aStudentInSectionASeesTheirSectionAndWholeClassPostsOnly() {
        assertThat(works.findForClassOnDate(SCHOOL, SESSION, 8L, 3L, TODAY))
                .extracting(HomeworkClasswork::getSubjectName).containsExactlyInAnyOrder("Science", "Assembly");
    }

    @Test
    void aStudentWithNoSectionSeesOnlyWholeClassPosts() {
        assertThat(works.findForClassOnDate(SCHOOL, SESSION, 8L, null, TODAY))
                .extracting(HomeworkClasswork::getSubjectName).containsExactly("Assembly");
    }

    @Test
    void upcomingIsHomeworkDueTodayOrLaterSoonestFirst() {
        assertThat(works.findHomeworkDueFrom(SCHOOL, SESSION, 8L, 3L, TODAY, PageRequest.of(0, 50)))
                .extracting(HomeworkClasswork::getSubjectName).containsExactly("Science", "Geography");
    }

    @Test
    void recentIsEarlierWorkDatesNewestFirst() {
        assertThat(works.findForClassBefore(SCHOOL, SESSION, 8L, 3L, TODAY, PageRequest.of(0, 30)))
                .extracting(HomeworkClasswork::getSubjectName).containsExactly("Geography", "History");
    }

    @Test
    void recipientQueriesReturnOnlyActiveEnrollmentsEffectiveTodayInThatClassOrSection() {
        enrollment("S1", 8L, 3L, StudentEnrollmentStatus.ACTIVE, TODAY.minusMonths(5), null);
        enrollment("S2", 8L, 4L, StudentEnrollmentStatus.ACTIVE, TODAY.minusMonths(5), null);
        enrollment("S3", 8L, 3L, StudentEnrollmentStatus.CLOSED, TODAY.minusMonths(5), TODAY.minusDays(10));
        enrollment("S4", 8L, 3L, StudentEnrollmentStatus.PLANNED, TODAY.plusDays(10), null);
        enrollment("S5", 9L, 5L, StudentEnrollmentStatus.ACTIVE, TODAY.minusMonths(5), null);
        em.flush();

        assertThat(enrollments.findActiveStudentIdsInSection(SCHOOL, SESSION, 8L, 3L, TODAY)).containsExactly("S1");
        assertThat(enrollments.findActiveStudentIdsInClass(SCHOOL, SESSION, 8L, TODAY)).containsExactlyInAnyOrder("S1", "S2");
        assertThat(enrollments.findActiveStudentIdsInClass(2L, SESSION, 8L, TODAY)).isEmpty();
    }

    @Test
    void attachmentsPersistInOrderAndAreRemovedWithTheirPost() {
        HomeworkClasswork post = works.findForClassOnDate(SCHOOL, SESSION, 8L, 3L, TODAY).stream()
                .filter(w -> w.getSubjectName().equals("Science")).findFirst().orElseThrow();
        post.getAttachments().add(attachment(post, "k/b.pdf", "application/pdf", 1));
        post.getAttachments().add(attachment(post, "k/a.png", "image/png", 0));
        works.saveAndFlush(post);
        em.clear();

        HomeworkClasswork reloaded = works.findById(post.getId()).orElseThrow();
        assertThat(reloaded.getAttachments()).extracting(com.indraacademy.ias_management.entity.HomeworkClassworkAttachment::getObjectKey)
                .containsExactly("k/a.png", "k/b.pdf");
        assertThat(attachmentRepo.existsByObjectKey("k/a.png")).isTrue();

        reloaded.getAttachments().remove(0);
        works.saveAndFlush(reloaded);
        assertThat(attachmentRepo.existsByObjectKey("k/a.png")).as("orphan removal").isFalse();

        works.delete(reloaded);
        works.flush();
        assertThat(attachmentRepo.count()).isZero();
    }

    private com.indraacademy.ias_management.entity.HomeworkClassworkAttachment attachment(
            HomeworkClasswork post, String key, String type, int order) {
        var a = new com.indraacademy.ias_management.entity.HomeworkClassworkAttachment();
        a.setHomeworkClasswork(post);
        a.setObjectKey(key);
        a.setFileName(key.substring(2));
        a.setContentType(type);
        a.setFileSize(100);
        a.setSortOrder(order);
        return a;
    }

    private void work(long schoolId, long sessionId, long classId, Long sectionId, String subject,
                      LocalDate workDate, String homework, LocalDate due) {
        HomeworkClasswork w = new HomeworkClasswork();
        w.setSchoolId(schoolId);
        w.setAcademicSessionId(sessionId);
        w.setTeacherId("T1");
        w.setClassId(classId);
        w.setClassName(String.valueOf(classId));
        w.setSectionId(sectionId);
        w.setSubjectName(subject);
        w.setWorkDate(workDate);
        w.setClasswork(homework == null ? "classwork" : null);
        w.setHomework(homework);
        w.setDueDate(due);
        em.persist(w);
    }

    private void enrollment(String studentId, long classId, Long sectionId, StudentEnrollmentStatus status,
                            LocalDate from, LocalDate until) {
        StudentEnrollment e = new StudentEnrollment();
        e.setSchoolId(SCHOOL);
        e.setStudentId(studentId);
        e.setAcademicSessionId(SESSION);
        e.setClassId(classId);
        e.setClassNameSnapshot(String.valueOf(classId));
        e.setSectionId(sectionId);
        e.setSectionNameSnapshot(sectionId == null ? null : "S" + sectionId);
        e.setStatus(status);
        e.setEffectiveFrom(from);
        e.setEffectiveUntil(until);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        em.persist(e);
    }
}
