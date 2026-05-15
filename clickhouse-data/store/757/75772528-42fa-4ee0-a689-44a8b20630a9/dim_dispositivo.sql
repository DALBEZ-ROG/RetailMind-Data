ATTACH TABLE _ UUID 'f3875a47-ba92-457a-b0c3-c28a00584fb2'
(
    `dispositivo_id` UInt32,
    `dispositivo_nombre` String
)
ENGINE = MergeTree
ORDER BY dispositivo_id
SETTINGS index_granularity = 8192
