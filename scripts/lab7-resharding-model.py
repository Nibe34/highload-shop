#!/usr/bin/env python3
"""ЛР7: модель решардингу. Частка ключів (user_id), що змінюють шард при розширенні кластера N → N+1,
для стратегій: key % N, hash(key) % N, consistent hashing (ring + virtual nodes), virtual buckets.

Хеш — той самий MurmurHash3 fmix64, що й у ShardRouter.mix().
"""
import bisect
import sys

M64 = (1 << 64) - 1
KEYS = range(1, 100_001)          # 100 тис. користувачів
BUCKETS = 1024


def mix(k):
    k &= M64
    k ^= k >> 33
    k = (k * 0xFF51AFD7ED558CCD) & M64
    k ^= k >> 33
    k = (k * 0xC4CEB9FE1A85EC53) & M64
    k ^= k >> 33
    return k


def modulo(n):
    return lambda k: k % n


def hashmod(n):
    return lambda k: mix(k) % n


def ring(n, vnodes):
    points = sorted((mix(hash((s, v)) & M64), s) for s in range(n) for v in range(vnodes))
    keys = [p for p, _ in points]

    def lookup(k):
        i = bisect.bisect(keys, mix(k)) % len(points)
        return points[i][1]
    return lookup


def bucket_maps(max_n):
    """Початково 2 шарди по 512 бакетів; при додаванні шарду новий забирає рівну частку бакетів у кожного з наявних."""
    owner = [0 if b < BUCKETS // 2 else 1 for b in range(BUCKETS)]
    maps = {2: owner[:]}
    for n in range(3, max_n + 1):
        target = BUCKETS // n
        per_old = {s: [b for b in range(BUCKETS) if owner[b] == s] for s in range(n - 1)}
        taken = 0
        s = 0
        while taken < target:              # по одному бакету з кожного старого шарду по колу
            if per_old[s]:
                owner[per_old[s].pop()] = n - 1
                taken += 1
            s = (s + 1) % (n - 1)
        maps[n] = owner[:]
    return maps


def moved_fraction(f_old, f_new):
    return sum(1 for k in KEYS if f_old(k) != f_new(k)) / len(KEYS)


def balance(f, n):
    counts = [0] * n
    for k in KEYS:
        counts[f(k)] += 1
    return max(counts) / (len(KEYS) / n)


def main():
    maps = bucket_maps(4)
    strategies = {
        "key % N": modulo,
        "hash(key) % N": hashmod,
        "consistent hashing, 1 vnode": lambda n: ring(n, 1),
        "consistent hashing, 128 vnodes": lambda n: ring(n, 128),
        "virtual buckets (1024)": lambda n: (lambda k, m=maps[n]: m[mix(k) % BUCKETS]),
    }
    print("| Стратегія | 2 → 3: переміщено | 3 → 4: переміщено | Ідеал (1/N_new) | Дисбаланс max/avg при N=3 | при N=4 |")
    print("|---|---|---|---|---|---|")
    for name, factory in strategies.items():
        f2, f3, f4 = factory(2), factory(3), factory(4)
        m23, m34 = moved_fraction(f2, f3), moved_fraction(f3, f4)
        print(f"| {name} | {m23:.1%} | {m34:.1%} | 33.3% / 25.0% | {balance(f3, 3):.2f} | {balance(f4, 4):.2f} |")


if __name__ == "__main__":
    sys.exit(main())
