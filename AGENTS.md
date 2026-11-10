# AGENTS.md

Guidance for AI coding agents working in this repo.

## Project

Moon Keyboard is a fully offline Android keyboard (input method) with glide typing, autocorrect and next-word prediction in English and Hinglish. The app has no internet permission: learned words, the clipboard and settings never leave the phone.

- Kotlin, Jetpack Compose for the keyboard UI and the settings app, kotlinx-serialization, androidx autofill (inline suggestions).
- AGP 9.5, Gradle 9.9.0 (wrapper included), Kotlin 2.4, minSdk 24, targetSdk 36, compileSdk 37. Java 11 bytecode.
- Package and applicationId `com.moonkeyboard`.
- Don't rename the applicationId, the `settings` SharedPreferences, or the files under `filesDir` (`history.tsv` for learned words, `clips/` with `index.json` for the clipboard): renaming wipes user data or splits installs.

## Layout

```
app/                         the Android input method and the settings app
  src/main/java/com/moonkeyboard/
    MainActivity.kt, AppScreens.kt, AppUi.kt   settings app (setup, Look, Typing, Emoji, Clipboard, Languages, Learned words)
    TestPad.kt                                 try the keyboard in-app, with touch/decoder debug info
    ime/
      MoonImeService.kt     InputMethodService: window, insets, clipboard capture, inline autofill request
      InputController.kt    turns key presses into edits; composing word, suggestions, undo, enter action,
                            autofill chips; its state drives the UI
      KeyboardUi.kt         suggestion strip, toolbar, autofill chip row, the keys canvas, one-handed and split
      TouchTracker.kt       taps, long-press popups, backspace repeat, glide paths, space-bar cursor slide
      Panels.kt             clipboard, emoji, text-editing and options panels
      KeyboardWindow.kt     docked or floating (movable, resizable) window
      Prefs.kt              every setting as a Pref; Language.kt loads the dictionary, learned history and the neural model
      ClipboardStore.kt, Emoji.kt, Haptics.kt, Icons.kt, KeyboardColors.kt, GestureRecorder.kt
  src/main/assets/          dict/en.dict (built language pack), dict/en-lm.onnx + en-lm.vocab (neural model), emoji.txt; all committed
engine/                      pure Kotlin, no Android: unit-tested on the JVM
  src/main/kotlin/com/moonkeyboard/engine/
    decode/                 TapDecoder (spatial + edit distance), GlideDecoder, KeyMap/Spatial
    dict/                   Lexicon (trie + word pairs/triples), LexiconBuilder
    suggest/                SuggestionEngine (suggest, glide, predict), WordContext, NeuralLm (runtime-free wrapper)
    history/                UserHistory (learned words and phrases)
    layout/                 Layout/Layouts, KeyboardGeometry (hit-testing, split landscape)
    input/, text/, trace/, eval/ (TypingSimulator for the accuracy tests)
  src/main/resources/layouts/   qwerty, symbols, symbols2, number, phone, datetime (JSON)
  src/test/                 unit tests; resources/eval/ holds held-out sentences for accuracy tests
tools/                       build-time only: BuildLexicon, BuildEmoji, Evaluate; fetch-data.sh
  lm/                       neural model pipeline (Python): train, quantize, blend test, app export
docs/architecture.md         feature set, architecture and decisions
.github/release-notes/       hand-written notes per version tag (vX.Y.md)
.github/assets/screenshots/  README screenshots
```

## Build, test and install

Use the Gradle wrapper with JDK 17 or newer and an Android SDK that has platform 37.

```sh
./gradlew :engine:test           # typing engine unit tests (includes accuracy floors)
./gradlew :app:assembleRelease   # signed release APK
./gradlew :app:assembleDebug     # debug APK
./gradlew spotlessCheck          # ktlint over every .kt and .gradle.kts file; spotlessApply fixes it
./gradlew lint                   # Android Lint; must pass with no errors
```

- ktlint runs through Spotless; its settings are in the root `build.gradle.kts` (`editorConfigOverride`), not `.editorconfig`, which Spotless ignores for ktlint. Indents are 2 spaces, and the rules that would rewrite the code's own style (short `if`/`else` without braces, comments right under the line above, one-line enums, several statements or arguments to a line) are off. Run `spotlessApply` before committing.
- Keep `lint` free of errors. Suppress a false alarm with `@SuppressLint` and a one-line reason, as the autofill chip background does.

On this machine the JDK, SDK and Gradle home live in `~/.cache/mangareader-build`, so pass them in:

```sh
B=~/.cache/mangareader-build
JAVA_HOME=$B/jdk GRADLE_USER_HOME=$B/gradle-home ./gradlew :engine:test :app:assembleRelease
```

Install on the connected phone (a moto g32 on **wireless** adb; use the stable mdns serial, the ip:port changes):

