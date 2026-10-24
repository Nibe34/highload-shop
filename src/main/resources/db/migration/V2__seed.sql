-- Детерміновані тестові дані: 20 категорій, 10 000 товарів, 2 000 користувачів, 50 000 замовлень.
-- Пароль усіх згенерованих користувачів: "password".
SELECT setseed(0.42);

INSERT INTO categories (name)
SELECT 'Category ' || g FROM generate_series(1, 20) g;

INSERT INTO products (category_id, name, description, price_cents, stock)
SELECT 1 + (g % 20),
       'Product ' || g,
       'Description of product ' || g || '. ' || repeat('Lorem ipsum dolor sit amet. ', 5),
       100 + (random() * 99900)::BIGINT,
       1000000
FROM generate_series(1, 10000) g;

INSERT INTO users (email, full_name, region, password_hash)
SELECT 'user' || g || '@shop.test',
       'User ' || g,
       (ARRAY['Kyiv','Kyiv','Kyiv','Kyiv','Kyiv','Kyiv','Kyiv','Kyiv','Lviv','Odesa'])[1 + (g % 10)],
       'seed$' || encode(sha256(convert_to('seed$password', 'UTF8')), 'hex')
FROM generate_series(1, 2000) g;

INSERT INTO orders (user_id, status, total_cents, created_at)
SELECT 1 + (random() * 1999)::BIGINT, 'CREATED', 0, now() - (random() * INTERVAL '90 days')
FROM generate_series(1, 50000);

-- товари з "довгим хвостом" популярності: перші товари купують частіше
INSERT INTO order_items (order_id, product_id, quantity, price_cents)
SELECT DISTINCT ON (o.id, p.pid) o.id, p.pid, 1 + (random() * 3)::INT, pr.price_cents
FROM orders o
CROSS JOIN LATERAL (
    SELECT 1 + floor(power(random(), 3) * 9999)::BIGINT + (o.id * 0) AS pid
    FROM generate_series(1, 3)
) p
JOIN products pr ON pr.id = p.pid;

UPDATE orders o SET total_cents = s.total
FROM (SELECT order_id, sum(quantity * price_cents) AS total FROM order_items GROUP BY order_id) s
WHERE s.order_id = o.id;
