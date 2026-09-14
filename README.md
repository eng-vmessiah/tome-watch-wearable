# tome-watch-wearable

Galaxy Watch Ultra companion app — **gyro wrist-flick page turner** for [Tome](https://github.com/nmessias/tome).

Uses the raw gyroscope (100% public API) — no Samsung Universal Gestures, no One UI deps.

## Gestures

| Gesture | Action |
|---|---|
| 1× flick ↓ | scroll-down |
| 1× flick ↑ | scroll-up |
| 2× flick ↓ (fast, <1s) | next page/chapter |
| 2× flick ↑ | previous |

Mapping is inverted via in-app settings (long-press main screen).

## Build

```bash
export ANDROID_HOME=~/android-sdk
gradle assembleDebug   # wear OS minSdk 30, target 34
```

## Install on watch

1. Watch: Settings → Developer options → ADB debugging + Wireless debugging ON
2. `adb pair <watch-ip>:<pair-port> <code>` then `adb connect <watch-ip>:<debug-port>`
3. `adb install app-debug.apk`

## Config

Server + token editable in-app (long-press → settings cards). Companion POSTs to
`<server>/api/watch/:token` of [tome-feature-watch](https://github.com/eng-vmessiah/tome-feature-watch).

## Reader-side (cell phone/Kindle)

Needs a WS client at `/ws/watch/:token?role=reader` that reacts to `{action}`.
A test reader is bundled with the plugin (`public/reader.html`).
