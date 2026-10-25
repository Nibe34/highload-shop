#!/usr/bin/env python3
"""Текстовий аналіз collapsed stacks async-profiler: частки часу за категоріями та топ «self» фреймів."""
import sys
from collections import Counter

prefix = sys.argv[1]

# Категорії wall/cpu часу: перша відповідна сигнатура у стеку (від листа до кореня)
CATEGORIES = [
    ("HikariCP: очікування з'єднання з пулу", ("ConcurrentBag.borrow", "HikariPool.getConnection")),
    ("JDBC: очікування відповіді PostgreSQL (socket read)", ("PGStream.receive", "VisibleBufferedInputStream.read", "NioSocketImpl")),
    ("Redis (Lettuce): очікування відповіді", ("AsyncCommand.await", "LettuceFutures", "CompletableFuture$Signaller")),
    ("Jackson: JSON (де)серіалізація", ("JsonGenerator", "BeanSerializer", "ObjectMapper", "ObjectWriter", "ObjectReader",
                                        "BeanDeserializer", "JsonParser", "MappingJackson2HttpMessageConverter")),
    ("JDBC: протокол, маппінг рядків", ("QueryExecutorImpl", "PgPreparedStatement", "PgResultSet", "JdbcTemplate", "RowMapper")),
    ("Tomcat/HTTP I/O", ("NioEndpoint", "Http11", "CoyoteOutputStream")),
    ("GC / JIT (JVM-потоки)", ("G1", "GCTaskThread", "C2Compiler", "CompileBroker", "Compile::", "C1Compiler")),
]


def load(path):
    stacks = Counter()
    try:
        for line in open(path):
            stack, _, count = line.rstrip().rpartition(" ")
            stacks[stack] += int(count)
    except FileNotFoundError:
        pass
    return stacks


def report(kind, stacks, unit="samples"):
    total = sum(stacks.values()) or 1
    print(f"=== {kind}: {total} {unit} ===")
    cats = Counter()
    for stack, n in stacks.items():
        # категорію визначає найглибший (найближчий до листа) фрейм, що відповідає сигнатурі
        category = "інше (бізнес-код, Spring, JVM)"
        for frame in reversed(stack.split(";")):
            hit = next((name for name, sigs in CATEGORIES if any(sig in frame for sig in sigs)), None)
            if hit:
                category = hit
                break
        cats[category] += n
    for name, n in cats.most_common():
        print(f"  {n / total:6.1%}  {name}")
    self_frames = Counter()
    for stack, n in stacks.items():
        self_frames[stack.split(";")[-1]] += n
    print("  top self frames:")
    for name, n in self_frames.most_common(8):
        print(f"    {n / total:6.1%}  {name[:110]}")
    print()


report("CPU (app-1, itimer)", load(f"{prefix}-cpu.collapsed"))
wall = load(f"{prefix}-wall.collapsed")
# wall-clock: лише потоки обробки HTTP-запитів (Tomcat http-nio-*-exec-*), без простою пулів
wall = Counter({s: n for s, n in wall.items() if s.startswith("[http-nio") and "exec" in s.split(";")[0]
                and "TaskQueue.poll" not in s and "TaskQueue.take" not in s})
report("Wall-clock (app-2, потоки обробки запитів http-nio-exec, без очікування задач)", wall)
alloc = load(f"{prefix}-alloc.collapsed")
report("Allocations (app-1, bytes)", alloc, "bytes")
