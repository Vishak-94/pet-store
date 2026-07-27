-- inventory-service owns the inventory table (from legacy supplier.ear).
CREATE TABLE IF NOT EXISTS inventory (
    item_id VARCHAR(10) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (item_id),
    CONSTRAINT ck_inventory_nonneg CHECK (quantity >= 0)
);

-- Dedup ledger for idempotent fulfilment: one row per ORDER whose stock has been
-- decremented (shipped). Keyed by order_id (an order ships at most once), NOT the
-- message eventId, so it also stops a re-driven OrderApprovedEvent (fresh eventId,
-- published for every APPROVED order on each restock — legacy processPendingPO,
-- PARITY_AUDIT H2/M8) from double-decrementing an already-shipped order. The PK makes
-- a redelivery/re-drive a no-op (second insert fails).
-- Superseded the earlier event_id-keyed `processed_event` table; drop it if present so
-- the durable file DB doesn't keep the stale ledger around.
DROP TABLE IF EXISTS processed_event;
CREATE TABLE IF NOT EXISTS fulfilled_order (
    order_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_fulfilled_order PRIMARY KEY (order_id)
);

-- NOTE: no credential store here anymore. Supplier logins are authenticated by
-- auth-service (the central IdP); inventory-service only VERIFIES RS256 tokens.
