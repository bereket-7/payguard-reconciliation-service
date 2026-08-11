-- Indexes and constraints for the daily reconciliation run.
--
-- The run was previously guarded by an in-JVM ConcurrentHashMap, so with two replicas both pods
-- executed the same settlement date concurrently: discrepancies were inserted twice and
-- markReconciled raced. Mutual exclusion is now a Postgres advisory lock taken by
-- ReconciliationLockService, which needs no table and is released automatically if a pod dies
-- mid-run — a lock row would have to be reaped by a timeout sweeper instead.

-- The run loads its ledger window by expected_at and skips already-reconciled rows; without this
-- the window query is a full sequential scan of every payment ever recorded.
create index idx_expected_payments_window
    on expected_payments (expected_at)
    where reconciled_at is null;

-- Charge-id lookup is the authoritative join key in MatchingEngine.
create index idx_expected_payments_charge
    on expected_payments (stripe_charge_id)
    where stripe_charge_id is not null;

-- Amount-bucketed fallback matching, and the per-merchant unreconciled report.
create index idx_expected_payments_merchant_amount on expected_payments (merchant_id, amount_minor);

create index idx_settlement_records_payout on settlement_records (stripe_payout_id);

-- Discrepancies are always read scoped to a run and a merchant.
create index idx_discrepancies_run_merchant on discrepancies (run_id, merchant_id);

-- Run history is listed newest-first and filtered by date.
create index idx_reconciliation_runs_settlement_date on reconciliation_runs (settlement_date, started_at desc);
