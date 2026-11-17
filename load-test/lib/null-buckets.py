"""Reads a Charts API series response on stdin and lists the null buckets inside a time window.

Usage: curl -s <series url> | python -I null-buckets.py <from_epoch_s> <to_epoch_s>
Prints "total=<buckets in window> null=<count>" and then one line per null bucket (ISO timestamp).
Only buckets that start inside [from, to] are counted, so the partly covered bucket at the start counts too.
"""
import json
import sys
from datetime import datetime

start, end = float(sys.argv[1]), float(sys.argv[2])
points = json.load(sys.stdin)["points"]
step = (datetime.fromisoformat(points[1]["ts"].replace("Z", "+00:00"))
        - datetime.fromisoformat(points[0]["ts"].replace("Z", "+00:00"))).total_seconds()
inside = []
for p in points:
    t = datetime.fromisoformat(p["ts"].replace("Z", "+00:00")).timestamp()
    if t + step > start and t <= end:
        inside.append((p["ts"], p["avg"] is None))
nulls = [ts for ts, is_null in inside if is_null]
print(f"total={len(inside)} null={len(nulls)}")
for ts in nulls:
    print(ts)
