# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project overview

**IMSControl** (`dev.imscontrol.app`) enables IMS features (VoLTE, VoNR, VoWiFi) on Google Pixel phones without root. It overrides carrier configuration through `CarrierConfigManager.overrideConfig()` using shell identity obtained via Shizuku. UI is Jetpack Compose Material 3, single activity (`HomeActivity`) with navigation between Home, Config, Editor, and DumpedConfig screens, plus two Quick Settings tiles.

Key files: `Moder.kt` (config read/write), `BrokerInstrumentation.kt` (privileged broker), `Utils.kt` (Shizuku checks, update fetch), `pages/`, `components/`.

## Build

Requires a **patched android.jar** (hidden APIs) for API 36 under `$ANDROID_SDK/platforms/android-36/`.

```bash
./gradlew assembleDebug
```

Do not build release/sign locally for verification; use CI. Do not run heavy local builds — delegate verification to GitHub Actions.

## Conventions

- ktlint enforced in CI (`standard:function-naming` and `property-naming` disabled in `.editorconfig`).
- `remember` for runtime-only state, `rememberSaveable` for UI input; effects keyed on `subId`, never `LaunchedEffect(true)`.
- Never add dependencies without checking imports; prefer platform APIs.
