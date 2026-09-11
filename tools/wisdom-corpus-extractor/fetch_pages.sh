#!/bin/bash
# Fetches every DJVU-backed Wikisource Page: namespace page for Annie Besant's 1922 Bhagavad-Gita
# (4th edition), one HTTP GET per page, with a descriptive User-Agent and a courteous delay,
# per Wikimedia's robot policy. Read-only, raw wikitext (action=raw) -- no AI involved in fetching.
set -e
cd "$(dirname "$0")"
mkdir -p pages
UA="EdunexifyWisdomCorpusResearch/1.0 (educational/non-commercial content verification; contact: research@edunexify.co.in)"
while read -r ch from to; do
  for p in $(seq "$from" "$to"); do
    out="pages/page_${p}.txt"
    if [ -s "$out" ]; then continue; fi
    curl -s -A "$UA" "https://en.wikisource.org/w/index.php?title=Page:Bhagavad_Gita_-_Annie_Besant_4th_edition.djvu/${p}&action=raw" -o "$out"
    sleep 1
  done
  echo "chapter $ch done ($from-$to)"
done < ranges.txt
echo "ALL DONE"
