# Derived from https://github.com/irvan-putra/bidik/blob/main/tools/badge_icon.py
# Shared adaptive-aware family geometry; only Kokok art/palette and Gradle version source differ.
#!/usr/bin/env python3
"""Launcher icon generator: one picture plus the shared version badge.

Deterministic. Same version in, same PNGs out. Inputs are pinned: Pillow
PILLOW_PIN and DejaVu Sans Bold with sha256 FONT_SHA256. The script fails
instead of falling back to another font or Pillow version, because a
different rasterizer or font changes the pixels.
    pip install pillow==12.3.0   (the font is vendored in tools/fonts/)

Badge: numbers only, a full-width strip across the bottom 24% of the icon.
Picture safety: every picture pixel is checked to lie inside the circle that
touches the icon edges (shrunk by a margin), so round launcher masks do not
clip it; the version text is checked the same way.

Outputs match the com.vanipy family standard (same geometry as the other
apps' generators):
- legacy mipmap PNGs per density: rounded-square icon with the strip.
- adaptive-icon foreground: the 72dp face (576px) centred on the 108dp
  layer (864px) with 18dp overscan; the strip spans the full layer width
  from 76% of the face so launchers that crop the overscan still show it.
- themed (monochrome) foreground: white silhouette; the numbers are cut
  out of the strip for contrast.
- mipmap-anydpi-v26 XML and the background colour resource.

`--check` regenerates everything into a temp dir and fails if any
committed resource differs, so CI can prove the PNGs came from this
script.
"""
import hashlib, math, os, re, shutil, sys, tempfile
import PIL
from PIL import Image, ImageChops, ImageDraw, ImageFont

PILLOW_PIN = "12.3.0"
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
FONT_VENDORED = os.path.join(ROOT, "tools", "fonts", "DejaVuSans-Bold.ttf")
FONT_SYSTEM = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_SHA256 = "d1c3ff99f1e1ce1827a33efd4dad81f40babda06bff9e43bd7591c86662a287b"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
S = 768
STRIP_TOP = 0.76      # strip covers 76%..100% of the icon height
TEXT_SIZE = 0.115     # numeral size, fraction of icon size
SAFE_R = 0.485        # picture and text must stay inside this radius (fraction)

if PIL.__version__ != PILLOW_PIN:
    sys.exit("Pillow %s required, found %s" % (PILLOW_PIN, PIL.__version__))


def font_path():
    # Prefer the vendored, hash-checked font (identical on every machine);
    # fall back to the system install for local runs.
    for path in (FONT_VENDORED, FONT_SYSTEM):
        if os.path.exists(path):
            return path
    sys.exit("missing font: neither " + FONT_VENDORED + " nor " + FONT_SYSTEM)


def font(px):
    path = font_path()
    if hashlib.sha256(open(path, "rb").read()).hexdigest() != FONT_SHA256:
        sys.exit("unexpected font file " + path)
    return ImageFont.truetype(path, px)


def circle_mask(size, r=SAFE_R):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([size * (.5 - r), size * (.5 - r), size * (.5 + r), size * (.5 + r)], fill=255)
    return m


def assert_inside_circle(layer, what, size=S, r=SAFE_R):
    alpha = layer.getchannel("A").point(lambda v: 255 if v > 8 else 0)
    outside = ImageChops.multiply(alpha, ImageChops.invert(circle_mask(size, r)))
    box = outside.getbbox()
    if box:
        sys.exit("%s leaves the round-mask safe circle at %s" % (what, box))


def new_layer(size=S):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def sparkle(d, cx, cy, r, fill, size=S):
    pts = []
    for i in range(96):
        t = i * 2 * math.pi / 96
        pts.append((cx * size + r * size * math.cos(t) ** 3, cy * size + r * size * math.sin(t) ** 3))
    d.polygon(pts, fill=fill)


def rrect(d, box, radius, fill, size=S):
    d.rounded_rectangle([c * size for c in box], radius=radius * size, fill=fill)


def compose(bg, art, version, strip, text, rounded=None, size=S, circle_check=True):
    """Background, then the picture layer, then the badge strip."""
    if circle_check:
        assert_inside_circle(art, "picture", size)
    base = Image.new("RGBA", (size, size), bg) if bg else Image.new("RGBA", (size, size), (0, 0, 0, 0))
    base.alpha_composite(art)
    d = ImageDraw.Draw(base)
    y0 = int(size * STRIP_TOP)
    d.rectangle([0, y0, size, size], fill=strip)
    tl, td = new_layer(size)
    f = font(int(size * TEXT_SIZE))
    td.text((size / 2, (y0 + size) / 2), version, font=f, fill=text, anchor="mm")
    if circle_check:
        assert_inside_circle(tl, "version text", size)
    base.alpha_composite(tl)
    if rounded:
        m = Image.new("L", (size, size), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=rounded * size, fill=255)
        base.putalpha(ImageChops.multiply(base.getchannel("A"), m))
    return base


def parse_version(argv, fallback=None):
    v = argv[1] if len(argv) > 1 else fallback
    if not v or not re.fullmatch(r"\d+\.\d+\.\d+", v):
        sys.exit("version must be X.Y.Z")
    return v


def write_densities(img, res_dir, name="ic_launcher.png"):
    for dens, px in DENSITIES.items():
        out = os.path.join(res_dir, "mipmap-" + dens)
        os.makedirs(out, exist_ok=True)
        img.resize((px, px), Image.LANCZOS).save(os.path.join(out, name), optimize=True)


