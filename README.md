# Realtime Touch Imitation

A single-touch Android macro recorder and replay project based on Android Accessibility Services.

## Current capabilities

- Capture single-pointer tap, long-press and swipe gestures using a full-screen overlay.
- Store gesture coordinates and timing, then replay them through Android AccessibilityService gesture dispatch.
- Edit and replay saved macros, including loops and per-step timing.
- Floating recording controls and a persistent foreground notification.
- No root required for the current overlay-capture and accessibility-playback implementation.

## Important Android limitation

The current capture mode uses a touchable full-screen overlay. The overlay receives and records touch events, but those events are **not passed through** to the app underneath. Standard third-party Android apps cannot transparently observe every raw touchscreen event while also forwarding the original event stream to another app. Accessibility event callbacks report semantic UI changes rather than a complete raw coordinate stream. Do not interpret this mode as background/pass-through recording.

The recorder is intentionally single-touch: it records one pointer path and does not create a second independent touch stream.

## Latency work

- No overlay redraw during ACTION_MOVE; redraw only after ACTION_UP/ACTION_CANCEL.
- Removed notification updates and per-gesture log writes from the touch callback.
- Gesture duration uses MotionEvent.eventTime (monotonic event timestamps) instead of wall-clock time.
- Macro replay remains asynchronous through the accessibility service.

These changes reduce app-side work during capture. Actual input latency still depends on Android's input dispatcher, device load, refresh rate and overlay implementation.

## Requirements

- Android 8.0+ (API 26+)
- JDK 17
- Gradle 8.9
- Android SDK Platform 35 and Build Tools 35.0.0

## Build locally

With Gradle 8.9, JDK 17 and Android SDK installed:

```bash
gradle --no-daemon :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Release APK (unsigned): `app/build/outputs/apk/release/app-release-unsigned.apk`

This repository does not commit a signing keystore. Sign release builds with your own private keystore outside the repository.

## Build on GitHub

Every push to `main` and every pull request targeting `main` runs the Android CI workflow. Debug and unsigned release APKs are uploaded as a workflow artifact.

## Permissions

- Accessibility Service: replay gestures.
- Display over other apps: floating controls and touch-capture overlay.
- Foreground service and notifications: keep recording controls visible while the app is in use.

## License

See the original project license and attribution requirements. This repository is a source-code copy/derivative of AndroTask; retain its MIT license notice when redistributing.
