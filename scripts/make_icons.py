#!/usr/bin/env python3
"""Generate kokok launcher icons: sun over horizon + version strip badge.
Outputs legacy, round and adaptive-icon assets into app/src/main/res."""
import os
from PIL import Image, ImageDraw

VERSION = "0.1.0"
ORANGE = (255, 140, 0, 255)
DEEP = (30, 20, 40, 255)
YELLOW = (255, 200, 90, 255)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = 432  # foreground asset base size


def sun_scene(size, version_strip=True):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    horizon = int(size * 0.62)
    d.rectangle([0, horizon, size, size], fill=DEEP)
    r = int(size * 0.20)
    cx, cy = size // 2, horizon
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ORANGE)
    d.rectangle([0, 0, size, horizon], fill=(255, 240, 220, 255))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ORANGE)
    d.rectangle([0, horizon - 2, size, horizon + 2], fill=YELLOW)
    if version_strip:
        strip_h = max(10, size // 10)
        d.rectangle([0, size - strip_h, size, size], fill=(0, 0, 0, 200))
        try:
            from PIL import ImageFont
            font = ImageFont.truetype("DejaVuSansMono-Bold.ttf", int(strip_h * 0.62))
        except Exception:
            font = ImageFont.load_default()
        tw = d.textlength(VERSION, font=font)
        d.text(((size - tw) / 2, size - strip_h + (strip_h - int(strip_h * 0.62)) / 2 - 1),
               VERSION, fill=(255, 255, 255, 255), font=font)
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
        sun_scene(px).save(os.path.join(d, "ic_launcher.png"))
        round_mask(sun_scene(px)).save(os.path.join(d, "ic_launcher_round.png"))
        sun_scene(ADAPTIVE).save(os.path.join(d, "ic_launcher_foreground.png"))
        # monochrome themed icon: alpha silhouette of sun + horizon
        mono = Image.new("RGBA", (ADAPTIVE, ADAPTIVE), (0, 0, 0, 0))
        md = ImageDraw.Draw(mono)
        h = int(ADAPTIVE * 0.62)
        r = int(ADAPTIVE * 0.20)
        cx = ADAPTIVE // 2
        md.ellipse([cx - r, h - r, cx + r, h + r], fill=(0, 0, 0, 255))
        md.rectangle([0, h - 6, ADAPTIVE, h + 6], fill=(0, 0, 0, 0))
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
