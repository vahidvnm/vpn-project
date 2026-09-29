# VPN Project

Android auto-connector for Iran-focused VPN configuration recovery.

The app is designed around **Bring Your Own Account / Config**:

- import user-owned OpenVPN or WireGuard configs,
- resolve and pin real public IP addresses,
- test healthy routes automatically,
- connect through Android `VpnService`,
- avoid sending user VPN credentials to any project backend.

Current status: **Phase 3 started**. The app can import OpenVPN/WireGuard configs, extract endpoints, resolve public IPv4 candidates with DNS-over-HTTPS, run first-pass TCP health probes, and start a bootstrap Android `VpnService` with TUN/full-route/DNS control. It is not a working internet tunnel until an OpenVPN or WireGuard engine is integrated. See [`ROADMAP.md`](ROADMAP.md) for the live plan.

## CI

GitHub Actions builds the debug APK on pushes to `main` and `arena/**` branches.
