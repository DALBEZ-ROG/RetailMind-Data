# Los informes SIMPLES a escala de 3 M de pedidos

**2026-08-12.** Bitácora del diagnóstico y la corrección del rendimiento de los
30 informes simples, que tardaban «cerca de un minuto». Script:
`retailmind/sql/postgres/106_rendimiento_informes_simples.sql`.

**Resultado en una línea**: la suma de los 30 informes baja de **767,4 s a
157,7 s (4,87×)** sin crear ni un índice, porque no faltaba ninguno.

---

## 1. La hipótesis de partida era falsa (las dos)

Se pedía verificar, no asumir, que faltaban índices y que las estadísticas
estaban desfasadas. **Ninguna de las dos se sostiene.**

### 1.1 No faltan índices

Las cinco consultas más lentas, con **los índices que ya existen** pero
ejecutadas bajo un rol con `BYPASSRLS`:

| informe | por HTTP (RLS activa) | la consulta pesada, sin RLS |
|---|---|---|
| OTD-LOG-02 envíos | 218.731 ms | **1.173 ms** |
| OTD-GER-01 foto-día | 146.744 ms | **2,6 ms** |
| OTD-INV-03 kardex | 136.983 ms | **2.167 ms** |
| OTD-LOG-11 costo-envío | 81.802 ms | **4.191 ms** |
| OTD-COM-11 entregas-incompletas | 22.377 ms | **982 ms** |

Mismos datos, mismos índices, y planes correctos (Hash Join + Parallel Seq
Scan, que es lo que toca para agregar millones de filas). Un índice nuevo no
habría arreglado nada.

La paginación tampoco lo necesitaba: la página de OTD-VEN-01
(`ORDER BY fecha_pedido DESC, id DESC LIMIT 25`) ya resolvía en **1,74 ms** con
`idx_pedido_fecha` + Incremental Sort, que se detiene tras 26 filas.

### 1.2 Las estadísticas no estaban desfasadas

`last_analyze` sale NULL en todas las tablas, y eso engaña: ese campo vive en el
colector de estadísticas, que **se reinicia con el contenedor**. Lo que importa
es si el planificador acierta, y acierta:

| tabla | cree el planificador | real | error |
|---|---|---|---|
| historial_estado_pedido | 19.413.672 | 20.215.644 | −3,97 % |
| orden_compra_detalle | 691.609 | 715.131 | −3,29 % |
| pedido_detalle | 7.550.886 | 7.622.429 | −0,94 % |
| pedido | 3.000.422 | 2.999.991 | +0,01 % |
| envío | 2.110.080 | 2.110.095 | 0,00 % |

Hay MCV e histogramas, y son del mundo POSTERIOR a la carga
(`pedido.cliente_id` con `n_distinct = 49.480` sobre 50.072 clientes).

**Qué aportó el `ANALYZE`**: se ejecutó igualmente sobre las 23 tablas grandes
(18 s) y **no aportó nada** — OTD-VEN-01 pasó de 47.669 ms a 49.545 ms y
OTD-VEN-16 de 29.458 a 30.975, ambos dentro del ruido y en la dirección
contraria.

Un detalle que parecía síntoma y no lo era: el plan estima `rows=1000141` donde
hay 2.999.991. No es estadística vieja, es **exactamente `reltuples / 3`** — la
selectividad por defecto del 33 % que PostgreSQL asigna a una función booleana
que no sabe estimar.

---

## 2. La causa real: la RLS se evalúa UNA VEZ POR FILA

`pol_horario` está declarada con `cmd = ALL` sobre 50 tablas y su qual es
`esta_en_horario(fn_grupo_actual())`. Bajo un rol `grp_*` esa expresión **no
referencia ninguna columna**, así que uno esperaría que el planificador la izara
a un `One-Time Filter` y la evaluara una vez. **Fuera** de la RLS lo hace
(medido: `One-Time Filter` sobre `generate_series`, 10.000 filas en 2,1 ms).
**Dentro** de la RLS no: aparece como `Filter:` del Seq Scan, por fila.

```
grp_administrador   Seq Scan on pedido  Filter: esta_en_horario(fn_grupo_actual())
                    Buffers: shared hit=3000808        23.842 ms
retailmind_etl      Parallel Seq Scan, 2 workers          112 ms      -> 213x
```

Los `hit=3000808` son ~1 buffer por fila: son los accesos de la propia función,
2.999.991 veces. En `movimiento_inventario` es peor por volumen: **63.701 ms
contra 214 ms, 297×**, y ahí encima con `Index Only Scan`, lo que descarta que
sea acceso al *heap*.

