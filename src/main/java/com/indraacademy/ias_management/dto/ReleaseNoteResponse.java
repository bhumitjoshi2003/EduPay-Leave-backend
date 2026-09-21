package com.indraacademy.ias_management.dto;

import java.time.LocalDate;
import java.util.List;

/** Display-ready release content — no audience/id/internal fields leaked to the client. */
public record ReleaseNoteResponse(
        String version,
        String title,
        String summary,
        List<String> items,
        LocalDate publishedAt
) {}
