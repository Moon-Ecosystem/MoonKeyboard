#!/usr/bin/env bash
# Downloads the source data for the English language pack into tools/data/ (git-ignored), then:
#   ./gradlew :tools:run --args="--words $PWD/tools/data/en_US_wordlist.combined.gz \
#       --sentences $PWD/tools/data/eng_sentences.tsv --hinglish $PWD/tools/data/hingcorpus.15m.txt.gz \
#       --out $PWD/app/src/main/assets/dict/en.dict --eval-out $PWD/engine/src/test/resources/eval/en.txt \
#       --hinglish-eval-out $PWD/engine/src/test/resources/eval/hi.txt"
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p data && cd data

# AOSP LatinIME word list, Apache 2.0 (googlesource serves files base64-encoded)
curl -sSf "https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/en_US_wordlist.combined.gz?format=TEXT" \
  | base64 -d > en_US_wordlist.combined.gz

# Tatoeba English sentences, CC BY 2.0 FR
curl -sSf -o eng_sentences.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
bunzip2 -f eng_sentences.tsv.bz2

# Unicode emoji list for the emoji panel, Unicode License v3 (see BuildEmoji.kt for the command)
curl -sSf -o emoji-test.txt https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt
# CLDR emoji keywords for emoji search, Unicode License v3
curl -sSf -o cldr-annotations-en.xml https://raw.githubusercontent.com/unicode-org/cldr/release-49/common/annotations/en.xml

# L3Cube-HingCorpus, romanised Hinglish tweets, CC BY-NC-SA 4.0. The archive is 2.5 GB (4.9 GB
# unpacked) and already shuffled, so only its first 15M lines are kept, streamed straight to gzip.
# (curl and tar are cut off by head on purpose, hence the || true)
{ curl -sSfL "https://drive.usercontent.google.com/download?id=1s_6eHO9zDhxQ-xVN1TyNguszV1meZkl9&export=download&confirm=t" || true; } \
  | { tar xzO 2>/dev/null || true; } | head -n 15000000 | gzip -6 > hingcorpus.15m.txt.gz
