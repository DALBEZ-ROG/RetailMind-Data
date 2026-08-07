# Auditoría previa al generador de actividad sintética y a Airflow

**Fecha**: 2026-08-06 · **Alcance**: solo lectura y diagnóstico · **Rama**: `seguridad/permisos-motor`
**Verificado contra**: PostgreSQL 18.4 del contenedor `retailmind-postgres-1` (puerto 5432, la base
VIVA) y ClickHouse `retailmind-clickhouse-1` (`retailmind_dwh`), ambos `healthy` durante la
auditoría.

Este informe documenta cómo está construido lo que YA existe, antes de diseñar (1) un generador
de actividad sintética contra PostgreSQL y (2) una orquestación con Airflow. Cada afirmación cita
archivo:línea o la consulta que la respalda. Donde no hubo evidencia concluyente se escribe
**NO VERIFICADO** en vez de rellenar con supuestos.

---

## ⚠️ CONDICIONES DE LA AUDITORÍA — LÉASE ANTES QUE NADA

### C-1. Estado de la verificación: motor VIVO, con los scripts 86 y 87 aplicados

La auditoría se ejecutó en dos pasadas. La primera, con el demonio de Docker caído, se apoyó en la
copia congelada del puerto 5433. La segunda —tras levantar `postgres` y `clickhouse`— **repitió
todas las comprobaciones estructurales contra la base viva del 5432**, y con ellas se cerró el
único punto que había quedado sin medir (A6).

```sql
SELECT current_database(), inet_server_port(), version(), …
-- retailmind | 5432 | PostgreSQL 18.4 (Debian 18.4-1.pgdg13+1)
--   tablas    111   ← 110 + `rol_personalizado` (script 87)
--   politicas  95
--   pedidos  4083   ult_pedido 2026-07-22 23:14:17-05
--   kardex  13288
--   fn_admin_cambiar_permiso presente (script 86)   rol_personalizado presente (script 87)
```

**Resultado: la copia del 5433 y el motor vivo coinciden en TODO lo que este informe afirma.** No
hubo ni una divergencia en triggers, restricciones, columnas GENERATED, políticas RLS,
`grupo_horario`, GRANTs ni en la integridad del kardex. La única diferencia es la esperada: el
motor vivo tiene la tabla `rol_personalizado` y la función `fn_admin_cambiar_permiso`, que no
intervienen en ninguno de los tres flujos auditados.

Toda la sesión fue de **solo lectura**: `SELECT`, catálogo (`pg_catalog`, `information_schema`) y
consultas a `system.tables` / `etl_ejecucion` de ClickHouse. Ni un DDL, ni un DML, ni un
contenedor levantado o detenido por mí.

### C-2. CONTRADICCIÓN con el prompt: el kardex NO está protegido por el motor

El prompt describe "kardex cuadrado en 1.406/1.406" como un hecho del sistema. Lo es —lo verifiqué—
pero **no existe ni un trigger, ni una restricción, ni un procedimiento almacenado que lo
mantenga**. Es una convención que hoy respetan el seed y dos rutas de código Java. Ver **B4**.
Es el hallazgo más importante de este informe para el generador.

### C-3. CONTRADICCIÓN dentro del propio código: comentario obsoleto sobre dónde vive PostgreSQL

`retailmind-backend/src/main/java/com/retailmind/dwh/DwhProgramacion.java:23-32` justifica la
elección de `@Scheduled` afirmando:

> «**PostgreSQL corre LOCAL, fuera de compose**, así que desde dentro del contenedor
> `ETL_PG_HOST=localhost` apunta al propio contenedor y no a la base. […] Cuando la
> contenerización se complete —sigue pendiente— esta opción pasa a ser la buena.»

La contenerización se completó el 2026-08-03 (`CLAUDE.md`, bloque CONTENERIZACIÓN COMPLETA). El
comentario que sostiene la decisión de diseño ya no describe el sistema, y describe justamente la
alternativa que la migración a Airflow reabre. La decisión sigue siendo defendible, pero **su
justificación escrita está caducada**.

### C-4. Deriva numérica entre `CLAUDE.md` y el almacén realmente publicado

Consultando `system.tables` de ClickHouse, las tablas publicadas HOY tienen **más filas** de las
que `CLAUDE.md` documenta por fase:

| tabla | `CLAUDE.md` | publicado (verificado en ClickHouse) |
|---|---|---|
| `fact_orden_compra` | 865 | **867** |
| `fact_compra_linea` | 2.949 | **2.951** |
| `fact_movimiento_inventario` | 13.287 | **13.288** |
| `fact_novedad_envio` | 176 | **177** |
| `fact_devolucion` | 196 | **197** |
| `fact_devolucion_linea` | 274 | **275** |
| `fact_ticket` | 248 | **249** |
| `fact_stock_mensual` | 21.122 | **22.528** |

El total publicado es **66.079 filas en 21 tablas**, no las 64.664 que cierra `CLAUDE.md`
(esa cifra corresponde a la corrida del 2026-08-01; la del 2026-08-03, ya dentro de Docker, es la
de 66.079 — y `CLAUDE.md` la menciona en el bloque de contenerización, así que el documento se
contradice consigo mismo por 1.415 filas).

No es un fallo: es la prueba de que **la aplicación siguió escribiendo en PostgreSQL después de
que se redactaran esas cifras**, y de que toda documentación de conteos envejece con cada uso real
del sistema. Cualquier control que un generador quiera basar en "el número documentado" es frágil
por construcción; el ETL ya lo resuelve bien (toma la cifra de control del origen en la misma
corrida — ver A5).

---

# BLOQUE A — Anatomía de `run_etl.py`

Archivo: `retailmind/etl/dwh/run_etl.py` (602 líneas).

## A1. Estructura interna

**No es un guion lineal.** Es un orquestador con grafo de dependencias, partido en funciones, y
**el orden NO está escrito en el archivo**: se deriva por algoritmo de Kahn a partir del atributo
de clase `depende_de` que cada tarea declara (`run_etl.py:148-201`).

Funciones reales:

| función | línea | papel |
|---|---|---|
| `dependencias()` | 148 | `{tabla → predecesoras}` leído de las CLASES, no de una lista |
| `orden_topologico()` | 158 | Kahn con desempate determinista por `registro.ORDEN_SUGERIDO` |
| `_ejecutar_tarea()` | 206 | carga UNA tabla con reintentos + 1 fila de bitácora |
| `_dependencia_rota()` | 363 | decide si una tarea se omite por descendencia |
| `_validar()` | 385 | ejecuta los 49 controles como "última tarea" |
| `_cerrar()` | 426 | marcador de cierre de la corrida |
| `ejecutar()` | 287 | el bucle completo |
| `main()` | 521 | CLI |

**Etapas reales en orden de ejecución.** El orden efectivo se resuelve en tiempo de ejecución;
las únicas 5 aristas reales del grafo son:

- `fact_pedido ← dim_cliente` (`tablas/fact_pedido.py:142`)
- `fact_venta_linea ← dim_producto` (`tablas/fact_venta_linea.py:124`)
- `fact_stock_mensual ← fact_movimiento_inventario, dim_producto` (`tablas/fact_stock_mensual.py:141`)
- `fact_novedad_envio ← fact_envio` (`tablas/fact_novedad_envio.py:116`)
- `fact_devolucion_linea ← fact_devolucion` (`tablas/fact_devolucion_linea.py:101`)
- `fact_prevision_demanda ← fact_venta_linea, dim_producto, dim_fecha` (`tablas/fact_prevision_demanda.py:151`)
- `fact_alerta_cliente ← fact_pedido, dim_cliente, fact_ticket, fact_devolucion` (`tablas/fact_alerta_cliente.py:91`)

Como el desempate es la posición en `registro.ORDEN_SUGERIDO` (`registro.py:106-128`), la
secuencia efectiva es reproducible y coincide con esa tupla. **Verificado contra la ejecución
real** de `retailmind/logs/etl_dwh.log:1152-…` (corrida 2026-08-03 20:13:00):

1. `dim_fecha` → 2. `dim_producto` → 3. `dim_cliente` → 4. `dim_proveedor` →
5. `dim_promocion_producto` → 6. `fact_pedido` → 7. `fact_venta_linea` → 8. `fact_flujo_caja` →
9. `fact_orden_compra` → 10. `fact_compra_linea` → 11. `fact_movimiento_inventario` →
12. `fact_envio` → 13. `fact_novedad_envio` → 14. `fact_devolucion` → 15. `fact_devolucion_linea` →
16. `fact_ticket` → 17. `fact_resena` → 18. `fact_devolucion_proveedor` → 19. `fact_stock_mensual` →
20. `fact_prevision_demanda` → 21. `fact_alerta_cliente` → **22. validación (49 controles)**.

Cada una de las 21 pasa internamente por los 5 pasos de `carga_atomica.cargar_atomico()`
(`carga_atomica.py:99-156`): DROP staging → CREATE staging → llenar → **validar** → `EXCHANGE
TABLES` → DROP staging.

**Consecuencia para Airflow**: el DAG NO necesita reimplementar el orden. Un `BashOperator` por
tabla con `--tablas X` reproduce el grafo, y las aristas se leen de las clases. El propio archivo
lo anticipa (`run_etl.py:13-17`).

