package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ReleaseNoteResponse;
import com.indraacademy.ias_management.service.ReleaseNoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated "What's New" reads for any logged-in role — the web app only ever checks this
 * after login, so this does not need to be public. Role filtering happens inside the service;
 * there is no create/update endpoint in Phase 1 (see ReleaseNoteSeeder for how releases are
 * currently authored).
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseNoteController {

    @Autowired private ReleaseNoteService releaseNoteService;

    @GetMapping("/latest")
    public ResponseEntity<ReleaseNoteResponse> getLatest() {
        return releaseNoteService.getLatestForCurrentUser()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping
    public List<ReleaseNoteResponse> getAll() {
        return releaseNoteService.getReleasesForCurrentUser();
    }
}
