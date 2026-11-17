"""Prints the peak values of a sampler.csv (written by sample.py): queue depth, open fds, CPU and memory.

Usage: python -I csv-peaks.py <sampler.csv>
"""
import csv
import sys

UNITS = {"B": 1 / 1048576, "kB": 1 / 1024, "KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "MB": 1, "GB": 1024}


def number(text):
    """Parses '12.3%' or '554MiB' into a float (MiB for memory); None for empty cells."""
    text = text.strip()
    if not text:
        return None
    for suffix in sorted(UNITS, key=len, reverse=True):
        if text.endswith(suffix):
            return float(text[: -len(suffix)]) * UNITS[suffix]
    return float(text.rstrip("%"))


with open(sys.argv[1], newline="") as f:
    rows = list(csv.DictReader(f))


def peak(column):
    values = [number(r[column]) for r in rows if r.get(column) is not None]
    values = [v for v in values if v is not None]
    return max(values) if values else None


def show(label, column, fmt):
    value = peak(column)
    print(f"  {label}: {'n/a' if value is None else fmt.format(value)}")


print(f"  samples: {len(rows)}")
show("queue depth", "telemetry.queue.depth", "{:.0f}")
show("open fds (app)", "process.files.open", "{:.0f}")
show("app CPU", "app_cpu", "{:.0f} %")
show("app memory", "app_mem", "{:.0f} MiB")
show("db CPU", "db_cpu", "{:.0f} %")
show("db memory", "db_mem", "{:.0f} MiB")
