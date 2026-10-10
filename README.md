# VPN Project

An Android VPN hub for user-provided configurations. The app does not sell VPN access, operate a VPN exit service, or provide accounts or configurations.

## Runtime support

- Embedded Xray supports a documented subset of V2Ray/Xray profiles.
- WireGuard uses the Android GoBackend.
- OpenVPN is currently an external-client handoff; there is no embedded OpenVPN engine.
- sing-box and Clash inputs are parsed and mapped to Xray only when their features are compatible. They are not native sing-box or Clash runtimes.

Only configurations the user is authorized to use should be imported. Credentials and raw configurations are kept on-device; the project has no service backend to receive them.

## Test and verification scope

- Optional automatic latency checks are off by default and apply only to the selected profile.
- A manual Quick check measures supported endpoint reachability. It does not authenticate a VPN session or measure speed or bandwidth.
- A manual Real delay runs a temporary Xray proxy check without Android VPN/TUN. It is not a full-device VPN test and does not measure speed or bandwidth.
- Tests are started for one selected profile at a time. The app does not run background or queue-wide profile tests.
- Smart fallback is separate from testing: it is off by default and, only after an explicit Connect, may try up to three nearby profiles if the user has enabled it.
- An Xray proxy-egress verification does not prove that ordinary Android app traffic crossed the TUN. DNS-route checks are best-effort, not authoritative DNS-leak tests.

A successful build or unit test is not evidence of operation on a particular device, network, or censorship environment. No connection or censorship-circumvention guarantee is made. Device-level testing requires an authorized profile and an explicit user action.

## Build

Requirements: JDK 17, Android SDK Platform 36, and SDK Build Tools 35.0.0 (the default for the selected Android Gradle Plugin). The Gradle Wrapper pins Gradle 8.13 and verifies the distribution checksum.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The current debug build targets Android API 35, compiles against API 36, and packages `arm64-v8a`. The target API remains at 35 pending a separate Android 16 behavior and edge-to-edge review.

The build downloads the pinned Xray AAR and verifies its SHA-256 before use. Do not commit imported VPN configurations, credentials, local SDK settings, or signing keys.

## Continuous integration

GitHub Actions validates the Gradle Wrapper, runs unit tests, and assembles a debug APK on pushes to `main` and `arena/**`, pull requests, or a manual workflow dispatch. The workflow does not run device-level VPN or profile tests.

See [`ROADMAP.md`](ROADMAP.md) and [`docs/implementation-plan-fa.md`](docs/implementation-plan-fa.md) for the live project status and outstanding work.
