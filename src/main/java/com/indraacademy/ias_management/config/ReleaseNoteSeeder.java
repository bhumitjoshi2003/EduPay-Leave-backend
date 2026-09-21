package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.entity.ReleaseNote;
import com.indraacademy.ias_management.repository.ReleaseNoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Phase 1 release-note authoring mechanism: there is no create/update endpoint yet (see
 * ReleaseNoteController's Javadoc), so a new release is shipped by appending another
 * seedRelease(...) call to run() below — plain code, reviewed and deployed like any other
 * change. A SUPER_ADMIN management UI can be layered on top of the existing
 * entity/repository/service later without any schema change.
 *
 * Idempotent per release version, not per table: each seedRelease(...) call only inserts if
 * that exact version doesn't already exist (release_notes.version is UNIQUE — see V70). This
 * means production rows are never touched — seeding 1.4.0 today and appending 1.5.0 next month
 * inserts only the new row on that later deploy; it does not require (and must never perform)
 * an empty-table check. Existing release content is treated as immutable here — if a version
 * already exists, its row is left exactly as it is, never updated.
 */
@Component
@Order(30)
public class ReleaseNoteSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReleaseNoteSeeder.class);

    @Autowired private ReleaseNoteRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        seedRelease("1.4.0", "What's New in Edunexify",
                "A few recent improvements to make Edunexify faster and easier to use.",
                "ALL", LocalDate.of(2026, 9, 21),
                "New School Setup guide for admins",
                "Improved photo and file storage",
                "Improved event image experience",
                "Cleaner email communication");
    }

    // Package-private (not private) so ReleaseNoteSeederTest can exercise it directly for
    // multi-release scenarios without fragile varargs-through-reflection.
    void seedRelease(String version, String title, String summary, String audience,
                     LocalDate publishedAt, String... items) {
        if (repository.existsByVersion(version)) {
            return;
        }
        ReleaseNote note = new ReleaseNote();
        note.setVersion(version);
        note.setTitle(title);
        note.setSummary(summary);
        note.setAudience(audience);
        note.setActive(true);
        note.setPublishedAt(publishedAt);
        note.setItems(String.join("\n", items));
        repository.save(note);
        log.info("Seeded release note {}", version);
    }
}
