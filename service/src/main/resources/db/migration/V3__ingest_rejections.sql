-- Which devices send bad data: rejected messages and discarded stats entries, summed per flush
-- window, device and reason. Tuning numbers:
--   chunk interval   1 day
--   retention        8 days, the same as the telemetry itself

CREATE TABLE ingest_rejections (
    window_start   timestamptz NOT NULL,
    window_end     timestamptz NOT NULL,
    -- NULL when the message carried no valid device id (not JSON, wrong type, bad id).
    device_id      text,
    -- The reason code sent back in the error frame, or 'discarded_stats' for a message that was
    -- accepted without some of its stats entries.
    reason         text        NOT NULL,
    message_count  bigint      NOT NULL,
    -- The stats keys that were discarded; empty unless reason is 'discarded_stats'. An array, because
    -- a discarded key is often not a valid metric name and may contain any character.
    discarded_keys text[]      NOT NULL,
    -- The last such message of the window, cut to 1 000 characters.
    sample         text        NOT NULL
);

SELECT create_hypertable(
    'ingest_rejections',
    by_range('window_start', INTERVAL '1 day'),
    create_default_indexes => false
);

CREATE INDEX ingest_rejections_device_window_idx ON ingest_rejections (device_id, window_start DESC);

SELECT add_retention_policy('ingest_rejections', drop_after => INTERVAL '8 days');
