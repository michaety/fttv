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
  PhoneRemote.kt    web server for the phone remote page
app/src/main/assets/
  remote.html       the phone remote page (served by PhoneRemote)
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
  row. Up/down to or from a very wide item (over 60% of the screen, like
  mde's Spotlight) is measured edge to edge, not centre to centre, or a
  narrow card off to the side would beat it.
- mde's Spotlight: left/right on the slide press the carousel's next/previous
  button (`stepSpotlight`), so its 1-7 buttons are never focused. The cursor
  is moved to the visible slide's link whenever the carousel turns, by itself
  too (`syncSpotlight`, every 500ms), so Select opens what's on screen.
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

**Phone remote (both apps).** Menu -> "Link your phone" shows a QR code
for `http://<tv-ip>:8765/?k=<key>`. `PhoneRemote.kt` serves `remote.html` and a
small JSON API while the app is in the foreground (started in `onStart`,
stopped in `onStop`). Both apps use port 8765; switching from one to the other
runs the new app's `onStart` before the old one's `onStop` frees the port, so
`start()` binds on a thread and retries for ~10s. The dialog (picture:
`res/<flavor>/drawable-nodpi/link_phone.png`) closes once a phone loads the
page.
- Looks: `remote.html` has the markup and script only. `PhoneRemote` puts the
  flavor's stylesheet into it: `remote-mde.css` or `remote-fishtank.css`, each
  copied from its site's own CSS (fonts, colours, borders, shadows; mde's
  FFGothic fonts load from cdn.mde.tv, Fishtank's textures and logo from
  cdn.fishtank.live). Fishtank's Sofia Pro is an Adobe Fonts kit locked to
  fishtank.live, so Figtree stands in. Restyle each against its site's mobile
  layout at 412px.
- `/api/home`: Continue watching (`__ftv_recent`, read from the WebView) and,
  from `/v1/site/landing`, every playlist (New, Popular, Free...), the
  Spotlight (its "Featured" playlist) and the series list. Unlike
  `/v1/playlists/<id>`, that endpoint has `watched` flags.
- `/api/series?tag=`: one series' episodes from `/v1/series/<tag>/videos`,
  newest first.
- `/api/search?q=`: `/v1/search?query=`. Results carry a series id, not a
  tag, so `/v1/series` maps it to `/series/<tag>/<video>`.
- mde likes, comments and chapters, all as the account the TV is signed in
  with (mde's API is cookie-only, so `apiSend` sends the WebView's cookies,
  like `get`):
  - `/api/episode` also has the video id, `likes`/`dislikes`/`myLike`
    (true, false or null, from `details`), the comment count, and `chapters`
    / `moments` as `{ title, t }`. Few episodes have chapters; the field names
    are read loosely (`marks`) because none were found to check against.
  - `/api/like?id=&v=like|dislike`: `POST /v1/videos/<id>/like|dislike`,
    which toggles on mde's side and returns the counts.
  - `/api/comments?id=&page=&sort=best|worst|newest|oldest`: 10 per page,
    pinned ones with page 0, replies inline.
  - `/api/comment/post?id=&message=[&reply=1]` and
    `/api/comment/vote?id=&v=like|dislike[&reply=1]`.
  - These are GETs (the server reads no bodies). The key in every request
    stops another page from posting as the user.
  - On the phone, comments sit under the player (scroll down, YouTube-style),
    with their heading peeking above the Up next / Episodes / About sheet.
