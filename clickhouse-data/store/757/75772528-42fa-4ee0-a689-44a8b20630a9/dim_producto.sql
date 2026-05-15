ATTACH TABLE _ UUID '22a59cff-3651-4634-893b-093bd4562f76'
(
    `producto_id` String,
    `categoria_id` UInt32,
    `brand` String,
    `price` Float32
)
ENGINE = MergeTree
ORDER BY producto_id
SETTINGS index_granularity = 8192
