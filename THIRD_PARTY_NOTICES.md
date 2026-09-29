# Third-party notices

This project embeds or downloads the following third-party components during Android builds:

## WireGuard Android tunnel

- Artifact: `com.wireguard.android:tunnel:1.0.20230706`
- Purpose: embedded WireGuard GoBackend for user-supplied WireGuard configs.
- License: Apache-2.0, per the upstream WireGuard Android tunnel artifact metadata.

## AndroidLibXrayLite / Xray core

- Artifact: `libv2ray.aar`
- Pinned release: `2dust/AndroidLibXrayLite` `v26.9.9`
- Downloaded by Gradle from the pinned GitHub release during Android builds; the binary is not committed to this repository.
- Purpose: experimental embedded Xray/V2Ray engine for user-supplied VLESS/VMess/Trojan/Shadowsocks share links.
- License: LGPL-3.0 according to the upstream Go package/release metadata. Before any public production release, review LGPL obligations, provide required notices/source links, and verify whether dynamic/relinking requirements are satisfied by the Android packaging approach.
