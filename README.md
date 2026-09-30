# VPN Project

Android auto-connector for Iran-focused VPN configuration recovery.

The app is designed around **Bring Your Own Account / Config**:

- import user-owned OpenVPN, WireGuard, or V2Ray/Xray configs from a file or the clipboard,
- resolve and pin real public IP addresses,
- test healthy routes automatically,
- connect through Android `VpnService`,
- avoid sending user VPN credentials to any project backend.

Current status: **Phase 4 in progress**. The app can import OpenVPN/WireGuard configs and common V2Ray/Xray share links from a file or the Android clipboard, extract endpoints, resolve public IPv4 candidates with DNS-over-HTTPS, run first-pass TCP/TLS health probes, warn when probes are running through an already-active VPN, start a bootstrap Android `VpnService`, start WireGuard Android GoBackend with a pinned runtime config, and experimentally start an embedded Xray core from imported V2Ray/Xray links. Phone testing confirmed WireGuard/UDP is often filtered in Iran and public DoH may be blocked/reset, so OpenVPN TCP and Xray/V2Ray fallbacks are now the practical focus. Latest phone testing confirmed the embedded Xray path can establish the Android VPN and verify end-to-end proxy egress with a user-owned V2Ray config (`httpupgrade/none`, 129ms verification, traffic stats moving); broader transport hardening is still in progress. The debug APK is currently filtered to arm64-v8a to keep the embedded Xray build size manageable for real phone testing. See [`ROADMAP.md`](ROADMAP.md) for the live plan.

## CI

GitHub Actions builds the debug APK on pushes to `main` and `arena/**` branches.
