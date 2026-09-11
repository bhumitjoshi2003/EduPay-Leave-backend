#!/usr/bin/env python3
"""
Deterministic (non-AI) parser for Wikisource's proofread transcription of Annie Besant's
1922 (4th edition) Bhagavad-Gita, backed by DJVU page scans.

For each fetched Page: namespace wikitext file, this:
  1. reads the <pagequality level="N"> proofreading status
  2. finds each {{lang block|sa| ... }} template (brace-depth matched, not naive regex) --
     one such block = one verse's Sanskrit text, per this edition's own layout
  3. captures the English translation text that follows, up to the next
     {{float right|{{larger|(N)}}}} verse-number marker (or the next Sanskrit block / end of
     page if a marker is missing -- that page is then flagged as an anomaly, never guessed)
  4. strips footnotes (<ref>...</ref>), layout templates ({{dhr}}, {{-}}, {{rule}}, {{smallrefs}}),
     and unwraps simple inline templates ({{c|text}} -> text) without altering the words
  5. records chapter, verse, sanskrit, translation, source page number, and pagequality

No LLM is used anywhere in this script. Ambiguous pages (missing verse markers, multi-verse
groupings like "(3-4)", zero Sanskrit blocks found, or unexpected structure) are written to
anomalies.json for human review rather than guessed at.
"""
import json
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PAGES_DIR = os.path.join(SCRIPT_DIR, "pages")
RANGES_FILE = os.path.join(SCRIPT_DIR, "ranges.txt")

SOURCE_NAME = "The Bhagavad-Gita, translated by Annie Besant"
SOURCE_VERSION = "4th edition (1922)"
SOURCE_URL_TEMPLATE = "https://en.wikisource.org/wiki/Page:Bhagavad_Gita_-_Annie_Besant_4th_edition.djvu/{page}"
LICENSE = "Public domain (original Sanskrit: {{PD-old}}; Besant translation: {{PD/US|1933}}, per Wikisource's translation-license tag on Bhagavad-Gita (Besant 4th))"
VERIFIED_BY = "Wikisource community proofreading (pagequality level 3 = proofread once against the page-image scan); Edunexify has not independently re-checked every verse against the scan images, only a spot-check sample -- see extraction report"


def load_ranges():
    ranges = []
    with open(RANGES_FILE) as f:
        for line in f:
            ch, a, b = line.split()
            ranges.append((int(ch), int(a), int(b)))
    return ranges


def find_matching_close(text, open_pos):
    """open_pos points at the first '{' of a '{{' opener. Returns index just past the matching
    '}}' for that same template, correctly handling nested {{ }} inside it."""
    depth = 0
    i = open_pos
    n = len(text)
    while i < n - 1:
        if text[i] == '{' and text[i + 1] == '{':
            depth += 1
            i += 2
            continue
        if text[i] == '}' and text[i + 1] == '}':
            depth -= 1
            i += 2
            if depth == 0:
                return i
            continue
        i += 1
    return -1  # unbalanced -- caller must treat as anomaly


