# HTML Video Recorder — App Spec (Pack 0, for Puchu)

## 1. What it is
An Android app that turns a self-contained HTML animation file into an MP4 video —
the same job as claude-motion.com/html-to-video, but running on the owner's phone.
One job only: HTML in → MP4 out. Nothing else.

## 2. The one decision that matters — REAL-TIME, not frame-by-frame
The website is slow because it draws the video frame by frame (3,935 frames for a
131-second video ≈ 30 minutes). An app doing the same frame-by-frame work on the
phone would be just as slow — the old Animation Studio app proved exactly that.
So this app does NOT render frame by frame. It plays the HTML once, at normal
speed, and records the screen while it plays (Android MediaProjection). A 131s
video takes ~135 seconds total. That is the whole point of the app.

## 3. Flow (3 screens, nothing extra)
1. **Pick file** — choose an .html file from the phone (file picker). Show file
   name + size. A "recent files" row remembers the last 5 files.
2. **Options** — the same options as the website (the owner asked for these):
   - Shape: 9:16 / 16:9 / 1:1
   - Size: 720p / 1080p (720p = 720×1280 for 9:16; 1080p = 1080×1920)
   - Format: MP4 (only MP4 in v1)
   - Frame rate: 30 fps (fixed in v1; 24/60 later if wanted)
   - Length (seconds): auto-filled from the HTML file's own duration when the
     file declares it; editable by hand. Default 131.16-style decimal allowed.
   - Big RECORD button.
3. **Recording** — full-screen playback of the HTML + a thin progress bar with
   elapsed / total seconds + a CANCEL button. When the length ends, recording
   stops by itself. Then a DONE screen: file name, size, duration, resolution,
   buttons [Play] [Share] [Record again]. The MP4 is saved in the phone's
   public Downloads folder so it shows in Gallery and the file manager at once.

## 4. Recording engine (the hard part — get this exactly right)
- WebView sized EXACTLY to the target resolution (720×1280 for 9:16 720p).
  The old Animation Studio bug: its WebView used the default ~980px-wide
  viewport, so the 720px stage rendered small with black bars on the right and
  bottom. NEVER repeat that — the stage must fill the frame edge to edge.
  Force the viewport: `setUseWideViewPort(false)`, initial scale so the page
  width equals the WebView width, no scrollbars, no zoom.
- JavaScript ON (the HTMLs are CSS/WAAPI animations, no timers needed).
- MediaProjection screen recording at 30 fps, video-only (the HTMLs are silent;
  narration is merged separately). No microphone.
- Recording starts AFTER the page has loaded and its first frame is painted;
  the timer starts with the playback so the video length matches the Length
  setting exactly (no extra black seconds at the start or end).
- While recording, keep the screen awake (wake lock) and show a persistent
  notification "Recording… tap to stop". If the user leaves the app, recording
  keeps going; if the phone sleeps, recording must NOT stop.
- On Cancel: stop cleanly, delete the partial file, back to Options.

## 5. Output
- MP4 (H.264 + no audio track, or silent AAC track — whichever the encoder
  needs), exactly the chosen resolution and frame rate, duration = Length ±0.5s.
- File name: `<html-name>-<yyyyMMdd-HHmm>.mp4` in Downloads.
- After saving, trigger a media scan so Gallery shows it immediately.

## 6. Build — official way only
- Gradle + Android SDK with proper dependency management. GitHub Actions builds
  the APK (the planned route — local Gradle is blocked by the sandbox).
- NEVER hand-assemble a third-party SDK closure (the manual aapt2/d8 pipeline
  crashed the AI Video app twice). No AdMob, no third-party SDKs at all in v1.
- New keystore for this app (one keystore per app).
- Min SDK 26 (Android 8), target SDK 34. Kotlin. No internet permission needed
  in v1 (everything is local) — do NOT add it.
- Repo: `ToolBari/Video-recorder` (created by owner 27 Nov 01:49). Package name:
  `com.toolbari.videorecorder`. App name: "Video Recorder".

## 7. Acceptance tests (owner phone-tests these — the app ships only when ALL pass)
1. Open the Short #5 HTML (131.16s, 9:16, 720p): output is EXACTLY 720×1280,
   30 fps, duration 131.16s ±0.5s.
2. No letterbox: the video fills the whole 720×1280 frame, no black bars on
   any side (check the first frame and a mid frame).
3. The whole 131s records in ~135s wall-clock (not 30 minutes).
4. The MP4 appears in Downloads AND in Gallery without moving any file.
5. Cancel mid-record deletes the partial file and returns to Options.
6. A 16:9 HTML records as 1280×720 with no crop.
7. App does not ask for any login, account, or internet.

## 8. Out of scope for v1 (do NOT build these now)
- Frame-by-frame rendering, GIF/WebM output, 60 fps, 4K, audio mixing,
  narration merge, editing, watermarks, ads, accounts, cloud upload.
