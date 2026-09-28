#!/usr/bin/env python3
"""Summarize manually supplied values for display; this is never a run scorer."""

import argparse
import json
import statistics
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("values", nargs="*", type=float, help="unverified numeric samples")
    parser.add_argument("--demo", action="store_true", help="print explicitly labeled demonstration values")
    args = parser.parse_args()

    if args.demo:
        if args.values:
            parser.error("--demo does not accept caller values")
        values = [100.0, 120.0, 110.0]
        status = "DEMO_ONLY_NOT_EVIDENCE"
    elif not args.values:
        print(json.dumps({"status": "INCONCLUSIVE", "reason": "NO_MEASUREMENTS", "samples": []}))
        return 2
    else:
        values = args.values
        status = "UNVERIFIED_NUMERIC_SUMMARY_NOT_A_SCORE"

    print(json.dumps({
        "status": status,
        "sample_count": len(values),
        "median": statistics.median(values),
        "min": min(values),
        "max": max(values),
        "score": None,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
