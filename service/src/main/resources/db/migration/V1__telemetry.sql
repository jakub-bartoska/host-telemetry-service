-- Raw device telemetry. Tuning numbers:
--   chunk interval           2 hours
--   compress chunks after    6 hours
--   retention                8 days (one week of history + one day margin)

-- Narrow table: one row per (time, device, metric), so new stat keys need no schema change.
CREATE TABLE telemetry (
    ts        timestamptz      NOT NULL,
    device_id text             NOT NULL,
    metric    text             NOT NULL,
    value     double precision NOT NULL
);

SELECT create_hypertable(
    'telemetry',
    by_range('ts', INTERVAL '2 hours'),
    create_default_indexes => false
);

-- Idempotency key: the writer inserts with ON CONFLICT DO NOTHING, so a resent sample is stored
-- only once. Also the only index the queries need.
CREATE UNIQUE INDEX telemetry_device_metric_ts_uq ON telemetry (device_id, metric, ts);

ALTER TABLE telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric',
    timescaledb.compress_orderby = 'ts'
);

SELECT add_compression_policy('telemetry', compress_after => INTERVAL '6 hours');

SELECT add_retention_policy('telemetry', drop_after => INTERVAL '8 days');
