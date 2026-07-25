-- inventory-service owns the inventory table (from legacy supplier.ear).
CREATE TABLE IF NOT EXISTS inventory (
    item_id VARCHAR(10) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (item_id),
    CONSTRAINT ck_inventory_nonneg CHECK (quantity >= 0)
);

-- Dedup ledger for at-least-once JMS delivery: one row per order-approved event
-- whose stock has been decremented. The PK on event_id makes a redelivery a no-op
-- (second insert fails), preventing a double-decrement / oversell on retry.
CREATE TABLE IF NOT EXISTS processed_event (
    event_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);

-- NOTE: no credential store here anymore. Supplier logins are authenticated by
-- auth-service (the central IdP); inventory-service only VERIFIES RS256 tokens.
