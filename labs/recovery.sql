CREATE TABLE migration_case (
  id BIGINT PRIMARY KEY,
  customer_ref TEXT NOT NULL,
  normalized_ref TEXT
);
INSERT INTO migration_case (id, customer_ref)
SELECT value, ' Customer-' || value || ' ' FROM generate_series(1, 10000) AS value;
CREATE TABLE backfill_progress (job TEXT PRIMARY KEY, last_id BIGINT NOT NULL);
INSERT INTO backfill_progress VALUES ('normalize-ref', 0);
