# IMSControl

> Carrier-config control for IMS on Google Pixel phones — VoLTE, VoNR, and VoWiFi toggles, a full carrier-config editor, and Quick Settings tiles. No root required.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Releases](https://img.shields.io/github/v/release/5m2wse50/IMSControl)](https://github.com/5m2wse50/IMSControl/releases)

## Features

- One-tap VoLTE, VoNR, VoWiFi, and cross-SIM toggles per subscription
- Full carrier-config browser and editor, including long, boolean, string, and array types
- Carrier-config dump view for inspection and debugging
- User-Agent presets with debounced writes
- Quick Settings tiles for VoLTE state and IMS registration status
- In-app update check with daily caching
- Edge-to-edge Material 3 interface with dynamic color

## Requirements

- A Google Pixel phone (Tensor chipsets fully supported, including Android 17)
- [Shizuku](https://shizuku.rikka.app/) installed and running
- Android 9 or later

Shizuku is the only privilege mechanism. The app never asks for root.

## Install

1. Download the latest APK from [Releases](https://github.com/5m2wse50/IMSControl/releases).
2. Install it on the phone.
3. Start Shizuku, then open IMSControl and grant the Shizuku permission when prompted.
4. Pick a SIM and toggle the setting you need. Changes apply immediately; use **Reset all settings** to revert.

## Building

The app calls hidden telephony APIs, so compilation needs a patched `android.jar`:

1. Download the patched `android.jar` for API 36 from [aosp-android-jar](https://github.com/Reginer/aosp-android-jar).
2. Place it under `$ANDROID_SDK/platforms/android-36/`, replacing the stock file.
3. Build:

```bash
./gradlew assembleDebug    # debug APK
./gradlew assembleRelease  # release APK (unsigned)
```

## Project structure

```
app/src/main/
├── aidl/…/IPrivilegedService.aidl   # Shizuku interface definition
├── java/dev/imscontrol/app/
│   ├── HomeActivity.kt              # entry point, navigation, edge-to-edge
│   ├── BrokerInstrumentation.kt     # privileged override broker (shell identity)
│   ├── Moder.kt                     # carrier-config read/write layer
│   ├── Utils.kt                     # Shizuku checks, version fetch, bundle helpers
│   ├── *QSTileService.kt            # Quick Settings tiles
│   ├── components/                  # reusable property rows and dialogs
│   ├── pages/                       # Home, Config, Editor, DumpedConfig screens
│   └── ui/theme/                    # Material 3 theme
└── res/                             # strings (en/ja/zh), platform launch theme
```

## How it works

Carrier restrictions are overridden through `CarrierConfigManager.overrideConfig()`, invoked with shell identity obtained via Shizuku. Overrides are per-subscription and non-destructive: clearing them restores carrier defaults.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Acknowledgments

IMSControl originated from the `pixel-volte-patch` project by kyujin-cho; its Apache-2.0 license and copyright notice are retained in [LICENSE](LICENSE).
