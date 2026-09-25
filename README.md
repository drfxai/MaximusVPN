# Maximus VPN

Native Android VPN application. The installable APK is published under [GitHub Releases](https://github.com/drfxai/MaximusVPN/releases) after a signed release build succeeds. Do not install an APK from an untrusted mirror.

## Install

1. Open the latest release and download `arm64-v8a.apk` for an ARM64 Android device, or `app-release.apk` for another supported device. Download `SHA256SUMS` too.
2. Put the APK and `SHA256SUMS` in the same directory and run `sha256sum --check SHA256SUMS` on a computer. Both APKs must be present for the full check; to check one file, compare its `sha256sum` output with its line in `SHA256SUMS`.
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

After CI passes on `main`, run **Actions → Signed Android release → Run workflow** with a fresh version tag such as `v1.0.1`. The workflow builds, tests, checks both signatures, and uploads the ARM64 APK, universal APK, and `SHA256SUMS` to Releases. GitHub adds the source code zip and tar.gz archives for the tag automatically. The workflow fails without valid signing credentials. Increase `versionCode` and `versionName` in `app/build.gradle.kts` for each app update before publishing.

## Security status

The VPN uses Android `VpnService` and a Kotlin tunnel path. Configuration compatibility with Xray and Mihomo does not by itself mean their native engines are embedded. The secret chat implementation has not completed authenticated key exchange or peer transport and should not be relied upon for private messaging. Validate DNS, IPv6, failover, and lockdown behavior on real Android devices before presenting a release as production secure.

## v1.0.2 diagnostic corrections

Health checks validate endpoint transport (TCP, TLS, and WebSocket), not VLESS authentication or end-to-end internet access. A WebSocket HTTP 404 requires checking the provider's Host, path, SNI and endpoint settings; a reachable TCP port alone is not a working WebSocket proxy.

Benchmarks use the same transport validation and count unsuccessful samples. Download/upload throughput is unmeasured (stored as zero), and no direct internet speed test or synthetic speed is attributed to a node. Historical benchmark scores from older builds should be refreshed. Failover messages distinguish the configured latency policy from proven availability.

Diagnostics display the build version and Kotlin forwarding runtime. Native Xray execution is not integrated. DNS leak tests, DoH runtime verification and end-to-end connectivity are not inferred from settings or a TUN interface.

To publish without the manual Actions form, update the app version and `release-version.txt` together in a reviewed change to `main`. A change to that file starts the same signed release workflow; use a new tag each time. Release assets include `arm64-v8a.apk`, `app-release.apk`, `SHA256SUMS`, and GitHub's source archives.


## v1.0.3 review and validation

This release builds standalone ARM64 and universal APKs with extracted native libraries. Both keep the same application ID and signing key. Android 15 CI checks installation and startup. The release workflow checks upgrading from the signed v1.0.2 universal APK; it also tests the ARM64 APK when the emulator advertises ARM64 translation. Physical Samsung installation remains a separate device check.

Runtime corrections include packet bounds, blocking TUN reads, shared service settings, failed socket protection, duplicate TCP uploads, FIN payloads, bounded queues, WebSocket ping/pong handling, UDP frame reassembly and UDP receive lifetime. DNS-over-HTTPS no longer silently falls back to plaintext DNS. Browser certificate errors are rejected.

Runtime support is limited to VLESS and Trojan over TCP/WebSocket with none/TLS security, and unauthenticated HTTP CONNECT/SOCKS5 TCP proxies. Only VLESS has proxied UDP in this build. Unsupported profiles remain importable but are rejected before VPN setup. Native Xray/Mihomo, REALITY, Vision, VMess, Shadowsocks, Hysteria and TUIC are not implemented. Imported raw routing/group configuration is not executed by a native core.

Peer discovery is not an authenticated relay; automatic failover no longer invents proxy credentials from bridge or mesh records. Chat sending is disabled because authenticated peer transport has not been implemented.

Remaining limitations: the Kotlin TCP bridge lacks a complete downstream retransmission/window implementation. IPv6 packets are not forwarded. The embedded browser runs in the VPN-excluded application, so its traffic bypasses the VPN; its UI now states this. DNS uses protected direct resolver sockets. Settings that alter the packet path require reconnecting. Android always-on VPN/lockdown is needed for OS-enforced blocking after the service exits. Device traffic/leak tests and real provider interoperability remain necessary; passing CI does not establish production-grade VPN security.
