package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.entity.ReleaseNote;
import com.indraacademy.ias_management.repository.ReleaseNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seeding is per-release-version idempotent, not per-table — see ReleaseNoteSeeder's Javadoc.
 * These tests deliberately never stub repository.count(): the seeder must not call it at all
 * any more, so a stray call would surface as an UnnecessaryStubbingException-free but also
 * unverified interaction — the real proof is every existsByVersion/save assertion below.
 */
@ExtendWith(MockitoExtension.class)
class ReleaseNoteSeederTest {

    @Mock private ReleaseNoteRepository repository;
    private ReleaseNoteSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new ReleaseNoteSeeder();
        ReflectionTestUtils.setField(seeder, "repository", repository);
    }

    @Test
    void emptyDatabase_insertsTheSeededRelease() {
        when(repository.existsByVersion("1.4.0")).thenReturn(false);

        seeder.run(null);

        ArgumentCaptor<ReleaseNote> captor = ArgumentCaptor.forClass(ReleaseNote.class);
        verify(repository).save(captor.capture());
        ReleaseNote saved = captor.getValue();
        assertThat(saved.getVersion()).isEqualTo("1.4.0");
        assertThat(saved.getAudience()).isEqualTo("ALL");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getItems()).contains("School Setup guide for admins");
    }

    @Test
    void seederRunTwice_stillExactlyOneReleaseRow() {
        // First run: row doesn't exist yet, gets inserted. Second run (e.g. next app restart):
        // existsByVersion now reflects that insert, so nothing is saved again.
        when(repository.existsByVersion("1.4.0")).thenReturn(false, true);

        seeder.run(null);
        seeder.run(null);

        verify(repository, times(1)).save(any());
    }

    @Test
    void databaseAlreadyHasOneRelease_appendingANewOneOnlyInsertsTheNewOne() {
        // Simulates the real-world sequence this bug report is about: production already has
        // 1.4.0, and a later code change appends a seedRelease("1.5.0", ...) call. Invoked
        // directly (seedRelease is package-private for exactly this reason) since run() itself
        // only lists whatever is currently shipped — this proves the underlying per-release
        // mechanism generalizes correctly to any number of appended releases, not just the one
        // currently in run().
        when(repository.existsByVersion("1.4.0")).thenReturn(true);
        when(repository.existsByVersion("1.5.0")).thenReturn(false);

        seeder.seedRelease("1.4.0", "Old title", "Old summary", "ALL", LocalDate.of(2026, 9, 21), "old item");
        seeder.seedRelease("1.5.0", "New title", "New summary", "ALL", LocalDate.of(2026, 10, 15), "new item");

        // 1.4.0 untouched: no save was ever attempted for it.
        verify(repository, never()).save(argThat(n -> n.getVersion().equals("1.4.0")));
        // 1.5.0 inserted exactly once, with its own content — not merged with or derived from 1.4.0.
        ArgumentCaptor<ReleaseNote> captor = ArgumentCaptor.forClass(ReleaseNote.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo("1.5.0");
        assertThat(captor.getValue().getTitle()).isEqualTo("New title");
    }

    @Test
    void existingReleaseContentIsNeverOverwritten() {
        // When a version already exists, the seeder must take no action on it whatsoever — no
        // save (insert or update), no find-then-modify. This is what "immutable for Phase 1
        // seeding" means in practice: the only path that could change a row (repository.save())
        // is never invoked once existsByVersion says the row is already there.
        when(repository.existsByVersion("1.4.0")).thenReturn(true);

        seeder.run(null);

        verify(repository, never()).save(any());
    }
}