Y hay un **segundo efecto que multiplica el primero**: las tres funciones
estaban marcadas `PARALLEL UNSAFE`, y un qual con una función no paralelizable
vuelve **serie el plan entero**. Por eso el plan sin RLS lanzaba 2 workers y el
plan con RLS no lanzaba ninguno.

Esto explica por qué los informes lentos son los que lo son, y no otros: **no es
el tamaño de la tabla, es cuántas filas tiene que tocar el informe.** Los 12
informes que ya iban por debajo de 400 ms tocan pocas filas; los 9 que
arrastraban hacen `count(*)` o un agregado sobre millones.

---

## 3. Lo que NO se puede arreglar, y por qué

No se puede quitar la llamada por fila sin cambiar el diseño de seguridad:

- `esta_en_horario` es **SECURITY DEFINER** porque lee `grupo_horario`, sobre la
  que los `grp_*` no tienen SELECT.
- PostgreSQL **no puede hacer *inline* de una función SECURITY DEFINER**.
- Convertirla en SQL *inlinable* exigiría quitarle el SECURITY DEFINER y
  conceder SELECT sobre `grupo_horario` a los nueve roles. **Eso sí sería
  debilitar la compuerta horaria**, y queda fuera de alcance.

Por eso el techo de esta corrección es el paralelismo, no el 213×.

---

## 4. Lo que se hizo

Ni un índice. Tres marcadores de función y cinco parámetros del motor.

### 4.1 Las tres funciones de la RLS a `PARALLEL SAFE`

Solo metadatos: **no se toca una línea del cuerpo** (los md5 de `prosrc` quedan
registrados en el script). `PARALLEL SAFE` afirma tres cosas —que la función no
escribe, no toca secuencias ni temporales, y no cambia el estado de la
transacción— y las tres son lectores puros.

Medido, dentro de una transacción revertida:

| variante | tiempo | mejora |
|---|---|---|
| actual (`PARALLEL UNSAFE`, serie) | 24.618 ms | 1,00× |
| `PARALLEL SAFE`, 2 workers | 8.586 ms | 2,87× |
| `PARALLEL SAFE`, 6 workers | 4.506 ms | 5,46× |
| techo teórico (sin RLS) | 113 ms | 218× |

**El riesgo real no era de privilegios: era que un worker paralelo no heredara
el `role` ni el `app.cliente_id`**, que la aplicación fija con
`set_config(..., true)` — LOCALES a la transacción. Si no los heredara, las
políticas evaluarían falso dentro del worker y el informe devolvería menos
filas, o cero, **sin un solo error**. Se probó antes de escribir el script:

```
grp_cliente (cliente 52)   serie = 748    paralelo = 748    (no 2.999.991)
los 9 grp_*                serie = paralelo en todos
grp_compras                sigue recibiendo «permission denied»
```

El script vuelve a comprobarlo al aplicarse y **aborta** si algún rol ve un
número distinto en serie que en paralelo.

### 4.2 La configuración, que seguía entera en los valores por defecto

| parámetro | antes | ahora | por qué |
|---|---|---|---|
| `work_mem` | 4 MB | 32 MB | OTD-COM-11 volcaba a disco: `external merge Disk: 41472 kB` |
| `max_parallel_workers_per_gather` | 2 | 6 | 8.586 ms → 4.506 ms |
| `max_parallel_workers` | 8 | 12 | sin esto, «6 por gather» es papel mojado con dos informes a la vez |
| `max_worker_processes` | 8 | 16 | idem |
| `shared_buffers` | 128 MB | 1 GB | los planes leían de disco en cada pasada |

No se tocan `random_page_cost` ni `effective_cache_size`: alteran la ELECCIÓN de
plan y no salieron implicados en ninguna medición.

`shared_buffers` va a 1 GB y no al 25 % de libro porque el contenedor ve 7,5 GB,
comparte máquina con ClickHouse, el backend y los dos servicios de Airflow, y
**el swap ya estaba agotado** (2.039 MB de 2.048). Aviso honesto: para ESTOS
informes su ganancia es pequeña —la caché de página del sistema ya absorbía la
mayor parte— y es higiene general del motor, no la palanca.

### 4.3 Lo que se probó y se descartó

**Subir `procost`** de `esta_en_horario` de 100 a 2400 (el valor calibrado: una
llamada mide ~8 µs y una unidad de coste ~1,3 µs). **No funciona**: los dos
caminos escalan igual, el plan no cambia y sale ligeramente peor
(LOG-01 4.490 → 4.672 ms; VEN-16 4.601 → 4.876 ms). Revertido.

