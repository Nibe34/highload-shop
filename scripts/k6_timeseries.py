#!/usr/bin/env python3
"""Агрегує k6 CSV-вивід (--out csv) у часовий ряд по секундах: RPS, p50/p95/p99, помилки, VUs."""
import csv
import gzip
import sys
from collections import defaultdict


def pct(values, p):
    if not values:
        return 0.0
    values.sort()
    return values[min(len(values) - 1, int(len(values) * p))]


def main(path):
    durations, failed, vus = defaultdict(list), defaultdict(int), {}
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt") as f:
        for row in csv.DictReader(f):
            t = int(row["timestamp"])
            name = row["metric_name"]
            if name == "http_req_duration":
                durations[t].append(float(row["metric_value"]))
            elif name == "http_req_failed" and row["metric_value"] in ("1", "1.000000"):
                failed[t] += 1
            elif name == "vus":
                vus[t] = int(float(row["metric_value"]))
    t0 = min(durations)
    print("t,vus,rps,p50,p95,p99,errors")
    last_vus = 0
    for t in range(t0, max(durations) + 1):
        d = durations.get(t, [])
        last_vus = vus.get(t, last_vus)
        print(f"{t - t0},{last_vus},{len(d)},{pct(d, .5):.2f},{pct(d, .95):.2f},{pct(d, .99):.2f},{failed.get(t, 0)}")


if __name__ == "__main__":
    main(sys.argv[1])
