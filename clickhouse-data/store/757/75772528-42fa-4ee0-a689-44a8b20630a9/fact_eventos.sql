ATTACH TABLE _ UUID '9d98de8f-37e3-4bd0-b204-290359c9b4b9'
(
    `event_pk` UInt64 DEFAULT rowNumberInAllBlocks(),
    `session_id` String,
    `user_id` String,
    `timestamp_utc` String,
    `event_index` UInt32,
    `user_action` String,
    `product_id` String,
    `time_spent_sec` Float32,
    `session_length` Float32,
    `interaction_count` UInt32,
    `is_conversion` UInt8,
    `drop_off_flag` UInt8,
    `price` Float32,
    `channel` String,
    `semana` UInt8 DEFAULT 1
)
ENGINE = MergeTree
ORDER BY (session_id, event_index)
SETTINGS index_granularity = 8192
