#!/usr/bin/env python3
"""Таблиці ЛР9: фази baseline → index → batch та медіани контрольованих A/B-експериментів."""
import json
import statistics

R = "results/lab09"


def load(path):
    m = json.load(open(f"{path}.json"))["metrics"]
    cpu = {k: float(v) for k, v in (l.split() for l in open(f"{path}.cpu"))}
    d = m["http_req_duration"]
    return {"rps": m["http_reqs"]["rate"], "p50": d["med"], "p95": d["p(95)"], "p99": d["p(99)"],
            "err": m["http_req_failed"]["value"] * 100,
            "app": sum(v for k, v in cpu.items() if "app" in k),
            "shards": sum(v for k, v in cpu.items() if "shard" in k)}


def pct(a, b, lower_better=False):
    d = (b - a) / a * 100
    return f"{d:+.0f} %"


print("#### Фази (по одному прогону після повного прогріву)\n")
print("| Навантаження | Метрика | Baseline | + Index | + Index + Batch (N+1) | Δ Baseline → Final |")
print("|---|---|---|---|---|---|")
for label, suffix in (("C · 100 VUs", "C"), ("Історія `/orders/my` · 50 VUs", "history"), ("B (запис) · 100 VUs", "B")):
    ph = [load(f"{R}/{p}-{suffix}") for p in ("baseline", "index", "batch")]
    for key, name in (("rps", "Throughput (RPS)"), ("p95", "p95, мс"), ("p99", "p99, мс"), ("err", "Error Rate, %"),
                      ("app", "CPU app Σ, ядер"), ("shards", "CPU шардів Σ, ядер")):
        vals = [p[key] for p in ph]
        fmt = (lambda v: f"{v:.0f}") if key == "rps" else (lambda v: f"{v:.2f}") if key in ("err", "app", "shards") else (lambda v: f"{v:.1f}")
        delta = pct(vals[0], vals[2]) if vals[0] else "—"
        print(f"| {label} | {name} | {fmt(vals[0])} | {fmt(vals[1])} | {fmt(vals[2])} | {delta} |")

print("\n#### Контрольовані A/B (ті самі JVM, чергування ABAB, медіана 3 повторів)\n")
print("| Експеримент | Навантаження | A: RPS / p95 / p99 | B: RPS / p95 / p99 | Δ RPS (медіана) | CPU шардів A → B |")
print("|---|---|---|---|---|---|")


def med(prefix, reps=(1, 2, 3)):
    runs = [load(f"{R}/ab/{prefix}-{r}") for r in reps]
    return {k: statistics.median(r[k] for r in runs) for k in runs[0]}, runs


for exp, a, b, label, reps in (
        ("Індекс (off → on)", "off-history", "on-history", "історія · 50 VUs", (1, 2, 3)),
        ("Індекс (off → on)", "off-B", "on-B", "B · 100 VUs", (1, 2, 3)),
        ("Індекс (off → on)", "off-C", "on-C", "C · 100 VUs", (1, 2, 3)),
        ("Індекс (off → on)", "off-C30", "on-C30", "C · 30 VUs", (1, 2)),
        ("N+1 → batch", "nplus1-history", "batch-history", "історія · 50 VUs", (1, 2, 3)),
        ("N+1 → batch", "nplus1-C30", "batch-C30", "C · 30 VUs", (1, 2, 3))):
    ma, ra = med(a, reps)
    mb, rb = med(b, reps)
    print(f"| {exp} | {label} | {ma['rps']:.0f} / {ma['p95']:.1f} / {ma['p99']:.1f} | {mb['rps']:.0f} / {mb['p95']:.1f} / {mb['p99']:.1f} | "
          f"{pct(ma['rps'], mb['rps'])} (усі прогони: {', '.join(f'{r['rps']:.0f}' for r in ra)} → {', '.join(f'{r['rps']:.0f}' for r in rb)}) | "
          f"{ma['shards']:.2f} → {mb['shards']:.2f} |")
