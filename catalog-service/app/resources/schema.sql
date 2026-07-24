-- Catalog schema, mirroring the legacy PopulateSQL.xml DDL (portable/varchar variant).
-- Locale-split: base table + _details table keyed by (id, locale).
-- Owned by catalog-service (DB-per-service) — moved out of the monolith.

CREATE TABLE IF NOT EXISTS category (
    catid VARCHAR(10) NOT NULL,
    CONSTRAINT pk_category PRIMARY KEY (catid)
);

CREATE TABLE IF NOT EXISTS category_details (
    catid VARCHAR(10) NOT NULL,
    name VARCHAR(80) NOT NULL,
    image VARCHAR(255),
    descn VARCHAR(255),
    locale VARCHAR(10) NOT NULL,
    CONSTRAINT pk_category_details PRIMARY KEY (catid, locale),
    CONSTRAINT fk_category_details_1 FOREIGN KEY (catid) REFERENCES category (catid)
);

CREATE TABLE IF NOT EXISTS product (
    productid VARCHAR(10) NOT NULL,
    catid VARCHAR(10) NOT NULL,
    CONSTRAINT pk_product PRIMARY KEY (productid),
    CONSTRAINT fk_product_1 FOREIGN KEY (catid) REFERENCES category (catid)
);

CREATE TABLE IF NOT EXISTS product_details (
    productid VARCHAR(10) NOT NULL,
    locale VARCHAR(10) NOT NULL,
    name VARCHAR(80) NOT NULL,
    image VARCHAR(255),
    descn VARCHAR(255),
    CONSTRAINT pk_product_details PRIMARY KEY (productid, locale),
    CONSTRAINT fk_product_details_1 FOREIGN KEY (productid) REFERENCES product (productid)
);

CREATE TABLE IF NOT EXISTS item (
    itemid VARCHAR(10) NOT NULL,
    productid VARCHAR(10) NOT NULL,
    CONSTRAINT pk_item PRIMARY KEY (itemid),
    CONSTRAINT fk_item_1 FOREIGN KEY (productid) REFERENCES product (productid)
);

CREATE TABLE IF NOT EXISTS item_details (
    itemid VARCHAR(10) NOT NULL,
    listprice DECIMAL(10,2) NOT NULL,
    unitcost DECIMAL(10,2) NOT NULL,
    locale VARCHAR(10) NOT NULL,
    image VARCHAR(255),
    descn VARCHAR(255) NOT NULL,
    attr1 VARCHAR(80),
    attr2 VARCHAR(80),
    attr3 VARCHAR(80),
    attr4 VARCHAR(80),
    attr5 VARCHAR(80),
    CONSTRAINT pk_item_details PRIMARY KEY (itemid, locale),
    CONSTRAINT fk_item_details_1 FOREIGN KEY (itemid) REFERENCES item (itemid)
);
