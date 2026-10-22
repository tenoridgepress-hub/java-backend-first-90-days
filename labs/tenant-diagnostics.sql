BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT tenant_id, event_id, attempts, quarantined, delivered_at IS NOT NULL AS delivered
FROM reservation_outbox ORDER BY tenant_id, event_id;
SELECT tenant_id, event_id, reservation_id, order_id, quantity
FROM consumer_inbox ORDER BY tenant_id, event_id;
WITH tenants AS (
    SELECT tenant_id FROM fulfillment_total
    UNION SELECT tenant_id FROM consumer_inbox
)
SELECT tenants.tenant_id, totals.quantity AS effect_quantity,
       COALESCE(SUM(inbox.quantity), 0) AS receipt_quantity,
       CASE WHEN totals.tenant_id IS NULL THEN 'missing_effect_row'
            WHEN totals.quantity <> COALESCE(SUM(inbox.quantity), 0) THEN 'quantity_mismatch'
            ELSE 'ok' END AS consistency
FROM tenants
LEFT JOIN fulfillment_total totals ON tenants.tenant_id = totals.tenant_id
LEFT JOIN consumer_inbox inbox ON tenants.tenant_id = inbox.tenant_id
GROUP BY tenants.tenant_id, totals.tenant_id, totals.quantity ORDER BY tenants.tenant_id;
SELECT COUNT(*) AS mismatched_tenant_links
FROM reservation_outbox outbox
JOIN reservation ON outbox.reservation_id = reservation.id
WHERE outbox.tenant_id <> reservation.tenant_id;
COMMIT;
