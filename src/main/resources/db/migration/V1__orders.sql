CREATE TABLE customer_order (
  id BIGINT PRIMARY KEY,
  customer_ref VARCHAR(64) NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0)
);
CREATE TABLE reservation (
  id VARCHAR(36) PRIMARY KEY,
  order_id BIGINT NOT NULL REFERENCES customer_order(id),
  event_id VARCHAR(64) NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  CONSTRAINT one_reservation_per_order UNIQUE (order_id),
  CONSTRAINT one_reservation_per_event UNIQUE (event_id)
);
INSERT INTO customer_order VALUES (1001, 'fictional-customer-a', 2);
INSERT INTO customer_order VALUES (1002, 'fictional-customer-b', 3);
