# VPN Project

Android auto-connector for Iran-focused VPN configuration recovery.

The app is designed around **Bring Your Own Account / Config**:

- import user-owned OpenVPN or WireGuard configs,
- resolve and pin real public IP addresses,
- test healthy routes automatically,
- connect through Android `VpnService`,
- avoid sending user VPN credentials to any project backend.

Current status: **Phase 4 in progress**. The app can import OpenVPN/WireGuard configs, extract endpoints, resolve public IPv4 candidates with DNS-over-HTTPS, run first-pass TCP health probes, start a bootstrap Android `VpnService`, and start the first real engine using WireGuard Android GoBackend with a pinned runtime config. It verifies WireGuard by checking RX/TX statistics plus public HTTPS egress IP, rebinds/re-verifies on Wi‑Fi/mobile changes, supports literal public IPv4/IPv6 WireGuard endpoints without unnecessary DoH, and prefers IP-literal DoH endpoints to reduce resolver hostname poisoning. See [`ROADMAP.md`](ROADMAP.md) for the live plan.

## CI

GitHub Actions builds the debug APK on pushes to `main` and `arena/**` branches.
