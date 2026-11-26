# Video Recorder

HTML file in → MP4 out, on the phone. REAL-TIME recording, not
frame-by-frame: a 131-second video takes about 135 seconds.

## How it works

Pick an HTML animation file → choose shape (9:16 / 16:9 / 1:1), size
(720p / 1080p) and length (auto-filled from the file, editable) → tap
RECORD → the app plays the HTML once at normal speed and records the
screen while it plays → the MP4 lands in Downloads and shows in Gallery.

The recording runs on a virtual display at the exact target resolution
(e.g. 720×1280), so the video fills the frame edge to edge — no
letterbox, no distortion. A foreground service keeps it recording even
if the phone sleeps.

## Build

No Gradle wrapper files are shipped — GitHub Actions provides Gradle.
Push to `main` and the **Android CI** workflow builds the debug APK;
download it from the run's `app-debug` artifact. No release keystore is
created here — create one before a release build.

## Notes

- When you tap RECORD, Android shows its system screen-capture consent
  dialog once. That is a system dialog, not a login, account, or
  internet request — the app has no internet permission at all.
- Video only (no audio track); narration is merged separately.
- Min SDK 26, target SDK 34. Kotlin + Jetpack Compose. No third-party
  SDKs, no ads, no accounts, no cloud.

## Acceptance tests (phone-test these)

1. Short #5 HTML (131.16s, 9:16, 720p): output EXACTLY 720×1280, 30 fps,
   duration 131.16s ±0.5s.
2. No letterbox: the video fills the whole 720×1280 frame, no black bars.
3. The whole 131s records in ~135s wall-clock (not 30 minutes).
4. The MP4 appears in Downloads AND in Gallery without moving any file.
5. Cancel mid-record deletes the partial file and returns to Options.
6. A 16:9 HTML records as 1280×720 with no crop.
7. App asks for no login, account, or internet.
