#!/usr/bin/env python3
"""Generate kokok launcher icons in the shared family style:
navy background, centered dawn glyph, bottom strip with version text.
Outputs legacy, round, adaptive and monochrome assets into app/src/main/res."""
import math
import os
import re
from PIL import Image, ImageDraw

def _version():
    gradle = os.path.join(os.path.dirname(__file__), "..", "app", "build.gradle.kts")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', open(gradle).read())
    return m.group(1) if m else "0.0.0"

VERSION = _version()
BG = (16, 24, 40, 255)
STRIP = (8, 12, 22, 255)
GLYPH = (242, 244, 247, 255)
ACCENT = (255, 140, 0, 255)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = 432  # foreground asset base size


def dawn_glyph(d, cx, cy, r, color):
    """Rising sun with rays: half-disc on a horizon line plus rays above."""
    d.pieslice([cx - r, cy - r, cx + r, cy + r], 180, 360, fill=color)
    lw = max(2, r // 6)
    d.line([cx - int(r * 1.5), cy, cx + int(r * 1.5), cy], fill=color, width=lw)
    for deg in (35, 70, 110, 145):
        a = math.radians(deg)
        r1, r2 = r + lw * 2, r + lw * 2 + int(r * 0.45)
        x1, y1 = cx - r1 * math.cos(a), cy - r1 * math.sin(a)
        x2, y2 = cx - r2 * math.cos(a), cy - r2 * math.sin(a)
        d.line([x1, y1, x2, y2], fill=color, width=lw)


def icon(size, version_strip=True, glyph_color=GLYPH, strip=True):
    img = Image.new("RGBA", (size, size), BG)
    d = ImageDraw.Draw(img)
    strip_h = int(size * 0.22)
    cy = int((size - strip_h) * 0.66)
    r = int(size * 0.13)
    dawn_glyph(d, size // 2, cy, r, glyph_color)
    if strip:
        d.rectangle([0, size - strip_h, size, size], fill=STRIP)
        if version_strip:
            try:
                from PIL import ImageFont
                font = ImageFont.truetype("DejaVuSans-Bold.ttf", int(strip_h * 0.52))
            except Exception:
                font = ImageFont.load_default()
            tw = d.textlength(VERSION, font=font)
            d.text(((size - tw) / 2, size - strip_h + (strip_h - int(strip_h * 0.52)) / 2 - 1),
                   VERSION, fill=GLYPH, font=font)
    return img


def round_mask(img):
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size, size], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    res = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
    for dpi, px in DENSITIES.items():
        d = os.path.join(res, f"mipmap-{dpi}")
        os.makedirs(d, exist_ok=True)
        icon(px).save(os.path.join(d, "ic_launcher.png"))
        round_mask(icon(px)).save(os.path.join(d, "ic_launcher_round.png"))
        # adaptive foreground: navy bg is provided by the launcher; glyph only,
        # version strip included to satisfy the all-icon-types version rule
        icon(ADAPTIVE, glyph_color=ACCENT).save(os.path.join(d, "ic_launcher_foreground.png"))
        # monochrome themed icon: alpha silhouette of the dawn glyph
        mono = Image.new("RGBA", (ADAPTIVE, ADAPTIVE), (0, 0, 0, 0))
        md = ImageDraw.Draw(mono)
        dawn_glyph(md, ADAPTIVE // 2, int(ADAPTIVE * 0.66), int(ADAPTIVE * 0.13), (0, 0, 0, 255))
        mono.save(os.path.join(d, "ic_launcher_monochrome.png"))
    anydpi = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(anydpi, f"{name}.xml"), "w") as f:
            f.write("""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_foreground"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
""")


if __name__ == "__main__":
    main()
