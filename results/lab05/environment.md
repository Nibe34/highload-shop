# Baseline Environment Specification

Дата: 2026-10-25T13:26:06+02:00
Хост: 12th Gen Intel(R) Core(TM) i5-12500H, 16 потоків, RAM 15 GB, 7.2.8-200.fc44.x86_64
Контейнери: podman version 5.8.7, podman-compose version 1.6.0
Load generator: k6 v2.3.0 (commit/e088784614, go1.26.8, linux/amd64), прив'язаний до CPU 12-15 (taskset)

| Контейнер | CPU limit | Memory limit | Образ |
|---|---|---|---|
| shop_app-1_1 | 1.0 | 768 MB | localhost/shop-app:latest |
| shop_app-2_1 | 1.0 | 768 MB | localhost/shop-app:latest |
| shop_app-3_1 | 1.0 | 768 MB | localhost/shop-app:latest |
| shop_lb_1 | 1.0 | 128 MB | docker.io/library/nginx:1.27 |
| shop_postgres_1 | 2.0 | 1024 MB | docker.io/library/postgres:17 |
| shop_redis_1 | 1.0 | 256 MB | docker.io/library/redis:7.4 |

JVM: openjdk version "21.0.12.1" 2026-08-18 LTS; JAVA_OPTS=-XX:MaxRAMPercentage=75 -XX:+UseG1GC
Tomcat max threads: 200; HikariCP maximumPoolSize: 10 на інстанс (30 сумарно)
PostgreSQL: postgres (PostgreSQL) 17.11 (Debian 17.11-1.pgdg13+2), max_connections=200, shared_buffers=256MB
Redis: v=7.4.11, AOF everysec
nginx: Round Robin, keepalive 64; backend instances: 3
Дані: products=10002, users=2005, orders=103014, order_items=203240
