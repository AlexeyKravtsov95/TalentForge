# M5.PLAY.1 — Google Play graphics and assets checklist

| | |
|---|---|
| Status | Icon **prepared** (format converted, image unchanged). Feature graphic and phone screenshots **missing**: they need the owner |
| Requirements | [Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151) and [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842), read 2026-10-19 |
| Rule | No invented screenshots, mock-ups or edited game UI. Every screenshot is an unmodified capture of the running release build |
| Related | [listing EN](store-listing-en.md) · [listing RU](store-listing-ru.md) · [RuStore/AppGallery assets](../appgallery/assets-checklist.md) |

## 1. Requirements (Google Play) and status

| Asset | Requirement | Status |
|---|---|---|
| App icon | 512 × 512 px, **32-bit PNG with alpha**, at most 1024 KB. Play applies the round mask; no badges or third-party logos | **Prepared**: `assets/TalentForge-GooglePlay-512.png` (§2) |
| Feature graphic | 1024 × 500 px, JPEG or **24-bit PNG without alpha**; alt text ≤ 140 characters. No "Best", "#1", "Top", "New", "Sale", no calls to action ("Download now"), no store badges, no third-party trademarked characters or logos without permission. Important content away from the edges | **MISSING** (§3) |
| Phone screenshots | at least 2 (across device types) to publish, at most 8 per device type. JPEG or **24-bit PNG without alpha**. Each side 320–3840 px; the long side at most 2 × the short side. For promotion eligibility: at least 4 screenshots of at least 1080 px, e.g. 9:16 at 1080 × 1920 | **MISSING** (§4) |
| 7-inch / 10-inch tablet screenshots | optional. Large-screen promotion wants at least 4 screenshots of 1080–7680 px | not planned for the first release; the app supports tablets, so a set may be added later |
| Promo video (YouTube) | optional | not planned |

## 2. App icon: format check and conversion

The approved M5.RC.2a store icon is
`docs/release/rc2a-evidence/source/TalentForge-RuStore-512.png`. Its **actual** format, read from
the PNG `IHDR` chunk:

| File | Size | IHDR | Alpha | Bytes | SHA-256 |
|---|---|---|---|---|---|
| `rc2a-evidence/source/TalentForge-RuStore-512.png` | 512 × 512 | bit depth 8, colour type **2 (RGB)** | **none** (no `tRNS`) | 185 254 | `f14541d824492b6deb03b6b11b1389e8c0cddf4c2b47b2cdcc074b92798bc85a` |
| `google-play/assets/TalentForge-GooglePlay-512.png` | 512 × 512 | bit depth 8, colour type **6 (RGBA)** | opaque (A = 255 everywhere) | 206 368 | `02b671ca08e92ff36f46ad6ffbc27e966bc8dff628126e9c7fac2d7269955855` |

The RuStore icon is a 24-bit PNG. Google Play asks for a 32-bit PNG with alpha. The Play file is
the same image with an opaque alpha channel added: every RGB byte is identical (checked below), so
the image itself is unchanged. RuStore and AppGallery keep the RGB file.

Reproduce and verify (Pillow 12.3.0, the version pinned in `pyproject.toml`):

```bash
python3 - <<'PY'
from PIL import Image
src = "docs/release/rc2a-evidence/source/TalentForge-RuStore-512.png"
dst = "docs/release/google-play/assets/TalentForge-GooglePlay-512.png"
with Image.open(src) as image:
    assert image.mode == "RGB" and image.size == (512, 512)
    image.convert("RGBA").save(dst, format="PNG", optimize=True)
with Image.open(src) as a, Image.open(dst) as b:
    assert b.mode == "RGBA" and b.getchannel("A").getextrema() == (255, 255)
    assert a.tobytes() == b.convert("RGB").tobytes()  # pixels identical
PY
```

Whether the icon's rights review was recorded is still owner item L-6
([LEGAL.1 options §5](../legal1-release-options.md#5-owner-decisions)): the concept was AI-assisted.

## 3. Feature graphic (MISSING, owner)

- A new 1024 × 500 image, 24-bit PNG without alpha or JPEG. It must be original TalentForge
  artwork: the app icon or brand mark plus the name, optionally a short tagline in the listing
  language.
- **Do not** use game logos, class paintings, talent icons or other game art: the feature graphic
  rule forbids third-party trademarked characters and logos without permission, and LR-1/LR-3 are
  open. Do not add promotional words or store badges.
- Alt text draft (≤ 140): "TalentForge app icon and name on a dark background" (51 characters).
  Adjust to the final image.
- Before upload: `file`/Pillow check that it is 1024 × 500 and has no alpha channel (mode `RGB`).

## 4. Phone screenshots (MISSING, owner)

Capture them from the **release build** after the owner's keys exist (an APK from
`assemblePublishableRelease`, or the Play build installed from the closed test). Never use the
debug or `.devcheck` build, a self-test build, a mock-up or an edited image. Use the sequence and
captions of [RuStore/AppGallery §7](../appgallery/store-listing-en.md#7-screenshot-sequence-and-captions)
and the capture procedure in [RuStore/AppGallery assets §3.3](../appgallery/assets-checklist.md#33-capture-procedure-owner-after-tf-rc1-signing-not-executed-here):
1080 × 1920 PNG, status and navigation bars cropped and padded with the sampled background, scaled
uniformly. One set per listing language (EN device, RU device).

Then convert to **24-bit PNG without alpha** (Play rejects screenshots with alpha) and check:

```bash
python3 - <<'PY'
import sys
from pathlib import Path
from PIL import Image
for path in sorted(Path(sys.argv[1]).glob("*.png")):
    with Image.open(path) as image:
        w, h = image.size
        ok = image.mode == "RGB" and 320 <= min(w, h) and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h)
        print("OK  " if ok else "FAIL", path.name, image.mode, w, h)
PY
```

(pass the screenshot directory as the argument). The existing RuStore/AppGallery screenshots, once
captured, fit Play as is if they are saved without alpha: 1080 × 1920 is 9:16, within 320–3840 px
and within the 2:1 ratio.

Screenshots 1–3 show talent icons, talent text and class artwork. That content is covered by LR-1
and LR-3, and Play's IP policy names "Marketing images from … video games" among violations.
The owner's decision to proceed with the current content covers these screenshots too, with the same
risk ([readiness §6](readiness.md#6-legal-constraints-m5legal1)).

## 5. Checklist

- [x] Icon 512 × 512, 32-bit PNG with alpha, ≤ 1024 KB, image unchanged (§2)
- [ ] Icon rights review recorded (L-6) 🔑
- [ ] Feature graphic 1024 × 500, no alpha, original art only, alt text (§3) 🔑
- [ ] EN phone screenshots, 4–8, 1080 × 1920, no alpha, from the release build (§4) 🔑
- [ ] RU phone screenshots, 4–8, same rules (§4) 🔑
- [ ] Final listing text approved (LR-2), EN and RU 🔑
- [ ] Privacy policy URL live ([data safety §3](data-safety.md#3-privacy-policy)) 🔑
