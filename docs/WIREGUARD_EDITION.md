# Nuvio TV WireGuard Edition – Implementation and Testing

Status: **development / draft PR; do not install for privacy-sensitive torrent traffic until CI and Shield tests pass**.

Target: NVIDIA Shield TV Pro (2019), Android TV 11 / API 30. Build configuration remains on upstream toolchain (compile/target API 36).

## Implementation

- Embedded official Apache-2.0 WireGuard Android **tunnel** AAR `com.wireguard.android:tunnel:1.0.20260102`.
- The AAR merges a **single** `GoBackend$VpnService` declaration. No standalone VPN app, root access or additional VPN service.
- Uses `VpnService.prepare(context)` and the OS consent dialog.
- The imported .conf is parsed by WireGuard. On connection, app lists are stripped and replaced with `IncludedApplications = context.packageName`. This is a strict positive allowlist for this application's Android UID.
- A full dual-stack profile is required: IPv4 and IPv6 /0 routes. Partial profiles are rejected.
- Mullvad verification uses an app-originated HTTPS call to `https://am.i.mullvad.net/json` and checks `mullvad_exit_ip == true`. A WireGuard handshake in the past 240 seconds is also required before Nuvio torrents can start.
- Profile contents are AES/GCM encrypted using a hardware/OS-protected key in Android Keystore (where available). Files are written beneath `noBackupFilesDir`. The app already sets `allowBackup=false`.
- The monitor examines current backend state and handshake every 10 seconds while verified. It does not issue continuous public IP requests.
- A `TorrentVpnSafety` gate blocks native Nuvio Engine initialization, including cache operations. `TorrentService` observes loss of verified state and shuts the engine down. There is no deliberate direct-torrent fallback.
- The VPN menu uses Nuvio's existing settings rows for D-Pad navigation, with import, selection, rename, delete, connect, disconnect, optional auto-connect, status and traffic data.

### Android TV file import improvements

Two independent import paths are available under **Settings → VPN & WireGuard**:

1. **WireGuard-Konfiguration importieren** uses `ACTION_GET_CONTENT` inside an Android app chooser. Android displays eligible installed pickers (potentially including FX File Explorer if it supports this intent); the app cannot force FX to appear.
2. **Mit Android-Dateidialog importieren** keeps the original `ACTION_OPEN_DOCUMENT` flow when the chooser does not offer a usable TV browser.

A third option starts in an external file manager: select a single `.conf` file → **Teilen/Share** → **Nuvio TV VPN – WireGuard importieren**. The exported, Hilt-enabled share receiver accepts a single `ACTION_SEND` content URI and shows a D-Pad-operable explicit confirmation. It never auto-imports, auto-connects, interprets an arbitrary text extra as a config, or accepts a plaintext path. The share target advertises `*/*` because Android TV file managers use inconsistent MIME types for `.conf`; a non-WireGuard file still fails the actual WireGuard parser and dual-stack policy.

Both picker and share flows use the **same** content-URI reader. The reader never stores URI permissions or plaintext files, caps input at 64 KiB **while reading** (ignoring possibly incorrect provider size metadata), sanitizes filenames, validates via the existing WireGuard parser and dual-stack route policy, writes only Android Keystore-encrypted profiles, and wipes its temporary plaintext buffer. Provider errors are not echoed verbatim to the TV UI. No broad storage permission is needed.

**Shield test coverage needed:** external picker presence, FX single-file share (including access-grant behavior), D-Pad focus on Confirm/Cancel, valid import, malformed/oversized file rejection, preservation of existing profiles and independent package IDs. File-manager picker/chooser UX is OS- and app-dependent; CI compilation does not establish that FX is offered on a specific Shield setup.
- The app ID for the full release flavor is `com.justify87.nuviotv.wireguard` and for fullDebug `com.justify87.nuviotv.wireguard.debug`.
- The upstream in-app updater is disabled pending dedicated fork releases.

## Important security limits

**A running userspace WireGuard VPN is not equivalent to an OS-enforced kill switch.** If Android revokes the VPN, the VPN process crashes or the OS restores underlying networking, a transient packet leak before the application's disconnect callback is possible. Do not claim 100% leak prevention.

The gate checks the engine at entry and stops it on observed VPN loss. That does not prove that all native sockets are closed atomically. Some non-torrent Nuvio requests may occur during application startup before the optional background auto-connection completes. Only hardware networking tests can validate this setup.

Do not enable system-wide Android lockdown without confirming the effect on excluded apps. Such lockdown can break the requirement that other Shield apps use their normal internet route.

The current version requires **Mullvad** for exit verification; alternative WireGuard providers can import but will not pass the Mullvad-only test. IPv4-only profiles will be rejected. This is intentionally conservative.

The current version does not yet include a full reconnect-on-network-change strategy, Android Always-on mode support, or optional routing of other installed applications.

## Nuvio account QR login / official Cloud configuration

