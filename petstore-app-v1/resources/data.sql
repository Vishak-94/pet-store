-- No seed data in the monolith.
--
-- Catalog seed (categories/products/items) moved to catalog-service (DB-per-service).
-- Customer/user seed lives in customer-service. Order tables (purchase_order,
-- line_item) are populated at runtime by checkout.
--
-- This file is intentionally almost empty; spring.sql.init.mode=always still
-- runs it harmlessly.
SELECT 1;