```sh
export ANDROID_SERIAL=adb-ZD2229N375-cT0BGW._adb-tls-connect._tcp
timeout 180 bash -c 'until adb devices | grep -q "ZD2229N375.*device$"; do sleep 5; done'
adb install -r app/build/outputs/apk/release/app-release.apk
```

- Don't build or install unless asked. Compile checks (`:app:compileReleaseKotlin`) and `:engine:test` are fine.
- If the phone doesn't show up within the timeout, the wireless connection dropped. Ask the user to reconnect rather than rebuilding.
- Install the **release** APK. It's signed with the upload key, so `install -r` keeps the user's learned words, clipboard and settings. Debug builds of the Compose keyboard are several times slower and feel laggy, so judge feel only on release builds.
- Release signing reads `signing.storeFile`, `signing.storePasswordFile` and `signing.keyAlias` (alias `upload`) from the git-ignored `local.properties`; CI writes them from secrets. Never print the password or its file's contents.
- `versionCode` and `versionName` are set by hand in `app/build.gradle.kts`.
- The language pack `app/src/main/assets/dict/en.dict` is committed. To rebuild it, run `tools/fetch-data.sh` and the `:tools:run` command in its header; other tools run with `-PmainClass=com.moonkeyboard.tools.<Name>Kt`.
- `:tools:run` runs inside `tools/`, so pass absolute paths in `--args`.
- The app is built for `arm64-v8a` only (ONNX Runtime's native code costs 13–18 MB per ABI); 32-bit support was dropped on purpose.

## Neural next-word model

A small transformer (4 layers, 50k lower-case words) is blended with the dictionary's 3-gram in `SuggestionEngine.Lm`: 70/30 for next-word predictions (`EngineConfig.neuralShare`) and 50/50 while typing and gliding (`neuralTypingShare`, where context also gets more weight against the touches: `neuralLmWeight`; retune with `Evaluate --neural <dir> --grid true`), with the user's learned words and phrases mixed in on top as before. It scores predictions, typing and glide (`neuralWhileTyping`), and its own top words are offered only when they're dictionary words that pass the filters. The engine never imports ONNX Runtime: `NeuralLm` takes a run function, which the app (`Language.kt`, `onnxruntime-android`) and the tools (`onnxruntime` for the JVM) supply. The setting is **Typing → Smarter predictions** (`Prefs.neuralPredictions`, on by default).

- Training and quantizing need a GPU and torch, so they run on Kaggle, not this machine. The scripts are in `tools/lm/`:
  1. `train_lm.py` trains the model on the same lines the 3-gram counts, with the same hold-out rules, and scores it on the held-out sets.
  2. `blend_lm.py` tests the blend weight against the 3-gram.
  3. `quant_lm.py` exports the model to ONNX and checks the int8 version against the original.
- `tools/lm/app_model.py` runs locally without torch. It turns the quantized model and `lm.pt` into the app's `en-lm.onnx` (scoring only the next word, one shared copy of the word vectors) and `en-lm.vocab`:
  `uv run --no-project --with onnx --with onnxruntime --with numpy python -I tools/lm/app_model.py lm_int8.onnx lm.pt <out dir>`
- Measure a model change with `Evaluate --dict <abs en.dict> --neural <abs app/src/main/assets/dict> --eval <abs engine/src/test/resources/eval/en.txt>` (and `hi.txt`). It prints the full report three ways: 3-gram only, blend for next-word only, blend everywhere.
- On the phone, the test pad's debug panel shows "neural model: last run N ms" (about 10–20 ms on the moto g32). If that line never appears, the model failed to load and suggestions are quietly dictionary-only.

## Language packs

English (with Hinglish) is built in (`assets/dict/`, with a `manifest.json`); other languages are signed `.mkpack` files the user downloads in the browser and opens with the keyboard, since the app has no internet permission. They're published as releases of the separate `Moon-Ecosystem/MoonKeyboard-Languages` repo.

- Format (`engine/.../pack/Packs.kt`): a zip of `manifest.json` (id, name, version, layout, and the SHA-256 of every file), `manifest.sig` (ECDSA P-256 over the manifest), `<id>.dict`, optional `<id>-lm.onnx` and `<id>-lm.vocab`, and layouts as `<name>.json`. Nothing in a pack is parsed until the signature and every hash check out. Bump `Packs.FORMAT` only for changes old apps can't read.
- Installed packs live in `filesDir/packs/<id>/` (`LanguagePacks.kt`). Each language learns in its own `history-<id>.tsv`; English keeps `history.tsv`.
- Each language's sources (its `pack.json` with the language rules such as `digraphs` and `nounCapitals`, its layout, data download and held-out sentences) live in that repo, under `<id>/`; its `fetch-data.sh` builds the dictionary with `BuildLexicon` and the pack with `BuildPack pack <source> <built> <private key> <out.mkpack>` from this repo. This repo stays language-neutral: a language needs a change here only for a rule the engine doesn't have yet. The signing key is `~/.android-keys/moonkeyboard-packs.pk8`, never in the repo; its public half is `LanguagePacks.PUBLIC_KEY`. Never print or commit the private key, and never regenerate it: packs signed with a new key won't install on existing apps.

## Git workflow

- Work happens on `dev`, or on a feature branch for an experiment (e.g. `neural-lm`), and reaches `main` through a pull request. Don't commit to `main` directly.
- Merge PRs into `main` with a merge commit, not squash or rebase: a branch built on `dev` keeps applying cleanly only if `dev`'s commits land on `main` unchanged.
- `.github/workflows/release.yml` runs `:engine:test` and builds a signed APK on every push to `main`, published as a pre-release **Moon Keyboard build N**. A `v*` tag publishes **Moon Keyboard X.Y** as the latest release, using `.github/release-notes/vX.Y.md` as its notes when that file exists.
- To release: bump `versionCode`/`versionName` and write `.github/release-notes/vX.Y.md` (user-facing, grouped by area) on the branch, merge its PR, then tag `vX.Y` on `main`'s merge commit and push the tag. A tag publishes from whatever commit it's on.
- Pushing to GitHub needs the `Rishabh672003` account: `gh auth switch -u Rishabh672003`, push, then switch back with `gh auth switch -u rjha100`.
- Don't push or tag unless asked.
- Commit messages are one plain sentence saying what changed for the user (see `git log`), with detail in the body when needed.
- Never commit stray local files: `.codegraph/`, `.claude/`, `tools/data/`, keystores.

## Conventions and gotchas

- **Naming:** never name other keyboards (Gboard and the like) in code, comments, identifiers or strings. Describe the behaviour instead.
- **Engine first:** decoding, suggestion and learning logic belongs in `engine`, with a unit test. `EnglishTest` keeps accuracy floors for taps and glides over held-out sentences; a change must not lower them.
- **Settings:** add a `Pref` in `Prefs.kt`, read it where needed (Compose recomposes on change), and add a row to the matching screen in `AppScreens.kt`. Keep subtitles to one short line.
- **Theming:** the keyboard, the settings app and anything handed to the system all take their colours from the same settings.
  - Use `keyboardColors()` in Compose, or `KeyboardColors.chosen(context, dark)` outside it (the autofill chip style in `MoonImeService`), so pitch black, the accent and Material You are included. Never pick `KeyboardColors.Dark`/`Light` directly.
  - Ask `darkTheme()` whether the theme is dark. Never guess it from a colour: pitch black and the wallpaper change the colours.
  - In the settings app, use `LocalAppColors` and read resources through Compose (`stringResource`, `LocalResources`), not `LocalContext`, so they follow a configuration change.
- **Layouts** are JSON in `engine/src/main/resources/layouts/`; `Layouts.withNumberRow`, `withEmojiKey` and `wideSpace` transform them at runtime. Keep row widths consistent (letter rows are 10 units).
- **IME window:** it covers the whole screen so the floating keyboard can go anywhere; `onComputeInsets` sets the touchable region to the keyboard. Some devices under-report the navigation-bar inset, hence the extra bottom padding.
- **Enter key:** `InputController.enterAction` decides between newline and the field's editor action (search, send, go, done...); the drawn icon follows it. `IME_ACTION_UNSPECIFIED` on a single-line field still performs the action.
- **Inline autofill chips** (password managers), Android 11+:
  - Each chip is a surface drawn by the autofill service. Compose clipping doesn't reach it, so `clipChip` crops it with `setClipBounds` to the part inside the chip row.
  - Never hide a chip with `visibility` once shown: that tears its surface down, and it flashes unclipped when it comes back. A fully hidden chip gets a 1×1 clip off the chip instead (an empty clip means no clip).
  - Keep the plain scrolling `Row`, not a `LazyRow` (a recycled chip left its surface stranded at the window's top-left), and keep overscroll off on it (the stretch effect isn't followed by the clip).
  - Inflation callbacks arrive out of order: slot views by index and publish once all have arrived, as the AOSP sample IME does.
- **Glide:** a path that drifts off the letters before lifting is trimmed back to where it left them (`trimOffLetters`), so the word still types.
- **Suggestion strip:** fixed slot widths (middle 40%, sides 30%); long words are squeezed horizontally down to 70% before being cut in the middle, and a too-long middle word takes the whole strip, as AOSP's strip does.
- Match the surrounding code style: short comments explaining why, not what; no new dependencies for a few lines of code.
- When you add or change a user-facing feature, update its entry in `README.md`.
