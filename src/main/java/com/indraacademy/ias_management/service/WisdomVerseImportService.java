package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportRecord;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportRejection;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportSummary;
import com.indraacademy.ias_management.entity.WisdomVerse;
import com.indraacademy.ias_management.repository.WisdomVerseRepository;
import com.indraacademy.ias_management.util.DevanagariTransliterator;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deliberate, on-demand ingestion of a reviewed scripture dataset into {@code wisdom_verse}.
 *
 * This is NOT wired to application startup and NEVER calls out to the internet or an LLM — the
 * caller (a super admin, via the controller endpoint) supplies the already-reviewed records
 * in the request body, sourced from a version-controlled import artifact prepared and checked
 * outside this service. That keeps scripture ingestion a deliberate release step rather than
 * something that could ever run automatically against a live database.
 *
 * Guarantees:
 * - Deterministic: transliteration is always (re)computed from the supplied Sanskrit via
 *   {@link DevanagariTransliterator}, never trusted from input — the same input file produces
 *   the same database rows every time, and two operators can independently verify the output.
 * - Idempotent: re-running the same batch does not create duplicates or fail — a record whose
 *   (sourceName, sourceVersion, chapter, verse) already exists is reported as skipped, not
 *   inserted again and not treated as an error.
 * - Reports, never silently drops: every rejected record is returned with its 0-based index in
 *   the submitted batch and the exact validation reason, so malformed input is visible to the
 *   caller rather than disappearing.
 */
@Service
public class WisdomVerseImportService {
 private final WisdomVerseRepository verses;
 private final SecurityUtil security;
 private final AuditService audit;

 public WisdomVerseImportService(WisdomVerseRepository verses, SecurityUtil security, AuditService audit) {
  this.verses = verses; this.security = security; this.audit = audit;
 }

 @Transactional
 public VerseImportSummary importVerified(List<VerseImportRecord> records) {
  if (!"SUPER_ADMIN".equals(security.getRole())) throw new AccessDeniedException("Super admin required");

  List<String> imported = new ArrayList<>();
  List<String> skipped = new ArrayList<>();
  List<VerseImportRejection> rejected = new ArrayList<>();
  Set<String> seenInBatch = new HashSet<>();

  for (int i = 0; i < records.size(); i++) {
   VerseImportRecord r = records.get(i);
   String reference = "chapter " + (r == null ? "?" : r.chapter()) + " verse " + (r == null ? "?" : r.verse());
   String reason = validate(r);
   if (reason != null) { rejected.add(new VerseImportRejection(i, reference, reason)); continue; }

   String batchKey = r.sourceName() + "|" + r.sourceVersion() + "|" + r.chapter() + "|" + r.verse();
   if (!seenInBatch.add(batchKey)) {
    rejected.add(new VerseImportRejection(i, reference, "Duplicate chapter+verse within this batch"));
    continue;
   }

   if (verses.existsBySourceNameAndSourceVersionAndChapterAndVerse(r.sourceName().trim(), r.sourceVersion().trim(), r.chapter(), r.verse())) {
    skipped.add(reference);
    continue;
   }

   WisdomVerse v = new WisdomVerse();
   v.setChapter(r.chapter()); v.setVerse(r.verse());
   v.setSanskrit(r.sanskrit().trim());
   v.setTransliteration(DevanagariTransliterator.toIast(r.sanskrit().trim()));
   v.setTranslation(r.translation().trim());
   v.setSourceName(r.sourceName().trim()); v.setSourceUrl(r.sourceUrl().trim());
   v.setLicense(r.license().trim()); v.setSourceVersion(r.sourceVersion().trim());
   v.setVerifiedBy(r.verifiedBy().trim()); v.setVerifiedAt(Instant.now());
   v.setThemes(r.themes() == null ? "" : r.themes().trim());
   verses.saveAndFlush(v);
   imported.add(reference);
  }

  audit.log(security.getUsername(), security.getRole(), "IMPORT_VERIFIED_VERSES", "Wisdom", "batch",
   null, "imported=" + imported.size() + " skipped=" + skipped.size() + " rejected=" + rejected.size(), null);
  return new VerseImportSummary(imported, skipped, rejected);
 }

 private String validate(VerseImportRecord r) {
  if (r == null) return "Record is null";
  if (r.chapter() < 1 || r.chapter() > 18) return "Chapter must be between 1 and 18";
  if (r.verse() < 1) return "Verse number must be positive";
  if (isBlank(r.sanskrit())) return "Sanskrit text is required";
  if (isBlank(r.translation())) return "Translation is required";
  if (isBlank(r.sourceName())) return "Source name is required";
  if (isBlank(r.sourceUrl())) return "Source URL is required";
  if (isBlank(r.license())) return "License is required";
  if (isBlank(r.sourceVersion())) return "Source edition/version is required";
  if (r.sourceVersion().length() > 100) return "Source edition/version must be 100 characters or fewer";
  if (isBlank(r.verifiedBy())) return "Verifier name is required";
  if (r.verifiedBy().length() > 200) return "Verifier name must be 200 characters or fewer";
  return null;
 }

 private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
