#!/usr/bin/env python3
import re, sys, json, csv, argparse
parser = argparse.ArgumentParser()
parser.add_argument("input", nargs="?", default="-")
parser.add_argument("--output", default="csv")
args = parser.parse_args()
text = open(args.input).read() if args.input != "-" else sys.stdin.read()
pattern = re.compile(r"PPU_PROFILE.*module=(\S+).*?total_ms=([0-9.]+)")
rows = pattern.findall(text)
writer = csv.writer(sys.stdout)
writer.writerow(["module","total_ms"])
for m in rows: writer.writerow(m)
