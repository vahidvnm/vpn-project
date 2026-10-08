# Third-party notices

This project embeds or downloads the following third-party components during Android builds:

## WireGuard Android tunnel

- Artifact: `com.wireguard.android:tunnel:1.0.20230706`
- Purpose: embedded WireGuard GoBackend for user-supplied WireGuard configs.
- License: Apache-2.0, per the upstream WireGuard Android tunnel artifact metadata.

## ZXing Android Embedded

- Artifact: `com.journeyapps:zxing-android-embedded:4.3.0` (with ZXing Core as a transitive dependency).
- Purpose: user-initiated live QR scanning and decoding/encoding QR payloads.
- License: Apache-2.0 according to the upstream project metadata; confirm transitive dependency notices during release preparation.

## AndroidX Core

- Artifact: `androidx.core:core:1.15.0`.
- Purpose: provides `ContextCompat`, used by the ZXing capture activity at runtime.
- License: Apache-2.0 according to the Google Maven artifact metadata.

## AndroidLibXrayLite / Xray core

- Artifact: `libv2ray.aar`
- Pinned release: `2dust/AndroidLibXrayLite` `v26.9.9`
- Downloaded by Gradle from the pinned GitHub release during Android builds; the binary is not committed to this repository.
- Expected SHA-256: `9ecf4c921568d8f4cb8550d3bafe08ff6f1d1984f45a6ad183dcdf52ee9302de` (verified against the GitHub release asset digest).
- Purpose: experimental embedded Xray/V2Ray engine for user-supplied VLESS/VMess/Trojan/Shadowsocks share links.
- License: LGPL-3.0 according to the upstream Go package/release metadata. Before any public production release, review LGPL obligations, provide required notices/source links, and verify whether dynamic/relinking requirements are satisfied by the Android packaging approach.
