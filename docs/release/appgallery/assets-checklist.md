# M5.GALLERY.1 — Store assets checklist (RuStore + AppGallery)

| | |
|---|---|
| Milestone | M5.GALLERY.1 — preparation. **No store screenshot was produced**: they must come from the running *signed release* build (TF-RC1-SIGNING) and are gated by LR-1/LR-3. Nothing was uploaded. |
| Brand source | owner-approved M5.RC.2a package ([rc2a-evidence](../rc2a-evidence/README.md)) |
| Requirements | AppGallery: [Specifications for App Visual Assets → APKs](https://developer.huawei.com/consumer/en/doc/app/agc-help-app-material-requirement-0000001146534651) (updated 2026-04-01), [Review Guidelines §1](https://developer.huawei.com/consumer/en/doc/app/50104-01). RuStore: [Правила модерации §6.6](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps). Accessed 2026-10-17. |

## 1. What each store needs

| Asset | AppGallery (Android APK, phone) | RuStore (phone) | Plan | Status |
|---|---|---|---|---|
| Store icon | 1, square, 216×216 or 512×512, PNG ≤ 2 MB | 512×512, PNG/JPG ≤ 3 MB, filled to the edges, no transparency | one file for both (§2) | ✅ file exists; ⏳ upload and preview 🔑 |
| Launcher icon | must match the store icon and name (1.20) | must match the store icon (6.6) | M5.RC.2a adaptive icon | ✅ integrated; device checks still open (TF-RC1-ICON) |
| Phone screenshots | 3–8; 800×450 or 450×800; sides 320–3840 px; PNG/JPG ≤ 2 MB | ≥ 3; PNG/JPG ≤ 3 MB; max 2160×3840; 16:9 recommended (otherwise cropped to 16:9); one orientation per set; no Android system UI; RU/EN text only | 6 portrait (+1 optional) at **1080×1920**, ≤ 2 MB each, per language (§3) | ❌ not produced |
| Tablet screenshots | — (the phone set covers tablets) | optional; if supplied, ≥ 3, landscape, ≤ 5 MB | not planned for v1 | — |
| Videos | optional | optional, one, hosted on vk.com / vk.ru | not planned | — |

## 2. Icon

| Check | Requirement | `TalentForge-RuStore-512.png` | Result |
|---|---|---|---|
| Size | AppGallery 216×216 or 512×512; RuStore 512×512 | 512×512 | ✅ |
| Format and weight | AppGallery PNG ≤ 2 MB; RuStore PNG/JPG ≤ 3 MB | PNG, 185 254 bytes (181 KB) | ✅ |
| Opaque, filled to the edges | RuStore 6.6 | 8-bit RGB, **no alpha channel** (PNG colour type 2) | ✅ |
| Square | AppGallery "square-shaped icon" | 512×512 | ✅ |
| Same artwork as the installed app | AppGallery 1.20, RuStore 6.6 | the same approved concept #3 as the adaptive launcher icon (M5.RC.2a, byte-identical package) | ✅ |
| Not a system icon; no fake badge; no third-party marks | AppGallery 1.6/1.7, RuStore 6.6 | gold talent tree on dark charcoal; no badge; no Blizzard logo | ✅ |
| Previewed in each console | — | — | ⏳ owner 🔑 |

Facts measured on 2026-10-17 from the committed file `docs/release/rc2a-evidence/source/TalentForge-RuStore-512.png`.

## 3. Screenshots

### 3.1 Rules (both stores at once)

1. **Real app only.** Capture the **signed release APK** (after TF-RC1-SIGNING) running on a phone or
   an emulator. No mock-ups, no desktop-rendered test images, no edited UI. The images already in the
   repository (`docs/screenshots/*`, `docs/release/rc1-evidence/screenshots/*`) are test evidence from
   older or debug builds and must **not** be used as store screenshots.
2. **No Android system UI** (RuStore 6.6): no status bar, navigation bar or system dialog. TalentForge
   draws edge to edge, so crop the status-bar and navigation-bar rows away from the capture.
3. **Exactly 9:16, 1080×1920 PNG.** This satisfies AppGallery (9:16 like 450×800; sides within
   320–3840) and RuStore (≤ 2160×3840; no forced crop because it already has the recommended
   proportion). If the cropped capture is not 9:16, extend the canvas at the top or bottom with the
   app's own background colour sampled from the capture; never stretch or scale non-uniformly. Keep
   each file ≤ 2 MB (the stricter AppGallery limit); if a PNG is larger, use JPG at quality ≥ 90.
4. **One orientation per set** (RuStore): portrait for phones.
5. **Per language:** capture the EN set with the device in en-US and the RU set in ru-RU. AppGallery
   accepts assets per language; RuStore screenshot text must be RU or EN.
6. **Captions** are optional and go in a band **outside** the app capture (for example the padding
   from rule 3). Use only the captions from the listing drafts ([EN §7](store-listing-en.md#7-screenshot-sequence-and-captions),
   [RU §7](store-listing-ru.md#7-последовательность-скриншотов-и-подписи)); no claim beyond the app's real
   features, no other store's name, no "best/official/No. 1" wording (AppGallery 1.14, RuStore 6.4).
7. **No personal data** in build names or anywhere else (AppGallery 1.19). Use neutral names typed for
   the capture (e.g. "Build 1", "Raid"), not people's names.
8. **Legal gate:** screenshots 1–3 show game icons, talent text and class artwork (LR-1, LR-3). Do
   not upload any screenshot before the legal review is complete.

### 3.2 Sequence

| # | Screen | EN caption | RU caption |
|---|---|---|---|
| 1 | Talent editor, portrait; a talent selected; inspector with + / − | Plan every talent point | Распределяйте каждое очко |
| 2 | Inspector expanded: current and next rank, a requirement with Show / «Показать» | Every rank explained | Описание каждого ранга |
| 3 | Choose a class / «Выберите класс» | Nine classes | Девять классов |
| 4 | Home: CONTINUE and RECENT / «ПРОДОЛЖИТЬ», «НЕДАВНИЕ» | Builds save automatically | Билды сохраняются сами |
| 5 | Share build sheet with the code / «Поделиться билдом» | Share a build as a code | Делитесь билдом кодом |
| 6 | Import preview / «Импортировать этот билд?» | Preview before you import | Предпросмотр перед импортом |
| 7 (optional) | Builds ⋯ sheet / «Билды» ⋯ | Rename, duplicate, delete | Переименование, копия, удаление |

### 3.3 Capture procedure (owner, after TF-RC1-SIGNING; not executed here)

```bash
# Device or emulator in the target language, release APK installed (not the .devcheck debug build).
adb shell screencap -p /sdcard/tf-01.png && adb pull /sdcard/tf-01.png && adb shell rm /sdcard/tf-01.png
# Per image: crop the status-bar and navigation-bar rows, pad to 9:16 with the sampled background,
# resize uniformly to 1080×1920 and save as PNG. Then check dimensions and size:
python3 -c "from PIL import Image; im=Image.open('tf-01-store.png'); print(im.size, im.mode)"
ls -l tf-01-store.png
```

Record which APK (`versionCode`, certificate SHA-256) and which device or emulator each screenshot came
from in the owner's release record.

## 4. Checklist

- [x] The store icon file meets both stores' specifications (§2).
- [x] The launcher icon is integrated from the same approved artwork (M5.RC.2a).
- [ ] TF-RC1-ICON closed (signed build, device launchers) — existing gate.
- [ ] EN screenshot set (6–7 images) captured from the signed release build per §3.
- [ ] RU screenshot set (6–7 images) captured from the signed release build per §3.
- [ ] The LR-1 / LR-3 legal review covers the screenshots.
- [ ] Owner approves the captions and the final set.
- [ ] 🔑 Upload and preview in AppGallery Connect (EN and RU localizations).
- [ ] 🔑 Upload and preview in the RuStore Console.