## A2. Argumentos de línea de comandos

**Sí los acepta.** `argparse` en `run_etl.py:531-550`:

| argumento | línea | qué hace |
|---|---|---|
| `--tablas A,B,C` | 536 | subconjunto de tareas; respeta el orden topológico. Las dependencias FUERA de la selección se ignoran a propósito (`run_etl.py:167-170`) |
| `--corrida-id UUID` | 538 | impone el identificador desde fuera. **Es el que usa el backend** para poder devolver el id antes de que Python escriba nada |
| `--intentos N` | 541 | reintentos por tarea (defecto 3, `run_etl.py:64`) |
| `--espera S` | 543 | espera antes del primer reintento; se duplica (5 s → 10 s, `run_etl.py:65,253`) |
| `--sin-validacion` | 546 | omite los 49 controles finales |
| `--orden` | 548 | imprime el orden resuelto y termina sin cargar nada |

Códigos de salida (`run_etl.py:80-83`): `0` OK · `1` no arrancó · `2` fallo parcial ·
`3` cargó pero los controles no cuadran. **Un DAG de Airflow puede distinguir los cuatro casos.**

CLI hermano: `python -m etl.dwh.cargar` (`cargar.py:125-140`) con `--tabla`, `--init`,
`--listar`, `--verificar`, `--bitacora`. **No debe usarse desde un orquestador**: abre una corrida
propia por invocación y partiría una corrida en 21 (`run_etl.py:210-214`).

## A3. Estrategia de carga por tabla destino

**Las 21 son CARGA COMPLETA. No hay ni una sola carga incremental, y no existe marca de agua en
ninguna parte del pipeline.**

Evidencia estructural, no por tabla: `carga_atomica.cargar_atomico()` es el ÚNICO camino de carga
y siempre hace lo mismo (`carga_atomica.py:112-141`):

```python
client.command(f"DROP TABLE IF EXISTS {db}.{staging}")   # línea 112
client.command(tarea.ddl(f"{db}.{staging}"))             # línea 113
resultado.filas_escritas = tarea.cargar_en(...)          # línea 120
...
client.command(f"EXCHANGE TABLES {db}.{destino} AND {db}.{staging}")  # línea 136
```

La tabla publicada se **reemplaza entera** por una recién construida. No hay `WHERE fecha >`,
no hay `max(fecha_carga)` leído del destino, no hay tabla de watermark. Se confirmó buscando en
las 21 extracciones: los únicos `WHERE` son filtros de NEGOCIO permanentes
(p. ej. `WHERE fv.estado <> 'anulada'` en `tablas/fact_venta_linea.py:197` y
`tablas/fact_pedido.py:217`), nunca de recencia.

| # | tabla destino | sabor | estrategia | marca de agua |
|---|---|---|---|---|
| 1 | `dim_fecha` | generada en CH | completa (730 filas fijas) | ninguna |
| 2 | `dim_producto` | `TareaCarga` | completa | ninguna |
| 3 | `dim_cliente` | `TareaCarga` | completa | ninguna |
| 4 | `dim_proveedor` | `TareaCarga` | completa | ninguna |
| 5 | `dim_promocion_producto` | `TareaCarga` | completa | ninguna |
| 6 | `fact_pedido` | `TareaCarga` | completa | ninguna |
| 7 | `fact_venta_linea` | `TareaCarga` | completa | ninguna |
| 8 | `fact_flujo_caja` | `TareaCarga` | completa | ninguna |
| 9 | `fact_orden_compra` | `TareaCarga` | completa | ninguna |
| 10 | `fact_compra_linea` | `TareaCarga` | completa | ninguna |
| 11 | `fact_movimiento_inventario` | `TareaCarga` | completa | ninguna |
| 12 | `fact_envio` | `TareaCarga` | completa | ninguna |
| 13 | `fact_novedad_envio` | `TareaCarga` | completa | ninguna |
| 14 | `fact_devolucion` | `TareaCarga` | completa | ninguna |
| 15 | `fact_devolucion_linea` | `TareaCarga` | completa | ninguna |
| 16 | `fact_ticket` | `TareaCarga` | completa | ninguna |
| 17 | `fact_resena` | `TareaCarga` | completa | ninguna |
| 18 | `fact_devolucion_proveedor` | `TareaCarga` | completa | ninguna |
| 19 | `fact_stock_mensual` | `TareaDerivada` | completa, `INSERT…SELECT` dentro de ClickHouse | ninguna |
| 20 | `fact_prevision_demanda` | `TareaModelo` | completa, recalculada por el modelo Python | ninguna |
| 21 | `fact_alerta_cliente` | `TareaModelo` | completa, recalculada por el modelo Python | ninguna |

Sabores definidos en `tarea.py:29` (`TareaCarga`), `tarea.py:162` (`TareaDerivada`),
`tarea.py:193` (`TareaModelo`).

**La columna `fecha_carga` que llevan casi todas las tablas NO es una marca de agua de
extracción**: se estampa en la transformación (p. ej. `tablas/dim_producto.py:110`) y sirve como
marca de agua *de presentación* — el `datosAl` que el backend lee de `fact_pedido`
(`DwhActualizacionService.java:435-443`). Nadie la lee para decidir qué extraer.

**Dato crítico y no evidente**: `dim_fecha` tiene el rango **CODIFICADO EN DURO**
(`tablas/dim_fecha.py:28-29`):

```python
FECHA_INICIO = date(2025, 1, 1)
FECHA_FIN    = date(2026, 12, 31)
```

Un generador que escriba pedidos posteriores al **2026-12-31** produce hechos sin fila de
calendario que los acompañe, y `validar()` de `dim_fecha` (`tablas/dim_fecha.py:141`) comprueba el
rango contra esas constantes, no contra los datos. Ver RIESGO R-6.

## A4. Idempotencia POR ETAPA

**Ninguna de las 21 etapas duplica datos al ejecutarse dos veces seguidas**, y la razón es la
misma para todas: la etapa no inserta sobre la tabla publicada, sino sobre una staging que **se
borra antes de crearse** (`carga_atomica.py:112`) y que sustituye a la publicada por
`EXCHANGE TABLES` (`carga_atomica.py:136`).

Etapa por etapa:

- **Las 18 `TareaCarga`** (`dim_producto`, `dim_cliente`, `dim_proveedor`,
  `dim_promocion_producto`, `fact_pedido`, `fact_venta_linea`, `fact_flujo_caja`,
  `fact_orden_compra`, `fact_compra_linea`, `fact_movimiento_inventario`, `fact_envio`,
  `fact_novedad_envio`, `fact_devolucion`, `fact_devolucion_linea`, `fact_ticket`, `fact_resena`,
  `fact_devolucion_proveedor`, y `dim_fecha` que genera en CH): idempotentes.
  `cargar_en()` (`tarea.py:99-115`) inserta SIEMPRE en `tabla_staging`, nunca en el destino.
- **`fact_stock_mensual`** (`TareaDerivada`, `tarea.py:177-182`): idempotente. El `INSERT…SELECT`
  apunta a la staging y se recalcula entero desde `fact_movimiento_inventario`.
- **`fact_prevision_demanda`, `fact_alerta_cliente`** (`TareaModelo`, `tarea.py:230-235`):
  idempotentes en cuanto a filas. Ojo: sus columnas `fecha_calculo_ts` / `fecha_carga` **sí
  cambian** entre corridas, así que "idéntico" es a nivel de clave, no byte a byte.
- **La bitácora**: NO es idempotente por diseño. Cada corrida añade filas a
  `retailmind_dwh.etl_ejecucion` (`bitacora.py:104-123`). Es un log; se espera que crezca.

El comentario de `carga_atomica.py:112-114` es explícito sobre por qué el DROP previo existe:
sin él, un reintento insertaría encima de lo anterior. **Esa es la única línea que separa el
pipeline de duplicar datos**, y es la primera que hay que respetar si alguien reescribe la carga.

## A5. Los 49 controles de validación

**Son DOS capas distintas, y conviene no confundirlas.**

### Capa 1 — validación POR TABLA, INTERCALADA, y que ABORTA

Corre dentro de cada etapa, en el paso 3 de `cargar_atomico` (`carga_atomica.py:123-132`), ANTES
del `EXCHANGE`:

- `validar_conteo()` (`carga_atomica.py:69-94`) es obligatoria para toda tarea: compara las filas
  escritas contra `controles['filas']`, que se toma **del origen en la misma corrida**, no de una
  constante.
- `tarea.validar()` (`tarea.py:151-159`) añade las comprobaciones propias (sumas de dinero,
  unidades…).
- Si hay un solo error → `raise ValidacionFallida` → **la tabla NO se publica** y la anterior
  sigue en su sitio (`carga_atomica.py:131-132`, `finally` en 151-156).

### Capa 2 — los 49 CONTROLES CRUZADOS, AL FINAL, EN BLOQUE

`validar_dwh.CONTROLES` tiene exactamente **49** entradas
(`grep -c "^    Control(" validar_dwh.py` → 49; por fase: 3+7+21+13+2+3). Se ejecutan de una vez
al terminar las 21 cargas, en `_validar()` (`run_etl.py:385-423`), **en proceso** (no como
subproceso), y quedan registrados en la bitácora como una tarea más llamada `validar_dwh`.

