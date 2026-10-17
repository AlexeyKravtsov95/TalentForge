# M5.RC.2a — application icon integration evidence

Scope: integrate the **approved** TalentForge application icon (concept #3: an amber/gold talent
tree branching from top to bottom on a dark charcoal background) into Android and iOS. No redesign,
no UI/business-logic/dataset/version/signing changes. Defect: `TF-RC1-ICON`
([rc1-defects.md](../rc1-defects.md#tf-rc1-icon--application-icon-missing)); checklist:
[rc1-release-checklist.md §4](../rc1-release-checklist.md#4-app-icon-readiness-tf-rc1-icon).

## Source and traceability

| File | What it is |
|---|---|
| `source/INTEGRATION.md` | The asset package's integration notes, as received |
| `source/PACKAGE-SHA256SUMS.txt` | SHA-256 of the package zip and of every file in it |
| `source/approved_variant_03_reference.png` | The approved concept raster (1254×1254), the reference for "preserve the approved composition" |
| `source/TalentForge-RuStore-512.png` | RuStore store icon from the same package: 512×512, opaque RGB, 181 KB. Committed for traceability only; uploading it is store publication and out of scope |
| `source/comparison.png` | The package's own simulated store/adaptive/small-size preview |

The integrated resources are **byte-identical** to the package files (checked with `cmp`):

| Platform | Repository path | Package path |
|---|---|---|
| Android | `mobile/composeApp/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml` | `android/res/mipmap-anydpi-v26/ic_launcher.xml` |
| Android | `…/res/drawable-nodpi/ic_launcher_{background,foreground,monochrome}.png` (432×432 px = 108 dp) | `android/res/drawable-nodpi/…` |
| Android | `…/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` (48/72/96/144/192 px) | `android/res/mipmap-*/…` |
| iOS | `mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` | `ios/AppIcon.appiconset/AppIcon-1024.png` |

The existing `AppIcon.appiconset/Contents.json` keeps its Xcode formatting and only gains
`"filename" : "AppIcon-1024.png"` in its single `universal / ios / 1024x1024` slot; it is
semantically identical to the package's `Contents.json`.

The package states that the assets are bitmap-derived from the approved raster (not vectors) and
that alpha extraction may leave minor halo artefacts. They are integrated as supplied; no artwork
was edited, regenerated or substituted.

## Asset QA (offline, no device)

`icon_qa.py <package dir> <out dir>` measures the layers and renders **simulated** masks. Results in
`simulated/icon-qa.json`; renders in `simulated/simulated-masks.png` and
`simulated/safe-zone-overlay.png` (green: 66 dp safe circle; red: 72 dp visible circle; yellow:
72 dp visible square).

| Check | Result |
|---|---|
| Layer canvas | 432×432 px each = 108 dp at 4 px/dp |
| Foreground extent (alpha > 8) | 57.5 × 61.8 dp, bbox 25.2–82.8 × 21.0–82.8 dp; farthest pixel **32.9 dp** from the centre |
| Monochrome extent (alpha > 8) | 54.5 × 60.5 dp; farthest pixel **32.6 dp** from the centre |
| Inside the 66 dp safe zone (radius 33 dp) | **Yes** for both: no mask (circle, squircle, rounded square, teardrop) can clip the artwork |
| Logo size vs. the 48–66 dp guideline | 57.5–61.8 dp: within range |
| Background layer | one solid colour, RGB (25, 28, 33), no alpha |
| Monochrome layer | alpha-only silhouette (one RGB value), as the themed-icon tint requires |
| iOS master | 1024×1024, RGB, **no alpha channel**, square, no pre-rounded corners; no embedded colour profile (treated as sRGB) |
| Raster fallbacks | 48/72/96/144/192 px, opaque, square. Never selected on a device: `minSdk` is 26, so `mipmap-anydpi-v26` always wins |

Visual read of the simulated renders: the gold tree, its nodes and the side arcs stay intact under
every mask. At 48 dp on xhdpi/xxhdpi (96/144 px) the tree, the node rings and the crystal stay
recognisable. At 48 px (mdpi), the gold branching emblem on the dark ground is still distinctive,
but the node rings merge. The themed render keeps the tree silhouette in both tones. These renders
are simulations; they do not replace launcher screenshots.

## Build and platform checks

`run-rc2a-checks.sh` runs on macOS with Xcode and the Android SDK, at the checkout's HEAD, and
records PASS / FAIL / FAIL-EXPECTED / BLOCKED for every step. It builds `assembleDebug`,
`assembleRelease`, `lintRelease` and the simulator Debug/Release `xcodebuild`, and inspects the
packaged icon with `aapt2`. It also takes simulator Home screenshots and, on Android devices, uses
the isolated `.devcheck` build for launcher captures. `rc2a_checks.py` holds the per-step logic.

```bash
docs/release/rc2a-evidence/run-rc2a-checks.sh --out <dir> [--emulator <serial>] [--phone <serial>]
```

`lintRelease` is expected to keep failing on the pre-existing `RememberReturnType` error
(`TF-RC1-LINT`); step `31-lint-icon` judges only the icon (no `MissingApplicationIcon`, no
icon-category issue).
