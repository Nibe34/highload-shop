-- Схема шарду замовлень (ЛР7). Без FK на users/products: вони живуть в іншій БД (каталог).
-- Цілісність посилань забезпечує застосунок (Saga checkout), а не СУБД.
CREATE TABLE orders (
    id          BIGINT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    status      VARCHAR(32) NOT NULL,
    total_cents BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    order_id    BIGINT  NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id  BIGINT  NOT NULL,
    quantity    INTEGER NOT NULL CHECK (quantity > 0),
    price_cents BIGINT  NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
