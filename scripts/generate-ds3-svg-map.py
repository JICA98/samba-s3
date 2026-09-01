#!/usr/bin/env python3
"""Extract DS3 interaction regions from the supplied data-button groups."""

from __future__ import annotations

import argparse
import json
import math
import re
import xml.etree.ElementTree as ET
from pathlib import Path


SVG_NS = "http://www.w3.org/2000/svg"
NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"
BUTTON_IDS = {
    "r2": "btn_r2", "l2": "btn_l2", "r1": "btn_r1", "l1": "btn_l1",
    "dpad-up": "btn_dpad_up", "dpad-down": "btn_dpad_down",
    "dpad-left": "btn_dpad_left", "dpad-right": "btn_dpad_right",
    "triangle": "btn_triangle", "circle": "btn_circle",
    "cross": "btn_cross", "square": "btn_square",
    "select": "btn_select", "start": "btn_start", "ps": "btn_guide",
    "l3": "stick_left", "r3": "stick_right",
}


def identity():
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
            if len(values) >= 3:
                cx, cy = values[1], values[2]
                matrix = multiply(multiply((1, 0, 0, 1, cx, cy), matrix), (1, 0, 0, 1, -cx, -cy))
        result = multiply(result, matrix)
    return result


def apply(matrix, point):
    a, b, c, d, e, f = matrix
    x, y = point
    return a * x + c * y + e, b * x + d * y + f


def bounds_for_element(element: ET.Element, matrix):
    points = []
    path_nodes = []
    for child in element.iter():
        tag = child.tag.rsplit("}", 1)[-1]
        if tag == "rect":
            x, y = float(child.attrib.get("x", 0)), float(child.attrib.get("y", 0))
            w, h = float(child.attrib["width"]), float(child.attrib["height"])
            points.extend([(x, y), (x + w, y), (x + w, y + h), (x, y + h)])
        elif tag == "circle":
            x, y, radius = float(child.attrib.get("cx", 0)), float(child.attrib.get("cy", 0)), float(child.attrib["r"])
            points.extend([(x - radius, y - radius), (x + radius, y + radius)])
        elif tag == "ellipse":
            x, y = float(child.attrib["cx"]), float(child.attrib["cy"])
            rx, ry = float(child.attrib["rx"]), float(child.attrib["ry"])
            points.extend([(x - rx, y - ry), (x + rx, y + ry)])
        elif tag == "path":
            path_nodes.append(child)
    if not points:
        # Trigger paths in this source use absolute coordinates. Their
        # numeric extrema are a safe hit envelope when no primitive gives us
        # a tighter region (buttons with circles/rects use those exactly).
        for child in path_nodes:
            values = [float(value) for value in re.findall(NUMBER, child.attrib.get("d", ""))]
            points.extend(zip(values[0::2], values[1::2]))
    if not points:
        raise ValueError(f"no geometry for {element.attrib.get('data-button')}")
    transformed = [apply(matrix, point) for point in points]
    xs, ys = zip(*transformed)
    return {"left": min(xs), "top": min(ys), "right": max(xs), "bottom": max(ys)}


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
        button = node.attrib.get("data-button")
        if button:
            region_id = BUTTON_IDS[button]
            regions.append({
                "id": region_id,
                "kind": "stick" if button in ("l3", "r3") else "button",
                "bounds": bounds_for_element(node, matrix),
            })
        for child in node:
            visit(child, matrix)

    visit(root, identity())
    if set(region["id"] for region in regions) != set(BUTTON_IDS.values()):
        raise ValueError("DS3 region IDs do not match the source data-button groups")

    args.output.write_text(
        json.dumps({
            "viewBox": {"width": view_box[2], "height": view_box[3]},
            "regions": regions,
        }, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
