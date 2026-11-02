# TurnBack

Android app that tells you **when to start walking back** so you reach a place by a set time.

Main screen has three cards — **Destination** (search box that also takes coordinates / Maps links, *I'm here*, *Pick on map*), **Be back by** (tap the time; −/+ sets how early to arrive) — and a **Start tracking** button; *Map & route* and *Navigate* sit below. Walking speed, street routing, the Google key, battery settings and tips live under the ⚙ Settings screen. While tracking, a status card at the top turns green / amber / red as the leave time approaches.

You can also share a place from Google Maps straight into TurnBack. Then tap **Start**. A foreground service watches your GPS, estimates walking time back and alerts you:

- **10 min before** you need to leave — "Head back soon"
- **When it's time to leave** — alarm-style alert
- **Every 5 min after that** if you're not getting closer (with "late by X min")
- Stops automatically when you arrive (within ~75 m), or 3 h after the deadline

## Place search

Search bar on the main screen: **Google Places Autocomplete (New)** if a key is set, else **OpenStreetMap Nominatim**. Results are biased to your current location.

Google flow is free-tier friendly: autocomplete runs in a session that ends with one Place Details call requesting only `location` (Autocomplete Session Usage: unlimited free; Place Details Essentials: 10,000 free/month). Enable *Places API (New)* on the key.

## Map & navigation

- **Map & route** screen: live position, destination marker, walking route (Google polyline if a key is set, else OSRM; dashed straight line if offline), distance/ETA/leave-by. Auto-refreshes after moving 150 m.
- **Navigate** hands off to Google Maps walking navigation (`google.navigation:q=…&mode=w`), falling back to the Maps web URL.
- Ongoing notification has **Stop / Map / Navigate** and is phone-only; head-back alerts have **Navigate / Map** and are mirrored to a paired watch (Wear OS / Galaxy Watch).

## Walking-time estimate

`ETA = route distance ÷ walking speed` (speed configurable, default 4.5 km/h).
Routing is refreshed every 3 min or 150 m, in this order:

1. **Google Routes API** (`computeRoutes`, `travelMode: WALK`) if a key is entered in the app. Google's duration is used and scaled to your speed setting (`× 5.0 / your km/h`).
2. **OSRM foot** at `routing.openstreetmap.de` (no key) — route distance ÷ your speed.
3. **Straight line × 1.3** when offline.

### Google key setup

1. Google Cloud Console → new project → attach billing (Maps Platform requires it; 10k Compute Routes Essentials calls/month free).
2. Enable **Routes API** → Credentials → Create API key.
3. Restrict key: *Android apps* → package `si.trax.turnback` + SHA-1 shown in the app's Settings screen; *API restrictions* → Routes API + Places API (New).
4. Quotas → cap requests/day (e.g. 500).
5. Paste key in Settings → **Test key**.

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
