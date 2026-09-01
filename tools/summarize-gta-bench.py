#!/usr/bin/env python3
"""Summarize S3BENCH logs and render a dependency-free SVG graph."""

from __future__ import annotations

import argparse
import csv
import html
import math
import re
from pathlib import Path


SAMPLE_RE = re.compile(
    r"state=ready\s+fps=(?P<fps>-?[0-9.]+)\s+"
    r"frametime_ms=(?P<frametime>-?[0-9.]+)\s+"
    r"presented=(?P<presented>-?[0-9]+)\s+"
    r"vblank_delta=(?P<vblank>-?[0-9]+)\s+"
    r"host_cpu=(?P<host>-?[0-9.]+)\s+"
    r"ppu_cpu=(?P<ppu>-?[0-9.]+)\s+"
    r"spu_cpu=(?P<spu>-?[0-9.]+)\s+"
    r"rsx_cpu=(?P<rsx>-?[0-9.]+)\s+"
    r"rsx_load=(?P<load>-?[0-9.]+)"
)


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else math.nan


def parse_log(path: Path) -> dict[str, object] | None:
    samples: list[dict[str, float]] = []
    for line in path.read_text(errors="replace").splitlines():
        match = SAMPLE_RE.search(line)
        if match:
            samples.append({key: float(value) for key, value in match.groupdict().items()})
    if not samples:
        return None
    fps = [sample["fps"] for sample in samples]
    return {
        "run": path.stem.removeprefix("gameplay-"),
        "samples": len(samples),
        "fps_mean": mean(fps),
        "fps_min": min(fps),
        "fps_max": max(fps),
        "frametime_mean_ms": mean([sample["frametime"] for sample in samples]),
        "host_cpu_mean_pct": mean([sample["host"] for sample in samples]),
        "ppu_cpu_mean_pct": mean([sample["ppu"] for sample in samples]),
        "rsx_cpu_mean_pct": mean([sample["rsx"] for sample in samples]),
        "rsx_load_mean_pct": mean([sample["load"] for sample in samples]),
        "vblank_mean": mean([sample["vblank"] for sample in samples]),
        "presented_last": int(samples[-1]["presented"]),
        "power_w": "",
        "power_status": "not measured: tablet remained physically charging",
    }


def write_csv(rows: list[dict[str, object]], path: Path) -> None:
    fields = [
        "run",
        "samples",
        "fps_mean",
        "fps_min",
        "fps_max",
        "frametime_mean_ms",
        "host_cpu_mean_pct",
        "ppu_cpu_mean_pct",
        "rsx_cpu_mean_pct",
        "rsx_load_mean_pct",
        "vblank_mean",
        "presented_last",
        "power_w",
        "power_status",
    ]
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    key: f"{value:.3f}" if isinstance(value, float) else value
                    for key, value in row.items()
                }
            )


def write_svg(rows: list[dict[str, object]], path: Path) -> None:
    width, height = 1100, 620
    left, right, top, bottom = 90, 40, 45, 85
    plot_width = width - left - right
    plot_height = height - top - bottom
    max_fps = max(60.0, max(float(row["fps_max"]) for row in rows))
    min_fps = min(0.0, min(float(row["fps_min"]) for row in rows))
    x_step = plot_width / max(1, len(rows) - 1)

    def x(index: int) -> float:
        return left + index * x_step

    def y(value: float) -> float:
        return top + (max_fps - value) / (max_fps - min_fps) * plot_height

    elements = [
        f'<rect width="{width}" height="{height}" fill="#10151b"/>',
        f'<text x="{left}" y="26" fill="#f2f5f7" font-family="sans-serif" font-size="18">GTA San Andreas — OnePlus Pad 2 benchmark</text>',
        f'<text x="{left}" y="{height - 18}" fill="#aeb8c2" font-family="sans-serif" font-size="12">Gameplay runs; FPS from native emu_flip/presented-frame counter. Charging power was not measured.</text>',
    ]
    for tick in range(0, 61, 10):
        yy = y(float(tick))
        elements.append(f'<line x1="{left}" x2="{width - right}" y1="{yy:.1f}" y2="{yy:.1f}" stroke="#2b3640"/>')
        elements.append(f'<text x="{left - 12}" y="{yy + 4:.1f}" text-anchor="end" fill="#aeb8c2" font-family="sans-serif" font-size="11">{tick}</text>')

    historical_y = y(20.0)
    elements.append(f'<line x1="{left}" x2="{width - right}" y1="{historical_y:.1f}" y2="{historical_y:.1f}" stroke="#e6a23c" stroke-dasharray="6 5"/>')
    elements.append(f'<text x="{width - right - 4}" y="{historical_y - 6:.1f}" text-anchor="end" fill="#e6a23c" font-family="sans-serif" font-size="11">reported ~20 FPS reference</text>')

    points = " ".join(f"{x(index):.1f},{y(float(row['fps_mean'])):.1f}" for index, row in enumerate(rows))
    elements.append(f'<polyline points="{points}" fill="none" stroke="#69c0ff" stroke-width="3"/>')
    for index, row in enumerate(rows):
        xx, yy = x(index), y(float(row["fps_mean"]))
        label = html.escape(str(row["run"]))
        elements.append(f'<circle cx="{xx:.1f}" cy="{yy:.1f}" r="5" fill="#69c0ff"/>')
        elements.append(f'<text x="{xx:.1f}" y="{height - 53}" text-anchor="end" transform="rotate(-35 {xx:.1f},{height - 53})" fill="#d2d9df" font-family="sans-serif" font-size="11">{label}</text>')
        elements.append(f'<text x="{xx:.1f}" y="{yy - 10:.1f}" text-anchor="middle" fill="#f2f5f7" font-family="sans-serif" font-size="11">{float(row["fps_mean"]):.1f}</text>')

    path.write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="620" viewBox="0 0 1100 620">'
        + "".join(elements)
        + "</svg>\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact_dir", type=Path)
    args = parser.parse_args()
    rows = []
    for path in sorted(args.artifact_dir.glob("gameplay-*.log")):
        row = parse_log(path)
        if row:
            rows.append(row)
    if not rows:
        raise SystemExit("no ready S3BENCH samples found")
    write_csv(rows, args.artifact_dir / "summary.csv")
    write_svg(rows, args.artifact_dir / "fps-summary.svg")
    print(f"summarized {len(rows)} runs")


if __name__ == "__main__":
    main()
