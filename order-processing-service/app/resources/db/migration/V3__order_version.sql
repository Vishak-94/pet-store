-- Optimistic-locking version column on the order aggregate (JPA @Version on WarehouseOrderEntity).
--
-- Concurrent status transitions on one order (the approve+deny race: two admins act on the same
-- PENDING order at once) were previously last-writer-wins — both could "succeed" while only one
-- transition really applied, and the losing side's after-commit gateway (fulfilment dispatch /
-- customer email) still fired off a stale read. With a version column, JPA appends
-- `WHERE version = ?` to each status UPDATE and bumps it on success; the second committer matches
-- no row and Hibernate raises OptimisticLockingFailureException, which the OPC API surfaces as 409.
--
-- Existing rows default to 0 so the first update from either path establishes the baseline cleanly.

ALTER TABLE wh_order ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
