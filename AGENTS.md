# Nuvio TV WireGuard Edition - Development Rules

This fork adds an embedded WireGuard userspace VPN to Nuvio TV, intended primarily for NVIDIA Shield TV Pro (2019 / Android TV 11).

- Work only in Justify87/NuvioTV. Treat NuvioMedia/NuvioTV, WireGuard/wireguard-android, wgtunnel/android and homelessbrian/NuvioPlusIPTV as read-only references.
- Keep changes focused, use the existing Kotlin / Compose / Gradle / Hilt conventions.
- Never commit private keys, real .conf files, signed release keystores, or credentials. Keep WireGuard configs encrypted via Android Keystore and out of Android backups.
- The default route policy includes **only** the running application's actual package name. Never hardcode an upstream package name.
- Do not confuse VpnService connected, recent WireGuard handshake, verified VPN egress, and a kernel-enforced kill switch.
- Protect *every* NuvioEngine initialization, including cache-related paths, at the service boundary. No direct torrent fallback when VPN protection is required.
- Treat VPN revocation, disconnect, handshake timeout and network changes as fail-closed signals, close the torrent engine, and require a fresh check before restarting it.
- Other apps on the Shield must continue to use their normal connection; do not silently enable system-wide Android lockdown.
- Preserve the built-in Media3 player, loopback HTTP playback path, and original Nuvio features.
- Avoid modifications to the native libtorrent AAR in version 1.
- Add unit tests for state transitions; build fullDebug in GitHub Actions and verify the APK.
- Never claim that a CI build proves network leak protection on hardware. The owner runs NVIDIA Shield tests.
- Keep GPLv3 notices for Nuvio and Apache-2.0 notices for WireGuard. Review every copied dependency's license.
- Never log the full WireGuard Config, private key, tokens, or unredacted VPN diagnostics.
