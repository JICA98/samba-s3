#!/usr/bin/env python3
import sys, statistics, json
# Simple median/min/max
runs = [float(x) for x in sys.argv[1:]] if len(sys.argv)>1 else [100,120,110]
print(f"median={statistics.median(runs)} min={min(runs)} max={max(runs)}")
