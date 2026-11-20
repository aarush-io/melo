<p align="center">
  <img src="design/melo-banner.svg" alt="Melo — Your music. Your space." width="100%" />
</p>

<p align="center">
  <strong>Your music. Your space.</strong><br />
  Spotify, YouTube Music, SoundCloud, and your local library in one Android player.
</p>

<p align="center">
  <a href="https://github.com/aarush-io/melo/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/aarush-io/melo?style=for-the-badge&label=LATEST&color=f1f0ec&labelColor=171717" /></a>
  <img alt="Android 8+" src="https://img.shields.io/badge/ANDROID-8.0%2B-f1f0ec?style=for-the-badge&labelColor=171717" />
  <a href="https://github.com/aarush-io/melo/releases"><img alt="Total GitHub release downloads" src="https://img.shields.io/github/downloads/aarush-io/melo/total?style=for-the-badge&label=DOWNLOADS&color=f1f0ec&labelColor=171717" /></a>
  <a href="https://github.com/aarush-io/melo/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/aarush-io/melo?style=for-the-badge&label=STARS&color=f1f0ec&labelColor=171717" /></a>
</p>

<p align="center">
  <a href="https://api.melo.aarushsagar.com/api/stats"><img alt="Active playback sessions" src="https://api.melo.aarushsagar.com/api/stats/live/badge.svg" /></a>
  <a href="LICENSE"><img alt="GPL v3" src="https://img.shields.io/badge/LICENSE-GPL_3.0-f1f0ec?style=for-the-badge&labelColor=171717" /></a>
</p>

---

## One listening space

Melo is a native Android music player built with Kotlin and Jetpack Compose. Bring your listening together, find something for the moment, and keep your favorite music close.

The app ID is **in.melo**. Melo is independent of Spotify, YouTube, Google, and SoundCloud.

## Take a look

<p align="center">
  <img src="docs/assets/img/readme/melo-product-showcase.png" alt="Melo Speed Dial, Home feed, Podcasts, and Now Playing screens" width="100%" />
</p>

<details>
  <summary>View the full-size screenshots</summary>

  <p align="center">
    <img src="docs/assets/img/readme/screens/speed-dial.png" alt="Melo Speed Dial with mood controls and favorite music" width="49%" />
    <img src="docs/assets/img/readme/screens/home-feed.png" alt="Melo Home with mood controls and music discovery shelves" width="49%" />
  </p>
  <p align="center">
    <img src="docs/assets/img/readme/screens/podcasts.png" alt="Melo Podcasts with show search, recommendations, and listening history" width="49%" />
    <img src="docs/assets/img/readme/screens/now-playing.png" alt="Melo Now Playing with album artwork and playback controls" width="49%" />
  </p>
</details>

## Made for listening

- **Your music together.** Browse Spotify, YouTube Music, SoundCloud, and audio stored on your device from one app.
- **Artwork stays colourful.** Album covers, playlists, and artist photography keep their original colour against a restrained interface.
- **A player that gets out of the way.** Queue, lyrics, playback controls, and song details are close at hand.
- **A library that feels like yours.** Organize music, playlists, downloads, history, and local files.
- **Tuned to your taste.** Personalize appearance, playback, navigation, and equalizer settings.
- **In your language.** English, French, and Russian.

Service access and available playback features depend on the source, account, region, and current service behavior.

## Get Melo

Download **Melo 1.2.0** from the [latest GitHub release](https://github.com/aarush-io/melo/releases/latest), then open the APK on an ARM64 Android device running Android 8.0 or later. Android will ask you to allow installation from the app you used to open the download.

Melo uses its own application ID and keeps its settings, sign-in, and downloads separate from other apps.

## Updates

Melo checks the public GitHub Releases feed for a newer stable version and can notify you in the app. When you choose to update, Melo downloads the release APK, checks the published SHA-256 digest when available, and confirms the package, version, and signing certificate before opening Android's installer. Android asks you to approve installation.

For an update to install over an existing Melo build, publish a higher version code and sign it with the same Melo release key. Attach the APK with this exact name:

    Melo-vVERSION.apk

For example, version 1.2.0 uses the tag v1.2.0 and the asset Melo-v1.2.0.apk.

## Build from source

### Requirements

- JDK 21
- Android SDK Platform 37 and Build Tools 36.0.0 or newer
- The unmodified Spotify 9.2.0.26 ARM64 APKM (build 148373686) to build the complete Spotify engine path

The Spotify APKM and generated engine binaries are not stored in this repository. Provide a local copy that you are entitled to use. Gradle checks the pinned APK and native library hashes before preparing the runtime.

### Windows

Clone the repository, configure the Android SDK using ANDROID_HOME or an ignored local.properties file, and place the APKM at:

    native-runtime/artifacts/spotify-9.2.0.26.apkm

Then build and package the signed release:

~~~powershell
.\gradlew.bat :app:assembleRelease -PmeloUpdateRepository=aarush-io/melo
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-release.ps1
~~~

The APK, SHA-256 checksum, and signing-certificate report are written to the ignored dist folder. Create the Melo signing key once with scripts/create-signing-key.ps1 and back up the ignored .signing directory securely. Never commit the signing key or its properties file.

On macOS or Linux, use ./gradlew with the same Gradle property and packaging script.

To build the app without the Spotify runtime archive for development, pass -PnativeRuntime=false. This build cannot play encrypted Spotify audio.

## Playback and evidence

Melo uses native Android protocols for its Spotify integration. The protocol notes document what has been checked statically, with test doubles, and against live services. These are different kinds of evidence. A moving playhead or a populated buffer is not proof of audible playback; full Spotify playback is only confirmed when decoded audio is checked beyond the clear lead-in, after seeks, and across track changes.

See the [native runtime guide](native-runtime/README.md), [audio delivery notes](protocol/07-audio-delivery.md), and [native engine notes](protocol/08-native-engine.md) before changing playback.

## Contribute

Start with [CONTRIBUTING.md](CONTRIBUTING.md). Please include the Android version and a concise reproduction when reporting a bug. For security concerns, see [SECURITY.md](SECURITY.md).

## Credits and license

Melo is a better-maintained fork of Spotifeur.

The YouTube Music integration credits [Resona](https://github.com/code-saksham-hash/Resona). The interface uses Jetpack Compose, AndroidX Media3, Coil, and OkHttp. Dot-matrix typography uses [Doto](https://github.com/google/fonts/tree/main/ofl/doto), distributed under the [SIL Open Font License](design/Doto-OFL.txt).

The application source is distributed under the [GNU GPL v3](LICENSE). The unmodified Spotify runtime is third-party software and remains subject to its own terms. Melo is not affiliated with or endorsed by Spotify, YouTube, Google.
