# Maximus VPN

Native Android VPN application. The installable APK is published under [GitHub Releases](https://github.com/drfxai/MaximusVPN/releases) after a signed release build succeeds. Do not install an APK from an untrusted mirror.

## Install

1. Open the latest release and download `app-release.apk` and `app-release.apk.sha256`.
2. Compare the APK SHA-256 digest with the checksum file. On a computer, run `sha256sum app-release.apk`.
3. Transfer the APK to your Android device, open it, and follow Android's prompt to allow installation from that source. Grant VPN permission when the app requests it.

If no APK is listed in Releases, no installable production build has been published yet. Android may require uninstalling an older copy if it was signed with a different certificate; uninstalling can erase local app data.

## Build checks

Pull requests and changes to `main` run Android CI, including unit tests and a debug APK build. A green debug build does not validate routing or leak behavior on a physical device. Release builds also run release lint and verify the APK signature.

## Publish a signed release (repository maintainers)

Use a stable signing keystore. In repository Settings → Secrets and variables → Actions, set:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 encoding of the existing release/upload JKS file, without line breaks |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_PASSWORD` | Password of the key with alias `upload` |

Never commit the JKS file or passwords. Back up the original signing key securely: users cannot update an installed APK if a future release uses a different signing certificate.

After CI passes on `main`, run **Actions → Signed Android release → Run workflow** with a fresh version tag such as `v1.0.1`. The workflow builds, tests, checks the signature, and uploads the APK and checksum to Releases. The workflow fails without valid signing credentials. Increase `versionCode` and `versionName` in `app/build.gradle.kts` for each app update before publishing.

## Security status

The VPN uses Android `VpnService` and a Kotlin tunnel path. Configuration compatibility with Xray and Mihomo does not by itself mean their native engines are embedded. The secret chat implementation has not completed authenticated key exchange or peer transport and should not be relied upon for private messaging. Validate DNS, IPv6, failover, and lockdown behavior on real Android devices before presenting a release as production secure.
