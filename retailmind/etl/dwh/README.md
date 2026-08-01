# `etl/dwh` — pipeline PostgreSQL → ClickHouse del data warehouse

Implementa `docs/estrategico/DISENO_ETL_CLICKHOUSE.md`: **19 tablas** (5 dimensiones + 14 hechos)
en la base **`retailmind_dwh`**, cargadas por **full refresh atómico**.

**Estado: FASES 0, 1, 2, 3A y 3B COMPLETAS — 11 de 19 tablas publicadas y validadas al centavo.** Las
8 restantes entran por fases (§9.4 y §9.5 del diseño).

| Tabla | Filas | Fase | Sirve |
|---|---:|---|---|
| `dim_fecha` | 730 | 1 | calendario (meses sin actividad) |
| `dim_producto` | 1.221 | 1 | VEN-03/04, GER-10 |
| `fact_venta_linea` | 10.384 | 1 | VEN-06 y el margen |
| `dim_cliente` | 72 | 2 | VEN-05 |
| `fact_pedido` | 4.083 | 2 | VEN-05/07/13, LOG-12, GER-02/05 |
| `fact_flujo_caja` | 4.981 | 2 | VEN-09/12, COM-03, GER-02 |
| `dim_proveedor` | 11 | 3A | COM-03 (plazo de crédito) |
| `fact_orden_compra` | 865 | 3A | COM-04/05/06, GER-02 (devengado) |
| `fact_compra_linea` | 2.949 | 3A | COM-07, COM-12 |
| `fact_movimiento_inventario` | 13.287 | 3B | INV-04, INV-10 |
| `fact_stock_mensual` | 21.122 | 3B | **INV-09**, INV-04 (denominador) |

`python -m etl.dwh.validar_dwh` corre los **24 controles cruzados** contra PostgreSQL y devuelve 1
si algo difiere. La igualdad es exacta: no hay tolerancia de centavos. Dos merecen mención propia:

- `cuadre_compras` cruza **dos tablas de hechos cargadas en fases distintas**:
  `$22.467.387,27 − $16.084.462,74 = $6.382.924,53`, descuadre $0,00.
- **`stock_cierre_final` es LA PRUEBA DEFINITIVA de la Fase 3B**: el `stock_cierre` del último mes
  contra `inventario.stock_actual`, **posición por posición** (1.406 de 1.406, 0 diferencias). No es
  un agregado a propósito — dos posiciones que se intercambian el saldo dan la misma suma, y ese es
  el «número plausible y equivocado» del que avisa §5.7. La misma prueba corre **dentro de la tarea**
  antes de publicar: si una sola posición difiere, la tabla no se publica.

`fact_stock_mensual` es la única `TareaDerivada` (se calcula dentro de ClickHouse) y la única con
dependencias reales: `fact_movimiento_inventario` + `dim_producto`.

> **Antes de implementar una tabla nueva, lee
> `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.** El diseño se escribió en papel sin ejecutar
> nada, y ya van **17 supuestos suyos que no se sostuvieron** contra la base — casi todos de la
> misma forma: una relación que se declara 1:1 y tiene dos excepciones que no rompen el JOIN, solo
> le añaden o le quitan una fila en silencio.

## Comandos

Todos se ejecutan desde `retailmind/`:

```bash
python -m etl.dwh.cargar --verificar          # ¿responden PostgreSQL y ClickHouse?
python -m etl.dwh.cargar --init               # crea retailmind_dwh + etl_ejecucion (idempotente)
python -m etl.dwh.cargar --listar             # estado de las 19 tablas
python -m etl.dwh.cargar --bitacora           # últimas corridas
python -m etl.dwh.cargar --tabla fact_pedido  # carga UNA tabla

