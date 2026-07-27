-- ISO 4217 currency the order total is denominated in (JPA WarehouseOrderEntity.currency).
--
-- The order model carried a `locale` field but the auto-approval rule (ApprovalPolicy, legacy
-- PurchaseOrderMDB.canIApprove) always meant to key on MONEY — its own legacy comment called it
-- "a stub for converting currency" and the thresholds ($500 / ¥50000) are amounts, not localisation.
-- This makes the money dimension explicit and separate from `locale`, which stays for display/i18n.
--
-- Additive + backward compatible: the column is nullable and existing rows backfill to 'USD' (the
-- storefront hardcodes locale en_US, so every historical order was already dollar-denominated).
-- ApprovalPolicy treats null/blank as USD too, so an un-backfilled row would approve identically.

ALTER TABLE wh_order ADD COLUMN IF NOT EXISTS currency VARCHAR(3);
UPDATE wh_order SET currency = 'USD' WHERE currency IS NULL;
