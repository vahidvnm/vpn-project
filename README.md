# VPN Project

Android auto-connector for Iran-focused VPN configuration recovery.

The app is designed around **Bring Your Own Account / Config**:

- import user-owned OpenVPN, WireGuard, or V2Ray/Xray configs from a file or the clipboard,
- resolve and pin real public IP addresses,
- test healthy routes automatically,
- connect through Android `VpnService`,
- avoid sending user VPN credentials to any project backend.

Current status: **Phase 4 in progress**. The app can import OpenVPN/WireGuard configs and common V2Ray/Xray share links from a file or the Android clipboard, extract endpoints, resolve public IPv4 candidates with DNS-over-HTTPS, run first-pass TCP/TLS health probes, warn when probes are running through an already-active VPN, start a bootstrap Android `VpnService`, and start the first real engine using WireGuard Android GoBackend with a pinned runtime config. Phone testing confirmed WireGuard/UDP is often filtered in Iran and public DoH may be blocked/reset, so the app now prepares pinned OpenVPN TCP fallback configs for external OpenVPN-client handoff and can diagnose V2Ray/Xray endpoints while internal OpenVPN/Xray engine licensing/integration is pending. See [`ROADMAP.md`](ROADMAP.md) for the live plan.

## CI

GitHub Actions builds the debug APK on pushes to `main` and `arena/**` branches.
