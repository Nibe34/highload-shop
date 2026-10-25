#!/usr/bin/env python3
"""Завантажує типовий (медіанний за тривалістю) трейс операції з Jaeger і будує waterfall:
текст (stdout) + PNG. Використання: trace_waterfall.py "GET /api/orders/my" <out_prefix>"""
import json
import os
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from plots import SERIES, TEXT2, plt, save  # noqa: E402

op, out = sys.argv[1], sys.argv[2]
url = "http://localhost:16686/api/traces?" + urllib.parse.urlencode({"service": "shop", "operation": op, "limit": 50, "lookback": "1h"})
traces = json.load(urllib.request.urlopen(url))["data"]
traces = [t for t in traces if len(t["spans"]) > 1]
traces.sort(key=lambda t: max(s["duration"] for s in t["spans"]))
trace = traces[len(traces) // 2]
json.dump(trace, open(out + ".json", "w"), indent=1)

spans = sorted(trace["spans"], key=lambda s: s["startTime"])
t0 = spans[0]["startTime"]
root = max(spans, key=lambda s: s["duration"])
total = root["duration"] / 1000


def kind(s):
    tags = {t["key"]: t["value"] for t in s.get("tags", [])}
    if "db.system" in tags or "db.system.name" in tags:
        return tags.get("db.system", tags.get("db.system.name")), tags.get("server.address", tags.get("net.peer.name", ""))
    return "app", ""


print(f"Trace {trace['traceID']}: {op}, total {total:.2f} ms, spans: {len(spans)}")
agg = {}
for s in spans:
    k, host = kind(s)
    d = s["duration"] / 1000
    print(f"  +{(s['startTime'] - t0) / 1000:7.2f} ms  {d:7.2f} ms  [{k:10s}] {s['operationName'][:60]} {host}")
    if s is not root and k != "app":
        agg.setdefault(k, [0, 0.0])
        agg[k][0] += 1
        agg[k][1] += d
print("  Σ за типом:", ", ".join(f"{k}: {n} спанів, {d:.2f} мс ({d / total:.0%})" for k, (n, d) in agg.items()))

fig, ax = plt.subplots(figsize=(10, 0.28 * len(spans) + 1.2))
colors = {"app": SERIES[0], "postgresql": SERIES[1], "redis": SERIES[2]}
for i, s in enumerate(spans):
    k, host = kind(s)
    ax.barh(i, s["duration"] / 1000, left=(s["startTime"] - t0) / 1000, color=colors.get(k, SERIES[3]), height=0.7)
    ax.text((s["startTime"] - t0 + s["duration"]) / 1000 + total * 0.005, i,
            f"{s['operationName'][:45]} {host.replace('postgres-', '')}", va="center", fontsize=7, color=TEXT2)
ax.invert_yaxis()
ax.set_yticks([])
ax.set_xlabel("мс від початку запиту")
ax.set_title(f"{op}: {total:.1f} мс, {len(spans)} спанів (синій — застосунок, помаранчевий — PostgreSQL, зелений — Redis)", loc="left")
ax.grid(axis="y", visible=False)
ax.set_xlim(0, total * 1.45)
save(fig, out + ".png")