**Qué hace el guion si uno falla**: `validar()` (`validar_dwh.py:2121-2151`) **NO aborta ni
detiene nada** — recorre los 49, imprime cuáles difieren, y devuelve `1`. Ese `1` se traduce en:

- fila de bitácora con `resultado='fallo'` (`run_etl.py:405-412`),
- marcador de cierre `fallo_parcial` (`run_etl.py:429,445`),
- **código de salida 3** (`run_etl.py:83,515`).

**Es decir: para los 49 controles finales, el guion solo REGISTRA y reporta. Las 21 tablas ya
están publicadas cuando se descubre el descuadre.** La red de seguridad real que impide publicar
basura es la Capa 1, no ésta.

**Política de reintentos, con una decisión no obvia** (`run_etl.py:239-246`): una
`ValidacionFallida` **se reintenta** (hasta 3 veces, espera 5 s → 10 s), porque el origen es una
base OLTP viva y un pedido que entre entre el volcado y el control descuadra el conteo por uno.
**Esto importa mucho para el generador**: hoy el ETL corre a las 02:00 contra una base quieta.
Con un generador escribiendo 24/7, ese caso "transitorio" deja de ser raro (ver RIESGO R-1).

**Un fallo no detiene la corrida, pero sí a su descendencia** (`run_etl.py:318-334`): si
`fact_movimiento_inventario` aborta, `fact_stock_mensual` se marca `omitido` y no se intenta.

## A6. Duración de una corrida completa

**VERIFICADO**, leyendo la bitácora `retailmind_dwh.etl_ejecucion` — la misma que escribe el
orquestador con reloj monótono (`run_etl.py:218,296,401` · `bitacora.py:40,91-95`). La bitácora
guarda **349 filas de 61 corridas**, entre 2026-07-30 18:12 y 2026-08-03 20:13.

```sql
SELECT corrida_id,
       argMinIf(filas_leidas, inicio, resultado='en_curso') AS tareas_en_cola,
       maxIf(inicio, resultado='en_curso')                  AS arranque,
       maxIf(duracion_seg, resultado!='en_curso')           AS duracion_total_seg,
       argMaxIf(resultado, inicio, resultado!='en_curso')   AS desenlace
FROM retailmind_dwh.etl_ejecucion WHERE tarea='corrida' GROUP BY corrida_id;
```

Las dos corridas COMPLETAS (21 tareas) registradas:

| arranque | tareas | filas | **duración TOTAL** | de eso, los 49 controles | desenlace |
|---|---|---|---|---|---|
| 2026-08-03 20:13 (**dentro de Docker**) | 21 | 66.079 | **6,251 s** | 1,295 s | éxito |
| 2026-08-01 21:36 (fuera de Docker) | 21 | 64.664 | **23,330 s** | 4,945 s | éxito |

Referencias del mismo periodo: 20 tareas → 21,6 s; las corridas de 19 tareas → 18,9–20,1 s.

**Respuesta: una corrida completa, con las 21 cargas Y los 49 controles, tarda hoy ~6 s dentro de
Docker** (~23 s cuando el ETL corría fuera del contenedor: el salto de ~3,7× es la latencia de red
que desaparece al quedar los tres procesos en la misma red de Docker).

Reparto de la corrida de 6,251 s: la tarea más cara es **la propia validación** (1,295 s de 6,251,
un 21 %), seguida de `fact_prevision_demanda` (0,46 s — el modelo), `fact_venta_linea` (0,38 s),
`fact_pedido` (0,363 s) y `fact_movimiento_inventario` (0,33 s). Ninguna carga individual llega al
medio segundo.

**Corrección a lo que dice el código**: los comentarios que citan "~20 s"
(`DwhProgramacion.java:58`, `DwhActualizacionController.java:22`,
`DwhActualizacionService.java:83`) describen el mundo anterior a la contenerización. Hoy la cifra
real es **~6 s**. No cambia ninguna decisión —los guardias de 30 minutos siguen sobradísimos— pero
es un dato más a favor de que la ventana de 02:00 puede ser mucho más frecuente si hiciera falta.

## A7. Cómo se dispara hoy

**Dos caminos, y ambos entran por el MISMO servicio**, que lanza `python -m etl.dwh.run_etl` como
proceso externo.

### Disparo automático — `@Scheduled`

- **Archivo y línea**: `retailmind-backend/src/main/java/com/retailmind/dwh/DwhProgramacion.java:116`

```java
@Scheduled(cron = "${dwh.programacion.cron}", zone = "${dwh.programacion.zona}")
public void actualizacionDiaria() {                       // línea 117
    servicio.disparar("programación automática");         // línea 119
```

- **Configuración**: `retailmind-backend/src/main/resources/application.properties:54-55`
  ```
  dwh.programacion.cron=${DWH_CRON:0 0 2 * * *}
  dwh.programacion.zona=${DWH_ZONA:America/Guayaquil}
  ```
  → **02:00 hora de Ecuador, todos los días.** El valor `-` desactiva la programación sin
  recompilar (`DwhProgramacion.java:91-94`). **Ése es el interruptor que Airflow tendría que
  accionar** para no competir por el `EXCHANGE TABLES`.
- `@EnableScheduling` está en la misma clase (`DwhProgramacion.java:65`).

### Disparo manual — el botón

- **Endpoint**: `POST /api/dwh/actualizar` →
  `DwhActualizacionController.java:62-66` (devuelve **202 Accepted** con `corridaId`).
- **Estado**: `GET /api/dwh/estado` → `DwhActualizacionController.java:77-80`.
- **Autorización**: `SecurityConfig.java:77` →
  `.requestMatchers("/api/dwh/**").hasAnyAuthority("ADMIN", "GERENTE")`.
- **Frontend**: `retailmind-frontend/src/app/core/services/dwh.service.ts:51` y la pantalla
  `features/operativo/informes/actualizacion-almacen.component.ts`.

### Lo que hace el servicio (relevante para Airflow)

`DwhActualizacionService.java`:

- **Lanza un proceso**, no reimplementa nada (línea 191):
  `List.of(python, "-m", "etl.dwh.run_etl", "--corrida-id", corrida.toString())`,
  con `pb.directory(new File(rutaEtl))` (línea 197) y `PYTHONPATH=.` (línea 204).
- **El UUID lo genera Java** y se lo IMPONE a Python (líneas 142, 192).
- **Guardia de UNA corrida a la vez, en dos capas** (líneas 144-166): `AtomicReference` con
  `compareAndSet` (memoria) + consulta a la bitácora para detectar una corrida ajena, con
  caducidad de 30 minutos (`MINUTOS_ABANDONO`, línea 87).
  **Airflow quedaría FUERA de la capa 1** (es otro proceso) pero **DENTRO de la capa 2**, porque
  ésta lee la bitácora — siempre que Airflow use el mismo `run_etl.py`, que es quien la escribe.
- **Timeout**: 30 minutos, luego `destroyForcibly()` (líneas 90, 218-224).
- Ejecutor de **un solo hilo** (líneas 105-109).

---

# BLOQUE B — Camino real de escritura en la capa operativa

## Advertencia transversal: dónde vive cada compuerta

| mecanismo | qué hace realmente en este sistema |
|---|---|
| **CHECK de la BD** | valida el VOCABULARIO de estados (`orden_compra`, `envio`, `pago`, `factura_*`, `ajuste_inventario`, `transferencia_bodega`), **nunca la TRANSICIÓN** |
| **`pedido`** | **no tiene CHECK de estado en absoluto**: el estado es una FK a `estado_pedido` (`pedido_estado_pedido_id_fkey`). El motor acepta CUALQUIER estado desde CUALQUIER otro |
| **Trigger** | recalcula totales, toca `fecha_actualizacion`, bloquea por horario, mantiene `usos_actuales` del cupón. **Ningún trigger valida una transición de proceso** |
| **Procedimiento almacenado** | 13 funciones `SECURITY DEFINER`, todas de recálculo/registro/permisos. Ninguna gobierna el ciclo de vida |
| **Backend Java** | **aquí viven TODAS las compuertas de proceso, sin excepción** |

Verificación de que ningún trigger valida transiciones:

```sql
SELECT c.relname, t.tgname, pg_get_triggerdef(t.oid)
FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
WHERE NOT t.tgisinternal AND c.relnamespace='public'::regnamespace;
```

De las ~90 filas devueltas, **todas** son `fn_touch_fecha_actualizacion`,
`fn_bloquear_fuera_horario`, `fn_recalcular_total_*`, `fn_registrar_uso_cupon` o
`fn_liberar_uso_cupon`.

### Columnas GENERATED (nunca escribirlas) — las 4 que existen

```sql
SELECT table_name, column_name, generation_expression
FROM information_schema.columns
WHERE table_schema='public' AND is_generated='ALWAYS';
```

| columna | expresión |
|---|---|
| `pedido_detalle.subtotal` | `cantidad * precio_unitario` |
| `orden_compra_detalle.subtotal` | `cantidad * precio_unitario` |
| `factura_venta_detalle.subtotal` | `cantidad * precio_unitario` |
| `factura_compra_detalle.subtotal` | `cantidad * precio_unitario` |

