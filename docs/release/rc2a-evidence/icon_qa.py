"""Offline QA renders for the M5.RC.2a icon assets.

Usage: python3 -I icon_qa.py <assets_dir> <out_dir>

Everything here is a *simulation* of launcher masking (geometry from the
Android adaptive-icon spec and an iOS rounded-rect approximation). It does
not replace on-device verification.
"""

import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SRC = Path(sys.argv[1])
OUT = Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)

res = SRC / "android/res"
fg = Image.open(res / "drawable-nodpi/ic_launcher_foreground.png").convert("RGBA")
bg = Image.open(res / "drawable-nodpi/ic_launcher_background.png").convert("RGBA")
mono = Image.open(res / "drawable-nodpi/ic_launcher_monochrome.png").convert("RGBA")
ios = Image.open(SRC / "ios/AppIcon.appiconset/AppIcon-1024.png").convert("RGBA")

N = fg.size[0]  # 432 px == 108 dp  -> 4 px/dp
PX_PER_DP = N / 108
report: dict = {"canvas_px": N, "px_per_dp": PX_PER_DP}


def alpha_geometry(img: Image.Image, thr: int) -> dict:
    a = img.getchannel("A")
    bbox = a.point(lambda v: 255 if v > thr else 0).getbbox()
    c = (N - 1) / 2
    maxr = 0.0
    px = a.load()
    for y in range(N):
        for x in range(N):
            if px[x, y] > thr:
                r = math.hypot(x - c, y - c)
                if r > maxr:
                    maxr = r
    left, top, right, bottom = bbox
    return {
        "threshold": thr,
        "bbox_px": bbox,
        "bbox_dp": [round(v / PX_PER_DP, 1) for v in bbox],
        "content_w_dp": round((right - left) / PX_PER_DP, 1),
        "content_h_dp": round((bottom - top) / PX_PER_DP, 1),
        "max_radius_dp": round(maxr / PX_PER_DP, 1),
    }


for name, img in (("foreground", fg), ("monochrome", mono)):
    report[name] = {str(t): alpha_geometry(img, t) for t in (8, 64)}
report["safe_zone_radius_dp"] = 33
report["visible_square_dp"] = 72
for name in ("foreground", "monochrome"):
    g = report[name]["8"]
    g["inside_safe_circle"] = g["max_radius_dp"] <= 33.0
    left, top, right, bottom = g["bbox_dp"]
    g["inside_safe_square_21_87"] = left >= 21 and top >= 21 and right <= 87 and bottom <= 87

bg_colors = bg.convert("RGB").getcolors(1 << 20)
report["background_unique_colors"] = len(bg_colors)
report["background_mean_rgb"] = [
    round(sum(c[1][i] * c[0] for c in bg_colors) / (N * N)) for i in range(3)
]
report["monochrome_unique_rgb"] = len(mono.convert("RGB").getcolors(1 << 20))
report["ios_alpha_extrema"] = ios.getchannel("A").getextrema()


def mask(shape: str, size: int) -> Image.Image:
    """Mask over the visible 72 dp square scaled to `size` px."""
    ss = 4
    S = size * ss
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, S - 1, S - 1], fill=255)
    elif shape == "rounded_square":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.16), fill=255)
    elif shape == "squircle":
        n = 4.0
        pts = []
        for i in range(720):
            th = 2 * math.pi * i / 720
            ct, st = math.cos(th), math.sin(th)
            x = abs(ct) ** (2 / n) * (1 if ct >= 0 else -1)
            y = abs(st) ** (2 / n) * (1 if st >= 0 else -1)
            pts.append(((x + 1) / 2 * (S - 1), (y + 1) / 2 * (S - 1)))
        d.polygon(pts, fill=255)
    elif shape == "teardrop":
        r = S // 2
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=255)
        d.rectangle([r, r, S - 1, S - 1], fill=255)
    elif shape == "ios":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.2237), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def visible(layer: Image.Image) -> Image.Image:
    o = int(18 * PX_PER_DP)
    return layer.crop((o, o, N - o, N - o))


full = Image.alpha_composite(bg, fg)
vis = visible(full)


