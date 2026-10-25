-- Глобально унікальні id замовлень без координації між шардами:
-- кожен шард отримує власну арифметичну прогресію (start = 1 000 000 + shard_index, step = 16).
-- ${orderIdStart} підставляє Flyway (placeholder). 16 — максимальна кількість шардів у цій схемі.
CREATE SEQUENCE order_id_seq START WITH ${orderIdStart} INCREMENT BY 16;
