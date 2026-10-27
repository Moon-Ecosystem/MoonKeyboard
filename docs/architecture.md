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

- **Hinglish**: Hindi typed in Latin letters (`kya kar rahe ho`), mixed freely with English. Output stays in Latin script, with no Devanagari. It has its own dictionary that knows spelling variants (`kya`/`kyaa`, `hai`/`he`). Suggestions come from English and Hinglish together, and glide typing works as-is on the same QWERTY layout.
- More languages through new packs. Non-Latin scripts such as Devanagari aren't planned, but the engine doesn't rule them out (see the note on Unicode below).
- Switching languages by swiping the space bar, multilingual typing without switching.
- Emoji search, floating and split keyboard, personal dictionary import/export, backup.
- Offline voice typing (needs a speech model, a large download).

## Architecture

### Modules

```
:app      Android: IME service, keyboard UI, panels, settings, Room storage
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
   ├─ ClipboardRepository (Room + files)
   └─ UserHistoryRepository (Room)
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

- **Lexicon and model format.** `:tools` compiles them into one binary per pack: a compact trie plus a pruned trigram model (Kneser-Ney smoothed). It ships in `assets/` uncompressed and is memory-mapped, so loading is instant and it doesn't use heap.
- **The model is an interface.** We start with n-grams because they are small, fast and predictable. A small neural model (TFLite/ONNX) can replace it later without touching the decoders.
- **Several packs at once.** With English and Hinglish both on, the decoder searches both lexicons and weights each by how likely each language is in the current sentence. This is what makes code-mixed typing work.
- **Unicode correctness from day one.** Backspace, cursor moves and word boundaries use grapheme clusters (`BreakIterator`), not chars. Emoji already need this, and it keeps other scripts possible later.
- **Hinglish spelling variants.** The Hinglish lexicon groups variants (`kya`, `kyaa`, `kia`) under one entry. Typing any of them suggests the user's own preferred spelling, which is learned from what they pick.

### Decoding

Both input types feed the same ranking:

`score(word) = log P(touches | word) + λ · log P(word | context) + user-history boost`

- **Tap typing.** Each touch is a probability spread over nearby keys, using the real key centres from the layout. A beam search walks the trie and allows a few insertions, deletions and swaps. That gives completions and autocorrect from one search.
- **Glide typing.** The path is resampled and compared with each candidate word's ideal path through the key centres, by shape and by position (the SHARK2 approach). Candidates are pre-filtered by the first and last key and the keys the path passes over, so only a few hundred words get scored. AOSP's open-source LatinIME has no glide decoder (Gboard's is closed), so this is our own code. FlorisBoard's Apache-2.0 glide classifier is a reference.
- **Autocorrect.** It replaces the typed word only when the best candidate beats the literal input by a clear margin. It never corrects words the user has typed before. Backspace right after a correction undoes it.

### Storage

All local, all deletable from settings.

| Data | Where | Kept |
| --- | --- | --- |
| Settings | DataStore | — |
| Learned words and phrases | Room: `word(lang, text, count, lastUsed)`, `bigram(...)` | Until the user deletes them; rarely used entries decay in ranking but aren't deleted |
| Clipboard text | Room `clip` table | Forever, until the user deletes it |
| Clipboard images/GIFs | Files in `filesDir/clips/`, row in `clip` table | Auto-deleted after the chosen period unless pinned |

`clip(id, kind[text|image|gif], text, file, mime, width, height, hash, createdAt, lastUsedAt, pinned)`

- **Capture.** The service listens with `ClipboardManager.addPrimaryClipChangedListener`. Since Android 10 only the default keyboard (or the app in front) may read the clipboard, and the keyboard is the default.
  - Copied images arrive as `content://` URIs with short-lived permission, so their bytes are copied into `filesDir/clips/` right away.
  - A hash of the content stops duplicates.
  - Clips marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, Android 13+) aren't saved.
- **Pasting images and GIFs.** The keyboard sends them with `InputConnectionCompat.commitContent` through a `FileProvider` URI, but only when the app's `EditorInfo` lists a matching MIME type. Otherwise those tiles are greyed out.
- **Expiry.** Unpinned images older than the user's setting (1 week by default, or 1 month, or never) are deleted when the keyboard starts and at most once a day after that. No WorkManager is needed.

### Language data sources (licences to confirm before use)

The code is MIT. Bundled data files keep their own licences, listed in a `NOTICE` file.

- English word frequencies: the AOSP LatinIME word lists (Apache 2.0, which can ship inside an MIT app with attribution), plus a corpus for n-grams (Wikipedia, CC BY-SA). If CC BY-SA applies to the compiled model, that file is licensed CC BY-SA on its own and the code stays MIT.
- Hinglish: the romanised half of Google's Dakshina dataset, plus romanised Hindi chat text.

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
- The licence is MIT.
- Distribution is to be decided once there is a working build.
