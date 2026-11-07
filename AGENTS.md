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
    MainActivity.kt, AppScreens.kt, AppUi.kt   settings app (setup, Look, Typing, Glide, Clipboard, Learned words)
    TestPad.kt                                 try the keyboard in-app, with touch/decoder debug info
    ime/
      MoonImeService.kt     InputMethodService: window, insets, clipboard capture, inline autofill request
      InputController.kt    turns key presses into edits; composing word, suggestions, undo, enter action,
                            autofill chips; its state drives the UI
      KeyboardUi.kt         suggestion strip, toolbar, autofill chip row, the keys canvas, one-handed and split
      TouchTracker.kt       taps, long-press popups, backspace repeat, glide paths, space-bar cursor slide
      Panels.kt             clipboard, emoji, text-editing and options panels
      KeyboardWindow.kt     docked or floating (movable, resizable) window
      Prefs.kt              every setting as a Pref; Language.kt loads the dictionary and learned history
      ClipboardStore.kt, Emoji.kt, Haptics.kt, Icons.kt, KeyboardColors.kt, GestureRecorder.kt
  src/main/assets/          dict/en.dict (built language pack, committed), emoji.txt
engine/                      pure Kotlin, no Android: unit-tested on the JVM
  src/main/kotlin/com/moonkeyboard/engine/
    decode/                 TapDecoder (spatial + edit distance), GlideDecoder, KeyMap/Spatial
    dict/                   Lexicon (trie + word pairs/triples), LexiconBuilder
    suggest/                SuggestionEngine (suggest, glide, predict), WordContext
    history/                UserHistory (learned words and phrases)
    layout/                 Layout/Layouts, KeyboardGeometry (hit-testing, split landscape)
    input/, text/, trace/, eval/ (TypingSimulator for the accuracy tests)
  src/main/resources/layouts/   qwerty, symbols, symbols2, number, phone, datetime (JSON)
  src/test/                 unit tests; resources/eval/ holds held-out sentences for accuracy tests
tools/                       build-time only: BuildLexicon, BuildEmoji, Evaluate; fetch-data.sh
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
```

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

## Git workflow

- Commit straight to `main`; there is no dev branch.
- `.github/workflows/release.yml` runs `:engine:test` and builds a signed APK on every push to `main`, published as a pre-release **Moon Keyboard build N**. A `v*` tag publishes **Moon Keyboard X.Y** as the latest release, using `.github/release-notes/vX.Y.md` as its notes when that file exists.
- To release: bump `versionCode`/`versionName`, write `.github/release-notes/vX.Y.md` (user-facing, grouped by area), commit, then tag `vX.Y` and push the tag.
- Don't push or tag unless asked.
- Commit messages are one plain sentence saying what changed for the user (see `git log`), with detail in the body when needed.
- Never commit stray local files: `.codegraph/`, `.claude/`, `tools/data/`, keystores.

## Conventions and gotchas

- **Naming:** never name other keyboards (Gboard and the like) in code, comments, identifiers or strings. Describe the behaviour instead.
- **Engine first:** decoding, suggestion and learning logic belongs in `engine`, with a unit test. `EnglishTest` keeps accuracy floors for taps and glides over held-out sentences; a change must not lower them.
- **Settings:** add a `Pref` in `Prefs.kt`, read it where needed (Compose recomposes on change), and add a row to the matching screen in `AppScreens.kt`. Keep subtitles to one short line.
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