python -m etl.dwh.run_etl                     # LAS 19 en orden + los 44 controles
python -m etl.dwh.run_etl --orden             # solo imprime el orden resuelto
python -m etl.dwh.run_etl --tablas a,b,c      # subconjunto, respetando el orden
python -m etl.dwh.run_etl --sin-validacion    # sin los controles finales
```

Códigos de salida de `run_etl`: `0` correcto · `1` no arrancó · `2` fallo parcial (alguna tabla
no se publicó) · `3` cargó todo pero los controles no cuadran.

## Estructura

| Archivo | Qué hace |
|---|---|
| `conexiones.py` | PostgreSQL (rol `retailmind_etl`, solo lectura) + ClickHouse (`retailmind_dwh`). Verificación de orígenes. |
| `bitacora.py` | Tabla `etl_ejecucion`: DDL + registro de cada corrida. |
| `carga_atomica.py` | **El patrón §6.2**: staging → validar → `EXCHANGE TABLES` → limpiar. |
| `tarea.py` | Contrato `TareaCarga` / `TareaDerivada` que cumple cada tabla. |
| `registro.py` | Lista blanca de tareas implementadas + orden topológico. |
| `cargar.py` | Punto de entrada de UNA tabla (`python -m etl.dwh.cargar`). |
| `run_etl.py` | **Orquestador (Fase 5)**: las 19 en orden topológico, con reintentos y un solo `corrida_id`. |
| `validar_dwh.py` | Validación cruzada de lo YA publicado (`--fase`, `--control`, `--detalle`). |
| `tablas/` | Una tabla por módulo. Vacío en Fase 0. |

## Las dos decisiones que sostienen todo

**1. El rol de lectura es un prerrequisito, no un detalle (§8.1).** `pol_horario` está declarada
con `cmd = ALL`, y ALL incluye SELECT. Un ETL que se conecte con cualquier rol `grp_*` a las 02:00
no recibe un 403: **RLS filtra en silencio y devuelve cero filas**. El pipeline terminaría «con
éxito», publicaría 19 tablas vacías y los 39 informes saldrían en blanco sin un solo error en
ningún log. Por eso este paquete conecta como **`retailmind_etl`** (script
`retailmind/sql/postgres/85_rol_etl.sql`), el único rol con `BYPASSRLS` y solo lectura.
`--verificar` comprueba explícitamente el atributo y aborta si es falso.

**2. La validación es la red de seguridad, no un adorno (§6.2 paso 3).** Toda tarea compara su
conteo contra el origen **en la misma corrida**. Una carga de 0 filas donde se esperaban 4.083
**aborta y no publica**: la tabla anterior sigue en su sitio y el informe muestra el dato de ayer.
Aunque el rol quedara mal configurado, el modo de fallo es una degradación visible, no una pantalla
vacía silenciosa.

## Qué se reutilizó del ETL viejo, y qué no

El ETL de `etl/carga/` y `etl/extraccion/` va de **PocketBase** a la base **`retailmind` legada** de
ClickHouse, que el diagnóstico declaró muerta (96,2 % relleno sintético huérfano). Se reutilizan sus
**patrones**, nunca su lógica:

| Patrón reutilizado | De dónde | Dónde vive ahora |
|---|---|---|
| Conexión por variables de entorno + `python-dotenv` | `config/db_connection.py`, `config/clickhouse_connection.py` | `conexiones.py` |
| Logging rotatorio (5 MB × 5) a archivo + consola | `config/db_connection.py` | `conexiones.py` (archivo propio `logs/etl_dwh.log`) |
| Inserción por lotes de 50.000 con `client.insert(...)` | `etl/carga/09_load_clickhouse.py` | `tarea.TareaCarga.cargar_en` (transmitiendo desde PostgreSQL con cursor server-side, en vez de trocear un DataFrame en memoria) |
| Tracker de carga con DDL `IF NOT EXISTS` junto al código y `ensure_*()` antes de escribir | `utils/load_tracker.py` (`carga_historial`) | `bitacora.py` (`etl_ejecucion`, en ClickHouse porque aquí PostgreSQL es solo lectura) |
| Reporte de errores en tres capas: persistir + loguear + imprimir, y que fallar al reportar nunca tumbe la carga | `utils/error_reporter.py` | `cargar.py` (bitácora + `logger` + consola) y `bitacora.registrar`, que traga su propia excepción |

Lo que **no** se reutilizó: el esquema de destino, las consultas, el origen PocketBase y el control
por semanas (`carga_historial.semana`), que no aplica a un full refresh diario.

## Añadir una tabla

1. `tablas/<nombre>.py` con una subclase de `TareaCarga` que defina `nombre`, `ddl()`, `columnas()`,
   `sql_extraccion()` y `sql_controles()`.
2. Registrarla en `registro.TAREAS`.
3. `python -m etl.dwh.cargar --tabla <nombre>`.

No hay que tocar `carga_atomica.py`, `bitacora.py` ni `cargar.py`: la mecánica de lotes, validación,
publicación atómica y bitácora está escrita una sola vez y sirve a las 19.

## Reglas que no se negocian

- **La base `retailmind` de ClickHouse (legada) no se toca.** `conexiones._validar_destino` lanza
  `BaseProhibida` si alguien apunta el pipeline a ella.
- **ClickHouse nunca es fuente.** Nada de este paquete escribe hacia PostgreSQL; la conexión es
  `readonly` en el cliente y el rol trae `default_transaction_read_only = on` desde el motor.
  Si los dos motores discrepan, gana PostgreSQL y el DWH se recarga.
- **Toda columna de fecha se declara `DateTime('America/Guayaquil')`** (§8.6). Declararla a secas
  hace que ClickHouse asuma UTC y `toStartOfMonth()` mande los pedidos del día 1 de madrugada al mes
  anterior. Este proyecto ya pagó la versión frontend de ese bug.
- **El dinero es `Decimal(14,2)`, nunca `Float64`.** El criterio de aceptación es la igualdad al
  centavo.

## Orquestación (Fase 5, hecha)

`run_etl.py` recorre las 19 tareas en orden topológico. **El grafo no está escrito en el
orquestador**: se deriva de los `depende_de` que declara cada tarea, y el desempate entre tareas
igual de listas es la posición en `registro.ORDEN_SUGERIDO`, para que dos corridas produzcan la
misma secuencia. Son 5 aristas, no una: además de la dependencia de cálculo
`fact_movimiento_inventario → fact_stock_mensual`, hay cuatro de CONSISTENCIA que `registro.py`
documenta (`fact_pedido ← dim_cliente`, `fact_venta_linea ← dim_producto`,
`fact_novedad_envio ← fact_envio`, `fact_devolucion_linea ← fact_devolucion`).

Tres comportamientos que conviene conocer antes de tocarlo:

- **Un fallo no detiene la corrida, pero sí a su descendencia.** Si una tabla aborta, las que
  dependen de ella se marcan `omitido` y las demás se cargan igual. El código de salida deja de
  ser 0 y el resumen dice quién quedó fuera y por culpa de quién.
- **Una validación fallida se reintenta.** El origen es una base OLTP viva: un pedido que entra
  entre el volcado y la consulta de control descuadra un conteo, y eso es transitorio. Si el
  descuadre es real, los tres intentos fallan igual y la tabla **no se publica**.
- **La bitácora se escribe en ASCII** (`_ascii()`). Es lo único que cruza Python → ClickHouse →
  Java, y el driver JDBC 0.6.5 decodifica la respuesta como latin-1: un `·` escrito desde aquí
  llegaría al navegador como `Â·`.

Desde la aplicación lo dispara `POST /api/dwh/actualizar` (ADMIN/GERENTE) y lo programa
`@Scheduled` a las 02:00 de `America/Guayaquil`; ambos pasan por el mismo guardia de una corrida
a la vez. Como cada tabla sigue siendo un comando autónomo, el DAG de Airflow de §7.1 seguiría
siendo un `BashOperator` de una línea por tarea: montar Airflow sigue siendo reversible.
