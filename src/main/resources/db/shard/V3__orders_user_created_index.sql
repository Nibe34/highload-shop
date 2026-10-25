-- ЛР9: усунення підтвердженого Root Cause — Seq Scan по orders для «історії замовлень користувача».
-- Складений індекс відповідає і фільтру (user_id = ?), і сортуванню (created_at DESC) з LIMIT:
-- PostgreSQL читає з індексу лише перші N записів користувача замість скану й сортування всієї таблиці.
-- У продакшні — CREATE INDEX CONCURRENTLY (без блокування запису; Flyway: executeInTransaction=false).
CREATE INDEX IF NOT EXISTS idx_orders_user_created ON orders (user_id, created_at DESC);