### La compuerta horaria — cómo funciona de verdad

`fn_bloquear_fuera_horario()` es un trigger **STATEMENT-level** sobre INSERT/UPDATE/DELETE de las
tablas operativas; llama a `esta_en_horario(fn_grupo_actual())` y lanza `SQLSTATE 42501` si el rol
está fuera de ventana. `esta_en_horario()` es `STABLE SECURITY DEFINER` y ancla el reloj a
`America/Guayaquil` (verificado con `pg_get_functiondef`). **`grp_administrador` y cualquier
superusuario están exentos** por el primer `IF` de la función.

Ventanas actuales en `grupo_horario` (**verificadas en el motor vivo**, idénticas a la copia):

| rol | ventana efectiva |
|---|---|
| `grp_cliente` | 24/7 los 7 días (1 minuto muerto a la semana) |
| `grp_soporte` | 24/7 (5 minutos muertos a la semana) |
| `grp_bodega`, `grp_compras`, `grp_despacho`, `grp_vendedor`, `grp_gerente`, `grp_analista` | ~24 h de domingo a viernes **EXCEPTO** martes `08:00–18:00` y sábado `08:00–13:00` |

Cuantificado minuto a minuto sobre la semana completa (10.080 minutos), evaluando la misma
condición que `esta_en_horario()`:

| rol | minutos/semana bloqueados | % de la semana | de ellos, fuera de martes y sábado |
|---|---|---|---|
| `grp_gerente` | 1.988 | **19,72 %** | 8 |
| `grp_compras`, `grp_despacho`, `grp_vendedor` | 1.987 | **19,71 %** | 7 |
| `grp_bodega`, `grp_analista` | 1.986 | **19,70 %** | 6 |
| `grp_soporte` | 5 | 0,05 % | 5 |
| `grp_cliente` | 1 | 0,01 % | 1 |

**Casi una quinta parte de la semana, los seis roles de staff están bloqueados por el motor**: no
pueden escribir NI LEER las tablas con `pol_horario` (la política está declarada `cmd=ALL`, y ALL
incluye SELECT). Los 6–8 minutos residuales fuera de martes y sábado son las juntas de las
ventanas (`hora_fin = 23:59` con comparación `<`, y `hora_inicio = 00:01` los domingos y
miércoles): huecos de un minuto que un generador con reintentos ni notaría, pero que existen.
`grp_administrador` está exento por el primer `IF` de `esta_en_horario()`. Ver RIESGO R-3.

Comprobación en vivo del momento de la auditoría (jueves, `dow = 4`):
`esta_en_horario()` devuelve `true` para los ocho roles — el día de la semana en que se prueba
decide el resultado, y ése es justamente el problema.

### Políticas RLS sobre las tablas de los tres flujos

```sql
SELECT tablename, policyname, cmd, roles, qual, with_check FROM pg_policies
WHERE tablename IN ('pedido','pedido_detalle','movimiento_inventario','inventario',
                    'orden_compra','recepcion_mercancia','recepcion_detalle','envio',
                    'factura_venta','pago');
```

Patrón: `pol_horario` (`cmd=ALL`, los 7 roles de staff, `USING`/`WITH CHECK =
esta_en_horario(fn_grupo_actual())`) + políticas de aislamiento del cliente
(`pol_cliente_propio`, `pol_cliente_pago`, `pol_cliente_emision`, `pol_cliente_checkout`,
`pol_cliente_tienda`) + `pol_soporte` de solo lectura. **`grp_soporte` NO aparece en
`pol_horario`**: sus políticas propias son SELECT con `esta_en_horario('grp_soporte')`.

---

## B1. Venta en línea: del nacimiento del pedido al despacho y la factura

**Punto de entrada**: `CarritoService.checkout()` (`carrito/CarritoService.java:158`), **una sola
transacción** que corre bajo `grp_cliente`.

### Secuencia real de escritura

| # | tabla | operación | dónde |
|---|---|---|---|
| 1 | `pedido` | INSERT, estado **`confirmado`** | `VentasService.java:82-88` |
| 2 | `log_auditoria` | INSERT — **SOLO si `vendedor_id != null`**, o sea NUNCA en el checkout online | `VentasService.java:89-93` |
| 3 | `pedido_detalle` | INSERT por línea (sin `subtotal`) | `VentasService.java:124-130` |
| 3b| `pedido` | **UPDATE automático por trigger** `trg_pedido_detalle_total` | — |
| 4 | `inventario` + `movimiento_inventario` + `inventario` | **`StockService.mover(..., "salida_venta", ...)`** | `VentasService.java:137-138` → `StockService.java:47-82` |
| 5 | `historial_estado_pedido` | INSERT `confirmado` | `VentasService.java:141` |
| 6 | `pedido` | UPDATE `metodo_envio_id`, `transportista_id` (zona) | `VentasService.java:186-188` |
| 7 | `uso_cupon` (+ trigger sobre `cupon`), `pedido.monto_descuento`, reescalado de `pedido_detalle.monto_impuesto` | solo si hay cupón | `CarritoService.java:271` → `DescuentosService.aplicarCupon` |
| 8 | `pago` | INSERT estado `completado` | `VentasService.java:505-511` |
| 9 | `transaccion_pago` | INSERT `captura`/`exitosa` | `VentasService.java:512-516` |
| 10 | `pedido` → **`pagado`** + `historial_estado_pedido` | `cambiarEstadoPedido` | `VentasService.java:518` |
| 11 | `factura_venta` | INSERT estado `emitida` — **AUTOMÁTICA, misma transacción** | `VentasService.java:613-623` |
| 12 | `factura_venta_detalle` | INSERT por línea con cupón prorrateado | `VentasService.java:651-662` |
| 12b| `factura_venta` | **UPDATE por trigger** `trg_factura_venta_detalle_total` (SECURITY DEFINER) | — |
| 13 | `pedido` → **`facturado`** + historial | `VentasService.java:666` |
| 14 | `carrito` | UPDATE `estado='convertido'` | `CarritoService.java:277` |

**A partir de aquí, transacciones separadas y roles distintos:**

| # | tabla | operación | rol | dónde |
|---|---|---|---|---|
| 15 | `pedido` → `en_preparacion` + historial | `iniciarPreparacion` | **BODEGA** | `VentasService.java:784-798` |
| 16 | `pedido` → `preparado` + historial | `marcarPreparado` | **BODEGA** | `VentasService.java:803-820` |
| 17 | `pedido` UPDATE transportista (si hay override) | | **DESPACHO** | `VentasService.java:898` |
| 18 | `envio` INSERT estado `en_transito` | | DESPACHO | `VentasService.java:915-926` |
| 19 | `log_auditoria` INSERT | explícito | DESPACHO | `VentasService.java:927-932` |
| 20 | `envio_detalle` INSERT | | DESPACHO | `VentasService.java:934-937` |
| 21 | `seguimiento_envio` INSERT | | DESPACHO | `VentasService.java:939-945` |
| 22 | `pedido` → **`despachado`** + historial | | DESPACHO | `VentasService.java:947` |
| 23 | `envio` UPDATE `entregado` + `seguimiento_envio` + `pedido` → **`entregado`** | `entregar` | DESPACHO | `VentasService.java:1005-1015` |

### Estados por los que pasa el pedido

`confirmado` → `pagado` → `facturado` → `en_preparacion` → `preparado` → `despachado` →
`entregado`
(catálogo completo en `estado_pedido`: `pendiente, confirmado, pagado, en_preparacion,
despachado, entregado, cancelado, devuelto, facturado, preparado, no_entregado`).

**Los pasos 1 a 14 ocurren en UNA transacción**: el pedido online nunca existe en `confirmado` ni
en `pagado` para un observador externo. Nace `facturado`.

### Compuertas y DÓNDE se imponen

| transición | condición | dónde |
|---|---|---|
| → `pagado` (online) | estado exacto `confirmado` | **solo backend**, `VentasService.java:494-497` |
| → `pagado` (interno) | canal ≠ `web`; estado ∈ {pendiente, confirmado}; pago ≤ saldo | **solo backend**, `VentasService.java:423-452` |
| → `facturado` | estado exacto `pagado`; sin factura no anulada previa | **solo backend**, `VentasService.java:583-600` |
| → `en_preparacion` | estado exacto `facturado` | **solo backend**, `VentasService.java:790` |
| → `preparado` | estado exacto `en_preparacion` | **solo backend**, `VentasService.java:813` |
| → `despachado` | estado exacto `preparado` **y** existe factura | **solo backend**, `VentasService.java:859-868` |
| → `entregado` | estado exacto `despachado` **y** el envío no está `fallido` | **solo backend**, `VentasService.java:987-1000` |

**Ninguna de las siete tiene respaldo en el motor.** Un `UPDATE pedido SET estado_pedido_id=6`
(entregado) sobre un pedido recién creado **pasa sin un solo error**, siempre que el rol tenga el
GRANT y esté en horario.

### Columnas calculadas — NO escribir

