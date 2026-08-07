create table expected_payments (
  id uuid primary key,
  transaction_id varchar(64) not null unique,
  merchant_id varchar(128) not null,
  amount_minor bigint not null,
  currency varchar(3) not null,
  stripe_charge_id varchar(255),
  status varchar(32) not null,
  expected_at timestamptz not null,
  reconciled_at timestamptz,
  run_id varchar(64)
);

create table settlement_records (
  id uuid primary key,
  stripe_payout_id varchar(255) not null,
  stripe_charge_id varchar(255) not null unique,
  merchant_id varchar(128) not null,
  gross_minor bigint not null,
  fee_minor bigint not null,
  net_minor bigint not null,
  currency varchar(3) not null,
  settled_at timestamptz not null,
  raw_payload text
);

create table reconciliation_runs (
  run_id varchar(64) primary key,
  settlement_date date not null,
  started_at timestamptz not null,
  completed_at timestamptz,
  status varchar(16) not null,
  expected_count int,
  settled_count int,
  matched_count int,
  discrepancy_count int
);

create table discrepancies (
  id uuid primary key,
  run_id varchar(64) not null,
  merchant_id varchar(128) not null,
  discrepancy_type varchar(32) not null,
  transaction_id varchar(64),
  stripe_charge_id varchar(255),
  details text not null,
  resolution_note text,
  created_at timestamptz not null
);

create table processed_events (
  event_id varchar(64) primary key,
  processed_at timestamptz not null
);
