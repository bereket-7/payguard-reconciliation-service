-- Transactional outbox for reconciliation.completed.
--
-- ReconciliationEventPublisher used to call kafkaTemplate.send() directly from inside the run's
-- transaction. That is a dual write across two systems with no shared commit: if the transaction
-- rolled back after the sends (a constraint violation on a late discrepancy insert, a connection
-- drop, the advisory lock's session dying), merchants had already received a settlement report for
-- a run whose rows no longer existed. The reverse ordering fails just as badly — a broker outage
-- threw out of a run that had already written every discrepancy, so the reconciliation was durable
-- but silent and nothing ever retried the notification.
--
-- The row is now written in the same transaction as the run, and a relay publishes it afterwards.
-- Columns match the payment service's outbox after V5 so the two relays stay operationally
-- identical: same backoff semantics, same dead-letter convention, same dashboards.

create table outbox (
  id uuid primary key,
  event_id varchar(64) not null unique,
  aggregate_id varchar(64) not null,
  event_type varchar(64) not null,
  topic varchar(128) not null,
  partition_key varchar(128) not null,
  payload text not null,
  created_at timestamptz not null,
  published_at timestamptz,
  attempts int not null default 0,
  last_error text,
  next_attempt_at timestamptz not null,
  dead_lettered_at timestamptz
);

-- Drives the relay's claim query. Partial, so the index holds only the work queue rather than every
-- event ever published — published rows are the overwhelming majority once the service has run for
-- a while. Column order matches the query's `order by`.
create index idx_outbox_publishable
    on outbox (next_attempt_at, created_at)
    where published_at is null and dead_lettered_at is null;

-- Operator queries: what got stuck, and the payguard.outbox.dead.letter.depth gauge.
create index idx_outbox_dead_lettered
    on outbox (dead_lettered_at)
    where dead_lettered_at is not null;