- fishtank: `/api/home` has the cameras from `/v1/live-streams` (online ones
  first; thumbnails come from the tiles on the TV's live page) and episodes
  from `/v1/videos` (public, all ~140 at once) by collection: Seasons 1-3
  first, then any collection we don't know yet, then digests, Shorts and BTS
  (`COLLECTIONS_TOP`/`COLLECTIONS_BOTTOM`). Seasons are sorted by the number
  in the title, since upload dates don't follow it.
  Search matches titles in that list; there's no search API.
- fishtank `/api/camera?name=`: there's no URL for a camera. The live page
  (/) picks one with its `Watch <name>` tile buttons, so
  `__ftvRemoteCamera` goes to / and clicks that tile. Off-season there are
  no tiles, so this is untested against a live season.
- fishtank `/api/play` goes through `__ftvRemoteEpisode`, which stores the
  title (`__ftv_title_<id>`) so Continue watching can show it.
- `/api/play?path=`: opens the episode in the WebView, signed in, so progress
  and achievements count. Only `/series/<tag>/<tag>` paths are accepted.
- `/api/status` and `/api/cmd?c=toggle|seek|seekto`: via
  `__ftvRemoteStatus`, `__ftvTogglePlay`, `__ftvSeek` and `__ftvRemoteSeekTo`
  in `dpad.js`.
- The server calls mde's API itself, because the phone page would be
  blocked by CORS. It sends the WebView's cookies (`CookieManager`).
- Every request needs the key from the QR code (403 otherwise). The key is
  random, kept in SharedPreferences, and stays the same, so a bookmark keeps
  working while the TV's IP doesn't change.
- Player page (tap the Now playing bar): `/api/episode?path=` gives the
  details and the episodes it belongs with in watching order (mde: the series
  by `episode` number, or for PSC the same game; fishtank: its season, with
  "(Extras)" after their episode). `/api/cmd` also takes `restart`, `captions`
  (`__ftvCaptions`) and `stop` (`leavePage`, as BACK).
- Queue: `/api/queue[/add|/remove|/top]?path=`. It lives on the TV in
  localStorage `__ftv_playqueue` (`__ftvQueue` in dpad.js), and an entry comes
  off when its page loads or is pushState'd to. At the end of an episode it
  goes first: mde's own up-next card is fed it by the `/videos/next` XHR
  swap (the site only asks just before showing the card, so something queued
  mid-episode still counts); fishtank's countdown takes it in
  `nextEpisode()`. mde entries carry the API's video object for that; fishtank
  entries carry their season (`row`) so autoplay carries on from there.
  `/api/queue` also lists "auto", what autoplay would play next (mde: worked
  out on the server, skipping `NO_NEXT_UP`; fishtank: the TV's remembered
  row).
- Home screen: `/manifest.webmanifest` (needs the key; its `start_url`
  carries it) and `/icon.png` (no key; `assets/remote-icon-<flavor>.png`).
  Chrome only offers a real install on HTTPS, so on the LAN it's "Create
  shortcut", which still uses the name and icon.
- fishtank's thumbnail CDN refuses requests without a Referer, so the page
  must keep the browser's default referrer policy.
- Why not the site's Cast button: it only casts to Google-certified Cast
  devices (Default Media Receiver `CC1AD845` plus device authentication), so
  a sideloaded Fire TV app can't appear there.

**TV player controls.** On a watch page, any key brings up a full-screen
layer (`#__ftv_seek`, still named after the old seek overlay): title on top,
a full-width scrub bar, and Restart / -10 / play-pause / +30 / Next /
Subtitles. Left/right scrub (step from `seekStep` in MainActivity, ramping
up while held), Down/Up move between the bar and the buttons, Select
presses, BACK hides it before leaving (`__ftvScrubCancel`). It stays up
while paused. Keep it cheap: the bar, knob and preview move with transforms,
it redraws once per frame, and the preview tile is drawn into a small canvas
from the decoded 1800x1008 seek sprite (scaling the whole sheet with CSS
lagged badly). `loadSprite` must not call its callback for an already-loaded
sheet: the redraw loop that made froze the page.

**Loading screen.** Opening an episode shows `#__ftv_loader` (blurred
artwork, title, spinner) until the video actually plays, then fades. It goes
up before navigating when we start the episode (`__ftvGo`: TV cards, phone
remote, Next, fishtank's countdown) and carries over a full page load via
sessionStorage `__ftv_loading`; it gives up after 15s.

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
If it's newer than the *installed* version, it downloads the flavor's asset
to `cache/update/update.apk`, then offers to install it. Install hands the file
(through a `FileProvider`) to Fire OS's installer screen. The user confirms,
and the screen ends on "App installed" with OPEN focused, which reopens the
app. The Menu (☰) button runs a manual check. Fire OS 8 is Android 11, so one
INSTALL press on the system confirmation is unavoidable (silent self-updates
need API 31). It always jumps straight to the latest release, never one version at a time.
- Fire OS can replace the APK without killing a running process, which leaves
  the old code (and the old `BuildConfig`) running, and that code used to offer
  the same update again. The check compares against the installed package
  instead, and restarts the app if the installed `versionCode` is newer than
  the running one.
- Why not a `PackageInstaller` session (used up to v1.9.0)? A session
  install ends on the Fire TV home screen, and nothing can reopen the app from
  there. Both a `MY_PACKAGE_REPLACED` receiver and an activity `PendingIntent`
  as the session's status receiver were tried; Android 11 aborts both as
  background activity starts.
- The prompt shows "What's new": the GitHub release text of every version
  between the installed one and the latest, newest first (`fetchNotes`, from
  `/releases`). Bold and "- " lists are turned into simple HTML, and the
  "Install:" download line is dropped, so write release notes for the TV too.
  The notes scroll with Up/Down; the dialog still starts on INSTALL.
- LATER (or Back) snoozes the launch prompt for 24 hours, so back-to-back
  releases bother people at most once a day. The menu's manual check ignores
  the snooze, like it ignores "Skip this version".
- Release policy: batch changes and publish every few days to a week, or same
  day only for a bug people are actually hitting. Every release costs users
  four presses (prompt, INSTALL, Fire OS's confirmation, OPEN).

**Other devices (mde, `syncAccount` in dpad.js).** Positions aren't on the
account (each browser keeps its own), but two things are: a `watched` flag per
episode (`GET /series/<show>/videos`, set near the end) and the History list
(`GET /playlists/history-<user id>`, user id from `GET /auth`: every episode
page opened anywhere, newest first, no times). On the homepage, at most once a
minute:
- A Continue watching entry is dropped if it's been finished elsewhere since
  the TV saved it. `wasWatched` is its flag when the TV first saved it, so
  rewatching an episode finished long ago doesn't vanish.
- If the newest History entry is unfinished, isn't on the TV, wasn't the last
  episode this TV opened (`__ftv_last_opened`) and wasn't removed by hand
  (`__ftv_dismissed`), it goes first with an "On another device" tag instead
  of a progress bar. It goes away as soon as anything newer is opened
  anywhere, so there's only ever one. It starts from the beginning.

**Renderer crashes.** On a ~2GB Firestick the low-memory killer sometimes
takes out the WebView's renderer, most often when mde's up-next card switches
episodes. `onRenderProcessGone` rebuilds the WebView and reloads where it was.
For that switch, dpad.js tells the app (`FtvApp.upNext`) which episode the
card will play as soon as mde asks `/videos/next`. If the renderer dies within
a minute while still on the finished episode, the app loads the next one
instead. Reloading the finished one made mde start it over, which looked like
autoplay replaying the same episode.

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
