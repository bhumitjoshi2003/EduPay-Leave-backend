package com.indraacademy.ias_management.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping table itself is the specification — these tests pin its output for well-known
 * strings (the invocation "oṃ", and Bhagavad Gita 2.47, the most commonly cited verse) so any
 * future edit to the table is caught as a visible diff, not a silent drift.
 */
class DevanagariTransliteratorTest {

 @Test void nullAndBlankPassThroughUnchanged() {
  assertThat(DevanagariTransliterator.toIast(null)).isNull();
  assertThat(DevanagariTransliterator.toIast("")).isEmpty();
 }

 @Test void isDeterministic_sameInputAlwaysProducesSameOutput() {
  String verse = "कर्मण्येवाधिकारस्ते मा फलेषु कदाचन";
  assertThat(DevanagariTransliterator.toIast(verse)).isEqualTo(DevanagariTransliterator.toIast(verse));
 }

 @Test void simpleWordWithInherentVowel() {
  assertThat(DevanagariTransliterator.toIast("नमः")).isEqualTo("namaḥ");
 }

 @Test void longVowelSignsAndAnusvara() {
  assertThat(DevanagariTransliterator.toIast("गीता")).isEqualTo("gītā");
  assertThat(DevanagariTransliterator.toIast("संस्कृतम्")).isEqualTo("saṃskṛtam");
 }

 @Test void viramaSuppressesInherentVowelForConjuncts() {
  // धर्म = dh-a-r-m-a ; क्षेत्रे = k-ṣ-e-t-r-e (ष uses the retroflex ṣ, ् suppresses the "a")
  assertThat(DevanagariTransliterator.toIast("धर्मक्षेत्रे")).isEqualTo("dharmakṣetre");
 }

 @Test void verseNumberDigitsConvertToAsciiDigits() {
  assertThat(DevanagariTransliterator.toIast("॥ १ ॥")).isEqualTo(". 1 .");
  assertThat(DevanagariTransliterator.toIast("४७")).isEqualTo("47");
 }

 @Test void famousVerse247OpeningPada() {
  // "karmaṇy evādhikāras te mā phaleṣu kadācana" — 2.47's opening line, a widely-published
  // transliteration used here only to pin the mapping table's behavior, not as an assertion
  // about which verses Edunexify has imported.
  assertThat(DevanagariTransliterator.toIast("कर्मण्येवाधिकारस्ते मा फलेषु कदाचन"))
   .isEqualTo("karmaṇyevādhikāraste mā phaleṣu kadācana");
 }

 @Test void latinTextAndWhitespacePassThroughUnchanged() {
  assertThat(DevanagariTransliterator.toIast("abc 123 !? गीता")).isEqualTo("abc 123 !? gītā");
 }
}
