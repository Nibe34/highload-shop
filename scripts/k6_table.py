#!/usr/bin/env python3
"""Зводить k6 --summary-export JSON-файли в markdown-таблицю.

Використання: k6_table.py label=file.json [label=file.json ...]
"""
import json
import sys


def load(path):
    with open(path) as f:
        return json.load(f)["metrics"]


def row(label, m):
    d = m["http_req_duration"]
    reqs = m["http_reqs"]
    failed = m.get("http_req_failed", {}).get("value", 0) * 100
    inst = []
    for name in ("app-1", "app-2", "app-3"):
        c = m.get(f"responses_by_instance{{instance:{name}}}", {}).get("count")
        if c is not None:
            inst.append(f"{name}: {c}")
    return (f"| {label} | {reqs['rate']:.0f} | {d['avg']:.1f} | {d['med']:.1f} | {d['p(95)']:.1f} | "
            f"{d['p(99)']:.1f} | {failed:.2f} | {', '.join(inst) or '—'} |")


def main(args):
    print("| Конфігурація | RPS | avg, мс | p50, мс | p95, мс | p99, мс | Errors, % | Розподіл по інстансах |")
    print("|---|---|---|---|---|---|---|---|")
    for arg in args:
        label, path = arg.split("=", 1)
        print(row(label, load(path)))


if __name__ == "__main__":
    main(sys.argv[1:])
