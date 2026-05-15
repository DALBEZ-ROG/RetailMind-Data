ATTACH TABLE _ UUID '35f4058b-aa12-475d-a122-e98cf8b3be4b'
(
    `user_id` String,
    `region_id` UInt32,
    `dispositivo_id` UInt32
)
ENGINE = MergeTree
ORDER BY user_id
SETTINGS index_granularity = 8192
