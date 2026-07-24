-- inventory-service owns the inventory table (from legacy supplier.ear).
CREATE TABLE IF NOT EXISTS inventory (
    item_id VARCHAR(10) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (item_id),
    CONSTRAINT ck_inventory_nonneg CHECK (quantity >= 0)
);

-- NOTE: no credential store here anymore. Supplier logins are authenticated by
-- auth-service (the central IdP); inventory-service only VERIFIES RS256 tokens.
