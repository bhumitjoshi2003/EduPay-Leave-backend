package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReleaseNote;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves release_notes.version is genuinely UNIQUE at the schema level (V70), not merely
 * enforced by application code — the seeder's existsByVersion check is a courtesy for the
 * normal path, this is the backstop for e.g. concurrent seeding.
 *
 * Scoped to just the ReleaseNote entity (see LeaveConcurrencyIT for the same pattern) so the
 * H2 create-drop schema doesn't need to accommodate every other Postgres-specific entity.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = ReleaseNote.class)
@EnableJpaRepositories(basePackageClasses = ReleaseNoteRepository.class)
class ReleaseNoteRepositoryUniquenessTest {

    @Autowired private ReleaseNoteRepository repository;

    @Test
    void secondRowWithTheSameVersionIsRejectedByTheDatabase() {
        repository.saveAndFlush(release("1.4.0"));

        assertThatThrownBy(() -> repository.saveAndFlush(release("1.4.0")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ReleaseNote release(String version) {
        ReleaseNote note = new ReleaseNote();
        note.setVersion(version);
        note.setTitle("Title");
        note.setAudience("ALL");
        note.setActive(true);
        note.setPublishedAt(LocalDate.of(2026, 9, 21));
        return note;
    }
}
