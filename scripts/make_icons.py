#!/usr/bin/env python3
"""Compatibility entrypoint for the shared adaptive-aware family generator."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().parents[1] / "tools" / "badge_icon.py"), run_name="__main__")
