#!/usr/bin/env bash
# Downloads the source data for the English language pack into tools/data/ (git-ignored), then:
#   ./gradlew :tools:run --args="--words $PWD/tools/data/en_US_wordlist.combined.gz \
#       --sentences $PWD/tools/data/eng_sentences.tsv --out $PWD/app/src/main/assets/dict/en.dict"
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p data && cd data

# AOSP LatinIME word list, Apache 2.0 (googlesource serves files base64-encoded)
curl -sSf "https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/en_US_wordlist.combined.gz?format=TEXT" \
  | base64 -d > en_US_wordlist.combined.gz

# Tatoeba English sentences, CC BY 2.0 FR
curl -sSf -o eng_sentences.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
bunzip2 -f eng_sentences.tsv.bz2