- `pedido_detalle.subtotal` (GENERATED).
- `pedido.subtotal`, `pedido.monto_impuesto`, `pedido.total` — los pone
  `fn_recalcular_total_pedido()` desde el detalle (trigger `trg_pedido_detalle_total`).
  **Ojo: esa función NO es `SECURITY DEFINER`** (a diferencia de la de compras), así que corre con
  el rol invocador y exige que ese rol tenga UPDATE de esas columnas sobre `pedido`.
- `pedido.total` también lo recalcula `trg_pedido_total` en BEFORE INSERT/UPDATE de
  `subtotal, monto_descuento, monto_impuesto, costo_envio` (`fn_recalcular_total_cabecera_pedido`).
- `factura_venta_detalle.subtotal` (GENERATED); totales de `factura_venta` por trigger
  `SECURITY DEFINER`.
- `fecha_actualizacion` de `pedido`, `envio`, `factura_venta`, `pago`, `inventario` (triggers touch).
- `cupon.usos_actuales` — lo mantiene `fn_registrar_uso_cupon` (`SECURITY DEFINER`, con lock).

### Efectos colaterales AUTOMÁTICOS vs. escritura EXPLÍCITA

**Automático (triggers)**: totales de pedido y factura; `fecha_actualizacion`; `usos_actuales`
del cupón; bloqueo horario.

**Explícito en Java — no lo hace nadie por ti**:
- **el kardex y el stock** (`StockService.mover`),
- `historial_estado_pedido` (`registrarHistorial`, `VentasService.java:1293-1298`),
- `envio_detalle`, `seguimiento_envio`,
- `log_auditoria` (`AuditoriaService.registrar`, invocado a mano en cada punto),
- la factura y su detalle.

### Roles necesarios y bloqueos

| paso | authority JWT (`SecurityConfig`) | rol de motor | ¿bloquea el horario? |
|---|---|---|---|
| checkout | CLIENTE | `grp_cliente` | **No** — ventana ~24/7 los 7 días |
| cobro manual | ADMIN, VENDEDOR (línea 116) | `grp_vendedor` | **Sí** martes y sábado fuera de ventana |
| factura manual | ADMIN, VENDEDOR (línea 120) | `grp_vendedor` | **Sí** |
| preparación | ADMIN, BODEGA (línea 124) | `grp_bodega` | **Sí** |
| despacho / entrega | ADMIN, DESPACHO (líneas 127, 132) | `grp_despacho` | **Sí** |

**Contraste operativo**: el CLIENTE puede comprar a las 3 de la madrugada de un martes; BODEGA no
puede prepararlo hasta las 08:00. Un generador que simule las dos puntas con el mismo reloj
producirá 403 sistemáticos en un lado y no en el otro.

Un detalle a favor del generador: **RLS del cliente no se activa sola**. `pol_cliente_propio` usa
`fn_cliente_actual()`, que lee el GUC `app.cliente_id` puesto por el aspecto
(`PgSessionRoleAspect.java:102-108`). Un generador que hable con la API lo hereda; uno que hable
con SQL directo debe ponerlo o verá cero filas sin error.

---

## B2. Compra (P2P): de la orden a la recepción en bodega

**Servicio**: `compras/ComprasService.java`.

### Secuencia real de escritura

| # | tabla | operación | dónde |
|---|---|---|---|
| 1 | `orden_compra` | INSERT estado **`enviada`** | `ComprasService.java:104-112` |
| 2 | `orden_compra_detalle` | INSERT por línea (sin `subtotal`) | `ComprasService.java:120-124` |
| 2b| `orden_compra` | **UPDATE por trigger** `trg_orden_compra_detalle_total` (SECURITY DEFINER) | — |
| 3 | `orden_compra` | UPDATE → **`confirmada`** (= aprobación) | `ComprasService.java:217` |
| 4 | `log_auditoria` | INSERT explícito | `ComprasService.java:222-223` |
| 5 | `orden_compra` | `UPDATE … SET estado = estado RETURNING …` — truco de bloqueo | `ComprasService.java:244-248` |
| 6 | `recepcion_mercancia` | INSERT estado `confirmada` | `ComprasService.java:269-274` |
| 7 | `recepcion_detalle` | INSERT por línea | `ComprasService.java:300-307` |
| 8 | `item_defectuoso` (+`log_auditoria`) | INSERT si `cantidad_rechazada > 0`, **SIN mover stock** | `ComprasService.java:313-325` |
| 9 | `inventario` | INSERT … ON CONFLICT DO NOTHING | `ComprasService.java:329-333` |
| 10 | `inventario` | SELECT … **FOR UPDATE** | `ComprasService.java:334-337` |
| 11 | `movimiento_inventario` | INSERT `entrada_compra` | `ComprasService.java:341-348` |
| 12 | `inventario` | UPDATE `stock_actual` | `ComprasService.java:350-353` |
| 13 | `orden_compra_detalle` | UPDATE `cantidad_recibida += n` | `ComprasService.java:355-357` |
| 14 | `producto_proveedor` | upsert vía `fn_upsert_producto_proveedor` (SECURITY DEFINER) | `ComprasService.java:366-367` |
| 15 | `orden_compra` | UPDATE → `recibida` o `recibida_parcial` | `ComprasService.java:373-374` |

Continuación (fuera del alcance literal de B2, pero es la misma cadena):
`factura_compra` + `factura_compra_detalle` (trigger de totales) + `cuenta_por_pagar`
(`ComprasService.java:423-450`), y luego `pago_proveedor` + UPDATE de CxP y factura
(`ComprasService.java:497-509`).

### Estados

`enviada` → `confirmada` → (`recibida_parcial` →) `recibida`.
CHECK del motor: `{borrador, enviada, confirmada, recibida_parcial, recibida, cancelada}`.
**No existe un estado `aprobada`**: aprobar deja la orden en `confirmada`
(`ComprasService.java:196-199`).

### Compuertas y dónde se imponen

| transición | condición | dónde |
|---|---|---|
| → `confirmada` | estado ∈ {borrador, enviada} | **solo backend**, `ComprasService.java:207-216` |
| → recepción | estado ∉ {borrador, enviada, recibida, cancelada} | **solo backend**, `ComprasService.java:251-263` |
| cantidad recibida | `≤ cantidad − cantidad_recibida` | **solo backend**, `ComprasService.java:291-297` |
| → factura | estado exacto `recibida`; sin factura previa | **solo backend**, `ComprasService.java:394-418` |
| → pago | CxP con saldo; `monto ≤ saldo` | **solo backend**, `ComprasService.java:485-495` |

El motor solo aporta: el CHECK del vocabulario de estados,
`recepcion_detalle_cantidad_recibida_check (> 0)`,
`recepcion_detalle_cantidad_rechazada_check (>= 0)` y
`ck_factura_compra_vencimiento (fecha_vencimiento >= fecha_emision)`.

**Nota de diseño (`ComprasService.java:239-243`)**: la recepción **no usa `SELECT … FOR UPDATE`**
sobre `orden_compra`, porque ese lock exige UPDATE de TABLA COMPLETA y `grp_bodega` solo tiene
UPDATE de 2 columnas. Usa `UPDATE … SET estado = estado RETURNING`, que toma el mismo lock de fila
respetando el grant por columna. **Un generador que hable SQL directo bajo `grp_bodega` y use
`FOR UPDATE` recibirá un 42501 que parecerá un problema de RLS y no lo es.**

### Columnas calculadas — NO escribir

- `orden_compra_detalle.subtotal`, `factura_compra_detalle.subtotal` (GENERATED).
- `orden_compra.subtotal/monto_impuesto/total` (`fn_recalcular_total_orden_compra`,
  **SECURITY DEFINER**).
- `factura_compra.subtotal/monto_impuesto/total` (`trg_factura_compra_detalle_total`).
- `fecha_actualizacion` de `orden_compra`, `factura_compra`, `cuenta_por_pagar`,
  `producto_proveedor`, `inventario`.

### Efectos colaterales

**Automático**: los totales de orden y factura; los `touch`; el bloqueo horario.

**Explícito**: `orden_compra_detalle.cantidad_recibida` (paso 13 — **si un generador lo olvida, la
orden nunca llega a `recibida` y no se puede facturar**); el kardex completo; el estado de la
orden; `cuenta_por_pagar`; `item_defectuoso`; `log_auditoria`.

**Hallazgo de duplicación**: la recepción **NO usa `StockService.mover()`**. Reimplementa el mismo
patrón a mano (`ComprasService.java:329-353`) — con una diferencia relevante: **no lee
`tipo_movimiento.factor`**, suma directo (`stockAnterior + cantidadRecibida`, línea 338). Hay dos
implementaciones del kardex en el código, y solo una consulta el catálogo de signos.

### Roles y bloqueos

| paso | authority JWT | rol de motor | ¿horario? |
|---|---|---|---|
| emitir orden | ADMIN, GERENTE, COMPRAS, BODEGA (`SecurityConfig.java:109-110`) | `grp_compras` | **Sí** |
| aprobar | ADMIN, GERENTE (líneas 83-84) | `grp_gerente` | **Sí** |
| recibir | ADMIN, GERENTE, COMPRAS, BODEGA | `grp_bodega` | **Sí** |
| facturar / pagar | ADMIN, GERENTE, COMPRAS, BODEGA | `grp_compras` | **Sí** |