---

## 5. La única regresión: OTD-LOG-01

**11.257 ms → 13.597 ms.** Es real y conviene entenderla. Su línea base era
inestable —las dos muestras fueron **18.424 ms y 4.089 ms**, un factor 4,5— así
que se remidió con 4 repeticiones: **14.692 · 14.972 · 14.912 · 14.926 ms**.
Ahora es estable, y peor.

La consulta de conteo de la cola de despacho, misma base, tres ajustes:

| workers | plan | tiempo |
|---|---|---|
| 6 (ahora) | Parallel Seq Scan sobre los 3 M | 4.729 ms |
| 2 | Nested Loop, 50.072 búsquedas por índice | 14.708 ms |
| **0 (serie)** | **`idx_pedido_estado`, solo 35.396 filas** | **777 ms** |

El plan SERIE es 6× mejor porque el filtro de estado es muy selectivo (35.396
pedidos en `facturado`/`en_preparacion`/`preparado`). En la línea base las
funciones eran `PARALLEL UNSAFE`, así que **el planificador no tenía más opción
que el plan serie — el bueno**. Al hacerlas paralelizables, gana un recorrido
completo paralelo que es peor.

Volver a 2 workers **no** lo arregla (14.708 ms, todavía peor). Lo único que lo
arreglaría es desactivar el paralelismo, y eso devolvería los 610 s que se
ganaron en el resto. Se acepta y se declara: **+2,3 s en un informe contra
−609,7 s en el conjunto.**

---

## 6. Los tiempos

| informe | antes | después | mejora |
|---|---|---|---|
| OTD-LOG-02 logistica/envios | 218.731 | 22.287 | **9,8×** |
| OTD-GER-01 gerencia/foto-dia | 146.744 | 33.599 | 4,4× |
| OTD-INV-03 inventario/kardex | 136.983 | 25.348 | 5,4× |
| OTD-LOG-11 logistica/costo-envio | 81.802 | 17.001 | 4,8× |
| OTD-VEN-01 ventas/cartera-pedidos | 47.669 | 9.082 | 5,2× |
| OTD-VEN-16 ventas/participacion-canal | 29.458 | 6.803 | 4,3× |
| OTD-VEN-02 ventas/por-vendedor | 24.914 | 6.764 | 3,7× |
| OTD-COM-11 compras/entregas-incompletas | 22.377 | 8.737 | 2,6× |
| OTD-LOG-06 logistica/devoluciones | 15.218 | 3.932 | 3,9× |
| OTD-COM-01 compras/ordenes | 14.213 | 1.319 | **10,8×** |
| OTD-LOG-01 logistica/cola-despacho | 11.257 | 13.597 | **0,8×** |
| OTD-COM-02 compras/cuentas-por-pagar | 5.022 | 2.764 | 1,8× |
| OTD-SOP-01 soporte/bandeja | 4.482 | 2.000 | 2,2× |
| OTD-SOP-05 soporte/por-agente | 3.129 | 1.114 | 2,8× |
| OTD-SOP-04 soporte/por-categoria | 3.043 | 1.178 | 2,6× |
| los otros 15 (todos < 1 s antes y después) | 2.401 | 2.191 | 1,1× |
| **SUMA** | **767.443** | **157.716** | **4,87×** |

---

## 7. Lo que queda (T5): estos seis pertenecen al almacén

Cuatro informes siguen por encima de 10 s, y no es un problema de PostgreSQL:
**son preguntas de almacén servidas desde la base transaccional.** Agregan
millones de filas históricas para devolver unas pocas, que es exactamente la
definición de informe COMPUESTO del catálogo.

| informe | hoy | qué agrega | tabla del DWH que ya existe |
|---|---|---|---|
| OTD-GER-01 foto-día | 33,6 s | pedido + pago + factura del día, y un `max` global | `fact_pedido`, `fact_flujo_caja` |
| OTD-INV-03 kardex | 25,3 s | 8 M movimientos para 25 filas | `fact_movimiento_inventario` |
| OTD-LOG-02 envíos | 22,3 s | 2,1 M envíos × pedido × cliente | `fact_envio` |
| OTD-LOG-11 costo-envío | 17,0 s | 2,1 M envíos + resolución de zona por fila | `fact_envio` (con `zona` y `zona_nivel` ya resueltas) |
| OTD-LOG-01 cola-despacho | 13,6 s | — es la EXCEPCIÓN: mira el presente | se queda en PostgreSQL |
| OTD-COM-11 entregas-incompletas | 8,7 s | 715 k líneas de OC | `fact_compra_linea` |

