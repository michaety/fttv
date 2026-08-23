# fttv

Fire TV apps for **fishtank.live** and **mde.tv**.

Both sites work fine in a browser and badly on a television. These are two small
Android TV apps that load each site fullscreen, strip out the parts that only
make sense with a mouse, and let you drive the rest from the remote.

Unofficial. Not affiliated with, endorsed by, or supported by either site.

---

## What you get

Two separate apps, each with its own icon on the Fire TV home row:

| | |
|---|---|
| **Fishtank TV** | fishtank.live |
| **MDE TV** | mde.tv |

Install one or both — they're independent.

### What they do

- **Remote navigation.** Arrow keys move a highlight around the page, Select
  activates it. No mouse, no phone app, no Bluetooth keyboard.
- **TV-appropriate layout.** The desktop site, not the phone one, so it's
  built for a wide screen.
- **Clutter removed.** Chat panels, nav bars, audio players and purchase
  prompts are hidden — they're unusable from a couch and they slow the page
  down.
- **Stays logged in.** Sign in once; the session survives restarts.
- **Zoom.** Fast-forward and rewind adjust page zoom if things are too small
  or too large on your screen.

### What they don't do

These are websites in a wrapper, not native TV apps. Expect some rough edges:

- Navigation is approximate. It moves to the nearest thing in the direction
  you pressed, which isn't always the thing you meant.
- The Firestick is running a full desktop web app. It is not fast.
- Video player controls are the site's own, and can be awkward to reach.
- Anything the site changes on their end may break something here.

---

## Requirements

- A Fire TV Stick or Fire TV device
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

Easiest route is the **Downloader** app from the Amazon Appstore — enter the
release URL, it downloads and prompts to install.

Alternatives, if you'd rather: **Apps2Fire** pushes an APK from your Android
phone over wifi, or `adb install fishtank-tv.apk` if you already have adb set
up.

**4. Launch and sign in**

The app appears in your apps row. Open it, sign in with email and password,
and you're done.

---

## Remote controls

| Button | Does |
|---|---|
| **Arrows** | Move the highlight |
| **Select** | Click the highlighted thing |
| **Back** | Go back a page, then exit |
| **Play/Pause** | Play or pause the video |
| **Fast-forward** | Zoom in |
| **Rewind** | Zoom out |

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
