"""
etl/dwh — pipeline ETL PostgreSQL → ClickHouse del data warehouse RetailMind.

Implementa el diseño de `docs/estrategico/DISENO_ETL_CLICKHOUSE.md`: 19 tablas
(5 dimensiones + 14 hechos) en la base nueva `retailmind_dwh`, cargadas por
FULL REFRESH ATÓMICO (§6.1 y §6.2).

Este paquete es NUEVO y no comparte lógica con `etl/carga/` ni `etl/extraccion/`,
que van de PocketBase al esquema legado de la base `retailmind` de ClickHouse.
De aquel ETL se reutilizan los PATRONES (conexión, inserción por lotes, tracker
de carga, reporte de errores), no su contenido.
"""
