# Shop — високонавантажений інтернет-магазин

Наскрізний проєкт з курсу «Інженерія високонавантажених систем» (ЛР 1–10).
Стек: Java 21, Spring Boot 3.5, PostgreSQL 17, podman/docker compose.

## Швидкий старт

```bash
podman-compose up -d --build      # або: docker compose up -d --build
scripts/smoke.sh                  # демонстрація всіх основних endpoints
```

* API: `http://localhost:8080/api/...`
* Swagger UI: `http://localhost:8080/swagger-ui.html`
* Тестові користувачі: `user1@shop.test` … `user2000@shop.test`, пароль `password`.

## Звіти з лабораторних робіт

| ЛР | Тема | Звіт |
|---|---|---|
| 1 | System Design, MVP | [docs/lab01-system-design.md](docs/lab01-system-design.md) |

Стан коду після кожної ЛР позначено git-тегом `labN` (`git checkout lab1`).
