"""Samples service metrics and container CPU/RAM every few seconds and prints CSV.

Usage: python -I sample.py [interval_seconds] > samples.csv   (stop with Ctrl+C / kill)
Reads APP_PORT (default 18080) and PROJECT (Compose project name, default hosload) from the environment;
needs `docker` on PATH.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

BASE = f"http://localhost:{os.environ.get('APP_PORT', '18080')}/actuator/metrics/"
PROJECT = os.environ.get("PROJECT", "hosload")
COUNTERS = ["telemetry.messages.received", "telemetry.messages.dropped", "telemetry.rows.written",
            "telemetry.queue.depth", "telemetry.batches.failed", "process.files.open", "process.cpu.usage"]
CONTAINERS = [f"{PROJECT}-app-1", f"{PROJECT}-db-1"]


def metric(name, stat="VALUE"):
    try:
        with urllib.request.urlopen(BASE + name, timeout=3) as r:
            body = json.load(r)
        return next((m["value"] for m in body["measurements"] if m["statistic"] in (stat, "COUNT")), "")
    except Exception:
        return ""


def container_stats():
    out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}};{{.CPUPerc}};{{.MemUsage}}"] + CONTAINERS,
                         capture_output=True, text=True).stdout
    return {line.split(";")[0]: line.split(";")[1:] for line in out.splitlines() if ";" in line}


interval = float(sys.argv[1]) if len(sys.argv) > 1 else 5
print("t," + ",".join(COUNTERS) + ",app_cpu,app_mem,db_cpu,db_mem", flush=True)
start = time.time()
while True:
    stats = container_stats()
    app = stats.get(CONTAINERS[0], ["", ""])
    db = stats.get(CONTAINERS[1], ["", ""])
    row = [f"{time.time() - start:.0f}"] + [str(metric(c)) for c in COUNTERS] + [app[0], app[1].split(" / ")[0], db[0], db[1].split(" / ")[0]]
    print(",".join(row), flush=True)
    time.sleep(max(0, interval - 1.5))
