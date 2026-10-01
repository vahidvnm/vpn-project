# VPN Project

Android multi-protocol VPN hub and auto-connector, developed Iran-first but designed to work as a general Bring-Your-Own-Config VPN orchestrator.

The app is designed around **Bring Your Own Account / Config** and a multi-engine VPN-hub model:

- import user-owned OpenVPN, WireGuard, or V2Ray/Xray configs from a file, the clipboard, or a user-provided subscription URL,
- manage multiple local profiles, subscription groups, and engines behind one app,
- resolve and pin real public IP addresses only when protocol-safe,
- test healthy routes automatically,
- connect through Android `VpnService`,
- avoid sending user VPN credentials to any project backend.

Current status: **Phase 4 verified / Phase 5 dashboard in progress**. The app can import OpenVPN/WireGuard configs and common V2Ray/Xray share links from a file, the Android clipboard, or a user-provided subscription URL, save encrypted local profiles and encrypted subscription groups, select/rename/favorite/delete saved profiles, remember the last verified profile, show a reference-inspired visual dashboard with a compact scenic connect hero, compact in-page location/config selector with short labels, cleaned Home surface without unfinished protocol/quick-action blocks, bottom-sheet config/add menus with long-press actions, auto endpoint test controls, three-item bottom navigation, and live Xray traffic counters, keep longer DNS/probe/engine details under Advanced diagnostics/tools, start WireGuard Android GoBackend with a pinned runtime config, and start an embedded Xray core from imported V2Ray/Xray links. Phone testing confirmed WireGuard/UDP is often filtered in Iran and public DoH may be blocked/reset, so the practical MVP direction is now an Xray/V2Ray-first BYO-config VPN hub, with OpenVPN TCP handoff and WireGuard kept as secondary engines. Latest phone testing confirmed the embedded Xray path can establish the Android VPN and verify end-to-end proxy egress with a user-owned V2Ray config (`httpupgrade/none`, repeated verified results, traffic stats moving). The debug APK is currently filtered to arm64-v8a to keep the embedded Xray build size manageable for real phone testing. See [`ROADMAP.md`](ROADMAP.md) for the live plan.

## CI

GitHub Actions builds the debug APK on pushes to `main` and `arena/**` branches.
