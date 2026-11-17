-- 5-minute rollup of telemetry, read by the day and week charts. Tuning numbers:
--   bucket                   5 minutes
--   refresh                  every 5 minutes over [now - 8 days, now - 5 minutes]
--   compress chunks after    1 day
--   retention                8 days, the same as the raw data
-- The day chart reads the last 24 hours, so it stays on uncompressed chunks; the week chart reads
-- mostly compressed ones. Late data (accepted up to 7 days back) still reaches compressed chunks:
-- the refresh policy re-materializes invalidated buckets inside its 8-day window.

-- Stores sum and count instead of avg, so buckets can be re-aggregated into wider chart buckets
-- correctly. WITH NO DATA keeps the statement transactional (Flyway).
-- materialized_only = false: queries also see the not-yet-materialized recent data.
CREATE MATERIALIZED VIEW telemetry_5m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '5 minutes', ts) AS bucket,
       device_id,
       metric,
       min(value)  AS min_value,
       max(value)  AS max_value,
       sum(value)  AS sum_value,
       count(*)    AS sample_count
FROM telemetry
GROUP BY bucket, device_id, metric
WITH NO DATA;

SELECT add_continuous_aggregate_policy(
    'telemetry_5m',
    start_offset => INTERVAL '8 days',
    end_offset => INTERVAL '5 minutes',
    schedule_interval => INTERVAL '5 minutes'
);

ALTER MATERIALIZED VIEW telemetry_5m SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric',
    timescaledb.compress_orderby = 'bucket'
);

SELECT add_compression_policy('telemetry_5m', compress_after => INTERVAL '1 day');

SELECT add_retention_policy('telemetry_5m', drop_after => INTERVAL '8 days');
