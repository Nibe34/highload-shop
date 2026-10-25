#!/usr/bin/env python3
"""Зведення JFR: GC-паузи, heap після GC (перевірка на витік), allocation rate. Аргумент — тека з gc.json, heap.json, alloc.json (jfr print --json)."""
import json, sys, statistics, re
d = sys.argv[1]
def events(f): return json.load(open(f"{d}/{f}"))["recording"]["events"]
def dur_ms(s):  # ISO-8601 duration "PT0.0123S"
    m = re.match(r"PT(?:(\d+)M)?([\d.]+)S", s); return (int(m.group(1) or 0) * 60 + float(m.group(2))) * 1000
gc = events("gc.json")
pauses = [dur_ms(e["values"]["sumOfPauses"]) for e in gc]
names = {}
for e in gc: names[e["values"]["name"]] = names.get(e["values"]["name"], 0) + 1
print(f"GC: {len(gc)} збирань за 90 с ({len(gc)/90:.2f}/с), типи: {names}")
print(f"  паузи: median {statistics.median(pauses):.2f} мс, p95 {sorted(pauses)[int(len(pauses)*0.95)]:.2f} мс, max {max(pauses):.2f} мс, сумарно {sum(pauses):.0f} мс ({sum(pauses)/900:.2f} % часу)")
heap = [e for e in events("heap.json") if e["values"]["when"] == "After GC"]
used = [e["values"]["heapUsed"] / 2**20 for e in heap]
n = len(used); first, last = used[: n // 3], used[-(n // 3):]
print(f"  heap після GC: min {min(used):.0f} MB, max {max(used):.0f} MB; середнє 1-ї третини {statistics.mean(first):.0f} MB → останньої {statistics.mean(last):.0f} MB "
      f"(тренд {'стабільний — ознак витоку немає' if statistics.mean(last) < statistics.mean(first) * 1.15 else 'ЗРОСТАЄ — підозра на витік'})")
alloc = events("alloc.json")
total = sum(e["values"]["weight"] for e in alloc)
print(f"Allocation rate (оцінка JFR ObjectAllocationSample): {total/2**20/90:.0f} MB/с")
by_type = {}
for e in alloc:
    t = e["values"]["objectClass"]["name"]; by_type[t] = by_type.get(t, 0) + e["values"]["weight"]
for t, w in sorted(by_type.items(), key=lambda x: -x[1])[:8]:
    print(f"  {w/total:6.1%}  {t}")
