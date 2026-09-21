package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReleaseNoteResponse;
import com.indraacademy.ias_management.entity.ReleaseNote;
import com.indraacademy.ias_management.repository.ReleaseNoteRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Read-only "What's New" lookup. Every active release whose audience is ALL or matches the
 * caller's role, newest first. No writes, no per-user "seen" state — the client remembers
 * what it has already shown (see the web/Android lastSeenReleaseVersion persistence).
 */
@Service
public class ReleaseNoteService {

    private static final String ALL_AUDIENCE = "ALL";

    private final ReleaseNoteRepository repository;
    private final SecurityUtil securityUtil;

    public ReleaseNoteService(ReleaseNoteRepository repository, SecurityUtil securityUtil) {
        this.repository = repository;
        this.securityUtil = securityUtil;
    }

    @Transactional(readOnly = true)
    public List<ReleaseNoteResponse> getReleasesForCurrentUser() {
        List<String> audiences = List.of(ALL_AUDIENCE, securityUtil.getRole());
        return repository.findByActiveTrueAndAudienceInOrderByPublishedAtDescIdDesc(audiences)
                .stream()
                .map(ReleaseNoteService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ReleaseNoteResponse> getLatestForCurrentUser() {
        return getReleasesForCurrentUser().stream().findFirst();
    }

    private static ReleaseNoteResponse toResponse(ReleaseNote note) {
        List<String> items = note.getItems() == null || note.getItems().isBlank()
                ? List.of()
                : Arrays.stream(note.getItems().split("\n"))
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .toList();
        return new ReleaseNoteResponse(note.getVersion(), note.getTitle(), note.getSummary(),
                items, note.getPublishedAt());
    }
}
