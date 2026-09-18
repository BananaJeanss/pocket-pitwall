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
2. Push the version bump to `main`.
3. The **Publish signed release** workflow runs core tests and release lint, builds and verifies the signed APK, creates the matching `vMAJOR.MINOR.PATCH` tag, then publishes the GitHub Release automatically.
4. If that version tag already exists, an automatic run skips publishing instead of overwriting it. The workflow can also be manually dispatched for an existing tag when recovery is needed.

Automatic update checks use GitHub's latest stable release, compare semantic version numbers, and require an APK asset. Prereleases are ignored. Checks are limited to once per day on launch unless manually requested. The download action opens GitHub; installation is always controlled by Android and the user. No self-install permissions or background installer are used.

## Prototype migration

The old 0.1/0.2 debug APKs and CI debug artifacts are not production releases. CI runners use disposable debug signing keys, so APKs from different runs can fail Android's signature check and cannot reliably update one another in place.

Before uninstalling a debug build, export every session as a ZIP. Stable releases can import those ZIP backups from the Sessions screen, restoring session metadata and raw sensor telemetry. Then uninstall the debug build, install the signed release, and import the backups.

Once a stable release is installed, later releases signed with the same release key and carrying a higher version code update in place and keep private session data automatically.

CI debug APK artifacts remain available for development testing. They are not advertised by the in-app stable update checker.
