-- Inventory seed (owned by inventory-service). EST-2 low (1) for oversell tests.
MERGE INTO inventory (item_id, quantity) KEY(item_id) VALUES
 ('EST-1', 100), ('EST-2', 1), ('EST-5', 50), ('EST-10', 50), ('EST-18', 5);
-- No staff seed — supplier credentials live in auth-service.
