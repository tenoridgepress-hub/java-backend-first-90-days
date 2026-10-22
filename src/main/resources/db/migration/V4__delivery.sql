ALTER TABLE reservation_outbox ADD COLUMN delivered_at TIMESTAMP;
ALTER TABLE reservation_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reservation_outbox ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE reservation_outbox ADD COLUMN quarantined BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX pending_outbox ON reservation_outbox (delivered_at, created_at, event_id);
CREATE TABLE consumer_inbox (
  event_id VARCHAR(64) PRIMARY KEY,
  reservation_id VARCHAR(36) NOT NULL,
  order_id BIGINT NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE fulfillment_total (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  quantity BIGINT NOT NULL
);
INSERT INTO fulfillment_total (id, quantity) VALUES (1, 0);
