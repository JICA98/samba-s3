#!/usr/bin/env python3
"""Create the runtime SVGs from the user-supplied visual sources.

The original files are intentionally kept unchanged under docs/assets/source.
Runtime SVGs are static assets, so browser-only scripts and status bars are
removed while the artwork, data attributes, and keyboard geometry remain.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ARROW_ENTITIES = {
    "&amp;#8593;": "↑",
    "&amp;#8592;": "←",
    "&amp;#8595;": "↓",
    "&amp;#8594;": "→",
    "&amp;#183;": "·",
}


def remove_scripts(svg: str) -> str:
    return re.sub(r"\n\s*<script\b[^>]*>.*?</script>", "", svg, flags=re.DOTALL)


def sanitize_keyboard(svg: str) -> str:
    svg = remove_scripts(svg)
    svg = re.sub(r"\n\s*<!-- built-in highlighting:.*?-->\n", "\n", svg, count=1)
    svg = re.sub(r"\n\s*<!-- status bar .*? -->\n", "\n", svg, count=1)
    svg = re.sub(r"\n\s*<text x=\"28\" y=\"436\"[^>]*>ANSI-104</text>", "", svg, count=1)
    svg = re.sub(r"\n\s*<text id=\"kb-status\".*?</text>", "", svg, count=1)
    for old, new in ARROW_ENTITIES.items():
        svg = svg.replace(old, new)
    return svg


def sanitize_ds3(svg: str) -> str:
    svg = remove_scripts(svg)
    svg = re.sub(r"\n\s*<!-- status bar -->\n", "\n", svg, count=1)
    svg = re.sub(r"\n\s*<text class=\"tiny\" x=\"28\" y=\"536\">.*?</text>", "", svg, count=1)
    svg = re.sub(r"\n\s*<text id=\"pad-status\".*?</text>", "", svg, count=1)
    svg = re.sub(r"\n\s*<text class=\"tiny\" x=\"972\" y=\"536\"[^>]*>.*?</text>", "", svg, count=1)
    # The supplied START caption runs into the Square button. Keep the
    # button geometry unchanged and move both small-button captions above it.
    svg = svg.replace('<text class="lbl" x="408" y="217">SELECT</text>', '<text class="lbl" x="408" y="181">SELECT</text>')
    svg = svg.replace('<text class="lbl" x="592" y="217">START</text>', '<text class="lbl" x="592" y="181">START</text>')
    svg = re.sub(r'(viewBox=")0 0 1000 560("[^>]* width=")1000(" height=")560(")', r'\g<1>0 0 1000 500\g<2>1000\g<3>500\g<4>', svg, count=1)
    return svg


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keyboard-source", type=Path, required=True)
    parser.add_argument("--keyboard-output", type=Path, required=True)
    parser.add_argument("--ds3-source", type=Path, required=True)
    parser.add_argument("--ds3-output", type=Path, required=True)
    args = parser.parse_args()

    keyboard = sanitize_keyboard(args.keyboard_source.read_text(encoding="utf-8"))
    ds3 = sanitize_ds3(args.ds3_source.read_text(encoding="utf-8"))
    args.keyboard_output.write_text(keyboard, encoding="utf-8")
    args.ds3_output.write_text(ds3, encoding="utf-8")


if __name__ == "__main__":
    main()