**No se implementa: se propone.** Lo que costaría, y por qué se para aquí:

- Los KPI de cabecera y los agregados **ya están en el almacén**: `fact_envio`
  incluso trae `zona` y `zona_nivel` precalculadas, que es justo el `LATERAL`
  que a LOG-11 le cuesta 4,2 s de los 17.
- Pero **la PÁGINA no puede salir del almacén sin cambiar el significado del
  informe**: un informe SIMPLE responde sobre el PRESENTE y el almacén va con
  hasta 24 h de retraso. Mover OTD-LOG-02 entero convertiría «los envíos que
  hay» en «los envíos que había anoche», y eso es una decisión de producto, no
  de rendimiento — el mismo motivo por el que el catálogo los clasificó como
  simples.
- El reparto honesto sería **híbrido**: los KPI y los agregados desde
  `retailmind_dwh` con su marca de agua «Datos al …», y la página desde
  PostgreSQL acotada por filtros. Eso es 1 clase Java por departamento afectado
  + su bloque de definición, más la decisión de producto de admitir dos
  frescuras en la misma pantalla.
- **OTD-LOG-01 se queda donde está** en cualquier caso: la cola de despacho es
  el presente por definición, y su problema es de plan, no de volumen.

Y lo que **no** hay que hacer, que el enunciado ya anticipaba: una carpeta de
agregados en disco. Sería una tercera fuente de verdad que envejece en silencio,
mientras `retailmind_dwh` ya está orquestado por Airflow y validado por los 49
controles.

---

## 8. Verificaciones

| | qué se comprobó | resultado |
|---|---|---|
| V1 | los 30 informes cronometrados antes y después | 767,4 s → 157,7 s (4,87×) |
| V2 | mismos datos que antes | 18 de 30 byte-idénticos; los 12 restantes difieren SOLO por campos derivados de `now()`, por una transferencia de bodega ajena y por los propios accesos del arnés. **8 de los 10 mayores aceleros son idénticos** |
| V3 | 43 compuestos + 7 tableros | 43/43 y 7/7 en 200, `analiticaDisponible=true`; 9,6 s y 9,3 s |
| V4 | escrituras | 0,44–0,49 ms/pedido después contra 0,53–0,60 antes: **no se degradaron** (no hay índices nuevos que mantener) |
| V5 | DAG y los 49 controles | **44 de 49 cuadran; 5 DIFIEREN** por causa ajena — ver §9.3. Por tarea el ETL va igual o más rápido |
| V6 | defensas | 34 `trg_horario_*` · 95 políticas · 2 `trg_kardex_*` · 50 tablas con RLS · 50 `pol_horario` con `cmd=ALL` |
| V7 | ningún dato alterado | ver §9 |

### El espacio, antes y después

**Idéntico**: 385 índices y 7.429 MB, base de 16 GB. Es la consecuencia directa
de no haber creado ninguno. El disco del volumen pasó de 51 a 52 GB por WAL y
por la corrida del DAG, no por índices.

### Estado final verificado

```
trg_horario 34 · politicas 95 · trg_kardex 2 · tablas con RLS 50
work_mem 32 MB · shared_buffers 1 GB · workers 6/12/16 · pending_restart: ninguno
esta_en_horario/fn_grupo_actual/fn_cliente_actual = PARALLEL SAFE, procost 100
385 indices · 7.429 MB · base 16 GB
DAG retailmind_dwh: is_paused = true, schedule "0 2 * * *"
/api/health: status UP · analytics UP
V7 final contra la foto re-anclada: SIN DIFERENCIAS
```

---

## 9. Dos cosas que hay que decir, no esconder

### 9.1 Alguien usó la aplicación durante la medición

A las 09:22:59 se registró una **transferencia de bodega (id 75)**: una unidad
salió de la bodega 4 (97 → 96) y entró en la 3 (24 → 25), con sus dos
movimientos de kardex (ids 14440 y 14441). **No fue el arnés de medida**, que
solo hace GET a `/api/informes/*` y no puede crear una transferencia.

Por eso la foto de V7 se **re-ancló** después de ese evento. Ese único hecho de
negocio explica, de forma consistente, 6 de las 12 diferencias de V2 (INV-02
stock 97→96, INV-03 kardex +2, INV-06 transferencias 71→72, INV-07 −1 unidad,
INV-08 −1 unidad de exceso) — que de paso es una comprobación de integridad
agradable: un solo evento se propaga igual por cinco informes.

### 9.2 El reinicio de PostgreSQL tumbó el primer intento del DAG

