package com.indraacademy.ias_management.dto;
import com.indraacademy.ias_management.entity.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;
public class WisdomDtos {
 public record ThoughtInput(@NotBlank @Size(max=300) String body, @NotBlank String audience, boolean active, long version) {}
 public record OverrideInput(@NotNull Long thoughtId, @NotNull LocalDate displayDate, @NotBlank String audience) {}
 public record TeachingInput(@NotNull Long verseId, @NotBlank @Size(max=160) String title,
   @NotBlank @Size(max=4000) String simpleMeaning, @NotBlank @Size(max=12000) String understanding,
   @NotBlank @Size(max=2000) String lesson, long version) {}
 public record VerseInput(@Min(1) @Max(18) int chapter, @Min(1) int verse,
   @NotBlank String sanskrit, @NotBlank String transliteration, @NotBlank String translation,
   @NotBlank String sourceName, @NotBlank String sourceUrl, @NotBlank String license,
   @NotBlank @Size(max=100) String sourceVersion, @NotBlank @Size(max=200) String verifiedBy,
   @NotBlank String themes, long version) {}
 /** time is optional — omitted or null defaults to school-local midnight, preserving the exact
  *  behavior/data shape of every teaching scheduled before this field existed. */
 public record ScheduleInput(@NotNull LocalDate date, LocalTime time, long version) {}
 public record VersionInput(long version) {}
 public record AiInput(@NotNull Long verseId, @NotBlank @Size(max=500) String theme) {}
 public record EditorialDraft(String simpleMeaning, String understanding, String lesson) {}
 public record SuggestInput(@NotBlank @Size(max=500) String theme) {}
 public record VerseCandidate(Long id, int chapter, int verse, String themes, String excerpt) {}
 public record SuggestRequest(String theme, List<VerseCandidate> candidates) {}
 public record VerseSuggestion(Long verseId, String rationale) {}
 public record VerseSuggestionView(WisdomVerse scripture, String rationale) {}
 /** One row of a reviewed, version-controlled scripture import artifact — see
  *  WisdomVerseImportService. Every field here must come from the source itself; nothing is
  *  computed by this record. Transliteration is deliberately absent: it is always derived
  *  mechanically from {@code sanskrit} by the importer, never accepted as input. */
 public record VerseImportRecord(int chapter, int verse, String sanskrit, String translation,
   String sourceName, String sourceUrl, String license, String sourceVersion, String verifiedBy,
   String themes) {}
 public record VerseImportRejection(int index, String reference, String reason) {}
 public record VerseImportSummary(List<String> imported, List<String> skippedExisting, List<VerseImportRejection> rejected) {}
 public record ThoughtView(LocalDate date, String body, String audience, boolean overridden) {}
 public record TeachingView(Long id, long version, String title, WisdomVerse scripture, String simpleMeaning,
   String understanding, String lesson, LocalDate publicationDate, String publicationZone, String status) {}
 public record Dashboard(ThoughtView thought, TeachingView teaching, LocalDate today, String timezone) {}
 public record Management(LocalDate today, String timezone, boolean upcomingScheduled) {}
}