The fork uses the public Nuvio Cloud client configuration published in
[NuvioMedia/self-host/.env.example](https://github.com/NuvioMedia/self-host/blob/main/.env.example):
`https://api.nuvio.tv` and its `sb_publishable_...` client key.
These are public client values, **not** a Supabase service-role key. Never add
service-role/private keys to the APK or GitHub.

- Debug and release builds now use the official Nuvio Cloud backend and public
  publishable key by default, instead of an empty URL/key on a fresh checkout.
- If overriding these values (for an intentional custom backend), use the exact
  property names `NUVIO_SUPABASE_URL` and `NUVIO_SUPABASE_ANON_KEY`.
- `local.dev.properties` takes precedence over `local.properties` for the
  `fullDebug` build. Blank values fall back to nonblank properties and then
  to the official defaults.
- The account web confirmation page remains `https://nuvio.tv/link`; the
  legacy QR web page remains `https://nuvio.tv/tv-login`.
- `verifyNuvioAuthConfig` runs in GitHub Actions **before** APK tests/build
  and as a dependency of `preFullDebugBuild`. It rejects invalid HTTPS
  URLs, placeholder keys and secret-role keys without printing key contents.
  This validates configuration shape, **not** backend availability or a
  successful end-to-end account login.
- If the official backend URL or client key is missing/invalid at runtime,
  the QR screen shows an actionable error instead of only “QR unavailable”.

The official cloud publishable key is necessarily distributed inside the
Android APK, just like other public mobile client keys. It is not an account
token and grants no privileged backend access by itself.

After a successful CI run, install the freshly built APK and test a complete
device-code login, session exchange and account sync on the Shield.
An old APK does not receive these settings until it is rebuilt and installed.
A CI-generated *temporary signing key* may prevent in-place upgrades; use a
stable keystore for preserving installed app data and VPN profiles.

## Enabling GitHub Actions

1. Visit [Fork Actions](https://github.com/Justify87/NuvioTV/actions).
2. On a new GitHub fork, choose **I understand my workflows, go ahead and enable them**, if shown.
3. Select **PR Full Debug Build** and use **Run workflow** (branch `feature/wireguard-edition`) if necessary. Opening or updating PR #1 should also trigger a build when Actions are enabled.
4. Wait for a successful `full-debug-build` run.
5. Download artifact **NuvioTV-WireGuard-debug** from the run's **Artifacts** section.
6. Read any failed step logs before attempting Shield installation.

The workflow runs `testFullDebugUnitTest`, builds `assembleFullDebug`, checks that the APK exists, examines its package/native ABIs and verifies signing with Android SDK `apksigner`.

### Stable signing for reinstallable APK updates

For a first-test APK, CI generates a temporary key. It changes between runs, so it cannot update a previously installed build signed with another key.

To enable stable update-compatible CI signing, generate a private keystore **offline**, keep it outside Git, and save these GitHub repository Actions secrets:

| Secret | Value |
| --- | --- |
| `NUVIO_CI_KEYSTORE_BASE64` | Base64 of the entire private JKS/PKCS12 keystore |
| `NUVIO_CI_KEY_ALIAS` | Keystore key alias |
| `NUVIO_CI_KEY_PASSWORD` | Key password |
| `NUVIO_CI_STORE_PASSWORD` | Keystore password |

Do not paste these secrets into issues, PRs, chat or logs. A debug APK installed with the temporary key must be uninstalled before installing a stable-key APK.

## Physical Shield verification checklist (owner)

1. Download the successful APK from the GitHub Actions artifact. Confirm the CI run's commit SHA.
2. Install via ADB, e.g. `adb install -r app-full-universal-debug.apk`, or use a trusted local installation method.
3. Launch **Nuvio WireGuard**; open **Settings → VPN & WireGuard**.
4. Import your Mullvad .conf using the TV file picker and select the imported profile.
5. Tap **VPN verbinden**, approve the Android system VPN consent.
6. Confirm **VERIFIED**, the reported Mullvad exit IP and recent handshake. If verification fails, do not test a real torrent.
7. Confirm that another Shield app still sees the household public internet IP, not the Mullvad IP.
8. Test with a legal public torrent (for example a Fedora ISO) and verify the torrent peer-facing IP with an independent controlled torrent tracker or IP check.
9. Verify ordinary HTTP/HTTPS playback, subtitles and the local `127.0.0.1` torrent playback path.
10. During a test stream, disconnect WireGuard. Confirm torrent fetching stops and does not reconnect directly.
11. Disconnect Wi-Fi, reconnect it, switch networks if possible, and repeat the outage test.
12. Test IPv4, IPv6 and DNS leak behavior from inside Nuvio (other UIDs such as ADB or a browser do not prove Nuvio's routing).
13. Test standby, resume, app restart, Android VPN permission revocation and force-stop.
14. Install a subsequent APK signed by the **same** stable keystore to test updates.

Useful commands:

```sh
adb devices
adb install -r app-full-universal-debug.apk
adb shell pm list packages | grep justify87
adb logcat -v time | grep -Ei 'GoBackend|WireGuard|TorrentService|nuvio'
```

Avoid sharing logs containing private keys, tokens, complete VPN profiles or unrelated personal information.

## Acceptance and limits

A CI success proves only compilation, automated tests and APK packaging. It does **not** prove that the Shield's firmware accepts the VPN service, that the native engine's sockets use the VPN or that no traffic escapes during an OS crash.

Further work after CI feedback: correct any build errors, inspect merged manifest, test ARM64 native libraries, add exit-IP refresh diagnostics and support controlled automatic re-connection. Only the owner can finish hardware verification.
