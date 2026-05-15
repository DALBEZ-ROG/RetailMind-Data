ATTACH TABLE _ UUID '3dc42153-c6d6-4880-a362-ab1e001e4851'
(
    `canal_id` UInt32,
    `canal_nombre` String
)
ENGINE = MergeTree
ORDER BY canal_id
SETTINGS index_granularity = 8192
