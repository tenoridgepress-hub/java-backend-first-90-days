ALTER TABLE customer_order ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant-a';
ALTER TABLE customer_order ALTER COLUMN tenant_id DROP DEFAULT;
CREATE INDEX tenant_order_page ON customer_order (tenant_id, id);
