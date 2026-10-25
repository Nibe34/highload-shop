#!/usr/bin/env python3
"""ЛР10: таблиця фінальної оптимізації (Tomcat threads × LB algorithm), 100 VUs, 2 повтори."""
import json
import statistics

R = "results/lab10/tuning"
print("| Сценарій | Tomcat threads | LB | RPS (2 прогони) | p95, мс | p99, мс |")
print("|---|---|---|---|---|---|")
for s in "AC":
    for t in (200, 16):
        for algo, name in (("round-robin", "Round Robin"), ("least-conn", "Least Connections")):
            ms = [json.load(open(f"{R}/t{t}-{algo}-{s}-{r}.json"))["metrics"] for r in (1, 2)]
            rps = [m["http_reqs"]["rate"] for m in ms]
            p95 = statistics.mean(m["http_req_duration"]["p(95)"] for m in ms)
            p99 = statistics.mean(m["http_req_duration"]["p(99)"] for m in ms)
            print(f"| {s} | {t} | {name} | {rps[0]:.0f}, {rps[1]:.0f} | {p95:.1f} | {p99:.1f} |")
print("\nОдиночний свіп (Round Robin, 100 VUs):\n")
print("| Tomcat threads | A: RPS / p99 | C: RPS / p99 |")
print("|---|---|---|")
for t in (200, 64, 32, 16):
    a = json.load(open(f"{R}/t{t}-A-100.json"))["metrics"]
    c = json.load(open(f"{R}/t{t}-C-100.json"))["metrics"]
    print(f"| {t} | {a['http_reqs']['rate']:.0f} / {a['http_req_duration']['p(99)']:.0f} мс | {c['http_reqs']['rate']:.0f} / {c['http_req_duration']['p(99)']:.0f} мс |")
