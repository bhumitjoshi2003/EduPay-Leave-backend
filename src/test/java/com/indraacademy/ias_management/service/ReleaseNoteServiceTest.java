package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReleaseNoteResponse;
import com.indraacademy.ias_management.entity.ReleaseNote;
import com.indraacademy.ias_management.repository.ReleaseNoteRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseNoteServiceTest {

    @Mock private ReleaseNoteRepository repository;
    @Mock private SecurityUtil securityUtil;

    private ReleaseNoteService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseNoteService(repository, securityUtil);
    }

    private ReleaseNote note(String version, String audience, LocalDate publishedAt, String items) {
        ReleaseNote note = new ReleaseNote();
        note.setVersion(version);
        note.setTitle("Title " + version);
        note.setSummary("Summary " + version);
        note.setAudience(audience);
        note.setActive(true);
        note.setPublishedAt(publishedAt);
        note.setItems(items);
        return note;
    }

    @Test
    void adminSeesBothAllAndAdminOnlyReleases() {
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(repository.findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(List.of("ALL", "ADMIN")))
                .thenReturn(List.of(
                        note("1.4.0", "ALL", LocalDate.of(2026, 9, 21), "General item"),
                        note("1.3.0", "ADMIN", LocalDate.of(2026, 9, 1), "Admin-only item")));

        List<ReleaseNoteResponse> result = service.getReleasesForCurrentUser();

        assertThat(result).extracting(ReleaseNoteResponse::version).containsExactly("1.4.0", "1.3.0");
    }

    @Test
    void teacherNeverReceivesAnAdminOnlyRelease_repositoryQueryScopesToRoleAndAll() {
        when(securityUtil.getRole()).thenReturn("TEACHER");
        when(repository.findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(List.of("ALL", "TEACHER")))
                .thenReturn(List.of(note("1.4.0", "ALL", LocalDate.of(2026, 9, 21), "General item")));

        List<ReleaseNoteResponse> result = service.getReleasesForCurrentUser();

        assertThat(result).extracting(ReleaseNoteResponse::version).containsExactly("1.4.0");
        // The ADMIN-only release from the other test can never leak in — the repository call
        // itself is scoped to exactly {ALL, TEACHER}, never a broader set.
        verify(repository).findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(List.of("ALL", "TEACHER"));
    }

    @Test
    void latestReturnsOnlyTheFirstResultFromTheOrderedList() {
        when(securityUtil.getRole()).thenReturn("STUDENT");
        when(repository.findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(anyCollection()))
                .thenReturn(List.of(
                        note("1.4.0", "ALL", LocalDate.of(2026, 9, 21), "Newest"),
                        note("1.3.0", "ALL", LocalDate.of(2026, 9, 1), "Older")));

        Optional<ReleaseNoteResponse> latest = service.getLatestForCurrentUser();

        assertThat(latest).isPresent();
        assertThat(latest.get().version()).isEqualTo("1.4.0");
    }

    @Test
    void latestIsEmptyWhenNoReleaseApplies() {
        when(securityUtil.getRole()).thenReturn("PARENT");
        when(repository.findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(anyCollection()))
                .thenReturn(List.of());

        assertThat(service.getLatestForCurrentUser()).isEmpty();
    }

    @Test
    void itemsAreSplitFromNewlineSeparatedTextAndTrimmed() {
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(repository.findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(anyCollection()))
                .thenReturn(List.of(note("1.4.0", "ALL", LocalDate.of(2026, 9, 21),
                        "  First item  \nSecond item\n\nThird item")));

        ReleaseNoteResponse response = service.getLatestForCurrentUser().orElseThrow();

        assertThat(response.items()).containsExactly("First item", "Second item", "Third item");
    }

    @Test
    void blankItemsProducesAnEmptyListRatherThanAnErrorOrNullEntries() {
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(repository.findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(anyCollection()))
                .thenReturn(List.of(note("1.4.0", "ALL", LocalDate.of(2026, 9, 21), null)));

        ReleaseNoteResponse response = service.getLatestForCurrentUser().orElseThrow();

        assertThat(response.items()).isEmpty();
    }

    @Test
    void responseNeverLeaksAudienceOrInternalId() {
        // ReleaseNoteResponse is a record with exactly these components — a compile-time
        // guarantee, verified here by construction, that audience/id/active are not present.
        ReleaseNoteResponse response = new ReleaseNoteResponse(
                "1.4.0", "Title", "Summary", List.of("item"), LocalDate.of(2026, 9, 21));

        assertThat(response.version()).isEqualTo("1.4.0");
        assertThat(response.toString()).doesNotContain("audience").doesNotContain("ADMIN");
    }
}
