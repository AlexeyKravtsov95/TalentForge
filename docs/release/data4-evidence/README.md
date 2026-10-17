# M5.DATA.4 evidence — product dataset 1.60.1.70450

Facts, investigation and decisions: [forever-dataset.md § Product dataset 1.60.1.70450](../../forever-dataset.md#product-dataset-160170450-m5data4).
This folder keeps the raw evidence of the refresh. Everything ran locally on macOS 26.3.1 /
Xcode 26.4.1 / JDK 17 / Python 3.12.13 on 2026-10-17 (UTC); GitHub Actions stays blocked
(TF-RC1-CI).

## Freshness

| File | Status | Product build | Latest publishable | `checkedAt` |
| --- | --- | --- | --- | --- |
| [freshness-before.json](freshness-before.json) | **OUTDATED** (exit 3) | `1.60.1.70374` | `1.60.1.70450` | 2026-10-17T18:40:34Z |
| [freshness-after.json](freshness-after.json) | **CURRENT** (exit 0) | `1.60.1.70450` | `1.60.1.70450` | 2026-10-17T19:10:13Z |

Both from `python scripts/check_product_freshness.py --output …` against wago.tools
`/api/builds`. "Publishable" means `is_bgdl` is explicitly `false`; it is not proof of the build
the live realms run. The RC.3 re-check within 24 h of the tag is still required.

## Validation

| Check | Result |
| --- | --- |
| `ruff check .` / `ruff format --check .` | PASS / 207 files formatted |
| `mypy` | PASS (72 source files) |
| `pytest` | 1183 passed, 11 deselected |
| `pytest -m integration` (live wago.tools) | 11 passed |
| `manifest --expect-complete`, `visuals check`, `tooltips check`, `localization check` × 5 bundled builds | all PASS |
| `allocation replay --build 1.60.1.70009 --all` | 53/53 |
| `scripts/tooltip_template_inventory.py --check` | in sync (469 entries) |
| `sync_mobile_datasets.py --check` | 50 files of 5 builds |
| `sync_mobile_visuals.py --check` | 1840 files of 5 builds |
| `sync_mobile_tooltips.py --check` | 45 files of 5 builds |
| `sync_mobile_localization.py --check` | 45 files of 5 builds |
| `:shared:jvmTest` (rerun) | 339 passed, 0 failed |
| `:composeApp:desktopTest` (rerun) | 687 passed, 0 failed, 37 skipped (opt-in screenshot captures) |
| `:shared:iosSimulatorArm64Test` (rerun) | 188 passed, 0 failed |
| `:composeApp:linkDebugFrameworkIosSimulatorArm64` | PASS |
| `iosApp` simulator `xcodebuild` (Debug, `CODE_SIGNING_ALLOWED=NO`) | BUILD SUCCEEDED |
| `:composeApp:assembleDebug` / `:composeApp:assembleRelease` | PASS / PASS (release unsigned; signing unchanged) |
| `:composeApp:lintRelease` | PASS: 0 errors, 9 warnings — the same 9 pre-existing warnings as M5.RC.2b |

## iOS simulator (iPhone 17 Pro, iOS 26.4)

Debug build of the M5.DATA.4 branch, installed over the existing app — its saved library kept.

| Screenshot | Shows |
| --- | --- |
| [01](ios-simulator/01-home-existing-builds.png) | the four builds saved before the refresh, listed with their points |
| [02](ios-simulator/02-saved-paladin-51-opens.png) | a saved Paladin 51/51 build opens unchanged (Holy 15 / Protection 5 / Retribution 31) |
| [03](ios-simulator/03-new-build-nine-classes.png) | New Build lists all nine classes of the product manifest ("talent data 1.60.1") |
| [04](ios-simulator/04-mage-70450-one-canvas.png) | a new Mage build on 70450: one whole-tree canvas, no tabs (Missile Barrage now in row 0) |
| [05](ios-simulator/05-arcane-wrath-inspector.png) | Arcane Wrath selected: 0/1, "Requires Arcane Blast 1/1", **Show** |

Not checked by hand: a physical device, and Russian on the device (the RU text paths are
covered by the localization repository and UI tests).
