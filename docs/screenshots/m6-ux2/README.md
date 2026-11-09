# M6.UX.2 — first-use guidance and data versions

## UX decisions

- Home keeps the purpose subtitle introduced by PR #87. One quiet, 48 dp
  “How to use / Как пользоваться” text action below the brand header opens
  four numbered paragraphs. It is available with an empty or populated
  library, also while loading or after a library read failure.
- Help is an ordinary saved navigation destination. It reuses About's
  scrolling scaffold, body typography, safe-area padding and visible Back
  chevron, plus the app's existing system Back and iOS edge-swipe stack.
  There is no launch modal, carousel, artwork or new navigation framework.
- The offline paragraph follows the actual OTA runtime: OTA ON explains
  optional online data updates; OTA OFF identifies the included data and
  disabled online updates. Help never initiates a network request.
- About changes only the app-version caption. Its existing data version,
  downloaded-update source and update controls remain in place. The generic
  component-version caption used by licenses remains separate.
- Empty Home shows the existing short talent-data version and a separate
  full **Game build / Сборка игры**. Both use the latest activated product;
  the editor's leased session and saved-build identities remain unchanged.
- Import preview now separates the existing short talent-data version from
  the full game build. Both derive from the preview's own build, including
  historical imports after a newer OTA activation.

## Native screenshots

These are native app captures, not desktop Compose renders. All were taken
with OTA OFF at the normal text size. Android: Pixel 9a emulator, Android 15,
1080×2424 px. iOS: iPhone 17 Pro Simulator, iOS 26.0.1, 1206×2622 px.

| Screen | Android EN | Android RU | iOS EN | iOS RU |
| --- | --- | --- | --- | --- |
| Home | [EN](android/en-home.png) | [RU](android/ru-home.png) | [EN](ios/en-home.png) | [RU](ios/ru-home.png) |
| Help | — | — | [EN](ios/en-help.png) | [RU](ios/ru-help.png) |
| About | — | — | [EN](ios/en-about.png) | [RU](ios/ru-about.png) |

Android captures cover the Home entry and game-build caption. The desktop
Compose regression matrix exercises the shared Help screen and navigation
for both platforms; Android Emulator's window was unavailable to the native
UI automation tool, so Android Help/About captures are not claimed.

## Regression coverage

`FirstUseHelpUiTest` exercises the real app across **128 combinations**:
EN/RU × empty/populated library × OTA OFF/ON × 320/360/393/412 dp ×
100/130/175/200% text. It checks all four paragraphs, wrapping without
ellipsis or clipping (1.5 px font-rounding tolerance), the entry's touch
target, repeated opening, visible/system Back, cancelled/completed iOS
edge-swipe events and no build-storage writes. Navigation encoding restores
Home → Help and rejects a rootless Help stack.

`OtaUiTest` checks activation while Home stays open (70374 → 70450), Help
while the initial network request is held, update acknowledgement, and a
70374 import preview after 70450 activation in both languages without
rewriting the saved build. Existing About and import assertions are updated
for the more specific captions. The full existing editor, build-library,
import/export and gesture suites are retained.

Validation results are recorded in the pull request.
