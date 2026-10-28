# Moon Keyboard: feature set and architecture

Status: agreed, decisions at the bottom. Licence: MIT. Visual design: [Moon Keyboard canvas](https://claude.ai/artifact/KM69vtn9edVg69yDa8e62N).

## Principles

1. **Fully offline.** The app ships without the `INTERNET` permission. Language data is bundled; nothing typed ever leaves the device.
2. **Language-agnostic core.** Everything specific to a language (layouts, dictionary, language model, word rules) lives in a *language pack*. Adding Hinglish or another language means adding data, not changing the engine.
3. **Layouts are data.** Key rows, popups and long-press accents are JSON files, not code. The same geometry feeds the renderer, touch hit-testing and the glide decoder.
4. **The user owns their data.** Clipboard text and learned words stay until the user deletes them. Anything automatic (image expiry) is a setting the user picks.
5. **Fast first.** Suggestions must update within one frame of a tap (under 16 ms) and a glide word must appear within 50 ms of lifting the finger.

## Feature set

### v1: English

| Area | Features |
| --- | --- |
| Typing | QWERTY, shift/caps lock, symbols and extra-symbols layers, long-press popups and accents, optional number row, number hints on the top row |
| Suggestions | 3-word strip, completion of the current word, next-word prediction, autocorrect (off/mild/normal), auto-capitalisation, double-space period, undo autocorrect with backspace |
| Glide typing | Word from a swipe path, live preview in the strip while swiping, alternatives in the strip, glide trail (can be turned off) |
| Learning | Learns new words and the user's common phrases; long-press a suggestion to forget it; nothing is learned in password or incognito fields |
| Clipboard | History of text, images and GIFs; pin items; text kept until the user deletes it; unpinned images and GIFs auto-cleared after 1 week (default), 1 month or never, as the user chooses; pasting images/GIFs into apps that accept them |
| Toolbar | Clipboard, emoji, text editing (cursor/selection keys), one-handed mode, theme, settings |
| Emoji | Category grid, recents, skin tones (search comes later) |
| Look | Light, dark, follow system; borders on/off (space bar always filled); key height; haptics and sound |
| Settings app | Onboarding (enable and select the keyboard), all preferences, clipboard storage used, delete learned data |

### Later

- **Hinglish** (milestone 5, built): Hindi typed in Latin letters (`kya kar rahe ho`), mixed freely with English. Output stays in Latin script, with no Devanagari. Common spelling variants (`kya`/`kyaa`, `nahi`/`nhi`) are all words, and learning brings the user's own spelling forward. Suggestions come from English and Hinglish together, glide typing works as-is on the same QWERTY layout, and a setting turns Hinglish off.
- More languages through new packs. Non-Latin scripts such as Devanagari aren't planned, but the engine doesn't rule them out (see the note on Unicode below).
- Switching languages by swiping the space bar, multilingual typing without switching.
- Emoji search, floating and split keyboard, personal dictionary import/export, backup.
- Offline voice typing (needs a speech model, a large download).

## Architecture

### Modules

```
:app      Android: IME service, keyboard UI, panels, settings, clipboard storage
:engine   Pure Kotlin (no Android): layouts, language packs, tap and glide decoding,
          language model, suggestion ranking
:tools    JVM command line: compiles word lists and n-gram counts into language-pack binaries
```

`:engine` has no Android dependency, so all decoding and ranking runs in plain JVM unit tests and benchmarks. If profiling later shows a hot spot, only that part moves to native code, behind the same interface.

### Main pieces

```
 InputMethodService (MoonImeService)
   │  owns lifecycle, EditorInfo, InputConnection
   ├─ KeyboardView (Compose)
   │    ├─ SuggestionStrip / Toolbar              Compose
   │    ├─ KeyArea: one Canvas draw node          keys, popups, glide trail
   │    └─ Panels: Clipboard, Emoji, TextEdit     Compose
   ├─ InputController
   │    composing text, shift state, autocorrect commit/undo, cursor tracking
   ├─ SuggestionService ──► :engine Decoder      background thread, latest request wins
   ├─ ClipboardStore (index file + image files)
   └─ UserHistory (one file)
```

- **Rendering.** The suggestion strip, toolbar and panels are Compose, the same as FlorisBoard. The key area is a single Compose `Canvas`, so a full redraw (glide trail, key press) costs one draw pass instead of 40 recompositions.
- **Threading.** Touch handling and `InputConnection` calls stay on the main thread. Decoding runs on one background dispatcher; each new keystroke cancels the stale request.

### Language packs (`:engine`)

```kotlin
interface LanguagePack {
    val id: String                       // "en", "hi-Latn" (Hinglish)
    val layouts: List<LayoutId>          // "qwerty"
    val lexicon: Lexicon                 // words + unigram frequencies, trie
    val model: LanguageModel             // P(word | previous words)
    val wordRules: WordRules             // what counts as a word char, apostrophes
}
```

- **Lexicon and model format.** `:tools` compiles them into one binary per pack: a trie stored as flat arrays plus a pruned word-pair model (the 40 most common successors of each word). It is read into a handful of primitive arrays with bulk copies, with no per-word objects. A trigram model and memory-mapping can come later if size or load time demand it.
- **The model is an interface.** We start with n-grams because they are small, fast and predictable. A small neural model (TFLite/ONNX) can replace it later without touching the decoders.
- **English and Hinglish in one pack.** Both languages share the Latin alphabet, so they live in one lexicon: Hinglish-only words carry a `HINGLISH` flag, and the word-pair model is counted over English sentences and Hinglish tweets together. The model itself then says which language the sentence is in (after "kya" it expects "kar", after "what" it expects "are"), with no separate language detection. Turning Hinglish off hides the flagged words; English words that are also Hindi ("main", "to") stay.
- **Unicode correctness from day one.** Backspace, cursor moves and word boundaries use grapheme clusters (`BreakIterator`), not chars. Emoji already need this, and it keeps other scripts possible later.
- **Hinglish spelling variants.** Each common spelling is its own word with its own frequency (`nahi` and `nhi` both). Typing one suggests the others as alternatives, and the user's history brings their own spelling forward. Grouping variants under one entry can come later if the strip gets crowded with them.

### Decoding

Both input types feed the same ranking:

`score(word) = log P(touches | word) + λ · log P(word | context) + user-history boost`

- **Tap typing.** Each touch is a probability spread over nearby keys, using the real key centres from the layout. A beam search walks the trie and allows a few insertions, deletions and swaps. That gives completions and autocorrect from one search.
- **Glide typing.** A trie walk keeps only words whose letters' keys the path passes near, in order, starting and ending at the right keys. Each survivor's ideal path through its key centres is resampled alongside the finger's path, and the mean distance between them is the score (SHARK2's location channel; its shape channel can be added if real traces show a need). AOSP's open-source LatinIME has no glide decoder (Gboard's is closed), so this is our own code. FlorisBoard's Apache-2.0 glide classifier is a reference.
- **Autocorrect.** It replaces the typed word only when the best candidate beats the literal input by a clear margin. It never corrects words the user has typed before. Backspace right after a correction undoes it.