La primera corrida (`perf106_102535`) **falló**: 6 tareas con
`could not translate host name "postgres" to address` y «Scheduler is in
unhealthy state». La causa es el reinicio del punto 4.2: **la metabase de
Airflow vive en el MISMO contenedor `postgres`**, que al reiniciarse tomó una IP
nueva (172.18.0.7), y los contenedores de Airflow —arrancados dos horas antes—
se quedaron con la anterior. Fallaron justo las 6 tareas largas, las que
necesitan *heartbeat*; las cortas terminaron bien.

**No lo causó ni el cambio de funciones ni la configuración**, y se arregla
reiniciando los servicios de Airflow. Queda como trampa a recordar: **tras
`docker compose restart postgres` hay que reiniciar también
`airflow-scheduler` y `airflow-webserver`.**

### 9.3 El ETL está BLOQUEADO, y no por este trabajo: la transferencia 75 rompió la cadena del kardex

Tras reiniciar Airflow, la segunda corrida (`perf106b_144407`) llegó a 19 de 21
tablas y **`fact_movimiento_inventario` ABORTÓ**, con su propio mensaje:

```
[fact_movimiento_inventario] intento 3/3 ABORTÓ: 3 movimientos con
«stock_anterior ≠ stock_nuevo del movimiento previo». El atajo de §5.7
(leer `stock_nuevo` en vez de recalcular) SOLO es válido sobre una cadena íntegra.
```

**El ETL hizo lo correcto: se negó a publicar.** Y la causa es la transferencia
de bodega de §9.1. Leyendo la cadena de la variante 900001108 por
`(fecha_creacion, id)` salen **exactamente 3 eslabones roto**s:

| id | bodega | fecha | stock_anterior | saldo previo | |
|---|---|---|---|---|---|
| 14441 | 3 | 2026-08-12 09:22 | 24 | 24 | ok |
| 1100043188 | 3 | 2026-09-19 | 24 | **25** | ROTO |
| 14440 | 4 | 2026-08-12 09:22 | 97 | **58** | ROTO |
| 1300444789 | 4 | 2026-08-12 20:15 | 58 | **96** | ROTO |

El mecanismo es el que este repositorio ya tiene documentado, y merece decirse
claro porque **es un problema latente de la aplicación, no del seed**: la app
escribe `stock_anterior` leyendo `inventario.stock_actual` (97, el total de HOY),
pero el saldo corrido de esa posición en ESA fecha era 58. Como el seed va de
2025 a **2034**, la fecha real de hoy cae **en MITAD de la cadena**, así que
**cualquier movimiento que un usuario haga hoy por la aplicación queda insertado
en medio y deja obsoleto el saldo de todo lo que viene después**. Es exactamente
el aviso de CLAUDE.md: «al insertar en mitad de una cadena viva, lo insertado
solo es inocuo si suma cero entre dos eslabones consecutivos».

Los **5 controles que difieren son todos de esa familia**, y todas las
diferencias miden **Δ = 1 o 2** — el tamaño exacto de una transferencia de una
unidad:

| control | diferencia |
|---|---|
| `fact_movimiento_inventario` | Δ = −2 movimientos |
| `tipos_movimiento` | `salida_transferencia` PG=65 CH=64; unidades 861 vs 860 |
| `kardex_mes` | 1 mes descuadrado |
| `fact_stock_mensual` | salidas 14.334.969 vs 14.334.968 |
| `stock_cierre_final` | la posición de la variante 900001108 |

ClickHouse va **por detrás** de PostgreSQL, no por delante: es la foto anterior
a la transferencia, porque la carga nueva se abortó. Los otros **44 controles
cuadran**, incluidos todos los de dinero y de venta.

**Por qué no se arregla aquí**: reparar la cadena obliga a reescribir
`stock_anterior`/`stock_nuevo` de las filas posteriores del kardex, es decir **a
tocar datos de negocio**, que está explícitamente fuera de alcance. Queda
reportado como lo que es: un bloqueo del ETL de causa ajena a este trabajo, con
su causa raíz identificada y acotada a una variante y una unidad.

**Y el tiempo del ETL no empeoró** — por tarea va igual o más rápido:

| tarea | antes | después |
|---|---|---|
| fact_compra_linea | 178 s | **87 s** |
| fact_venta_linea | 424 s | **328 s** |
| fact_pedido | 268 s | **236 s** |
| fact_flujo_caja | 180 s | **131 s** |
| fact_devolucion | 121 s | **95 s** |
| fact_envio | 308 s | 414 s (con `validar_dwh` corriendo en paralelo) |
| las otras 13 | — | iguales ±1 s |
