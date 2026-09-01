#!/usr/bin/env python3
"""Extract keyboard key hit regions from the SVG data-code groups."""

from __future__ import annotations

import argparse
import json
import math
import re
import xml.etree.ElementTree as ET
from pathlib import Path


SVG_NS = "http://www.w3.org/2000/svg"
NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"


def identity() -> tuple[float, float, float, float, float, float]:
    return 1.0, 0.0, 0.0, 1.0, 0.0, 0.0


def multiply(a, b):
    aa, ab, ac, ad, ae, af = a
    ba, bb, bc, bd, be, bf = b
    return (
        aa * ba + ac * bb,
        ab * ba + ad * bb,
        aa * bc + ac * bd,
        ab * bc + ad * bd,
        aa * be + ac * bf + ae,
        ab * be + ad * bf + af,
    )


def transform_matrix(value: str | None):
    result = identity()
    if not value:
        return result
    for name, args in re.findall(r"(translate|scale|rotate)\s*\(([^)]*)\)", value):
        values = [float(item) for item in re.findall(NUMBER, args)]
        if name == "translate":
            matrix = (1, 0, 0, 1, values[0], values[1] if len(values) > 1 else 0)
        elif name == "scale":
            sy = values[1] if len(values) > 1 else values[0]
            matrix = (values[0], 0, 0, sy, 0, 0)
        else:
            angle = math.radians(values[0])
            cos, sin = math.cos(angle), math.sin(angle)
            matrix = (cos, sin, -sin, cos, 0, 0)
        result = multiply(result, matrix)
    return result


def apply(matrix, point):
    a, b, c, d, e, f = matrix
    x, y = point
    return a * x + c * y + e, b * x + d * y + f


def local_bounds(rect: ET.Element):
    return (
        float(rect.attrib["x"]),
        float(rect.attrib["y"]),
        float(rect.attrib["x"]) + float(rect.attrib["width"]),
        float(rect.attrib["y"]) + float(rect.attrib["height"]),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = ET.parse(args.input).getroot()
    view_box = [float(value) for value in re.findall(NUMBER, root.attrib["viewBox"])]
    regions = []

    def visit(node: ET.Element, parent_matrix) -> None:
        matrix = multiply(parent_matrix, transform_matrix(node.attrib.get("transform")))
        code = node.attrib.get("data-code")
        if code:
            cap = next(
                (
                    child
                    for child in node.iter()
                    if child.tag == f"{{{SVG_NS}}}rect" and "cap" in child.attrib.get("class", "").split()
                ),
                None,
            )
            if cap is None:
                raise ValueError(f"data-code group has no cap rect: {code}")
            left, top, right, bottom = local_bounds(cap)
            corners = [
                apply(matrix, (left, top)),
                apply(matrix, (right, top)),
                apply(matrix, (right, bottom)),
                apply(matrix, (left, bottom)),
            ]
            xs, ys = zip(*corners)
            regions.append({
                "code": code,
                "bounds": {
                    "left": min(xs), "top": min(ys),
                    "right": max(xs), "bottom": max(ys),
                },
            })
        for child in node:
            visit(child, matrix)

    visit(root, identity())
    if len(regions) != 104:
        raise ValueError(f"expected 104 keyboard regions, found {len(regions)}")

    args.output.write_text(
        json.dumps({
            "viewBox": {"width": view_box[2], "height": view_box[3]},
            "regions": regions,
        }, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
