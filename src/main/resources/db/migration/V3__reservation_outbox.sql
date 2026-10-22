CREATE TABLE reservation_outbox (
  event_id VARCHAR(64) PRIMARY KEY,
  reservation_id VARCHAR(36) NOT NULL REFERENCES reservation(id),
  order_id BIGINT NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT one_outbox_event_per_reservation UNIQUE (reservation_id)
);
