# fishtank-tv — developer notes

The Android project behind both apps. For what the apps do and how to install
them, see the [main README](../README.md).

One codebase, two product flavors:

| Flavor | App | Package | Home URL | Release asset |
|---|---|---|---|---|
| `fishtank` | Fishtank TV | `com.fishtank.tv` | `https://www.fishtank.live/` | `fishtank-tv.apk` |
| `mde` | MDE TV | `com.mde.tv` | `https://www.mde.tv/` | `mde-tv.apk` |

Per-flavor values (name, home URL, update asset name) live in
`app/build.gradle`. Everything else is shared, and site-specific behaviour is
chosen at runtime from the URL.

## Layout

```
app/src/main/java/com/fishtank/tv/
  MainActivity.kt   WebView shell, key handling, site CSS/cleanup
  FtvWebView.kt     WebView whose keyboard action key submits the field
  UpdateChecker.kt  self-update from GitHub releases
  ThumbCache.kt     480px copies of fishtank's episode thumbnails
app/src/main/assets/
  dpad.js           the injected D-pad script (cursor, scrubbing, player
                    control, Continue watching). An asset rather than a Kotlin
                    string: JVM string constants max out at 64KB.
app/src/{fishtank,mde}/res/   per-flavor icons and banners
```

## How it works

**WebView shell.** A fullscreen WebView loads the site's *desktop* layout,
using a desktop Chrome user agent that carries the WebView's real Chrome
version. The Firestick renders the web at 2x, so the page is laid out at
960x540 CSS pixels, just under both sites' `lg` breakpoints.

**Injection.** `injectPageScripts()` runs on `onPageCommitVisible` (first paint)
and again on `onPageFinished`. Both are idempotent. It adds:
- `siteCss(url)`: hides clutter and adjusts layout. Match on stable parts of
  class names, never on build hashes. mde uses CSS-module classes like
  `5R0ZAa_music`, so match `[class*="_music"]`.
- `siteCleanupJs(url)`: tweaks CSS can't express (text matching). Only hide or
  reorder here, never `remove()`. These nodes belong to React, and removing
  them crashed fishtank to "This page couldn't load".
- `assets/dpad.js`: the remote-driven cursor. `__FTV_*__` placeholders in it
  are filled in by `injectPageScripts()`.

**D-pad cursor (`dpad.js`).**
- Candidates: everything focusable within a screen of the viewport (in scroll
  rows only, horizontally), minus anything hidden or covered. A hit-test
  rules out modals' backgrounds and clipped panes.
- Movement: nearest in the pressed direction. Items in line with the cursor
  win ("beam" rule, as Android does), and left/right stays inside a scrolling
  row.
- Selection style: the item zooms (up to 10%, fitted so it stays on screen and
  inside clipping ancestors), and a separate fixed "lift" element draws the
  shadow and light edge.
- Keys on a watch page:
  - Select: play/pause, or commit a scrub
  - Left/right, FF/RW: scrub (`__ftvScrub`). The target moves and the
    overlay shows a preview tile, but the player is only seeked once, on
    Select or 1.5s after the last press. Back cancels.
  - Up/down: show the time bar
- Scrub previews: both players are Bunny Stream, whose seek previews are
  6x6 sprite sheets with one tile every 2s. mde exposes them as a
  `thumbnails` metadata text track on its `<video>`. fishtank's are public
  at `<cdn>/<video id>/seek/_N.jpg`.
- Players:
  - mde: a same-origin `<video>`.
  - fishtank: Bunny's cross-origin iframe, driven through player.js
    `postMessage` (subscribe with `addEventListener`, not `on`).
- Fullscreen pins the player in place and neutralises ancestors that would
  trap `position: fixed`. It never reparents: moving an iframe reloads it.

**Amazon WebView video overlay (important).** Amazon's WebView gives the first
large playing video in the app a hardware overlay. It allows one per app
(`AWV Unable to create overlay; MAX_OVERLAYS limit hit`), and on current Fire
OS the overlay never gets frames, so that video plays with sound but a
black/grey picture. `createOverlayHolder()` runs a second, hidden 8px WebView
that plays a 720p black clip, then pauses it. This holds the slot, so the
sites' videos are composited normally. The clip has to be a real resolution
(128x72 was ignored) and has to live outside the site's DOM (React discards
injected nodes). Left playing, the holder slowed every page load by about
3.5s, which is why it's paused. It's destroyed in `onStop` and recreated in
`onStart`, and the main WebView resumes only once the holder is playing
again.

**Resume, up next, screen.**
- mde resumes and auto-plays the next episode itself.
- fishtank does neither, so the app does it there: positions are saved per
  episode in the site's localStorage (`__ftv_pos_<id>`) and restored when
  the player first reports a time. The row an episode was opened from is
  remembered in sessionStorage (`__ftv_queue`, oldest first), and on the
  player's `ended` event the next one plays after a 10s countdown card that
  Back cancels.
- Continue watching: part-watched episodes (`__ftv_recent` in localStorage,
  newest first, 12 max, dropped when finished) are shown as a row at the top
  of mde's homepage and fishtank's Episodes page. The row is a clone of one
  of the site's own rows and cards, so it matches each site's style. It's
  per-TV: neither site stores watch progress on the account (checked by
  logging playback traffic on both).
- Up next in Continue watching: finishing an episode (last 30s, or `ended`)
  adds the next one with `next: true`, drawn with an "UP NEXT" strip instead
  of a progress bar. On mde the next episode comes from `GET /videos/next/<id>`
  (or `pscNext` for PSC), only if it's in the same series, and never for the
  series in `NO_NEXT_UP` (Stand-Up, FILMS, The Vault and other collections).
  On fishtank it's the next episode in the row it was opened from.
