# Video Downloader Browser (Android)

A lightweight Android web browser that detects videos on the page you're viewing
and lets you download them with one tap.

## Features

- **Full browser** — address/search bar, back/forward/refresh, swipe-to-refresh,
  cookies, JavaScript, and zoom, all built on Android's `WebView`.
- **Automatic video detection** — two complementary strategies:
  1. **Network sniffing**: every request the page makes is inspected
     (`WebViewClient.shouldInterceptRequest`); anything that looks like media
     (`.mp4`, `.webm`, `.m3u8`, `.mpd`, `videoplayback`, …) is captured.
  2. **DOM scanning**: after each page loads, injected JavaScript reports the
     `src` of every `<video>` and `<source>` element back to the app.
- **One-tap downloads** — a badge on the download button shows how many videos
  were found. Tapping it opens a sheet; pick one and it downloads through the
  system **DownloadManager** (with the page's cookies, `Referer`, and
  user-agent so authenticated/gated media still works). Files land in the
  device's **Downloads** folder with a completion notification.
- Handles page-initiated downloads via `WebView.setDownloadListener` too.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/vdbrowser/app/
│   ├── MainActivity.kt     # browser UI, WebView wiring, JS bridge
│   ├── MediaSniffer.kt     # collects & de-dupes candidate media URLs
│   ├── MediaItem.kt        # one detected resource
│   ├── DownloadHelper.kt   # hands a file to DownloadManager
│   └── MediaAdapter.kt     # list rows in the downloads sheet
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
