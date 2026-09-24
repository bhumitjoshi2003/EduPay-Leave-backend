package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ClassUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Real SQL (H2) for student visibility scoping, expiry and teacher listing. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = ClassUpdate.class)
class ClassUpdateRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired ClassUpdateRepository updates;

    static final long SCHOOL = 1L;
    static final long SESSION = 11L;
    static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 23, 9, 0);

    @BeforeEach
    void seed() {
        // Class 8: section A (3), section B (4), whole-class (null); plus class 9, another school/session.
        update("A1", SCHOOL, SESSION, 8L, 3L, "T1", null, 1, null);
        update("B1", SCHOOL, SESSION, 8L, 4L, "T1", null, 2, null);
        update("Whole", SCHOOL, SESSION, 8L, null, "T2", null, 3, null);
        update("Nine", SCHOOL, SESSION, 9L, 5L, "T1", null, 4, null);
        update("Other school", 2L, SESSION, 8L, 3L, "T1", null, 5, null);
        update("Old session", SCHOOL, 10L, 8L, 3L, "T1", null, 6, null);
        update("Expired", SCHOOL, SESSION, 8L, 3L, "T1", NOW.minusSeconds(1), 7, null);
        update("Expires later", SCHOOL, SESSION, 8L, 3L, "T1", NOW.plus(Duration.ofHours(2)), 8, "schools/1/k.pdf");
        em.flush();
    }

    @Test
    void aStudentInSectionASeesTheirSectionAndWholeClassActiveUpdatesNewestFirst() {
        assertThat(updates.findActiveForClass(SCHOOL, SESSION, 8L, 3L, NOW, PageRequest.of(0, 50)))
                .extracting(ClassUpdate::getTitle).containsExactly("Expires later", "Whole", "A1");
    }

    @Test
    void aStudentWithNoSectionSeesOnlyWholeClassUpdates() {
        assertThat(updates.findActiveForClass(SCHOOL, SESSION, 8L, null, NOW, PageRequest.of(0, 50)))
                .extracting(ClassUpdate::getTitle).containsExactly("Whole");
    }

    @Test
    void theActiveListHonoursTheLimit() {
        assertThat(updates.findActiveForClass(SCHOOL, SESSION, 8L, 3L, NOW, PageRequest.of(0, 2)))
                .extracting(ClassUpdate::getTitle).containsExactly("Expires later", "Whole");
    }

    @Test
    void anUpdateLeavesTheActiveListOnceItExpires() {
        assertThat(updates.findActiveForClass(SCHOOL, SESSION, 8L, 3L, NOW.plus(Duration.ofHours(3)), PageRequest.of(0, 50)))
                .extracting(ClassUpdate::getTitle).containsExactly("Whole", "A1");
    }

    @Test
    void teacherRecentIsOwnUpdatesInTheSchoolIncludingExpiredNewestFirst() {
        assertThat(updates.findBySchoolIdAndTeacherIdOrderByCreatedAtDescIdDesc(SCHOOL, "T1", PageRequest.of(0, 30)))
                .extracting(ClassUpdate::getTitle)
                .containsExactly("Expires later", "Expired", "Old session", "Nine", "B1", "A1");
    }

    @Test
    void attachmentKeysAreLookedUpAcrossUpdates() {
        assertThat(updates.existsByAttachmentObjectKey("schools/1/k.pdf")).isTrue();
        assertThat(updates.existsByAttachmentObjectKey("schools/1/other.pdf")).isFalse();
    }

    private void update(String title, long school, long session, long classId, Long sectionId, String teacher,
                        Instant expiresAt, int minutes, String key) {
        ClassUpdate u = new ClassUpdate();
        u.setSchoolId(school);
        u.setAcademicSessionId(session);
        u.setTeacherId(teacher);
        u.setClassId(classId);
        u.setClassName(String.valueOf(classId));
        u.setSectionId(sectionId);
        u.setSubjectName("Science");
        u.setTitle(title);
        u.setMessage("m");
        u.setExpiresAt(expiresAt);
        u.setCreatedAt(BASE.plusMinutes(minutes));
        if (key != null) {
            u.setAttachmentObjectKey(key);
            u.setAttachmentFileName("k.pdf");
            u.setAttachmentContentType("application/pdf");
            u.setAttachmentFileSize(10L);
        }
        em.persist(u);
    }
}