- On launch the cursor starts on the first Continue watching card, or on
  fishtank's Episodes button.
- The screen is kept on only while `__ftvIsPlaying()` is true, polled every
  15s, so the Fire TV's screensaver works.
- The Menu offers a subtitles toggle when the page's `<video>` has a
  subtitles track (mde). fishtank's player exposes no caption control.

**fishtank Episodes page.** Seasons are sorted oldest first, season 3's two
BTS videos are hidden there and copied into the BTS tab, and the "Digest" tab
is labelled "Season 4 Digest" (all in `siteCleanupJs`).

**fishtank thumbnails.** Bunny serves every episode thumbnail at 1920x1080
and ignores `?width`. Reading and decoding ~60 of them took ~1.3-2s each on the
Firestick, even from cache. `ThumbCache.kt` intercepts `<id>/thumbnail.jpg`,
shrinks it to 480px wide once (Bunny wants fishtank's Referer, so the page's
request headers are passed along), and serves the small copy after that. Copies
expire after a week. The Bunny player iframe's own requests aren't touched.

**mde Pause Snack Continue page.** `renderPscRows` in `dpad.js` hides the
series list and puts one row per game in its place, newest game first.
- Data: `api.mde.tv/v1/series/pause-snack-continue/videos`. Pages start at 0,
  pageSize is capped at 200, and each video has a `watched` flag.
- The game is the title before " - Ep. N", without "Scary Mondays:" or "PSC
  Wildcard Wednesday:". Anything else goes in a Specials row.
- Up next stays within the game: the answer to the site's own
  `GET /videos/next/<id>` request is swapped for the game's next episode
  (XHR patch in `dpad.js`). After a game's last episode, mde's pick is used.
- Rows are copies of the site's `_seriesVideoList` element and cards, because
  the card styles are scoped to that list. So skip our copies (inside
  `#__ftv_psc`) when looking for the site's list.

**Back navigation.**
- From an episode, BACK goes to the page it was opened from (a show, the PSC
  page, fishtank's Episodes page), with the cursor on that episode
  (`window.__ftvBack` in `dpad.js`). Only the last such page is kept, in
  sessionStorage, and the homepage clears it. From anywhere else BACK goes home.
- On mde, opening an episode is a full page load, so BACK does a plain
  `loadUrl(home)`. That was measured fastest: the home link waits on a server
  re-render, and the back/forward cache doesn't apply.
- On fishtank, BACK routes through the page.
- On the homepage, pressing BACK twice exits.

**Self-update (`UpdateChecker`).** Ten seconds after launch, the app asks
`api.github.com/repos/michaety/fttv/releases/latest` for the latest release.
If it's newer than `BuildConfig.VERSION_NAME`, it downloads the flavor's asset
into an uncommitted `PackageInstaller` session, then offers to install it. The
Menu (☰) button runs a manual check. Fire OS 8 is Android 11, so one INSTALL
press on the system confirmation is unavoidable (silent self-updates need
API 31).

**Keyboard.** Select on a text field focuses it and sends a real touch to
raise the Fire TV keyboard (a script focus alone won't). The tap waits for
Select's key-up, or the key-up "presses" the keyboard's highlighted key. The
Fire TV keyboard always shows Previous/Next, so `FtvWebView` turns any
editor action into "submit the focused field's form".

**Casting.** Casting into the app was tried and removed in v1.4.0. The
website's cast button is Chromecast-only, and a Chromecast receiver needs a
Google-signed device certificate. A UPnP/DLNA receiver worked, but it only
appears in DLNA casting apps, not the cast button people actually use.

## Build

Needs JDK 17 and the Android SDK (platform 34, build-tools 34). Point
`local.properties` at the SDK:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

```bash
./gradlew assembleDebug          # both flavors
./gradlew assembleMdeDebug       # just one
# -> app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
```

## Run and debug on a Fire TV

Enable ADB debugging on the Fire TV (Developer Options), then:

```bash
adb connect <firestick-ip>:5555
adb install -r app/build/outputs/apk/mde/debug/app-mde-debug.apk
adb shell am start -n com.mde.tv/com.fishtank.tv.MainActivity
```

WebView debugging is enabled, so the page can be inspected live. Find the
app's PID, forward its devtools socket, then open `chrome://inspect` or talk
CDP to `http://127.0.0.1:9333`:

```bash
adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof com.mde.tv)
```

Useful facts:
- `adb shell input keyevent <code>` drives the remote: 19/20/21/22 arrows,
  23 Select, 4 Back, 82 Menu.
- `adb exec-out screencap -p` can't capture video on the hardware overlay
  (it shows as flat grey), but normally composited video does show up, which
  makes it a handy check.
- When video misbehaves, check logcat for `SurfaceViewOverlayCore`,
  `Unable to create overlay` and `video frame queue is empty`.

## Release

1. Bump `versionCode` and `versionName` in `app/build.gradle`. The updater
   compares `versionName` as dotted numbers.
2. `./gradlew clean assembleDebug`.
3. Create a GitHub release tagged `v<versionName>` with the two APKs named
   exactly `fishtank-tv.apk` and `mde-tv.apk`. The README's download links
   and `UpdateChecker` both rely on those names.

Releases are debug-signed with `~/.android/debug.keystore` on the build
machine. Keep using the same key: an APK signed with a different key won't
install over an existing one, so updates (and logins) would break.
