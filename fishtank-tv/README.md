# Fishtank TV

A Fire TV / Android TV shell that loads **fishtank.live** or **mde.tv** in a fullscreen
WebView and injects D-pad navigation, so the site is usable from the remote without
plugging in a mouse.

Rebuild of the March 2026 project, with the two outstanding changes already applied:
desktop user-agent, and a two-site launcher instead of two separate APKs.

## What it does

- **Launcher screen** — two focusable tiles, fishtank.live and mde.tv
- **Fullscreen WebView** — desktop Chrome user-agent, so you get the desktop layout
  rather than the phone one
- **Injected D-pad navigation** — arrows do a geometric nearest-neighbour search for
  the next focusable element; amber ring shows what is selected
- **Select** activates. Text inputs get focus so the Fire TV keyboard appears;
  video elements toggle play/pause
- **Menu button** re-acquires focus if it ever gets stuck
- **Back** goes back through history, then drops to the launcher
- **Play/Pause** on the remote controls the page's `<video>` element
- **Cookies persist**, so you stay logged in between launches
- **Dead-end handling** — if there's nothing focusable in the direction you pressed,
  the page scrolls instead

## Build

Requirements: **JDK 17** and the **Android SDK command-line tools**. Android Studio is
not needed — the Gradle wrapper is included and complete (jar and both launcher
scripts), so `gradlew` works straight out of the box.

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Google.AndroidStudio     # or just the standalone cmdline-tools
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

Create `local.properties` in the project root pointing at your SDK:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

Then:

```powershell
.\gradlew assembleDebug
```

APK lands at `app\build\outputs\apk\debug\app-debug.apk`.

## Install

Push it over with Apps2Fire from your phone, as usual. Alternatively, enable
*Settings → My Fire TV → Developer Options → Install Unknown Apps* and sideload with
Downloader, or use `adb install -r app-debug.apk` over the network.

## Configuration

Both URLs live at the top of `LauncherActivity.kt`:

```kotlin
const val FISHTANK_URL = "https://www.fishtank.live"
const val MDE_URL = "https://mde.tv"
```

The user-agent string is at the top of `MainActivity.kt` as `DESKTOP_UA`. If a site
serves something broken to a desktop UA, try a tablet string there instead.

## Tuning the navigation

The injected script is `DPAD_JS` at the bottom of `MainActivity.kt`. Two knobs matter:

- `SEL` — the CSS selector defining what counts as focusable. If elements on the site
  are unreachable, they're probably not matching this; add their class or role.
- `across * 2.5` in the scoring — how strongly straight-ahead movement is preferred
  over diagonal. Raise it if the cursor jumps sideways too eagerly.

## Known limits

- It is a website on a TV, not a real leanback app. Expect some awkwardness.
- Video that needs DRM/Widevine may not play in a plain WebView. If a stream is black
  but audio works, that's the likely cause.
- Debug-signed. For a cleaner install, generate a keystore and run `assembleRelease`.
