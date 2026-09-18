# Pocket Pitwall

Android motion telemetry for indoor karting. Record with the screen locked, annotate laps afterward, compare sessions and export your data.

**Kotlin · Jetpack Compose · Material 3 · Android 8+**

> [!IMPORTANT]
> Pocket sensors do not reliably measure indoor track position or instantaneous speed. Lap suggestions are estimates; track sketches are references. Average speed requires a user-entered lap length.

> [!NOTE]
> **AI-generated software:** The initial app and subsequent changes were generated with OpenAI Codex. Build and emulator checks do not validate real-world timing accuracy or guarantee screen-off capture on every phone. See the latest [CI results](https://github.com/BananaJeanss/pocket-pitwall/actions).

## Install

- **Stable APKs:** [GitHub Releases](https://github.com/BananaJeanss/pocket-pitwall/releases). Publishing requires the repository owner to configure the private signing secrets; see [release setup](docs/releases.md).
- **Development APKs:** Open a successful **Android build and checks** run under Actions and download `pocket-pitwall-debug-apk`. Debug builds may use different signing keys between runners. Export before uninstalling; import is not yet supported.

The app checks for stable releases in Settings and, if enabled, once per day on launch. It opens the release page for you to download and install. It never silently installs software.

## Screens

| Screen | Controls |
| --- | --- |
| Record | Track, direction, start/stop, recording timer |
| Sessions | Saved sessions and review |
| Laps | Annotated lap and sector times |
| Timeline | Motion graphs, exact timestamp markers, estimated finish matching |
| Compare | Lap and sector deltas; rotation overlays within/across sessions |
| Details | Notes, lap length, reference sketch, export, delete |
| Settings | System/light/dark theme, wallpaper colors, fullscreen, recording defaults, updates |

System Back and toolbar Up return through the app. Help is available from the toolbar. Android 13+ supports monochrome themed launcher icons; wallpaper-derived UI colors require Android 12+.

## Record and review

1. Name the track and choose Normal or Reverse. Start while parked, secure the phone as permitted by the venue, then lock the screen. Recording stops after one hour.
2. Stop when parked and open the saved session.
3. In **Timeline**, use track timing or synchronized video to identify each finish crossing. Add **SF** at each timestamp. Two SF markers define a complete lap.
4. Add **S2** at the start of sector 2 and **S3** at the start of sector 3. Missing, duplicated or reversed sector markers leave splits blank.
5. Compare laps and export from **Details**. Correct a marker by removing it and adding the corrected timestamp.

A reference sketch does not determine crossing times. Comparisons align normalized elapsed lap time, not physical position; graph traces have independent scales. Negative comparison-minus-reference deltas mean a shorter annotated duration.

### Estimated crossings

Select a known finish crossing at least two seconds into the recording and enter an expected lap time from 10–180 seconds. Matching compares a four-second gyroscope-magnitude window at 20 Hz, searching ±20% around the expected lap duration with 0.1-second resolution. It requires correlation ≥0.85, rejects flat windows and gaps over 250 ms, and stops at the first unmatched lap.

Add the known anchor SF yourself. Review each suggestion; accepted markers stay labelled estimated. Similar corners, traffic and pocket movement can produce false matches. The score is not a calibrated confidence level. Sectors are not automatically detected.

## Privacy and storage

Recordings stay in private app storage. No account, analytics, ads, location permission or backend. Sensor capture works offline. The Internet permission is used only for GitHub release checks; GitHub receives ordinary request metadata, including the app version, but no session data. Automatic checks can be disabled. External links open in your browser.

Sessions use raw CSV plus atomic JSON metadata. Pending edits use an application-lifetime ordered queue so Activity recreation does not cancel writes. This does not guarantee survival of a sudden process kill before data reaches disk. Android backup is disabled. Exports use the system document picker.

## Exports

| File | Contents |
| --- | --- |
| `sensors.csv` | `elapsed_s,sensor_type,x,y,z,w,accuracy` |
| `laps.csv` | `lap,start_s,end_s,lap_s,s1_s,s2_s,s3_s,estimated,average_kmh_from_user_length` |
| `session.json` | Schema version, identity, creation time, title, direction, status, duration, length, sensors, notes, timing markers, sketch and pins |
| ZIP | All three files |

Sensor type `10` is linear acceleration in m/s², `4` is gyroscope in rad/s, and optional `15` is a unitless game rotation vector. Coordinates are phone axes, not calibrated kart axes. Timestamps are seconds from a monotonic recording origin. `w` is the fourth component when supplied, otherwise zero. `accuracy` is Android sensor status, not position accuracy.

Capture requests 50 Hz; actual rates depend on hardware. Flush and sync are requested about once per second of sensor activity. Incomplete final rows are ignored. Unknown sectors/speed export as empty fields. `estimated=false` means manually annotated, not officially measured. JSON/ZIP re-import is not yet implemented.

## Build and test

Requirements: JDK 17, Gradle 8.9, Android SDK platform 35, build tools 35.0.0 and dependency network access.

```sh
gradle :core:check :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. A wrapper binary is not included; with Gradle installed, generate one using `gradle wrapper --gradle-version 8.9`.

```sh
# Pure Java checks without Android SDK or Gradle:
./scripts/test-core.sh

# With an Android emulator/device connected:
gradle :app:connectedDebugAndroidTest
```

CI runs the analysis/version checks, Android lint, debug build and Android 15 emulator navigation tests. It saves test reports and screenshots as artifacts. Release CI separately tests, lints, signs, verifies and publishes versioned APKs with SHA-256 checksums. [Signing and publishing instructions](docs/releases.md).

## Known limits

- Requires linear acceleration and gyroscope sensors. Pocket/body motion is mixed with kart motion.
- No measured racing line, instantaneous speed, official timing integration or automatic sector detection.
- No import/restore yet. Exported data can be analyzed externally.
- Vendor battery management can interrupt capture; real screen-off testing is still required, especially on Xiaomi/HyperOS.
- Game rotation-vector heading drifts. No global heading is inferred.
- One-hour recording limit; stored sessions consume device space and are never automatically deleted.
- English UI. Physical-device timing accuracy and broad accessibility/device coverage remain unverified.
- Motorcity is an editable default name, not an affiliation or official integration.

## Project layout

- `app/`: Compose screens, foreground recorder, storage queue, preferences and update checking.
- `core/`: Android-independent Java timing, interpolation, matching and version comparison.
- `.github/workflows/`: development checks and signed releases.

[Open issues](https://github.com/BananaJeanss/pocket-pitwall/issues) · [MIT licence](LICENSE)
