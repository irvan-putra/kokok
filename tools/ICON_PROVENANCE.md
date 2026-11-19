# Launcher generation

Family generator ported from Bidik tools/badge_icon.py, fetched 2026-11-19:
https://github.com/irvan-putra/bidik/blob/main/tools/badge_icon.py

Kept its 72dp face / 108dp adaptive layer, badge geometry, deterministic Pillow/font checks, monochrome number cutouts, mask-safety checks, and --check mechanism. Adapted only Kokok's existing dawn art and palette, round resource output, and version input from app/build.gradle.kts. scripts/make_icons.py delegates to this generator. No alternative font fallback is allowed.

Generate with python scripts/make_icons.py. Validate with python tools/badge_icon.py --check. Requires Pillow 12.3.0. UiRenderTest renders the actual manifest-loaded AdaptiveIconDrawable and its monochrome layer with the Android mask, as well as legacy resources. A device launcher remains a separate verification step.
