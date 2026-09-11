package com.indraacademy.ias_management.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic, mechanical Devanagari -> IAST (International Alphabet of Sanskrit
 * Transliteration) converter. This is a fixed character/cluster mapping table, not a language
 * model — the same input always produces the same output, and no external call or LLM is
 * involved. It exists specifically so verse transliteration is never "AI-generated": every verse
 * imported via {@link com.indraacademy.ias_management.service.WisdomVerseImportService} has its
 * transliteration derived here, from the verified Sanskrit text itself, rather than trusted from
 * an input field.
 *
 * Covers the Devanagari range used by classical Sanskrit: independent vowels, dependent vowel
 * signs (matras), consonants (with the implicit "a" removed by virama handling), anusvara,
 * visarga, candrabindu, avagraha, digits, and the daṇḍa/double daṇḍa verse punctuation. Consonant
 * clusters (conjuncts) are handled naturally by virama suppression rather than an exhaustive
 * conjunct table — no special-casing is needed because IAST is built the same way.
 */
public final class DevanagariTransliterator {
 private DevanagariTransliterator() {}

 private static final Map<Character, String> INDEPENDENT_VOWELS = new LinkedHashMap<>();
 private static final Map<Character, String> VOWEL_SIGNS = new LinkedHashMap<>();
 private static final Map<Character, String> CONSONANTS = new LinkedHashMap<>();
 private static final Map<Character, String> MARKS = new LinkedHashMap<>();
 private static final Map<Character, Character> DIGITS = new LinkedHashMap<>();

 static {
  INDEPENDENT_VOWELS.put('अ',"a");INDEPENDENT_VOWELS.put('आ',"ā");INDEPENDENT_VOWELS.put('इ',"i");
  INDEPENDENT_VOWELS.put('ई',"ī");INDEPENDENT_VOWELS.put('उ',"u");INDEPENDENT_VOWELS.put('ऊ',"ū");
  INDEPENDENT_VOWELS.put('ऋ',"ṛ");INDEPENDENT_VOWELS.put('ॠ',"ṝ");INDEPENDENT_VOWELS.put('ऌ',"ḷ");
  INDEPENDENT_VOWELS.put('ॡ',"ḹ");INDEPENDENT_VOWELS.put('ए',"e");INDEPENDENT_VOWELS.put('ऐ',"ai");
  INDEPENDENT_VOWELS.put('ओ',"o");INDEPENDENT_VOWELS.put('औ',"au");

  VOWEL_SIGNS.put('ा',"ā");VOWEL_SIGNS.put('ि',"i");VOWEL_SIGNS.put('ी',"ī");VOWEL_SIGNS.put('ु',"u");
  VOWEL_SIGNS.put('ू',"ū");VOWEL_SIGNS.put('ृ',"ṛ");VOWEL_SIGNS.put('ॄ',"ṝ");VOWEL_SIGNS.put('ॢ',"ḷ");
  VOWEL_SIGNS.put('ॣ',"ḹ");VOWEL_SIGNS.put('े',"e");VOWEL_SIGNS.put('ै',"ai");VOWEL_SIGNS.put('ो',"o");
  VOWEL_SIGNS.put('ौ',"au");

  CONSONANTS.put('क',"k");CONSONANTS.put('ख',"kh");CONSONANTS.put('ग',"g");CONSONANTS.put('घ',"gh");
  CONSONANTS.put('ङ',"ṅ");CONSONANTS.put('च',"c");CONSONANTS.put('छ',"ch");CONSONANTS.put('ज',"j");
  CONSONANTS.put('झ',"jh");CONSONANTS.put('ञ',"ñ");CONSONANTS.put('ट',"ṭ");CONSONANTS.put('ठ',"ṭh");
  CONSONANTS.put('ड',"ḍ");CONSONANTS.put('ढ',"ḍh");CONSONANTS.put('ण',"ṇ");CONSONANTS.put('त',"t");
  CONSONANTS.put('थ',"th");CONSONANTS.put('द',"d");CONSONANTS.put('ध',"dh");CONSONANTS.put('न',"n");
  CONSONANTS.put('प',"p");CONSONANTS.put('फ',"ph");CONSONANTS.put('ब',"b");CONSONANTS.put('भ',"bh");
  CONSONANTS.put('म',"m");CONSONANTS.put('य',"y");CONSONANTS.put('र',"r");CONSONANTS.put('ल',"l");
  CONSONANTS.put('व',"v");CONSONANTS.put('श',"ś");CONSONANTS.put('ष',"ṣ");CONSONANTS.put('स',"s");
  CONSONANTS.put('ह',"h");CONSONANTS.put('ळ',"ḻ");

  MARKS.put('ं',"ṃ");MARKS.put('ः',"ḥ");MARKS.put('ँ',"m̐");MARKS.put('ऽ',"'");
  MARKS.put('।',".");MARKS.put('॥',".");

  String digitsStr="०१२३४५६७८९";
  for (int i=0;i<digitsStr.length();i++) DIGITS.put(digitsStr.charAt(i), (char)('0'+i));
 }

 /** Converts Devanagari text to IAST. Non-Devanagari characters (Latin letters, whitespace,
  *  existing ASCII digits, punctuation) pass through unchanged. */
 public static String toIast(String devanagari) {
  if (devanagari == null || devanagari.isBlank()) return devanagari;
  StringBuilder out = new StringBuilder();
  int i = 0;
  int len = devanagari.length();
  while (i < len) {
   char c = devanagari.charAt(i);
   if (CONSONANTS.containsKey(c)) {
    out.append(CONSONANTS.get(c));
    boolean wroteVowel = false;
    if (i + 1 < len) {
     char next = devanagari.charAt(i + 1);
     if (next == '्') { // virama: suppress inherent "a", consonant cluster continues
      i += 2;
      wroteVowel = true;
     } else if (VOWEL_SIGNS.containsKey(next)) {
      out.append(VOWEL_SIGNS.get(next));
      i += 2;
      wroteVowel = true;
     }
    }
    if (!wroteVowel) {
     out.append('a'); // implicit inherent vowel
     i += 1;
    }
   } else if (INDEPENDENT_VOWELS.containsKey(c)) {
    out.append(INDEPENDENT_VOWELS.get(c));
    i += 1;
   } else if (MARKS.containsKey(c)) {
    out.append(MARKS.get(c));
    i += 1;
   } else if (DIGITS.containsKey(c)) {
    out.append(DIGITS.get(c));
    i += 1;
   } else {
    out.append(c); // whitespace, Latin text, existing punctuation
    i += 1;
   }
  }
  return out.toString();
 }
}
