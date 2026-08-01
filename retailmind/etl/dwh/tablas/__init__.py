"""
etl/dwh/tablas — una tabla del DWH por módulo.

VACÍO EN LA FASE 0, a propósito: los prerrequisitos (rol de lectura, base de
destino, esqueleto) no cargan ni una fila. Cada fase del §9 del diseño va
depositando aquí sus módulos, y cada uno se registra en `etl/dwh/registro.py`.

Convención: el módulo se llama igual que la tabla (`fact_venta_linea.py`) y
expone UNA subclase de `TareaCarga` (o de `TareaDerivada` para
`fact_stock_mensual`, la única que se calcula dentro de ClickHouse).
"""
