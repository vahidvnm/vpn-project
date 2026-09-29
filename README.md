# VPN Project

Android auto-connector for Iran-focused VPN configuration recovery.

The app is designed around **Bring Your Own Account / Config**:

- import user-owned OpenVPN or WireGuard configs,
- resolve and pin real public IP addresses,
- test healthy routes automatically,
- connect through Android `VpnService`,
- avoid sending user VPN credentials to any project backend.

Current status: **Phase 4 in progress**. The app can import OpenVPN/WireGuard configs, extract endpoints, resolve public IPv4 candidates with DNS-over-HTTPS, run first-pass TCP health probes, start a bootstrap Android `VpnService`, and start the first real engine using WireGuard Android GoBackend with a pinned runtime config. Phone testing confirmed WireGuard/UDP is often filtered in Iran, so the app now also prepares pinned OpenVPN TCP fallback configs for external OpenVPN-client handoff while internal OpenVPN engine licensing is pending. See [`ROADMAP.md`](ROADMAP.md) for the live plan.

## CI

GitHub Actions builds the debug APK on pushes to `main` and `arena/**` branches.
