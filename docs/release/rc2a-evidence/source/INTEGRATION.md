# TalentForge — M5.RC.2a Icon Asset Candidate (Approved concept #3)

Source: user-selected **third** concept from the top-down branching talent-tree series.

## Package contents

- `design/approved_variant_03_reference.png`: original selected raster concept (1254x1254), for visual sign-off and source traceability.
- `design/talent_tree_foreground_transparent.png`: extracted icon graphic, alpha. Bitmap derivative, not editable vector paths.
- `ios/AppIcon.appiconset/`: square **opaque** 1024×1024 icon and Contents.json; **no pre-rounded corners**.
- `rustore/TalentForge-RuStore-512.png`: square **opaque** 512×512 PNG.
- `android/res/drawable-nodpi/`: separate 432×432 foreground, background, monochrome PNG layers.
- `android/res/mipmap-anydpi-v26/ic_launcher.xml`: Android adaptive icon resource.
- `android/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`: fallback assets.
- `previews/comparison.png`: store/adaptive simulated masking and small-size checks.

## Engineering instructions

1. Copy the contents of `android/res/` to `mobile/composeApp/src/androidMain/res/`.
2. Add `android:icon="@mipmap/ic_launcher"` to the `<application>` element in `AndroidManifest.xml`. `android:roundIcon` optional.
3. Replace the contents of `mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` with this package's iOS AppIcon.appiconset content (retaining project references).
4. Do not overwrite any class/talent artwork or modify UI theme/colors/datasets/business logic.
5. Build Android release/debug; verify the icon is packaged using `aapt2 dump badging`; check Android adaptive masks (circle, squircle), Android 13+ themed monochrome, and at 48dp.
6. Build iOS app; verify AppIcon catalog compilation and appearance on the simulator. A signed archive / App Store validation remains pending signing.
7. Compare every rendered launcher appearance to approved variant #3; do not invent an unrelated logo.

## Design and QA caveats

- These are *bitmap-derived production candidates*, not vectors. Alpha extraction from the original approved raster was done programmatically. This can omit dark metallic details and retain minor halo artifacts. Check the result on actual device launchers before closing TF-RC1-ICON.
- Android adaptive safe area was sized conservatively for central 66dp. It may look smaller than the full store icon; verify optically before release.
- iOS/RuStore images are full-bleed opaque squares; the system/store applies rounding as appropriate.
- Store submission, signing, permission/rights reviews and release-number decisions are outside this deliverable.
- A formal vector redraw is optional if the rendered icon fails small-size QA; never auto-substitute a different design.
