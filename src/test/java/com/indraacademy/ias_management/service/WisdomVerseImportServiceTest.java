package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportRecord;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportSummary;
import com.indraacademy.ias_management.entity.WisdomVerse;
import com.indraacademy.ias_management.repository.WisdomVerseRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WisdomVerseImportServiceTest {
 WisdomVerseRepository verses = mock(WisdomVerseRepository.class);
 SecurityUtil security = mock(SecurityUtil.class);
 AuditService audit = mock(AuditService.class);
 WisdomVerseImportService service;

 @BeforeEach void setup() {
  service = new WisdomVerseImportService(verses, security, audit);
  when(security.getRole()).thenReturn("SUPER_ADMIN");
 }

 VerseImportRecord valid(int chapter, int verse) {
  return new VerseImportRecord(chapter, verse, "गीता", "A verified translation.",
   "Test Source", "https://example.test/source", "Public domain", "1st ed.", "Reviewer", "duty");
 }

 @Test void nonSuperAdminCannotImport() {
  when(security.getRole()).thenReturn("ADMIN");
  assertThatThrownBy(() -> service.importVerified(List.of(valid(2, 47))))
   .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  verifyNoInteractions(verses);
 }

 @Test void validRecordIsImportedWithMechanicallyDerivedTransliteration() {
  VerseImportSummary result = service.importVerified(List.of(valid(2, 47)));
  assertThat(result.imported()).containsExactly("chapter 2 verse 47");
  assertThat(result.rejected()).isEmpty();
  ArgumentCaptor<WisdomVerse> captor = ArgumentCaptor.forClass(WisdomVerse.class);
  verify(verses).saveAndFlush(captor.capture());
  WisdomVerse saved = captor.getValue();
  assertThat(saved.getTransliteration()).isEqualTo("gītā"); // derived from "गीता", never trusted from input
  assertThat(saved.getVerifiedAt()).isNotNull();
 }

 @Test void rejectsChapterOutOfRange() {
  VerseImportSummary result = service.importVerified(List.of(
   new VerseImportRecord(0, 1, "S", "T", "Src", "https://x", "Lic", "v1", "Rev", "")));
  assertThat(result.rejected()).hasSize(1);
  assertThat(result.rejected().get(0).reason()).contains("Chapter must be between 1 and 18");
  verify(verses, never()).saveAndFlush(any());
 }

 @Test void rejectsNonPositiveVerse() {
  VerseImportSummary result = service.importVerified(List.of(
   new VerseImportRecord(2, 0, "S", "T", "Src", "https://x", "Lic", "v1", "Rev", "")));
  assertThat(result.rejected().get(0).reason()).contains("Verse number must be positive");
 }

 @Test void rejectsBlankSanskrit() {
  VerseImportSummary result = service.importVerified(List.of(
   new VerseImportRecord(2, 47, "  ", "T", "Src", "https://x", "Lic", "v1", "Rev", "")));
  assertThat(result.rejected().get(0).reason()).contains("Sanskrit text is required");
 }

 @Test void rejectsBlankTranslation() {
  VerseImportSummary result = service.importVerified(List.of(
   new VerseImportRecord(2, 47, "गीता", " ", "Src", "https://x", "Lic", "v1", "Rev", "")));
  assertThat(result.rejected().get(0).reason()).contains("Translation is required");
 }

 @Test void rejectsMissingProvenanceFields() {
  VerseImportSummary noSource = service.importVerified(List.of(
   new VerseImportRecord(2, 47, "गीता", "T", "", "https://x", "Lic", "v1", "Rev", "")));
  assertThat(noSource.rejected().get(0).reason()).contains("Source name is required");

  VerseImportSummary noLicense = service.importVerified(List.of(
   new VerseImportRecord(2, 47, "गीता", "T", "Src", "https://x", "", "v1", "Rev", "")));
  assertThat(noLicense.rejected().get(0).reason()).contains("License is required");

  VerseImportSummary noVerifier = service.importVerified(List.of(
   new VerseImportRecord(2, 47, "गीता", "T", "Src", "https://x", "Lic", "v1", "", "")));
  assertThat(noVerifier.rejected().get(0).reason()).contains("Verifier name is required");
 }

 @Test void rejectsOverlongSourceVersionInsteadOfFailingAtTheDatabase() {
  VerseImportSummary result = service.importVerified(List.of(
   new VerseImportRecord(2, 47, "गीता", "T", "Src", "https://x", "Lic", "v".repeat(101), "Rev", "")));
  assertThat(result.rejected().get(0).reason()).contains("Source edition/version must be 100 characters or fewer");
  verify(verses, never()).saveAndFlush(any());
 }

 @Test void rejectsOverlongVerifiedByInsteadOfFailingAtTheDatabase() {
  VerseImportSummary result = service.importVerified(List.of(
   new VerseImportRecord(2, 47, "गीता", "T", "Src", "https://x", "Lic", "v1", "r".repeat(201), "")));
  assertThat(result.rejected().get(0).reason()).contains("Verifier name must be 200 characters or fewer");
  verify(verses, never()).saveAndFlush(any());
 }

 @Test void rejectsDuplicateChapterVerseWithinTheSameBatch() {
  VerseImportSummary result = service.importVerified(List.of(valid(2, 47), valid(2, 47)));
  assertThat(result.imported()).hasSize(1);
  assertThat(result.rejected()).hasSize(1);
  assertThat(result.rejected().get(0).reason()).contains("Duplicate chapter+verse within this batch");
 }

 @Test void reRunningTheSameBatchIsIdempotent_skipsAlreadyImported() {
  when(verses.existsBySourceNameAndSourceVersionAndChapterAndVerse("Test Source", "1st ed.", 2, 47)).thenReturn(true);
  VerseImportSummary result = service.importVerified(List.of(valid(2, 47)));
  assertThat(result.imported()).isEmpty();
  assertThat(result.skippedExisting()).containsExactly("chapter 2 verse 47");
  assertThat(result.rejected()).isEmpty();
  verify(verses, never()).saveAndFlush(any());
 }

 @Test void mixedBatchReportsEachRecordIndependently() {
  VerseImportSummary result = service.importVerified(List.of(
   valid(2, 47),
   new VerseImportRecord(99, 1, "S", "T", "Src", "https://x", "Lic", "v1", "Rev", "")));
  assertThat(result.imported()).containsExactly("chapter 2 verse 47");
  assertThat(result.rejected()).hasSize(1);
  assertThat(result.rejected().get(0).index()).isEqualTo(1);
 }
}
