# Eval resources

Three probe sets this project uses to measure the suggestion engine against
unseen input.

## `en.txt` and `hi.txt`

Held-out English and Hinglish sentences built from the same corpora
`tools/BuildLexicon.kt` reads, with the tool keeping the last ones aside so
the eval and the dictionary never share material. `tools/Evaluate.kt`'s
default mode runs its `TypingSimulator` over these: each target word is
tapped with Gaussian spatial noise and the engine's autocorrect is checked
against it. Measures how the engine behaves on normal typing with normal
finger slips.

## `typos.tsv`

4,266 real-world misspelling → correct pairs scraped from
[Wikipedia: Lists of common misspellings/For machines][wiki]. Each line is
`typo<TAB>correct`; `tools/Evaluate.kt --typos …` runs each pair as a
zero-noise tap of the typo and checks whether the engine autocorrects to the
intended word. Measures the spelling-error side of autocorrect: the user
knew what they wanted to type and typed the wrong letters anyway. Licensed
CC BY-SA; see Wikipedia's attribution requirements if you reuse it.

[wiki]: https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines

## `aalto_typos.tsv`

20,000 real mobile-typing typo → intended pairs sampled from the Aalto
University [ITE Typing Dataset][aalto] (Palin et al., 2024, CC BY 4.0).
Derived from `ITE_words/ac_words_en.csv`'s `TYPED_WORD` / `ORIGINAL_WORD`
columns, kept only when the Levenshtein distance between the two is 1–3 and
no more than half the word length (filters out "typed one word, meant a
different word" rows). Measures the fat-finger side of autocorrect — real
touchscreen users with their actual error patterns. The error profile here
is dominated by QWERTY-neighbour substitutions (`w→e`, `i→o`, `e→r`) rather
than the vowel-swap and silent-letter patterns the Wikipedia list is heavy
on; a probe tuned on one won't fit the other.

[aalto]: https://zenodo.org/records/12528163
