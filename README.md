# TurnBack

Android app that tells you **when to start walking back** so you reach a place by a set time.

Set a destination (current spot, map pick, pasted coordinates, or share a place from Google Maps), pick the "back by" time and a buffer, tap **Start**. A foreground service watches your GPS, estimates walking time back and alerts you:

- **10 min before** you need to leave — "Head back soon"
- **When it's time to leave** — alarm-style alert
- **Every 5 min after that** if you're not getting closer (with "late by X min")
- Stops automatically when you arrive (within ~75 m), or 3 h after the deadline

## Walking-time estimate

`ETA = route distance ÷ walking speed` (speed configurable, default 4.5 km/h).
Route distance comes from the public OSRM foot router at `routing.openstreetmap.de` (refreshed every 3 min or 150 m), falling back to straight-line × 1.3 when offline.

Leave time = deadline − ETA − buffer.

## Build

Pushed commits to `main` are built by GitHub Actions (`.github/workflows/build.yml`) into a signed release APK, attached to a GitHub Release (`build-N`) and as a workflow artifact.

Signing uses repo secrets `KEYSTORE_B64` (base64 PKCS12, alias `turnback`) and `KEYSTORE_PASSWORD`. Keep the keystore safe — updates must be signed with the same key.

Local build: Android Studio, or `gradle assembleRelease` (Gradle 8.11, JDK 17, Android SDK 35).

## Notes

- minSdk 26 (Android 8), targetSdk 35
- No Google Play Services; uses `LocationManager` (GPS + network)
- Map: osmdroid with OpenStreetMap tiles
- Some phones (Xiaomi, Huawei, Samsung "deep sleep") kill background apps: exclude TurnBack from battery optimization.
