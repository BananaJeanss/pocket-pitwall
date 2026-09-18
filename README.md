# Pocket Pitwall

**Offline Android motion telemetry for indoor karting.** Record a session with the screen locked, review phone movement afterward, mark laps and sectors, and compare saved sessions.

Built with **Kotlin, Jetpack Compose and Material 3**. Charcoal surfaces, lime highlights, cyan comparison traces. Android 8.0+ (API 26); linear-acceleration and gyroscope sensors required.

> [!IMPORTANT]
> **This app does not measure indoor track position or instantaneous speed.** A phone moving inside a pocket cannot reliably reconstruct those quantities from inertial sensors alone. Track sketches are user-drawn references. Lap suggestions are experimental pattern matches, not transponder timing. Average speed is calculated only when you enter a known lap length.

> [!NOTE]
> **AI-generated notice:** The initial source code, UI and documentation were generated with OpenAI Codex. This is an experimental prototype, not a professionally validated timing instrument. The 12 Java analysis checks, Android lint and debug APK assembly passed in [GitHub Actions run #2](https://github.com/BananaJeanss/pocket-pitwall/actions/runs/35318236279) on 18 September 2026. Physical-device recording and visual QA have **not yet been verified**. A successful build does not establish real-world timing accuracy.

## What it does

| Feature | Behavior |
| --- | --- |
| Pocket recording | Foreground service with visible notification, partial wake lock and a one-hour limit |
| Sensor capture | Requests 50 Hz linear acceleration, gyroscope and optional game rotation vector; actual rates depend on hardware |
| Session storage | Separate raw CSV and atomic JSON metadata for every session; no account or network permission |
| After-session timing | Add/remove SF, S2 and S3 markers at exact timestamps using the trace and scrubber |
| Lap suggestions | Find similar 4-second rotation patterns around a known finish crossing; review each candidate |
| Sector splits | One S2 and one S3 in order between two finish crossings produce three sector durations |
| Track reference | Sketch the circuit and place physical SF/S2/S3 pins independently of timing annotations |
| Comparisons | Lap/sector time differences and rotation overlays within or across sessions of the same direction |
| Speed | Average km/h from user-entered lap length divided by annotated lap duration; no live or peak speed |
| Exports | Lap CSV, metadata JSON, or ZIP containing both plus original sensor CSV |
| Recovery | Recover previously flushed sensor data after an interrupted recording; label it interrupted |

Normal and reverse sessions are labelled separately. “Motorcity · Underground” is an editable starting name, not an official track integration or a verified circuit map. No Motorcity branding, track map or affiliation is implied.

## Use

1. Open the app, name the session, and select the direction.
2. Start recording while stationary. Carry the phone only as permitted by the venue, secure it, and lock the screen. Do not interact with it while driving.
3. Stop from the app or recording notification when parked. The service also stops after one hour.
4. Open **Review**. Use an external reference such as track timing or synchronized video to identify crossings. Motion data alone does not tell you where the finish line is.
5. Set the timestamp and add **SF** at every start/finish crossing. Two successive SF markers define a complete lap. Leading/trailing partial laps are excluded.
6. Add **S2** at the start of sector 2 and **S3** at the start of sector 3 in each lap. Missing, duplicated or reversed sector markers leave sector values blank instead of guessing.
7. Optionally sketch a circuit and place its reference pins. These positions do **not** automatically generate crossing timestamps.
8. Compare laps, add notes/known lap length, and export. To correct a timing marker, remove it and add one at the corrected timestamp.

### Estimated crossing detection

Place the cursor at a known finish crossing at least two seconds into a recording. Enter an approximate lap time from 10 to 180 seconds, then select **Suggest crossings**.

The analyzer resamples gyroscope magnitude at 20 Hz and compares a ±2 second window using normalized correlation. It searches 80–120% of the expected lap time after each candidate, accepts a correlation of at least 0.85, rejects flat windows and gaps over 250 ms, and stops at the first unmatched lap. Search resolution is 0.1 seconds. This is a heuristic, **not a calibrated confidence score**.

The known anchor is not automatically added as a marker: add its SF manually. Accepted candidates remain labelled **estimated** in the UI and exports. Review individual candidates against an external timing reference. Similar corners, phone movement, traffic and changing pace can all cause false matches or missed laps. The detector does not infer sector crossings.

### Reading comparisons

Lime is the reference lap; cyan is the comparison lap. Both use normalized elapsed-time progress (0–100%), not measured distance. Traces are independently scaled to show shape. A matching point on this graph does not prove that the kart was at the same physical position. Sector deltas are comparison minus reference; negative means a shorter annotated duration.

## Build

Requirements: **JDK 17**, **Gradle 8.9**, Android SDK platform **35**, Android build tools, and network access to Google's/Maven/Gradle repositories. Versions are pinned in the Gradle files. No signing secrets are needed for a debug APK.

```sh
# With Gradle 8.9 installed:
gradle :core:check :app:lintDebug :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Open the root project in Android Studio and configure Gradle 8.9 locally, or generate a standard wrapper first:

```sh
gradle wrapper --gradle-version 8.9 --distribution-type bin
./gradlew :core:check :app:lintDebug :app:assembleDebug
```

A Gradle wrapper binary is not bundled in this source snapshot. CI installs the pinned Gradle version explicitly. `local.properties` (if used for `sdk.dir`) is ignored by Git.

### GitHub Actions

The included **Android build and checks** workflow tests the pure analysis module, runs Android lint, builds a debug APK, and uploads it as `pocket-pitwall-debug-apk`. Once the repository is published, open a successful workflow run under **Actions** to download the artifact. APKs signed with GitHub's temporary debug key are for testing; signing keys may change between runners, so upgrades may require uninstalling first. Export sessions before uninstalling. A stable signed release pipeline is not included.

### Analysis checks without Android SDK or Gradle

```sh
./scripts/test-core.sh
```

Checks cover complete/partial laps, sorting, missing/ambiguous sectors, estimate provenance, interpolation gaps, length-based average speed, synthetic repeated laps, stationary-data rejection and invalid detection settings. They validate analysis behavior, not real-world timing accuracy.

## Data formats

### `sensors.csv`

```csv
elapsed_s,sensor_type,x,y,z,w,accuracy
```

- `elapsed_s`: sensor monotonic timestamp minus recording origin, seconds. It is not wall-clock time.
- `sensor_type`: Android `10` = linear acceleration (m/s²), `4` = gyroscope (rad/s), `15` = game rotation vector (unitless).
- `x,y,z`: native device-coordinate values; no claim that device axes align with the kart.
- `w`: fourth component when supplied; otherwise zero. Relevant to rotation-vector records, not acceleration/gyro.
- `accuracy`: Android sensor accuracy status for the event, **not** a location accuracy estimate.

Events retain their own sensor timestamps; the CSV interleaves streams. Flush and filesystem sync are requested roughly once per second of sensor activity. A sudden process kill can still lose the most recent unflushed events. Corrupt/incomplete final lines are ignored by the trace reader.

### `laps.csv`

```csv
lap,start_s,end_s,lap_s,s1_s,s2_s,s3_s,estimated,average_kmh_from_user_length
```

Missing sectors and unknown speed are empty fields. `estimated=true` if any marker used for that lap or its valid sectors is estimated. `false` means manually annotated, **not officially measured**. No raw double integration is presented as speed.

### `session.json`

Schema version 1 includes session identity, UTC epoch creation time, title, direction, status, duration, user-entered length, sensor metadata, notes, timing markers, normalized sketch points, and reference pins. ZIP is a portable export for external analysis; re-import into the app is not implemented.

## Architecture

```text
app/   Kotlin Android UI, foreground recorder, file storage and document exports
core/  Pure Java timing, interpolation and candidate-matching algorithms
```

The Java core is intentionally Android-independent so it can be tested with only a JDK. Disk capture runs on a dedicated HandlerThread. Session loading/export uses background IO. Matching runs off the UI thread. The app has no backend, ads, analytics, location permissions, Internet permission or broad storage permission; exports use Android's document picker.

## Known limits and device validation

- No measured position, racing line, instantaneous speed, official timing integration, BLE/UWB positioning or automatic sector detection.
- Pocket movement is mixed with kart movement; acceleration magnitude is **not** longitudinal acceleration or braking force.
- Android vendors can still interrupt background recording. A foreground service/wake lock does not guarantee every sample.
- Phone sensor fusion quality varies; optional game rotation vectors have yaw drift and no global heading.
- Physical-device testing is required, especially screen-off behavior on Xiaomi/HyperOS and other battery-managed Android variants.
- Large sessions take time to load. Recording is capped at one hour; session count is limited by available storage. The app does not automatically delete old sessions.
- The service uses Android's `specialUse` foreground-service declaration. Any future Play Store submission needs appropriate policy review; none is claimed here.
- English UI only; no backup/restore import or release signing pipeline yet.

Before relying on a recording, test sensor availability, notification-denied behavior, lock-screen capture, rotation/relaunch, stop-from-notification, interrupted recovery, low storage, and exports on the actual phone. Compare annotated times against independent timing. Confirm APK build/lint and inspect real device layouts before calling this release-ready.

## References

- [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
- [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

## License

MIT. See [LICENSE](LICENSE).
