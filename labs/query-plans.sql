BEGIN;
CREATE TEMP TABLE lab_order (
  id BIGINT PRIMARY KEY,
  customer_ref VARCHAR(64) NOT NULL,
  quantity INTEGER NOT NULL
);
INSERT INTO lab_order
SELECT series, 'fictional-' || series, 2
FROM generate_series(1, 100000) AS series;
ANALYZE lab_order;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, customer_ref, quantity FROM lab_order
ORDER BY id LIMIT 21 OFFSET 90000;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, customer_ref, quantity FROM lab_order
WHERE id > 90000 ORDER BY id LIMIT 21;
ROLLBACK;