KOKOK_BG = (16, 24, 40, 255)
KOKOK_LIGHT = (242, 244, 247, 255)
KOKOK_STRIP = (8, 12, 22, 255)


def kokok_art():
    """Kokok's original dawn glyph, kept within the family's safe face."""
    art, d = new_layer()
    cx, cy, r = S // 2, int(S * .515), int(S * .13)
    color = KOKOK_LIGHT
    d.pieslice([cx-r, cy-r, cx+r, cy+r], 180, 360, fill=color)
    lw = max(2, r // 6)
    d.line([cx-int(r*1.5), cy, cx+int(r*1.5), cy], fill=color, width=lw)
    for deg in (35, 70, 110, 145):
        a = math.radians(deg)
        r1, r2 = r+lw*2, r+lw*2+int(r*.45)
        d.line([cx-r1*math.cos(a), cy-r1*math.sin(a),
                cx-r2*math.cos(a), cy-r2*math.sin(a)], fill=color, width=lw)
    return art


def kokok(version):
    return compose(KOKOK_BG, kokok_art(), version, KOKOK_STRIP, KOKOK_LIGHT, rounded=.2)


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

COLORS_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#101828</color>
</resources>
"""


def kokok_adaptive(version):
    """Foreground + monochrome layers with the family strip on the face.

    The 72dp face is 576px centred on the 108dp 864px layer (18dp
    overscan). Strip and text use the same 76%/88%/11.5% face geometry as
    the legacy icon, so masked and unmasked launchers show the same badge.
    The themed numbers are cut out of the strip for contrast.
    """
    T, F, I = 864, 576, 144
    art = kokok_art()
    art.paste((255, 140, 0, 255), (0, 0, S, S), art.getchannel("A"))
    art = art.resize((F, F), Image.LANCZOS)
    fg, d = new_layer(T)
    fg.alpha_composite(art, (I, I))
    y = I + int(F * STRIP_TOP)
    d.rectangle([0, y, T, T], fill=KOKOK_STRIP)
    text, td = new_layer(T)
    td.text((T / 2, I + F * .88), version, font=font(int(F * TEXT_SIZE)),
            fill=(255, 255, 255, 255), anchor="mm")
    fg.alpha_composite(text)
    alpha = Image.new("L", (T, T), 0)
    alpha.paste(art.getchannel("A"), (I, I))
    ImageDraw.Draw(alpha).rectangle([0, y, T, T], fill=255)
    alpha = ImageChops.subtract(alpha, text.getchannel("A"))
    mono = Image.new("RGBA", (T, T), (255, 255, 255, 255))
    mono.putalpha(alpha)
    return fg, mono


def write_all(version, res_dir):
    write_densities(kokok(version), res_dir)
    write_densities(kokok(version), res_dir, "ic_launcher_round.png")
    fg, mono = kokok_adaptive(version)
    drawables = os.path.join(res_dir, "drawable-xxxhdpi")
    os.makedirs(drawables, exist_ok=True)
    fg.resize((432, 432), Image.LANCZOS).save(
        os.path.join(drawables, "ic_launcher_foreground.png"), optimize=True)
    mono.resize((432, 432), Image.LANCZOS).save(
        os.path.join(drawables, "ic_launcher_monochrome.png"), optimize=True)
    anydpi = os.path.join(res_dir, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w") as fh:
            fh.write(ADAPTIVE_XML)
    values = os.path.join(res_dir, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "colors.xml"), "w") as fh:
        fh.write(COLORS_XML)


def written_paths(res_dir):
    paths = [os.path.join("mipmap-" + d, n) for d in DENSITIES
             for n in ("ic_launcher.png", "ic_launcher_round.png")]
    paths += [
        os.path.join("drawable-xxxhdpi", "ic_launcher_foreground.png"),
        os.path.join("drawable-xxxhdpi", "ic_launcher_monochrome.png"),
        os.path.join("mipmap-anydpi-v26", "ic_launcher.xml"),
        os.path.join("mipmap-anydpi-v26", "ic_launcher_round.xml"),
        os.path.join("values", "colors.xml"),
    ]
    return [os.path.join(res_dir, p) for p in paths]


def check(version, res_dir):
    """Fail unless every committed resource matches this script's output."""
    tmp = tempfile.mkdtemp(prefix="kokok-icons-")
    try:
        write_all(version, tmp)
        bad = []
        for generated in written_paths(tmp):
            committed = os.path.join(res_dir, os.path.relpath(generated, tmp))
            if not os.path.exists(committed) or open(committed, "rb").read() != open(generated, "rb").read():
                bad.append(os.path.relpath(committed, res_dir))
        if bad:
            sys.exit("committed launcher resources differ from the generator: " + ", ".join(bad))
        print("launcher resources match the generator for", version)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def default_version():
    with open(os.path.join(ROOT, "app", "build.gradle.kts")) as fh:
        return re.search(r'versionName\s*=\s*"([^"]+)"', fh.read()).group(1)


def main():
    check_mode = "--check" in sys.argv[1:]
    argv = [a for a in sys.argv[1:] if a != "--check"]
    version = parse_version(["badge_icon.py"] + argv, fallback=default_version())
    res_dir = os.path.join(ROOT, "app", "src", "main", "res")
    if check_mode:
        check(version, res_dir)
    else:
        write_all(version, res_dir)
        print("wrote icons for", version)


if __name__ == "__main__":
    main()
