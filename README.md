# Video Downloader Browser (Android)

A lightweight Android web browser that detects videos on the page you're viewing
and lets you download them with one tap.

## Features

- **Full browser** — address/search bar, back/forward/refresh, swipe-to-refresh,
  cookies, JavaScript, and zoom, all built on Android's `WebView`.
- **Tabs (Chrome-style)** — a tab-count button opens a tab switcher to create,
  switch, and close tabs; each tab keeps its own history, scroll position and
  detected-media list. Links that open a new window (`target=_blank` /
  `window.open`) spawn a new tab. On **large screens (tablets/foldables, sw600dp)**
  a desktop-style horizontal tab strip is shown on top instead.
- **Automatic video detection** — two complementary strategies:
  1. **Network sniffing**: every request the page makes is inspected
     (`WebViewClient.shouldInterceptRequest`); anything that looks like media
     (`.mp4`, `.webm`, `.m3u8`, `.mpd`, `videoplayback`, …) is captured.
  2. **DOM scanning**: after each page loads, injected JavaScript reports the
     `src` of every `<video>` and `<source>` element back to the app.
- **One-tap downloads** — a badge on the download button shows how many videos
  were found. Tapping it opens a sheet that lists each video **with its file
  size** (resolved via a HEAD / ranged-GET request carrying the page's
  credentials); pick one and it downloads with the page's cookies, `Referer`,
  and user-agent so authenticated/gated media still works. Files land in the
  device's **Downloads** folder.
- **Fast multi-connection downloads** — a custom engine splits each file into up
  to 6 byte-range segments fetched **in parallel** (when the server supports
  HTTP ranges) and writes them into one file via positional channel writes,
  which is typically much faster than a single stream. It runs in a foreground
  service so downloads continue in the background, with a progress notification.
- **HLS/DASH → MP4** — adaptive streams (`.m3u8` / `.mpd`) are remuxed into a
  single `.mp4` with a bundled FFmpeg (the maintained `ffmpeg-kit-https` fork).
  FFmpeg fetches the playlist and segments itself (decrypting AES-128 when
  keyed) and copies streams without re-encoding, so it's fast and lossless.
- **Download progress dialog** — the overflow (⋮) menu → *Downloads in progress*
  shows every download this session with a live progress bar, **transfer speed**,
  and byte counter, plus **pause / resume / cancel** controls. Pause/resume works
  per segment (each resumes from where it stopped) for range-capable servers.
- **Download history** — overflow → *Download history* lists finished downloads
  (state, size, time) persistently across restarts, with a Clear action.
- **Ad/tracker blocker** — requests to a curated list of ad and analytics hosts
  are dropped in `shouldInterceptRequest`. Toggle it from the overflow menu;
  the choice is remembered.
- **Bookmarks** — bookmark the current page from the overflow menu and reopen
  saved pages from the bookmarks sheet; stored persistently on the device.
- Handles page-initiated downloads via `WebView.setDownloadListener` too.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/vdbrowser/app/
│   ├── MainActivity.kt              # browser UI, tabs, WebView wiring, menus
│   ├── Tab.kt                      # one tab: WebView + its own media sniffer
│   ├── TabAdapter.kt               # rows in the tab switcher
│   ├── MediaSniffer.kt             # collects & de-dupes candidate media URLs
│   ├── MediaItem.kt                # one detected resource (+ resolved size)
│   ├── SizeFetcher.kt              # resolves remote content length off-thread
│   ├── AdBlocker.kt                # host-based ad/tracker blocklist
│   ├── DownloadHelper.kt           # builds a request and starts the service
│   ├── DownloadEngine.kt           # multi-connection (parallel range) downloader
│   ├── DownloadService.kt          # foreground service + progress notification
│   ├── Downloads.kt                # in-memory progress registry (+ speed)
│   ├── DownloadProgressAdapter.kt  # rows in the progress dialog
│   ├── MediaAdapter.kt             # rows in the detected-media sheet
│   └── Util.kt                     # byte-size formatting
└── res/                    # layouts, drawables, strings, theme, icon
```

## Build & run

Requires Android Studio (Giraffe+) or the Android SDK with `ANDROID_HOME` set.

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # build + install on a connected device/emulator
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

- **minSdk 24** (Android 7.0) · **targetSdk / compileSdk 34** · Kotlin · ViewBinding.

## Notes & limitations

- **HLS/DASH streams** (`.m3u8` / `.mpd`) are detected and can be downloaded, but
  they are *playlists* that reference many segments. A raw download grabs the
  playlist, not a single playable file — joining segments needs an FFmpeg/HLS
  step that is intentionally out of scope for this minimal app.
- **`blob:` URLs** (common on some streaming sites) cannot be fetched by
  `DownloadManager` and are skipped.
- Use responsibly: only download content you have the right to save, and respect
  each site's terms of service and copyright.

## Possible next steps

- Bundle an FFmpeg library to merge HLS segments into a single MP4.
- Add a downloads history screen and pause/resume.
- Tabbed browsing, bookmarks, and an ad/tracker blocker.
```