def adaptive(shape: str, size: int, src: Image.Image = vis) -> Image.Image:
    im = src.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask(shape, size))
    return out


def themed(dark: bool, size: int) -> Image.Image:
    bgc = (0x2B, 0x2F, 0x38, 255) if dark else (0xD8, 0xE2, 0xFF, 255)
    fgc = (0xB4, 0xC5, 0xFF, 255) if dark else (0x1A, 0x2B, 0x5C, 255)
    a = visible(mono).getchannel("A")
    layer = Image.new("RGBA", a.size, bgc)
    layer.paste(Image.new("RGBA", a.size, fgc), (0, 0), a)
    return adaptive("circle", size, layer)


def ios_masked(size: int) -> Image.Image:
    im = ios.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask("ios", size))
    return out


try:
    font = ImageFont.truetype("DejaVuSans.ttf", 13)
except OSError:
    font = ImageFont.load_default()

W, H = 1420, 760
sheet = Image.new("RGBA", (W, H), (0x22, 0x24, 0x28, 255))
d = ImageDraw.Draw(sheet)
d.text(
    (24, 16),
    "TalentForge M5.RC.2a - SIMULATED launcher masks (not a device screenshot)",
    fill="white",
    font=font,
)
x = 24
for shape in ("circle", "squircle", "rounded_square", "teardrop"):
    sheet.alpha_composite(adaptive(shape, 200), (x, 50))
    d.text((x, 258), f"Android adaptive: {shape}", fill="white", font=font)
    x += 230
sheet.alpha_composite(ios_masked(200), (x, 50))
d.text((x, 258), "iOS (system mask approx.)", fill="white", font=font)

y = 300
d.text(
    (24, y),
    "48 dp at 1:1 pixels (mdpi 48 / xhdpi 96 / xxhdpi 144 px), circle mask; "
    "legacy rasters; iOS 40/60/120/180 px",
    fill="white",
    font=font,
)
x = 24
for px in (48, 96, 144):
    sheet.alpha_composite(adaptive("circle", px), (x, y + 30))
    d.text((x, y + 180), f"adaptive {px}", fill="white", font=font)
    x += px + 40
for dens, px in (("mdpi", 48), ("xhdpi", 96), ("xxhdpi", 144)):
    sheet.alpha_composite(
        Image.open(res / f"mipmap-{dens}/ic_launcher.png").convert("RGBA"), (x, y + 30)
    )
    d.text((x, y + 180), f"legacy {dens}", fill="white", font=font)
    x += px + 40
x += 10
for px in (40, 60, 120, 180):
    sheet.alpha_composite(ios_masked(px), (x, y + 30))
    d.text((x, y + 200), f"iOS {px}", fill="white", font=font)
    x += px + 30

y = 520
d.text(
    (24, y),
    "Android 13+ themed icon from the monochrome layer (illustrative tones): "
    "light / dark, and at 48 px",
    fill="white",
    font=font,
)
pl = Image.new("RGBA", (220, 190), (0xF4, 0xF4, 0xF8, 255))
pl.alpha_composite(themed(False, 144), (38, 20))
pd = Image.new("RGBA", (220, 190), (0x11, 0x12, 0x16, 255))
pd.alpha_composite(themed(True, 144), (38, 20))
sheet.alpha_composite(pl, (24, y + 30))
sheet.alpha_composite(pd, (264, y + 30))
sheet.alpha_composite(themed(True, 48), (520, y + 60))
sheet.alpha_composite(themed(False, 48), (590, y + 60))
sheet.convert("RGB").save(OUT / "simulated-masks.png", optimize=True)

ov = full.copy()
od = ImageDraw.Draw(ov)
c = N / 2
for r_dp, col in ((33, (0, 255, 0, 255)), (36, (255, 0, 0, 255))):
    r = r_dp * PX_PER_DP
    od.ellipse([c - r, c - r, c + r, c + r], outline=col, width=2)
o18 = 18 * PX_PER_DP
od.rectangle([o18, o18, N - o18, N - o18], outline=(255, 255, 0, 255), width=1)
ov.convert("RGB").save(OUT / "safe-zone-overlay.png", optimize=True)

(OUT / "icon-qa.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
