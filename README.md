<div align="center">

<img src=".github/icon.png" alt="Moon Keyboard logo" width="96"/>

# Moon Keyboard

### A fast, fully offline keyboard for Android

Glide typing, autocorrect and next-word prediction in English and Hinglish. The app has no internet permission, so nothing you type leaves your phone.

[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?labelColor=27303D)](#building-from-source)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue?labelColor=27303D)](LICENSE)

</div>

## Features

- **Glide typing:** slide across the letters of a word, with an optional trail that fades as you go.
- **Suggestions and autocorrect:** typos are fixed when you press space, and backspace undoes a fix. Next-word prediction uses the last two words for context.
- **Learns how you write:** words and phrases you type are learned on the device, never in password or incognito fields. Browse, search and forget them in Settings, or tell the suggestion strip to stop offering a word. Swear words can be blocked from suggestions and glide typing.
- **Hinglish:** suggests Hindi typed in English letters, like "kya kar rahe ho", alongside English. It can be switched off.
- **Emoji:** the full Emoji 18.0 set with search (hold the comma key) and recents. Type "pizza" and 🍕 shows in the suggestion strip.
- **Clipboard:** text, images and GIFs you copy, with pinning and automatic clean-up of old images. What you just copied is offered in the suggestion strip once.
- **Text editing:** a d-pad with repeating arrows, select, select all, copy, paste, Home and End.
- **Layouts:** one-handed mode, a floating keyboard you can move and resize, and a split layout in landscape for two thumbs.
- **Your keyboard, your way:** light, dark or system theme, key borders, number row, key height, vibration strength, key sound and auto-capitalise. Pick and reorder the top bar's buttons by dragging.
- **Test pad:** try the keyboard from the app without switching to it.

## Privacy

- The app has no internet permission. It cannot send anything anywhere.
- Learned words, your clipboard and your settings stay on the device.
- Nothing is learned in password or incognito fields.
- No analytics, ads or crash reporting.

## Building from source

You need JDK 17 or newer and an Android SDK with platform 36.1. The Gradle wrapper (Gradle 9.3.1) is included.

```sh
./gradlew :engine:test           # typing engine unit tests
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # release APK
```

Debug builds of the keyboard UI are noticeably slower; use a release build to judge how it feels.

To sign with your own key, add these to the git-ignored `local.properties`. Without them the default debug key is used.

```properties
signing.storeFile=/path/to/upload.jks
signing.storePasswordFile=/path/to/upload.password
signing.keyAlias=upload
```

The English + Hinglish language pack (`app/src/main/assets/dict/en.dict`) is committed. To rebuild it, run `tools/fetch-data.sh` and follow the command in its header.

The project has three modules: `engine` (pure Kotlin decoding, suggestions and learning), `app` (the Android input method, built with Jetpack Compose) and `tools` (builds the language pack and emoji list). See [docs/architecture.md](docs/architecture.md).

## License

The code is distributed under the [MIT License](LICENSE). The language pack includes L3Cube-HingCorpus data, so the pack file itself is licensed under CC BY-NC-SA 4.0 (non-commercial). See [NOTICE](NOTICE) for all data sources and their licenses.
