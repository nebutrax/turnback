# TurnBack

Android app that tells you **when to start walking back** so you reach a place by a set time.

Set a destination (current spot, map pick, pasted coordinates, or share a place from Google Maps), pick the "back by" time and a buffer, tap **Start**. A foreground service watches your GPS, estimates walking time back and alerts you:

- **10 min before** you need to leave — "Head back soon"
- **When it's time to leave** — alarm-style alert
- **Every 5 min after that** if you're not getting closer (with "late by X min")
- Stops automatically when you arrive (within ~75 m), or 3 h after the deadline

## Map & navigation

- **Map & route** screen: live position, destination marker, walking route (Google polyline if a key is set, else OSRM; dashed straight line if offline), distance/ETA/leave-by. Auto-refreshes after moving 150 m.
- **Navigate** hands off to Google Maps walking navigation (`google.navigation:q=…&mode=w`), falling back to the Maps web URL.
- Ongoing notification has **Stop / Map / Navigate**; head-back alerts have **Navigate / Map**.

## Walking-time estimate

`ETA = route distance ÷ walking speed` (speed configurable, default 4.5 km/h).
Routing is refreshed every 3 min or 150 m, in this order:

1. **Google Routes API** (`computeRoutes`, `travelMode: WALK`) if a key is entered in the app. Google's duration is used and scaled to your speed setting (`× 5.0 / your km/h`).
2. **OSRM foot** at `routing.openstreetmap.de` (no key) — route distance ÷ your speed.
3. **Straight line × 1.3** when offline.

### Google key setup

1. Google Cloud Console → new project → attach billing (Maps Platform requires it; 10k Compute Routes Essentials calls/month free).
2. Enable **Routes API** → Credentials → Create API key.
3. Restrict key: *Android apps* → package `si.trax.turnback` + SHA-1 shown at the bottom of the app's settings; *API restrictions* → Routes API only.
4. Quotas → cap requests/day (e.g. 500).
5. Paste key in the app → **Test**.

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
