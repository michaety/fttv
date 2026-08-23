# fttv

Fire TV apps for **fishtank.live** and **mde.tv**.

Both sites work fine in a browser and badly on a television. These are two small
Android TV apps that load each site fullscreen, strip out the parts that only
make sense with a mouse, and let you drive the rest from the remote.

Unofficial. Not affiliated with, endorsed by, or supported by either site.

---

## What you get

Two separate apps, each with its own icon on the Fire TV home row:

| | | |
|---|---|---|
| <img src="assets/icons/fishtank-tv.png" width="64" alt="Fishtank TV icon"> | **Fishtank TV** — fishtank.live | [Download](https://github.com/michaety/fttv/releases/latest/download/fishtank-tv.apk) |
| <img src="assets/icons/mde-tv.png" width="64" alt="MDE TV icon"> | **MDE TV** — mde.tv | [Download](https://github.com/michaety/fttv/releases/latest/download/mde-tv.apk) |

Install one or both — they're independent. See [Installing](#installing) below for
how to sideload the APK on a Fire TV device.

<table>
<tr>
<td><img src="assets/screenshots/fishtank-tv.jpg" alt="Fishtank TV screenshot" width="480"></td>
<td><img src="assets/screenshots/mde-tv.jpg" alt="MDE TV screenshot" width="480"></td>
</tr>
</table>

### What they do

- **Remote navigation.** Arrow keys move a highlight around the page, Select
  activates it. No mouse, no phone app, no Bluetooth keyboard.
- **TV-appropriate layout.** The desktop site, not the phone one, so it's
  built for a wide screen.
- **Clutter removed.** Chat panels, nav bars, audio players and purchase
  prompts are hidden — they're unusable from a couch and they slow the page
  down.
- **Stays logged in.** Sign in once; the session survives restarts.
- **Fast-forward and rewind seek the video** 10 seconds at a time, with an
  on-screen scrub indicator.
- **Back always returns to that app's homepage** instead of exiting or
  wandering back through video history.

Navigating with the remote (MDE TV shown, same on Fishtank TV):

<img src="assets/screenshots/nav-demo.gif" alt="Navigating MDE TV with the D-pad" width="600">

### What they don't do

These are websites in a wrapper, not native TV apps. Expect some rough edges:

- Navigation is approximate. It moves to the nearest thing in the direction
  you pressed, which isn't always the thing you meant.
- The Firestick is running a full desktop web app. It is not fast.
- Video player controls are the site's own, and can be awkward to reach.
- Anything the site changes on their end may break something here.

---

## Requirements

- A Fire TV Stick or Fire TV device. The app's minimum SDK (22 / Fire OS 5)
  covers every Fire TV model ever sold, from the original 2014 stick through
  the latest 4K Max, so it will install and launch anywhere. Performance is
  the real variable, not compatibility — these are heavy desktop web pages in
  a WebView, and it's only been tested on a Fire TV Stick 4K (2nd gen, 1.5GB
  RAM), where video playback shows real memory-pressure slowdown. Lower-RAM
  devices (original Stick, Stick Lite, 1st-gen 4K) will likely feel worse;
  the 4K Max (3rd gen, more RAM) should be fine or better.
- An account on whichever site you're installing for — these apps don't
  create accounts or bypass anything
- **A password on that account.** Google sign-in does not work inside these
  apps — Google blocks sign-in from embedded browsers, and there's no way
  around it from this side. If your account is Google-only, use "forgot
  password" on the site to set one first, then sign in with email and
  password on the TV.

---

## Installing

Sideloading is off by default on Fire TV. You need to turn it on once.

**1. Allow app installs**

Settings → My Fire TV → Developer Options → Install Unknown Apps → enable it
for whichever app you'll install from (Downloader, or your file manager).

If you don't see Developer Options: Settings → My Fire TV → About, then click
your device name seven times.

**2. Get the APK**

Download the latest release from the
[Releases](../../releases) page. Each release has two files:

- `fishtank-tv.apk`
- `mde-tv.apk`

**3. Install it**

Easiest route is the **[Downloader](https://www.amazon.com.au/AFTVnews-com-Downloader/dp/B01N0BP507?dplnkId=e9566ed7-f213-4ad2-89a4-d1566c14b1ee)**
app from the Amazon Appstore — enter the release URL, it downloads and prompts
to install.

Alternatives, if you'd rather: **[Apps2Fire](https://play.google.com/store/apps/details?id=mobi.koni.appstofiretv)**
pushes an APK from your Android phone over wifi, or `adb install fishtank-tv.apk`
if you already have adb set up.

**4. Launch and sign in**

The app appears in your apps row. Open it, sign in with email and password,
and you're done.

---

## Remote controls

| Button | Does |
|---|---|
| **Arrows** | Move the highlight |
| **Select** | Click the highlighted thing |
| **Back** | Return to the homepage (exits the app from there) |
| **Play/Pause** | Play or pause the video |
| **Fast-forward** | Seek forward 10s |
| **Rewind** | Seek back 10s |

---

## Updating

Download the newer APK and install it over the top. Your login is kept.

---

## Reporting problems

Open an issue. Useful to include: which app, which Fire TV model, and what
you were doing. If something on the site is unreachable with the remote, say
which thing — that usually means a selector needs adding.

---

## Notes

These apps display websites you already have accounts for. They don't
circumvent payment, DRM, or access controls, and they don't collect anything —
there's no analytics, no telemetry, and no server involved beyond the sites
themselves.