GRANTs verificados (`aclexplode` sobre `pg_class.relacl`): `orden_compra` → `grp_compras`
{S,I,U,D}, `grp_gerente` {S,U}, `grp_bodega` solo **2 columnas** de UPDATE;
`movimiento_inventario` → `grp_bodega` {S,I,U}, `grp_compras` {S,I}, `grp_cliente` **solo I**;
`inventario` → `grp_bodega`/`grp_compras`/`grp_cliente`/`grp_vendedor` {S,I,U}.

**Ningún flujo de compras tiene aislamiento RLS por fila: sobre `orden_compra`, `recepcion_*` y
`factura_compra` la única política es `pol_horario`.** La compuerta es el reloj, no el dato.

---

## B3. Movimiento de inventario de bodega

Hay **tres** rutas de escritura de kardex en el sistema. Es importante que un generador las
distinga, porque no son intercambiables.

### Ruta canónica: `StockService.mover()` (`inventario/StockService.java:36-84`)

`@Transactional(propagation = MANDATORY)` — **no abre transacción propia**: se exige que corra
dentro de la del caso de uso, para heredar el `SET LOCAL ROLE`.

Secuencia exacta:

1. `SELECT factor FROM tipo_movimiento WHERE codigo = ? AND activo` (línea 43) — lista blanca en BD.
2. `INSERT INTO inventario … ON CONFLICT DO NOTHING` (líneas 47-51).
3. `SELECT stock_actual … FOR UPDATE` (líneas 52-55) — **el único punto de serialización**.
4. `stockNuevo = stockAnterior + factor * cantidad`; si `< 0`, `IllegalArgumentException` → 400 (57-66).
5. `INSERT INTO movimiento_inventario (…, stock_anterior, stock_nuevo, …)` (68-77).
6. `UPDATE inventario SET stock_actual = ?` (79-82).

**Los pasos 5 y 6 son dos sentencias independientes.** Nada obliga a que la segunda ocurra.

Usos: venta (`VentasService.java:137`), transferencia (`InventarioService.java:55-58`),
ajuste (`InventarioService.java:150`), anulación de ajuste (`InventarioService.java:209-215`),
RMA y reposición de proveedor.

### Ruta 2: recepción de compra — copia manual (`ComprasService.java:329-353`), ver B2.

### Ruta 3: los scripts SQL del seed (`retailmind/sql/postgres/`), que insertan directo.

### Cabeceras: `transferencia_bodega` y `ajuste_inventario`

Las dos son **SOLO CABECERA**: no tienen tabla de detalle. La variante y la cantidad viven en el
kardex y, en texto, en el campo libre con el formato `[SKU x N] …`
(`InventarioService.java:52` y `:147`). Estados por CHECK:
`transferencia_bodega ∈ {pendiente, en_transito, recibida, cancelada}` —
la app crea directamente `'recibida'` (línea 49);
`ajuste_inventario ∈ {borrador, aplicado, anulado}` — la app crea `'aplicado'` (línea 143);
`'borrador'` **no tiene flujo** (documentado en `InventarioService.java:169-176`).

### Compuertas

| condición | dónde |
|---|---|
| `cantidad > 0` | backend (`StockService.java:40`) **y** CHECK `movimiento_inventario_cantidad_check` |
| stock no negativo | backend (`StockService.java:58`) **y** CHECK `inventario_stock_actual_check` + `movimiento_inventario_stock_nuevo_check` |
| bodegas distintas | backend (`InventarioService.java:39`) **y** CHECK `ck_transferencia_bodegas_distintas` |
| anular solo `aplicado` | **solo backend** (`InventarioService.java:188-191`) |
| tipo de movimiento válido | **BD**: FK a `tipo_movimiento` + `tipo_movimiento_factor_check (∈ {-1,1})` |

Es el único de los tres flujos donde el motor respalda de verdad las invariantes de valor. Lo que
NO respalda es la COHERENCIA entre kardex y stock (ver B4).

### Efectos colaterales

**Automático**: `trg_inventario_touch`, `trg_horario_*`. **Nada más.**
**Explícito**: absolutamente todo lo demás, incluida la fila de `inventario`.
`movimiento_inventario` no tiene trigger `touch` (no tiene `fecha_actualizacion`).

### Roles y bloqueos

- Transferencias/ajustes: `SecurityConfig` → ADMIN/BODEGA; motor: `grp_bodega`
  {S,I,U} sobre `movimiento_inventario`, `inventario`, `transferencia_bodega`, `ajuste_inventario`.
- **Horario: SÍ bloquea.** `trg_horario_movimiento_inventario` y `trg_horario_inventario` son
  STATEMENT-level sobre INSERT/UPDATE/DELETE. Martes fuera de 08–18 y sábado fuera de 08–13,
  bodega no mueve una sola unidad.
- RLS: sobre `movimiento_inventario` e `inventario` la única política de staff es `pol_horario`.
  El cliente entra por `pol_cliente_checkout` (INSERT) y `pol_cliente_tienda` (ALL).

---

## B4. Integridad del kardex: qué mantiene realmente el 1.406/1.406

### Lo que se verificó (contra la base VIVA del 5432)

```sql
-- 1) inventario.stock_actual == Σ(cantidad × factor) por (variante, bodega)
WITH k AS (SELECT m.producto_variante_id v, m.bodega_id b,
                  SUM(m.cantidad*tm.factor) saldo
           FROM movimiento_inventario m
           JOIN tipo_movimiento tm ON tm.id=m.tipo_movimiento_id GROUP BY 1,2)
SELECT count(*), count(*) FILTER (WHERE i.stock_actual = k.saldo)
FROM k FULL JOIN inventario i
  ON i.producto_variante_id=k.v AND i.bodega_id=k.b;
--  1406 | 1406   (0 diferencias)

-- 2) ecuación por fila: stock_nuevo = stock_anterior + cantidad*factor
--  13288 de 13288  ✔

-- 3) encadenamiento por (fecha_creacion, id): prev.stock_nuevo = stock_anterior
--  0 rotos · 0 cadenas que arrancan distinto de cero  ✔
```

Las tres invariantes se cumplen perfectamente.

### Lo que las mantiene: NADA en el motor

**Respuesta directa: es una CONVENCIÓN de la aplicación. No hay trigger, no hay restricción, no
hay procedimiento almacenado.**

Evidencia negativa, buscada explícitamente:

1. **Triggers sobre `movimiento_inventario`**: solo `trg_horario_movimiento_inventario`
   (`fn_bloquear_fuera_horario`). Sobre `inventario`: `trg_horario_inventario` y
   `trg_inventario_touch`. El resto de entradas en `pg_trigger` para ambas tablas son
   `RI_ConstraintTrigger_*` con `tgisinternal = true`, es decir claves foráneas.
2. **Restricciones**: `movimiento_inventario` solo tiene 4 CHECKs de rango
   (`cantidad > 0`, `costo_unitario >= 0`, `stock_anterior >= 0`, `stock_nuevo >= 0`).
   **No existe** `CHECK (stock_nuevo = stock_anterior + cantidad * factor)` — y no podría existir
   en un CHECK simple, porque `factor` vive en otra tabla.
3. **Índices**: 7 sobre `movimiento_inventario`, **ninguno UNIQUE salvo la PK**. Nada impide
   insertar el mismo movimiento dos veces.
4. **Funciones SECURITY DEFINER**: las 13 que existen son de recálculo de totales, cupón,
   ticket, acceso, pago fallido y `producto_proveedor`. **Ninguna toca el inventario.**

### Qué mantiene el balance, entonces

Exactamente tres cosas, todas fuera de la base:

1. **`StockService.mover()`** — el `FOR UPDATE` de la línea 54 serializa los movimientos de la
   misma `(variante, bodega)`, y las líneas 57, 68-77 y 79-82 escriben los tres datos coherentes.
2. **La copia manual de `ComprasService.registrarRecepcion()`** (líneas 329-353), que hace lo
   mismo sin consultar `factor`.
3. **La disciplina de los scripts del seed**, que reencadenaron a mano
   (`78_rebalanceo_kardex.sql`, `80_transferencias_ajustes_stock.sql`).

### Lo que esto significa para un generador

**Un generador que escriba SQL directo a `movimiento_inventario` puede romper el balance sin que
nada se queje.** Los tres modos de fallo silencioso:

- insertar el movimiento y no actualizar `inventario` (o al revés);
- escribir `stock_nuevo` sin respetar `factor` (una `salida_venta` con `factor = -1` escrita como
  suma);
- insertar en el PASADO — el encadenamiento se ordena por `(fecha_creacion, id)`, así que un
  movimiento con fecha anterior invalida `stock_anterior/stock_nuevo` de todos los posteriores de
  esa `(variante, bodega)`.

