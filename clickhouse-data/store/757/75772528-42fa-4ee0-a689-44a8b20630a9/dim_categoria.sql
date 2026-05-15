ATTACH TABLE _ UUID '7f58695c-25d6-4c1c-a900-bda2b65039a6'
(
    `categoria_id` UInt32,
    `categoria_nombre` String
)
ENGINE = MergeTree
ORDER BY categoria_id
SETTINGS index_granularity = 8192
