ATTACH TABLE _ UUID 'bedf62b7-6742-468a-b9e2-b6ba74423f6e'
(
    `fuente_id` UInt32,
    `fuente_nombre` String
)
ENGINE = MergeTree
ORDER BY fuente_id
SETTINGS index_granularity = 8192
