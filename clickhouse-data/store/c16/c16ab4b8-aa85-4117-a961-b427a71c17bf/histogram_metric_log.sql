ATTACH TABLE _ UUID '7b3c1d3e-be88-413c-8c49-3273d453c25e'
(
    `hostname` LowCardinality(String) COMMENT 'Hostname of the server executing the query.',
    `event_date` Date COMMENT 'Event date.',
    `event_time` DateTime COMMENT 'Event time.',
    `event_time_microseconds` DateTime64(6) COMMENT 'Event time with microseconds resolution.',
    `metric` LowCardinality(String) COMMENT 'Metric name.',
    `labels` Map(LowCardinality(String), LowCardinality(String)) COMMENT 'Metric labels.',
    `histogram` Map(Float64, UInt64) COMMENT 'Cumulative histogram: maps bucket upper bound to number of observations <= that bound; includes +inf as the final bucket.',
    `count` UInt64 COMMENT 'Total number of observations, equals histogram[+inf].',
    `sum` Float64 COMMENT 'Sum of all observed values.',
    INDEX event_time_index event_time TYPE minmax GRANULARITY 1,
    INDEX event_time_microseconds_index event_time_microseconds TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, event_time)
SETTINGS index_granularity = 8192
COMMENT 'Contains periodic snapshots of histogram metrics. Each row stores histogram bucket counts of one metric and label-combination.\n\nIt is safe to truncate or drop this table at any time.'
