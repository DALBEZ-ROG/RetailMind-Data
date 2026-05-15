ATTACH TABLE _ UUID '9a1bdb36-263a-438e-83d3-89db4f1c8219'
(
    `region_id` UInt32,
    `region_nombre` String
)
ENGINE = MergeTree
ORDER BY region_id
SETTINGS index_granularity = 8192
