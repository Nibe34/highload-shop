-- Роль для фізичної потокової реплікації (ЛР6). Пароль — навчальний, у продакшні передається через secret.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'replicator') THEN
        CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replicator';
    END IF;
END
$$;