def strip_wikitext(text):
    # Footnotes are apparatus, not verse text -- remove entirely. Self-closing named-reference
    # reuses (<ref name="x" />) MUST be stripped before the paired-tag pattern runs, or the
    # paired pattern's opening match "<ref[^>]*>" wrongly treats a self-closing tag as an opener
    # and greedily swallows everything up to some unrelated LATER </ref> elsewhere in the text.
    text = re.sub(r"<ref[^>]*/>", "", text)
    text = re.sub(r"<ref[^>]*>.*?</ref>", "", text, flags=re.DOTALL)
    # Page-boundary proofreading metadata (running headers, page-quality tags, and anything else
    # ProofreadPage wraps in <noinclude>) never belongs in verse text -- strip the whole block.
    text = re.sub(r"<noinclude>.*?</noinclude>", "", text, flags=re.DOTALL)
    text = re.sub(r"\{\{RunningHeader[^}]*\}\}", "", text)
    # Layout-only templates -- remove entirely, wherever they appear (including mid-verse, as
    # {{parabr}} is used in some chapters as an inline paragraph break within the Sanskrit
    # itself). Replaced with a newline so words on either side never get mechanically fused.
    for tmpl in ("{{dhr}}", "{{-}}", "{{rule}}", "{{smallrefs}}", "{{nop}}", "{{parabr}}"):
        text = text.replace(tmpl, "\n")
    text = re.sub(r"\{\{gap\|[^{}]*\}\}", "", text)
    text = re.sub(r"\{\{block center/s\|[^{}]*\}\}", "", text)
    text = re.sub(r"\{\{block center\|width=\d+px\|<poem>\s*", "", text)
    text = text.replace("<poem>", "").replace("</poem>", "")
    # Fixed-point unwrap: {{c|..}}/{{sc|..}}/{{small-caps|..}} -> inner text, {{SIC|x}} or
    # {{SIC|x|y}} -> the accepted/only text, [[target|display]] or [[target]] -> display text.
    # Looped until stable so arbitrarily nested combinations (e.g. {{c|{{SIC|{{gap|..}}|..}}}})
    # fully resolve, not just a fixed guessed depth.
    for _ in range(10):
        before = text
        text = re.sub(r"\{\{(?:c|sc|small-caps)\|([^{}]*)\}\}", r"\1", text)
        text = re.sub(r"\{\{SIC\|[^{}|]*\|([^{}]*)\}\}", r"\1", text)
        text = re.sub(r"\{\{SIC\|([^{}|]*)\}\}", r"\1", text)
        text = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", text)
        text = re.sub(r"\[\[([^\]|]*)\]\]", r"\1", text)
        text = re.sub(r"\{\{block center\|[^{}]*\}\}", "", text)
        if text == before:
            break
    # italics markers
    text = text.replace("''", "")
    # collapse whitespace
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{2,}", "\n", text)
    return text.strip()


VERSE_MARKER_RE = re.compile(r"\{\{float right\|\{\{larger\|(?:\{\{SIC\|[^|]*\|)?\(?([^)}]+)\)\}?\}?\}\}\}\}")
LANG_BLOCK_START_RE = re.compile(r"\{\{lang block\|sa\|")
QUALITY_RE = re.compile(r'<pagequality level="(\d+)"')
DEVANAGARI_DIGITS = "०१२३४५६७८९"
SANSKRIT_VERSE_NUMBER_RE = re.compile(r"॥\s*([" + DEVANAGARI_DIGITS + r"]+)\s*॥\s*$")


def devanagari_number_to_int(s):
    return int("".join(str(DEVANAGARI_DIGITS.index(c)) for c in s))