Y hay un cuarto, más sutil, que es específico de la escritura **concurrente** y que hoy no puede
ocurrir porque el sistema no tiene carga: `movimiento_inventario.fecha_creacion` tiene
`DEFAULT now()`, y `now()` en PostgreSQL es el **instante de inicio de la transacción**, no el del
INSERT. Con dos transacciones solapadas sobre el mismo par, la que empezó ANTES puede obtener el
lock DESPUÉS y escribir una fila con `fecha_creacion` menor e `id` mayor: el orden
`(fecha_creacion, id)` deja de coincidir con el orden real de aplicación y **la cadena se rompe
aunque cada fila individual sea correcta y el saldo final cuadre**. El ETL lo notaría solo a
través de `fact_stock_mensual`, que reconstruye leyendo
`argMax(stock_nuevo, (fecha, movimiento_id))`. **La premisa está VERIFICADA en el motor vivo**
(`now()` se mantuvo constante mientras `clock_timestamp()` avanzaba un segundo, y
`column_default = now()` en esa columna); la consecuencia es una deducción —**no ejecuté
escrituras concurrentes**, por la restricción de solo lectura—. Detalle en RIESGO R-9.

---

## B5. ¿Se puede distinguir un registro del seed de uno posterior?

**Respuesta corta: NO de forma fiable, y donde parece que sí, la marca está incompleta.**

### No existe ninguna columna de procedencia

```sql
SELECT table_name, column_name FROM information_schema.columns
WHERE table_schema='public'
  AND column_name ~* 'origen|fuente|seed|lote_carga|batch|sintetic';
```

Devuelve 7 filas y **ninguna es de procedencia**: `item_defectuoso.origen` (rma/recepcion),
`reserva_stock.origen`, tres `ip_origen`, `tipo_cambio.moneda_origen_id`,
`transferencia_bodega.bodega_origen_id`.

### Lo que SÍ existe (y sus límites)

**1. Marcador de texto en `movimiento_inventario.observacion`** — el mejor candidato, pero
incompleto:

| marcador | filas |
|---|---|
| `[SEED-BB] salida por venta` | 9.741 |
| `[SEED-REB] entrada por compra` | 1.610 |
| `[SEED-BA] entrada por compra` | 1.223 |
| `[SEED-REB] carga inicial de inventario 2025` | 343 |
| `[SEED-BB] reingreso por devolucion apta` | 112 |
| `[SEED-BB] reposicion de proveedor` | 5 |
| **sin marca** | **254** |

**El marcador NO cubre todo el seed.** De los 254 sin marca, buena parte son del propio seed:

```sql
SELECT referencia_tipo, min(fecha_creacion)::date, max(fecha_creacion)::date, count(*)
FROM movimiento_inventario WHERE observacion IS NULL OR observacion NOT LIKE '%SEED%'
GROUP BY 1;
--  transferencia_bodega | 2025-01-06 | 2026-07-23 | 121
--  ajuste_inventario    | 2025-01-13 | 2026-07-22 |  56
--  pedido               | 2026-07-04 | 2026-07-22 |  44
--  recepcion_mercancia  | 2026-07-04 | 2026-08-02 |  23
--  devolucion           | 2026-07-04 | 2026-07-16 |   7
--  devolucion_proveedor / item_defectuoso        |   3
```

**Prueba directa e incontestable**: **93 de esos 254 movimientos sin marca tienen
`fecha_creacion < 2026-01-01`.** En 2025 la aplicación no estaba escribiendo nada: son del seed.
Vienen del script 80 (`80_transferencias_ajustes_stock.sql`, 61 transferencias + 50 ajustes), que
**no escribió el marcador** — `transferencia_bodega` tiene 71 filas y `ajuste_inventario` 53,
coherente con eso.

Es decir: `observacion LIKE '%SEED%'` **sub-cuenta el seed**, y usarlo como filtro clasificaría
177 movimientos sembrados como "actividad real".

**2. `orden_compra.observacion`**: 848 de 867 con marca `SEED`. Las 19 restantes son de la app.

**3. `historial_estado_pedido.comentario`**: **0 de 24.610** llevan marca. Inservible.

**4. `pedido`: NO hay forma de distinguirlo.** El seed usa el mismo formato de número:
```sql
SELECT regexp_replace(numero,'[0-9]+','#','g'), count(*) FROM pedido GROUP BY 1;
--  PED-#-# | 4083     (una sola forma para los 4.083)
```
y el INSERT del seed (`60_seed_bloque_b_ventas.sql:271-278`) escribe exactamente las mismas
columnas que `VentasService.java:82-88`, sin marcador alguno. La única pista **indirecta** es el
movimiento de kardex asociado: 35 pedidos tienen kardex sin marca, todos entre 2026-07-04 y
2026-07-22.

**5. Metadatos de lote en `configuracion_tienda`**: ~30 claves `seed_*` con el JSON de conteos por
script (`seed_ba_55_entidades`, `seed_bb_60_ventas`, `seed_reb78_kardex`, …). Son el registro de
la CORRIDA del seed, **no una etiqueta por fila**: no permiten clasificar un registro concreto.

**6. Esquema `seed_backup`**: respaldos completos previos a cada bloque. Permite reconstruir por
diferencia, pero es forense, no una marca.

**7. Los huecos de id NO sirven**: `pedido` tiene 4.083 filas con `max(id) = 4217` — 134 ids
perdidos por transacciones revertidas, repartidos, no agrupados al final.

### Conclusión de B5

Hoy, ante una fila cualquiera de `pedido`, `pedido_detalle`, `factura_venta`, `pago`, `envio` o
`historial_estado_pedido`, **no hay forma de saber quién la escribió**. Solo `movimiento_inventario`
y `orden_compra` llevan una marca de texto, y en el primer caso es incompleta.

---

## B6. Conexión de escritura del backend

**Confirmado exactamente como lo describe el prompt.**

**Mecanismo**: la aplicación conecta con **`retailmind_app`** (LOGIN, NOINHERIT, sin privilegios de
negocio) y **asume el rol del usuario POR TRANSACCIÓN**.

**Dónde está implementado**: `retailmind-backend/src/main/java/com/retailmind/security/PgSessionRoleAspect.java`

- Aspecto AOP `@Order(10)`, después del advisor de `@Transactional` (`order=0` en `PostgresConfig`),
  lo que garantiza que corre DENTRO de la transacción (líneas 49-52, 26-28).
- Pointcut (líneas 62-67): cualquier método o clase con `@Transactional` dentro de
  `com.retailmind..*`, **excluyendo `com.retailmind.analytics..*`**.
- Sin transacción activa o sin usuario autenticado, no asume rol (líneas 69-76).
- Resuelve el rol de motor: enum `DbGroupRole` para los 9 del sistema, o `principal.getRolMotor()`
  para roles personalizados del script 87 (líneas 78-82).
- **La sentencia exacta** (líneas 98-101):

```java
try (PreparedStatement ps = con.prepareStatement("SELECT set_config('role', ?, true)")) {
    ps.setString(1, pgRole);
    ps.execute();
}
```

  **No es `SET LOCAL ROLE ` + nombre concatenado.** Es el mismo GUC y el mismo alcance de
  transacción, con el nombre como **parámetro ligado**.
- Si el usuario es CLIENTE, además `set_config('app.cliente_id', ?, true)` (líneas 102-108) — el
  GUC del que dependen todas las políticas `pol_cliente_*`.
- El tercer argumento `true` = `is_local`: **muere con el COMMIT/ROLLBACK**, así que la conexión
  vuelve al pool de Hikari limpia, como `retailmind_app`.

**Consecuencia operativa, que es la regla de oro del proyecto y aquí queda confirmada por el
código**: si un método toca PostgreSQL **fuera de `@Transactional`**, el aspecto no se dispara
(línea 69) y la sentencia corre como `retailmind_app`, que **no tiene privilegios de negocio**.
No hay un modo "sin seguridad": hay un modo "sin privilegios".

**El ETL usa otra identidad**: `retailmind_etl` (LOGIN + BYPASSRLS + solo lectura), en
`retailmind/etl/dwh/conexiones.py:56` y `91-121`, con `conn.set_session(readonly=True)` (línea 113)
y `default_transaction_read_only = on` a nivel de rol. `verificar_postgres()` (líneas 155-183)
comprueba que el rol tenga BYPASSRLS **y** que `SELECT count(*) FROM pedido` no devuelva 0,
justo porque RLS filtraría en silencio.

---

# RIESGOS PARA EL GENERADOR

Puntos donde una escritura sintética mal hecha rompe la integridad **sin producir un error
visible**. Ordenados por gravedad.

### R-1 · El ETL valida contra un origen en movimiento, y el reintento tapa el descuadre

`carga_atomica.validar_conteo()` exige igualdad EXACTA entre las filas escritas y el conteo del
origen (`carga_atomica.py:89-93`). Con la base quieta eso es una red de seguridad excelente. Con
un generador escribiendo 24/7, **cada carga compite contra el reloj**: `sql_extraccion()` y
`sql_controles()` se ejecutan en **conexiones distintas y momentos distintos**
(`tarea.py:86` y `tarea.py:141`), sin snapshot compartido. Un pedido nuevo entre ambas descuadra
la tabla, que aborta, y el orquestador **reintenta hasta 3 veces con espera creciente**
(`run_etl.py:239-246`). El comentario de `run_etl.py:36-43` dice explícitamente que reintenta
porque el descuadre "suele ser transitorio". Con actividad continua, ese supuesto se invierte: los
3 intentos pueden fallar todos, y la tabla se quedará con el dato de ayer **mientras la corrida
reporta "fallo parcial"** — o, peor, cuadrará por casualidad en el intento 2 y nadie mirará. El
síntoma no es un error: es un almacén que envejece en silencio.

