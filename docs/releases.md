# Signed Android releases

The release workflow publishes an APK and `SHA256SUMS.txt` to GitHub Releases after core tests, release lint and signed APK assembly succeed. It refuses missing credentials and never substitutes a disposable debug key.

## One-time signing setup

Create a private signing key on a trusted computer with JDK 17. The following prompts for passwords without putting them in shell history:

```sh
keytool -genkeypair -v -keystore pitwall-release.jks -alias pitwall -keyalg RSA -keysize 3072 -validity 10000
```

Back up the keystore and its passwords privately. Never commit them. Loss of the key prevents in-place updates to installed releases.

In the repository's **Settings → Secrets and variables → Actions**, configure:

| Secret | Value |
| --- | --- |
| `PITWALL_KEYSTORE_BASE64` | Base64-encoded keystore, on one line |
| `PITWALL_STORE_PASSWORD` | Keystore password |
| `PITWALL_KEY_ALIAS` | `pitwall` if using the command above |
| `PITWALL_KEY_PASSWORD` | Key password (often the same as the store password) |

Keep these as secrets, not repository variables. The workflow writes the key only to the temporary runner and removes it even after failures. It does not upload the key as an artifact.

## Publish

1. Increment both `versionCode` and `versionName` in `app/build.gradle.kts`; never reuse a version code.
2. Push to `main` and wait for the build and emulator checks to pass.
3. Tag that tested commit with the matching stable version, such as `v0.2.0`, and push the tag.
4. The **Publish signed release** workflow signs and publishes the APK. It can also be manually dispatched for an existing tag. Existing releases are not overwritten.

Automatic update checks use GitHub's latest stable release, compare semantic version numbers, and require an APK asset. Prereleases are ignored. Checks are limited to once per day on launch unless manually requested. The download action opens GitHub; installation is always controlled by Android and the user. No self-install permissions or background installer are used.

## Prototype migration

The old 0.1 debug APK and CI debug artifacts are not production releases. A new signing key usually cannot update them in place. Export sessions before uninstalling a debug build. Import/restore is not yet implemented, so exported data remains available for external analysis, not automatic restoration into the app. Once installed from a stable release key, later releases using the same key and a higher version code can update in place.

CI debug APK artifacts remain available for development testing. They are not advertised by the in-app stable update checker.
