-- Інструментація БД для навантажувального тестування (ЛР5) та профілювання (ЛР9).
-- Потребує shared_preload_libraries=pg_stat_statements у конфігурації PostgreSQL.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