### R-2 · El kardex no está protegido por nada (B4)

Escribir en `movimiento_inventario` sin actualizar `inventario`, o sin respetar
`tipo_movimiento.factor`, o con fecha en el pasado, **no produce error**. Los CHECKs solo miran
que los números no sean negativos. El descuadre aparecería en el control 3B del ETL —
`fact_stock_mensual` compara posición por posición contra `inventario.stock_actual` — pero ese
control corre **después** de que la carga ya se hizo, y lo que aborta es la publicación de la
tabla del DWH, no la escritura en PostgreSQL. Para entonces el dato operativo ya está corrupto.
**El único camino seguro es `StockService.mover()`**, y solo dentro de una transacción.

### R-3 · La compuerta horaria bloquea al staff el 19,7 % de la semana

**Medido en el motor vivo**: 1.986–1.988 minutos de cada 10.080 — casi una quinta parte de la
semana — los seis roles de staff no pueden tocar las tablas operativas, concentrados en el martes
fuera de 08–18 y el sábado fuera de 08–13. `grp_cliente` en cambio pierde **1 minuto a la
semana**.

`trg_horario_*` es STATEMENT-level y `pol_horario` está declarada `cmd=ALL` — **ALL incluye
SELECT**. Un generador que simule bodega/compras/despacho/vendedor un martes a las 22:00 recibirá
`SQLSTATE 42501` en cada sentencia, que `GlobalExceptionHandler` traduce a **403**. Y hay un
segundo modo, peor: si el generador LEE bajo un rol de staff fuera de ventana, `pol_horario`
**no devuelve error, devuelve cero filas** — el generador concluirá "no hay pedidos que preparar"
y no habrá ni una línea de log.

La asimetría es lo grave: **el cliente compra 24/7 y el staff atiende el 80 % del tiempo**, así
que una simulación ingenua acumulará pedidos que nadie prepara cada martes y cada sábado. Y como
la prueba de humo se hará el día que se haga —un jueves, `esta_en_horario()` da `true` para los
ocho roles—, el defecto no aparecerá hasta el primer martes por la noche.

### R-4 · Las siete compuertas del ciclo de venta viven SOLO en Java

`pedido` no tiene ni un CHECK de estado: el estado es una FK a `estado_pedido`. Un generador que
escriba SQL directo puede saltar de `confirmado` a `entregado` en un UPDATE, sin pago, sin
factura, sin envío y sin historial. El sistema quedaría con un pedido entregado sin fila en `pago`
— exactamente el defecto legacy que la Fase 2 del ETL documenta para los pedidos 20 y 21. **Todo
lo que respalda el proceso son los `IllegalStateException` de `VentasService`**. Un generador que
escriba por SQL debe reimplementar las siete compuertas o no las tendrá.

### R-5 · Hay DOS implementaciones del kardex y solo una consulta `factor`

`StockService.mover()` lee `tipo_movimiento.factor` (línea 43) y calcula con él.
`ComprasService.registrarRecepcion()` suma directo (`ComprasService.java:338`). Son coherentes hoy
porque `entrada_compra` tiene factor `+1`. Si alguien añade un tipo de movimiento nuevo o cambia
un factor, **una ruta lo respeta y la otra no**, y el desacuerdo no produce error: produce stock
equivocado.

### R-6 · `dim_fecha` está codificada en duro hasta el 2026-12-31

`tablas/dim_fecha.py:28-29`. Un generador que produzca actividad más allá de esa fecha —el caso
normal si corre en tiempo real durante meses— genera hechos **sin fila de calendario**. Como la
mayoría de los informes agregan por `mes` calculado en PostgreSQL y no por join a `dim_fecha`,
**la mayor parte del sistema seguirá funcionando**, y el hueco aparecerá solo en los tableros que
sí unen. Fallo parcial, silencioso y difícil de atribuir.

### R-7 · Un generador que hable SQL directo no activa RLS: la ve vacía

`pol_cliente_propio` depende del GUC `app.cliente_id`, que pone el aspecto
(`PgSessionRoleAspect.java:102-108`). Una conexión que asuma `grp_cliente` con
`set_config('role', …)` pero **sin** poner `app.cliente_id` verá **cero pedidos** —
`fn_cliente_actual()` no resuelve— y podrá **insertar** filas que violan el aislamiento si la
política `WITH CHECK` se evalúa distinta. Es el mismo modo de fallo que el diseño del ETL
documenta como §8.1 y por el que `retailmind_etl` lleva BYPASSRLS.

### R-8 · No hay forma de deshacer lo que el generador escriba (B5)

No existe columna de procedencia, y el único marcador (`observacion`) está incompleto y no cubre
`pedido`. **Una vez que el generador escriba, sus filas serán indistinguibles de las 4.083
sembradas y de las ~35 reales de desarrollo.** El esquema `seed_backup` permite volver atrás en
bloque, pero no separar "lo del generador" de "lo demás". Si el generador se pone en marcha sin
resolver esto primero, la decisión de dar marcha atrás deja de existir.

### R-9 · La concurrencia rompe el ORDEN de la cadena, no su saldo

**Premisa VERIFICADA en el motor vivo.** `movimiento_inventario.fecha_creacion` tiene
`DEFAULT now()`, y `now()` es el instante de INICIO de transacción, no el del INSERT:

```sql
SELECT now(), clock_timestamp(), pg_sleep(1), now(), clock_timestamp();
-- now():             13:47:52.947  →  13:47:52.947   (CONSTANTE)
-- clock_timestamp(): 13:47:52.952  →  13:47:53.953   (avanza 1 s)
```

Consecuencia (deducida, no observada): dos transacciones solapadas sobre el mismo par
`(variante, bodega)` pueden quedar con `fecha_creacion` e `id` en orden **inverso** — la que
empezó antes puede obtener el lock después—, y el encadenamiento `stock_anterior/stock_nuevo`,
que se lee por `(fecha_creacion, id)`, dejará de cuadrar **aunque cada fila sea correcta y el
saldo final cuadre**. Afecta a `fact_stock_mensual`, que reconstruye con
`argMax(stock_nuevo, (fecha, movimiento_id))`.

Hoy el sistema no tiene concurrencia real, así que la cadena está intacta (0 enlaces rotos en
13.288 movimientos, verificado). Un generador continuo es justamente lo que introduce esa
concurrencia. **No ejecuté la prueba de escrituras simultáneas: la sesión era de solo lectura.**

### R-10 · El guardia de "una corrida a la vez" es parcial frente a Airflow

`DwhActualizacionService` protege con `AtomicReference` (en memoria, ciego a otros procesos) y con
la bitácora, que **sí** vería una corrida de Airflow siempre que ésta use `run_etl.py`
(`DwhActualizacionService.java:144-166`). Pero **el `@Scheduled` de las 02:00 sigue armado** por
defecto (`application.properties:54`): si Airflow programa a la misma hora, ambos disparan y
compiten por el `EXCHANGE TABLES`. El interruptor existe —`DWH_CRON=-`— y hay que accionarlo
explícitamente; nada lo hace solo.

---

## Apéndice — Qué quedó NO VERIFICADO y por qué

Tras la segunda pasada contra el motor vivo, la lista se reduce a **un solo punto**:

| punto | qué faltó |
|---|---|
| **R-9** — que la concurrencia invierta de hecho el orden de la cadena | La PREMISA está verificada en el motor vivo (`now()` es constante dentro de la transacción y es el `DEFAULT` de `movimiento_inventario.fecha_creacion`). La CONSECUENCIA —dos transacciones solapadas escribiendo con `(fecha_creacion, id)` en orden inverso— es una deducción: **no ejecuté escrituras simultáneas, porque la sesión era de solo lectura**. Comprobarlo exige dos transacciones concurrentes contra el mismo par `(variante, bodega)`, es decir, escribir |

Cerrado en la segunda pasada, contra el contenedor del 5432 / ClickHouse:

- **A6**: duración total medida en la bitácora — **6,251 s** la corrida completa dentro de Docker
  (21 tareas · 66.079 filas · 49 controles en 1,295 s); 23,330 s la anterior, fuera de Docker.
- **Catálogo de B** (triggers, restricciones, columnas GENERATED, ausencia de CHECK de estado en
  `pedido`, políticas RLS, GRANTs): re-verificado en el motor vivo, **sin una sola divergencia**
  respecto a la copia congelada.
- **`grupo_horario`**: idéntico, y además cuantificado minuto a minuto (19,7 % de la semana).
- **Integridad del kardex**: 1.406/1.406 pares, 0 ecuaciones rotas en 13.288 filas, 0 enlaces
  rotos, 0 cadenas que arranquen distinto de cero — en la base VIVA.
- **Conteos del DWH**: leídos de `system.tables`; 21 tablas del modelo + `etl_ejecucion`.
- **Scripts 86 y 87**: presentes en el motor vivo (`fn_admin_cambiar_permiso`,
  `rol_personalizado`), y no intervienen en ninguno de los tres flujos auditados.
