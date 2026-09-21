package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ReleaseNoteResponse;
import com.indraacademy.ias_management.service.ReleaseNoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseNoteControllerTest {

    @Mock private ReleaseNoteService releaseNoteService;
    private ReleaseNoteController controller;

    @BeforeEach
    void setUp() {
        controller = new ReleaseNoteController();
        ReflectionTestUtils.setField(controller, "releaseNoteService", releaseNoteService);
    }

    @Test
    void getLatest_returnsOkWithBody_whenAReleaseApplies() {
        ReleaseNoteResponse note = new ReleaseNoteResponse("1.4.0", "Title", "Summary",
                List.of("item"), LocalDate.of(2026, 9, 21));
        when(releaseNoteService.getLatestForCurrentUser()).thenReturn(Optional.of(note));

        var response = controller.getLatest();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(note);
    }

    @Test
    void getLatest_returnsNoContent_whenNoReleaseApplies() {
        when(releaseNoteService.getLatestForCurrentUser()).thenReturn(Optional.empty());

        var response = controller.getLatest();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void getAll_returnsWhateverTheServiceProvides() {
        List<ReleaseNoteResponse> notes = List.of(
                new ReleaseNoteResponse("1.4.0", "Title", "Summary", List.of(), LocalDate.of(2026, 9, 21)));
        when(releaseNoteService.getReleasesForCurrentUser()).thenReturn(notes);

        assertThat(controller.getAll()).isEqualTo(notes);
    }
}
