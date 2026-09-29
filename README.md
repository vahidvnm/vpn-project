# VPN Project

Android auto-connector for Iran-focused VPN configuration recovery.

The app is designed around **Bring Your Own Account / Config**:

- import user-owned OpenVPN or WireGuard configs,
- resolve and pin real public IP addresses,
- test healthy routes automatically,
- connect through Android `VpnService`,
- avoid sending user VPN credentials to any project backend.

Current status: **Phase 4 started**. The app can import OpenVPN/WireGuard configs, extract endpoints, resolve public IPv4 candidates with DNS-over-HTTPS, run first-pass TCP health probes, start a bootstrap Android `VpnService`, and start the first real engine using WireGuard Android GoBackend with a pinned runtime config. Verified handshake/RX-TX/egress checks are still pending. See [`ROADMAP.md`](ROADMAP.md) for the live plan.

## CI

GitHub Actions builds the debug APK on pushes to `main` and `arena/**` branches.