def parse_chapter_text(chapter, joined_text, page_at_offset):
    """joined_text is every page in this chapter's range concatenated in page order (with a
    marker comment between pages so page numbers can still be attributed by character offset).
    A verse's Sanskrit and its translation/verse-number marker are looked for across the WHOLE
    chapter, not one page at a time, since Wikisource's page-scan boundaries routinely fall
    mid-verse (a verse's Sanskrit at the bottom of one page, its translation at the top of the
    next) -- treating pages independently would wrongly flag every such verse as an anomaly."""
    records = []
    anomalies = []
    block_starts = [m.start() for m in LANG_BLOCK_START_RE.finditer(joined_text)]
    if not block_starts:
        return [], [{"chapter": chapter, "reason": "no Sanskrit blocks found anywhere in this chapter's fetched pages"}]

    consumed = set()  # indices into block_starts already merged into a preceding block
    for idx, start in enumerate(block_starts):
        if idx in consumed:
            continue
        page_num = page_at_offset(start)
        quality = None
        # nearest preceding pagequality tag establishes this block's page's proofreading status
        q_matches = list(QUALITY_RE.finditer(joined_text, 0, start))
        if q_matches:
            quality = int(q_matches[-1].group(1))

        close = find_matching_close(joined_text, start)
        if close == -1:
            anomalies.append({"chapter": chapter, "page": page_num, "reason": f"unbalanced template braces in Sanskrit block #{idx+1}"})
            continue
        sanskrit_raw = joined_text[start + len("{{lang block|sa|"):close - 2]

        # Wikisource's own page-continuation convention: a Sanskrit block that ends mid-verse
        # (scan broke the verse across two page images) is immediately followed by "/e"-suffixed
        # closing templates instead of the normal "}} }}" double/triple close. The true
        # continuation is the NEXT {{lang block|sa|...}} found (opened via matching "/s"
        # templates on the following page) -- merge it into this same verse's Sanskrit rather
        # than treating it as a separate verse candidate.
        while re.match(r"\s*<noinclude>\{\{(?:larger block|block center)/e\}\}", joined_text[close:close + 200]):
            next_idx = idx + 1
            if next_idx >= len(block_starts):
                break
            next_start = block_starts[next_idx]
            next_close = find_matching_close(joined_text, next_start)
            if next_close == -1:
                break
            continuation_raw = joined_text[next_start + len("{{lang block|sa|"):next_close - 2]
            sanskrit_raw += "\n" + continuation_raw
            consumed.add(next_idx)
            close = next_close
            idx = next_idx

        sanskrit = strip_wikitext(sanskrit_raw)

        search_from = close
        while True:
            m = re.match(r"\s*(\}\}|\{\{(?:larger block|block center)/e\}\})", joined_text[search_from:])
            if not m:
                break
            search_from += m.end()

        marker_match = VERSE_MARKER_RE.search(joined_text, search_from)
        next_block_start = block_starts[idx + 1] if idx + 1 < len(block_starts) else len(joined_text)
        if not marker_match or marker_match.start() > next_block_start:
            if sanskrit.startswith("इति श्रीमद्भगवद्गीता") or sanskrit.startswith("श्रीकृष्णार्पणमस्तु"):
                # The traditional closing colophon ("Thus, in the glorious Bhagavad-Gita...
                # ends the Nth discourse") -- not a numbered verse in this or any edition, so
                # the absence of a verse-number marker here is expected, not an error.
                continue
            anomalies.append({"chapter": chapter, "page": page_num, "reason": f"no verse-number marker found after Sanskrit block #{idx+1} before the next block/end of chapter's fetched pages", "sanskrit_excerpt": sanskrit[:80]})
            continue
        translation_raw = joined_text[search_from:marker_match.start()]
        translation = strip_wikitext(translation_raw)
        verse_marker = marker_match.group(1).strip()

        if not sanskrit or not translation:
            anomalies.append({"chapter": chapter, "page": page_num, "reason": f"empty sanskrit or translation after stripping for block #{idx+1}", "verse_marker": verse_marker})
            continue

        # Cross-check: the Devanagari verse number embedded at the end of the Sanskrit itself
        # ("... ॥ १९ ॥") versus the separate English {{float right}} marker. These are two
        # independent numberings printed in the same source; when they disagree, that is a
        # genuine source inconsistency (observed once, in Discourse 17) -- the Sanskrit-embedded
        # number is preferred as authoritative (it is intrinsic to the verse text itself, not a
        # separately-typeset annotation), and the disagreement is still recorded for visibility.
        sanskrit_number_match = SANSKRIT_VERSE_NUMBER_RE.search(sanskrit)
        effective_marker = verse_marker
        mismatch = None
        if sanskrit_number_match:
            try:
                sanskrit_number = devanagari_number_to_int(sanskrit_number_match.group(1))
                english_nums = re.findall(r"\d+", verse_marker)
                if english_nums and int(english_nums[0]) != sanskrit_number and "-" not in verse_marker and len(english_nums) == 1:
                    mismatch = {"chapter": chapter, "page": page_num, "reason": f"Sanskrit-embedded verse number ({sanskrit_number}) disagrees with the English marker ({verse_marker}) -- using the Sanskrit-embedded number as authoritative", "sanskrit_number": sanskrit_number, "english_marker": verse_marker}
                    effective_marker = str(sanskrit_number)
            except ValueError:
                pass

        if mismatch:
            anomalies.append(mismatch)

        records.append({"verse_marker": effective_marker, "sanskrit": sanskrit, "translation": translation, "page": page_num, "quality": quality})

    return records, anomalies