### Storage

All local, all deletable from settings.

| Data | Where | Kept |
| --- | --- | --- |
| Settings | SharedPreferences | — |
| Learned words and phrases | `filesDir/history.tsv`: word counts, word-pair counts, forgotten words (a word list doesn't need a database) | Until the user deletes them |
| Clipboard text | `filesDir/clips/index.json` (a few hundred rows don't need a database) | Forever, until the user deletes it |
| Clipboard images/GIFs | Files in `filesDir/clips/`, a row in `index.json` | Auto-deleted after the chosen period unless pinned |

Each row: `id, kind (text|image|gif), content (the text, or the image's file name), mime, hash, createdAt, pinned`.

- **Capture.** The service listens with `ClipboardManager.addPrimaryClipChangedListener`. Since Android 10 only the default keyboard (or the app in front) may read the clipboard, and the keyboard is the default.
  - Copied images arrive as `content://` URIs with short-lived permission, so their bytes are copied into `filesDir/clips/` right away.
  - A hash of the content stops duplicates.
  - Clips marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, Android 13+) aren't saved.
- **Pasting images and GIFs.** The keyboard sends them with `InputConnectionCompat.commitContent` through a `FileProvider` URI, but only when the app's `EditorInfo` lists a matching MIME type. Otherwise those tiles are greyed out.
- **Expiry.** Unpinned images older than the user's setting (1 week by default, or 1 month, or never) are deleted when the keyboard starts and at most once a day after that. No WorkManager is needed.

### Language data sources (licences to confirm before use)

The code is MIT. Bundled data files keep their own licences, listed in a `NOTICE` file.

- English word frequencies: the AOSP LatinIME word lists (Apache 2.0, which can ship inside an MIT app with attribution), plus a corpus for n-grams (Wikipedia, CC BY-SA). If CC BY-SA applies to the compiled model, that file is licensed CC BY-SA on its own and the code stays MIT.
- Hinglish: L3Cube-HingCorpus, 52M romanised Hindi-English tweets (CC BY-NC-SA 4.0). A word is Hinglish when it isn't English, turns up mostly in tweets with two or more unmistakably Hindi words ("hai", "nahi", "kya"…) and mostly in lower case. Because of this data the pack file is non-commercial; the code stays MIT.

### Testing

- `:engine` unit tests on the JVM.
- A replay benchmark: real sentences turned into noisy taps and synthetic glide paths, measuring how often the right word is the top suggestion or in the top 3, and how fast. Every change to the decoder is checked against it.

## Milestones

1. **IME skeleton:** service, layout JSON, key rendering, typing, shift and symbols, settings onboarding.
2. **Suggestions:** English pack via `:tools`, tap decoder, autocorrect, next-word prediction, learning.
3. **Glide typing:** decoder, trail, live preview, benchmark.
4. **Clipboard, toolbar, emoji, themes:** borderless, number row, one-handed mode.
5. **Hinglish.**

## Decisions

- Clipboard text is kept until the user deletes it. Unpinned images and GIFs expire after 1 week by default; the user can pick 1 month or never.
- Hinglish is Latin script only, with no Devanagari output.
- Hinglish data is HingCorpus (best quality), which makes the bundled dictionary non-commercial (CC BY-NC-SA 4.0). That's fine: nothing will be released paid or with ads.
- The licence is MIT.
- Distribution is to be decided once there is a working build.
