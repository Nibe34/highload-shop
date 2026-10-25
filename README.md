# Shop — високонавантажений інтернет-магазин

Наскрізний проєкт з курсу «Інженерія високонавантажених систем» (ЛР 1–10).
Стек: Java 21, Spring Boot 3.5, PostgreSQL 17 (primary + replica), Redis 7.4, nginx, podman/docker compose.

## Швидкий старт

```bash
podman-compose up -d --build      # або: docker compose up -d --build
scripts/smoke.sh                  # демонстрація всіх основних endpoints
```

* API (через балансувальник nginx): `http://localhost:8080/api/...`
* Swagger UI: `http://localhost:8080/swagger-ui.html`
* Тестові користувачі: `user1@shop.test` … `user2000@shop.test`, пароль `password`.

## Звіти з лабораторних робіт

| ЛР | Тема | Звіт |
|---|---|---|
| 1 | System Design, MVP | [docs/lab01-system-design.md](docs/lab01-system-design.md) |
| 2 | Stateless Architecture | [docs/lab02-stateless.md](docs/lab02-stateless.md) |
| 3 | Horizontal Scaling, Load Balancing | [docs/lab03-horizontal-scaling.md](docs/lab03-horizontal-scaling.md) |
| 4 | Distributed Caching | [docs/lab04-distributed-caching.md](docs/lab04-distributed-caching.md) |
| 5 | Load & Performance Baseline | [docs/lab05-load-baseline.md](docs/lab05-load-baseline.md) |
| 6 | Database Replication, Read/Write Separation | [docs/lab06-replication.md](docs/lab06-replication.md) |

Стан коду після кожної ЛР позначено git-тегом `labN` (`git checkout lab1`).