def main():
    ranges = load_ranges()
    all_records = []
    all_anomalies = []
    for chapter, start, end in ranges:
        joined_parts = []
        boundaries = []  # (offset_where_this_page_starts, page_num)
        offset = 0
        for page in range(start, end + 1):
            path = os.path.join(PAGES_DIR, f"page_{page}.txt")
            if not os.path.exists(path):
                all_anomalies.append({"chapter": chapter, "page": page, "reason": "page file not fetched"})
                continue
            raw = open(path, encoding="utf-8").read()
            if "<title>Wikimedia Error</title>" in raw or "DOCTYPE" in raw:
                all_anomalies.append({"chapter": chapter, "page": page, "reason": "fetch returned an error page, not wikitext"})
                continue
            boundaries.append((offset, page))
            joined_parts.append(raw)
            offset += len(raw) + 1
        joined_text = "\n".join(joined_parts)

        def page_at_offset(pos, boundaries=boundaries):
            result = boundaries[0][1] if boundaries else None
            for off, pg in boundaries:
                if off <= pos:
                    result = pg
                else:
                    break
            return result

        records, anomalies = parse_chapter_text(chapter, joined_text, page_at_offset)
        for r in records:
            r["chapter"] = chapter
        all_records.extend(records)
        all_anomalies.extend(anomalies)

    # Now assign verse numbers per chapter, in page order, splitting any "(a-b)" or "(a, b)"
    # multi-verse markers only where the marker text itself says so -- never guessed.
    final_records = []
    per_chapter_counts = {}
    for chapter, start, end in ranges:
        chapter_recs = [r for r in all_records if r["chapter"] == chapter]
        seen_numbers = set()
        for r in chapter_recs:
            marker = r["verse_marker"]
            nums = re.findall(r"\d+", marker)
            if len(nums) == 1:
                verse_list = [int(nums[0])]
            elif "-" in marker and len(nums) == 2:
                verse_list = list(range(int(nums[0]), int(nums[1]) + 1))
            elif len(nums) > 1:
                verse_list = [int(n) for n in nums]
            else:
                all_anomalies.append({"page": r["page"], "chapter": chapter, "reason": f"unparseable verse marker '{marker}'"})
                continue
            if len(verse_list) > 1:
                # Source groups multiple verse numbers under one Sanskrit/translation block --
                # do not split translation text arbitrarily; record as a grouped anomaly for
                # human review rather than guessing a split point.
                all_anomalies.append({"page": r["page"], "chapter": chapter, "reason": f"marker '{marker}' groups multiple verse numbers under one block -- translation not split", "verses": verse_list})
                continue
            v = verse_list[0]
            if v in seen_numbers:
                all_anomalies.append({"page": r["page"], "chapter": chapter, "reason": f"duplicate verse number {v} within chapter {chapter}"})
                continue
            seen_numbers.add(v)
            final_records.append({
                "chapter": chapter, "verse": v,
                "sanskrit": r["sanskrit"], "translation": r["translation"],
                "sourceName": SOURCE_NAME, "sourceUrl": SOURCE_URL_TEMPLATE.format(page=r["page"]),
                "license": LICENSE, "sourceVersion": SOURCE_VERSION, "verifiedBy": VERIFIED_BY,
                "themes": "",
                "_pageQuality": r["quality"],
            })
        per_chapter_counts[chapter] = len(seen_numbers)

    # Final safety-net validation: never ship a record with residual wikitext/HTML markup, even
    # if every upstream cleanup rule was expected to have already handled it. Anything that still
    # looks contaminated is excluded from the verified artifact and reported instead of guessed at.
    LEAK_RE = re.compile(r"\{\{|\}\}|<ref|<noinclude|</noinclude|<pagequality|<poem|</poem|\[\[")
    clean_records = []
    for r in final_records:
        leaked_fields = [f for f in ("sanskrit", "translation") if LEAK_RE.search(r[f])]
        if leaked_fields:
            all_anomalies.append({"chapter": r["chapter"], "verse": r["verse"], "reason": f"residual markup detected in {leaked_fields} after cleanup -- excluded from verified artifact, needs manual review", "excerpt": {f: r[f][:150] for f in leaked_fields}})
            per_chapter_counts[r["chapter"]] -= 1
            continue
        clean_records.append(r)
    final_records = clean_records

    with open(os.path.join(SCRIPT_DIR, "gita_verses.json"), "w", encoding="utf-8") as f:
        json.dump(final_records, f, ensure_ascii=False, indent=1)
    with open(os.path.join(SCRIPT_DIR, "anomalies.json"), "w", encoding="utf-8") as f:
        json.dump(all_anomalies, f, ensure_ascii=False, indent=1)

    print("Chapter-by-chapter verse counts:")
    total = 0
    for chapter, _, _ in ranges:
        c = per_chapter_counts.get(chapter, 0)
        total += c
        print(f"Chapter {chapter}: {c}")
    print(f"TOTAL: {total}")
    print(f"Anomalies: {len(all_anomalies)}")


if __name__ == "__main__":
    main()
