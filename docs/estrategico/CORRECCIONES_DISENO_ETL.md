# Correcciones al diseño del ETL — bitácora de supuestos que no se sostuvieron

> Documento vivo. Acompaña a `DISENO_ETL_CLICKHOUSE.md` y lo **corrige**; no lo sustituye.
> Última actualización: **2026-07-31** (Fase 5 — conexión de los informes de Compras. **Catálogo
> táctico completo**).

---

## 0. Por qué existe este archivo

`DISENO_ETL_CLICKHOUSE.md` se escribió **en papel, antes de ejecutar una sola carga**. Eso fue lo
correcto —el diseño es lo que hizo posible agrupar 39 objetivos en 19 tablas— pero tiene una
consecuencia que ya se pagó tres veces: **varias de sus afirmaciones sobre los datos son hipótesis,
no hechos**, y algunas resultaron falsas al contrastarlas contra PostgreSQL.

Hasta ahora esas correcciones vivían solo en el docstring del módulo que las descubrió. Funciona
para quien lee ese archivo, y no funciona para quien abre el diseño en la fase siguiente y lo lee
como si fuera verdad. Esta bitácora cierra ese hueco.

**Regla de trabajo, a partir de la Fase 3**: cada supuesto del diseño que falle se registra aquí
**antes de dar la tarea por terminada**, con la cifra que lo prueba.

### El patrón que se repite

De los **33 supuestos fallidos** registrados, la mayoría comparte una forma:

> El diseño afirma que una relación es **1:1** o que una columna **siempre está poblada**, y los
> datos reales tienen un puñado de excepciones —dos, seis, ciento setenta y seis— que no rompen
> nada visiblemente. **El JOIN no falla: devuelve una fila de más, o una fila de menos, o una fila
> con NULL.** El informe se pinta, las cifras parecen plausibles, y nadie se entera.

De ahí el criterio de aceptación del proyecto: **igualdad exacta al centavo contra PostgreSQL**. No
es perfeccionismo. Es el único mecanismo que convierte un `+2` silencioso en un fallo ruidoso.

### Cómo leer cada entrada

| Campo | Contenido |
|---|---|
| **Fase** | dónde se detectó |
| **El diseño decía** | la afirmación literal y su sección |
| **La realidad** | la cifra que lo desmiente, medida contra la base |
| **Cómo se resolvió** | lo que hace el código hoy |
| **Qué habría roto** | el informe concreto que habría salido mal, y de qué manera |

La última fila es la importante. Un supuesto que falla y no rompe nada es una curiosidad; los que
están aquí rompían algo.

---

## Índice

| # | Fase | Supuesto que falló | Gravedad |
|---|---|---|---|
| [C1.1](#c11-factura_venta-no-es-11-con-el-pedido) | 1 | `factura_venta` es 1:1 con el pedido | 🔴 fila de más, silenciosa |
| [C1.2](#c12-las-6-excepciones-de-descuento-no-son-los-pedidos-legacy) | 1 | Las excepciones de descuento son los pedidos legacy 20/21/24662 | 🟠 descuento mal atribuido |
| [C2.1](#c21-el-cobro-fallido-no-tiene-fecha-de-pago-ni-pedido) | 2 | Todo cobro tiene `fecha_pago` y `pedido_id` | 🔴 **informe vacío sin error** |
| [C2.2](#c22-el-cliente-de-un-cobro-fallido-casi-nunca-es-recuperable) | 2 | El cliente del cobro se obtiene del pedido | 🟡 columna hueca |
| [C2.3](#c23-movimiento_id-no-es-único-entre-los-dos-orígenes) | 2 | `movimiento_id` identifica la fila | 🟠 conteo distinto por lo bajo |
| [C2.4](#c24-monto_cupon-y-monto_descuento-no-son-la-misma-cifra) | 2 | El cupón del pedido es una sola cifra | 🟡 $137,64 y 2 pedidos |
| [C2.5](#c25-los-tramos-de-tiempo-no-pueden-ir-en-float32) | 2 | Los tramos van en `Float32` (§5.1) | 🟠 medida no validable |
| [C2.6](#c26-hay-un-sexto-hito-real-en-el-ciclo-del-pedido) | 2 | El ciclo tiene cinco hitos | 🟡 pregunta sin respuesta |
| [C2.7](#c27-las-cuatro-etapas-de-log-12-no-se-miden-sobre-la-misma-población) | 2 | Las etapas comparten denominador | 🔴 cuello de botella falso |
| [C3.1](#c31-la-cadena-oc--recepción--factura--cxp-no-es-11-completa) | 3A | OC→recepción→factura→CxP son 1:1 (§3.1, §5.4) | 🔴 **dos conteos iguales, conjuntos distintos** |
| [C3.2](#c32-el-rechazo-en-puerta-no-siempre-se-descuenta-de-lo-recibido) | 3A | `pct_rechazo` sin denominador declarado (§5.5) | 🟠 % inflado en 37 líneas |
| [C3.3](#c33-motivo_rechazo-trae-6-valores-donde-el-negocio-tiene-5) | 3A | `motivo_rechazo` es un catálogo limpio | 🟠 categoría fantasma en COM-07 |
| [C3.4](#c34-la-fecha-de-recepción-necesita-conversión-de-zona-83) | 3A | (§8.6 lo advertía; el diseño de §5.4 no lo aplica) | 🟠 5 ciclos con un día de más |
| [C3.5](#c35-dos-proveedores-no-tienen-ciudad) | 3A | `dim_proveedor.ciudad` siempre poblada (§4.4) | 🟡 NULL en un `GROUP BY` |
| [C3.6](#c36-la-factura-de-compra-no-vale-lo-que-la-orden) | 3A | (implícito en §5.4: OC y factura intercambiables) | 🟠 $226.070,31 de gasto inventado |
| [C3.7](#c37-el-detalle-de-recepción-cubre-2855-de-2949-líneas-y-el-resto-no-es-cero) | 3A | Toda línea tiene su recepción (§5.5) | 🟡 94 líneas sin llegada |
| [C3B.1](#c3b1-naturaleza--ajuste-no-son-los-ajustes-la-apertura-del-almacén-va-dentro) | 3B | `naturaleza='ajuste'` identifica los ajustes (§5.6) | 🔴 **merma inflada 380×** |
| [C3B.2](#c3b2-ajuste_motivo-es-texto-libre-con-un-prefijo-de-máquina-dentro) | 3B | `motivo` es un catálogo de 7 motivos tipificados | 🔴 53 «motivos» de una fila |
| [C3B.3](#c3b3-177-movimientos-no-tienen-costo-unitario-y-son-justo-los-que-inv-10-necesitaría) | 3B | Todo movimiento lleva `costo_unitario` (§5.6) | 🟠 la merma no se puede valorar con él |
| [C3B.4](#c3b4-21122-filas-no-26700-el-cartesiano-completo-inventa-5592-ceros) | 3B | Cartesiano 19 meses × 1.406 ≈ 26.700 filas (§5.7) | 🟠 5.592 ceros fabricados |
| [C3C.1](#c3c1-la-zona-horaria-decide-el-día-y-con-él-los-tres-plazos-del-envío) | 3C | Los plazos se restan sin convertir la zona (§5.8) | 🔴 **1 de cada 5 envíos cambia de tránsito** |
| [C3C.2](#c3c2-24-envíos-no-tienen-tarifa-y-no-son-envíos-gratis) | 3C | `costo` y `peso_total_kg` siempre poblados (§5.8) | 🟠 un mes 22 % más barato |
| [C3C.3](#c3c3-los-valores-de-accion-son-los-verbos-del-api-no-lo-que-guarda-la-base) | 3C | `accion` ∈ {`reprogramar`, `devolver_almacen`} (§5.9) | 🔴 **filtro que casa con 0 filas** |
| [C4.1](#c41-el-reembolso-tiene-dos-orígenes-y-no-dicen-lo-mismo) | 4 | El reembolso es un solo hecho (§5.10) | 🟠 una devolución y $169,70 de distancia |
| [C4.2](#c42-el-ciclo-completo-del-rma-solo-es-medible-en-35-de-196) | 4 | `dias_ciclo_total` = cierre − solicitud (§5.10) | 🔴 **el 82 % del informe, invisible** |
| [C4.3](#c43-un-ticket-no-tiene-categoría-y-el-join-interno-del-diseño-lo-tira) | 4 | `ticket_soporte ⋈ categoria_ticket` (§5.12) | 🟠 1 ticket de 248, en silencio |
| [C4.4](#c44-unir-fact_resena-a-dim_producto-por-producto_id-multiplica-filas) | 4 | Une a `dim_producto` por `producto_id` (§5.13) | 🔴 **fan-out sin error ni suma rota** |
| [C4.5](#c45-resuelto-y-cerrado-no-son-el-mismo-hecho) | 4 | 76 cierres (§5.12), sin decir que hay 120 «resueltos» | 🟠 base del informe sin declarar |
| [C4.6](#c46-la-primera-respuesta-cuesta-51-tickets-y-135-h-según-cómo-se-defina) | 4 | La definición del catálogo, sin medir la alternativa | 🟠 244 vs 193, y un tiempo menor |
| [C4.7](#c47-los-valores-de-origen-tampoco-son-los-del-diseño) | 4 | `origen` ∈ {`inspeccion_rma`, `recepcion_compra`} (§5.14) | 🔴 **filtro que casa con 0 filas** |
| [C4.8](#c48-la-detección-del-ítem-defectuoso-no-precede-a-su-devolución) | 4 | `dias_hasta_resolucion` desde la detección (§5.14) | 🔴 **18 de 28 tramos NEGATIVOS** |
| [C5.1](#c51-el-mes-del-gasto-de-compras-no-es-el-mes-de-la-orden) | 5 | `mes` sirve para agrupar COM-04 (§5.4) | 🔴 **$4,6 M cambian de mes, el total no** |
| [C5.2](#c52-las-259-líneas-incompletas-son-tres-cosas-distintas) | 5 | 259 líneas con recepción menor a la pedida (catálogo §4) | 🔴 **el proveedor carga con lo que canceló Compras** |

---

# Fase 1 — el piloto

## C1.1 · `factura_venta` no es 1:1 con el pedido

| | |
|---|---|
| **Fase** | 1 (`fact_venta_linea`), **reincidente** en 2 (`fact_pedido`) |
| **El diseño decía** | §5.2: el origen es `pedido_detalle ⋈ … ⟕ factura_venta_detalle`, filtrando la factura por `estado <> 'anulada'`. Da por hecho que ese filtro deja **una** factura por pedido. |

**La realidad.** El filtro deja **3.886 facturas no anuladas sobre 3.885 pedidos**:

```
facturas de venta totales ......... 3.887
  no anuladas ..................... 3.886
pedidos distintos con factura ..... 3.885     ← 3.886 ≠ 3.885
```

El **pedido 2** tiene **DOS facturas en estado 'emitida'** —`FV-20260704-02744` y
`FV-20260705-88152`, idénticas en importes ($402,16)—: un duplicado legacy del arranque del
sistema. Sus líneas aparecen por duplicado en `factura_venta_detalle`.

**Cómo se resolvió.** Se define una **factura canónica** por pedido: la no anulada más reciente por
`(fecha_emision, id)`.

```sql
SELECT DISTINCT ON (fv.pedido_id) fv.id, fv.pedido_id
FROM factura_venta fv
WHERE fv.estado <> 'anulada'
ORDER BY fv.pedido_id, fv.fecha_emision DESC, fv.id DESC
```

Cubre los dos casos reales del sistema: el duplicado idéntico (pedido 2) y la reemisión tras
anulación (pedidos 22 y 24662). La regla está **escrita dos veces a propósito** —en la tarea y en
`validar_dwh.py`— para que la validación pueda contradecir al ETL en vez de compartir su error.

**Qué habría roto.** `fact_venta_linea` habría salido con **10.386 filas donde hay 10.384**, y
`fact_pedido` con **4.084 donde hay 4.083**. Un `+2` sobre 10.384 es un 0,02 % de error: ningún
informe se ve raro, ninguna suma escandaliza, y el margen del pedido 2 se cuenta dos veces para
siempre. Es exactamente el error que el criterio «igualdad al centavo» existe para atrapar.

---

## C1.2 · Las 6 excepciones de descuento no son los pedidos legacy

| | |
|---|---|
| **Fase** | 1 (`fact_venta_linea`) |
| **El diseño decía** | §5.2: los pedidos que no permitirán despejar el cupón por línea serán «los legacy **20 y 21** y el **24662**», los tres ya documentados en `CLAUDE.md`. |

**La realidad.** No son esos. Los seis pedidos con descuento y **sin ninguna factura vigente de la
que prorratear** son:

```
40    PED-20260715-79957   telefono  pagado   promoción $  3,49
4031  PED-20260709-111677  web       pagado   cupón     $147,73
4078  PED-20260705-111786  web       pagado   cupón     $ 11,28
4106  PED-20260720-111841  web       pagado   cupón     $  5,00
4161  PED-20260715-111969  web       pagado   cupón     $ 64,03
4176  PED-20260720-112000  web       pagado   cupón     $ 29,22
```

Los seis quedaron en estado **`pagado` sin llegar a `facturado`**. No hay `factura_venta_detalle`
donde leer el reparto porque no hay factura: es un pedido a medio camino del ciclo, no un dato
corrupto. Los pedidos 20 y 21 que el diseño anticipaba **sí tienen factura** y no son excepción
aquí (aparecen en [C2.4](#c24-monto_cupon-y-monto_descuento-no-son-la-misma-cifra), por otro
motivo); el 24662 tiene su factura anulada y la regla canónica de C1.1 ya lo cubre.

**Cómo se resolvió.** `descuento_cupon_prorrateado = 0`, la fila se **marca** con
`excepcion_descuento = 1` —para que la excepción quede consultable en el propio almacén y no solo
contada— y el número se registra en `etl_ejecucion.excepciones`.

**Qué habría roto.** OTD-GER-11 («descuento por mes y por producto»). Con la lista equivocada, el
ETL habría buscado la excepción donde no estaba y habría tratado los seis pedidos reales como si su
factura existiera: `NULL − monto_descuento` en el despeje del cupón, y **$260,75 de descuento
atribuidos a productos al azar o perdidos**, según cómo se comporte el NULL.

---

# Fase 2 — el núcleo de la venta y el dinero

## C2.1 · El cobro fallido no tiene fecha de pago ni pedido

> **Éste es el error que habría dejado un informe VACÍO sin dar un solo error.** Es el motivo de
> que la advertencia esté hoy al principio del encargo de cada fase.

| | |
|---|---|
| **Fase** | 2 (`fact_flujo_caja`) |
| **El diseño decía** | §5.3: `fecha ← pago.fecha_pago` y el documento del movimiento es `pedido`, para **todo** el lado del ingreso. |

**La realidad.** 176 de los 4.079 cobros no tienen ninguna de las dos cosas:

```
pago (total) ......................... 4.079
  con pedido_id ...................... 3.903     ← faltan 176
  con fecha_pago ..................... 3.903     ← faltan los mismos 176
cobros en estado 'fallido' ........... 176
  de ellos, sin pedido_id ............ 176  (100 %)
```

Y es **coherente con cómo nace el dato** (script 52): el intento de pago se registra **antes** de
que exista el pedido —el cliente no llegó a comprar— y `fecha_pago` es la fecha de **liquidación**,
que en un cobro rechazado no existe nunca.

**Cómo se resolvió.** `COALESCE(fecha_pago, fecha_creacion)`, más una columna explícita
`fecha_es_intento UInt8`: `1` = el instante es el del **intento rechazado**, `0` = es la
liquidación efectiva. No son la misma clase de fecha y mezclarlas sin decirlo fabricaría una serie
de cobros que incluye dinero que nunca entró.

**Qué habría roto.** **OTD-VEN-12 es un informe por período sobre esos mismos 176 intentos
fallidos.** Con `fecha_pago` a secas, los 176 llegan con fecha nula, quedan fuera de toda partición
por mes, y el informe sale **vacío**. Sin excepción, sin fila roja, sin traza en el log: una
pantalla en blanco que se explica como «no hubo cobros fallidos en el período». La carga habría
cuadrado en conteo (4.981 filas) y el error solo se ve si alguien abre justo ese informe y se
extraña.

---

## C2.2 · El cliente de un cobro fallido casi nunca es recuperable

| | |
|---|---|
| **Fase** | 2 (`fact_flujo_caja`) |
| **El diseño decía** | §5.3: `contraparte_id ← pedido.cliente_id` para el lado del ingreso. |

**La realidad.** Sin `pedido_id` (C2.1) no hay `cliente_id`. Solo **2 de los 176** traen el cliente
dentro de `transaccion_pago.respuesta_pasarela`:

```
cobros fallidos ...................................... 176
  con 'cliente_id' en respuesta_pasarela ............. 2     ← del checkout real
  sin ninguna traza del titular ...................... 174   ← del seed: {estado, motivo}
```

**Cómo se resolvió.** Se recupera lo recuperable —esos 2, leyendo el jsonb— y los 174 restantes van
a `contraparte_id = 0` con nombre `'(no identificado)'`. **Inventar un cliente para llenar la
columna sería peor que la ausencia**: convierte un hueco conocido en un dato falso.

**Qué habría roto.** Nada visible, y eso es justamente el riesgo. Un `GROUP BY cliente` sobre
cobros fallidos habría mostrado 174 filas con cliente `NULL` agrupadas como si fueran un cliente
más — o, con un `JOIN` en vez de `LEFT JOIN`, habría perdido los 174 sin avisar.

---

## C2.3 · `movimiento_id` no es único entre los dos orígenes

| | |
|---|---|
| **Fase** | 2 (`fact_flujo_caja`) |
| **El diseño decía** | §5.3: la tabla une cobros y pagos en `UNION ALL` con `movimiento_id` como identificador de la fila. |

**La realidad.** `pago.id` y `pago_proveedor.id` son **secuencias independientes que se solapan**
(ambas arrancan en 1):

```
pagos de cliente cuyo id también existe en pago_proveedor .... 796
```

**Cómo se resolvió.** La clave real de la tabla es el **par `(sentido, movimiento_id)`**, y así se
valida (`countDistinct((sentido, movimiento_id)) = count()`). `MergeTree` no exige unicidad en su
`ORDER BY`, de modo que el modelo no se rompe — pero la afirmación del diseño sí era falsa y había
que dejarlo dicho.

**Qué habría roto.** Cualquier informe que escribiera `countDistinct(movimiento_id)` para contar
movimientos daría **4.185 donde hay 4.981**: 796 movimientos menos, un 16 % de subconteo que parece
un número perfectamente razonable.

---

## C2.4 · `monto_cupon` y `monto_descuento` no son la misma cifra

| | |
|---|---|
| **Fase** | 2 (`fact_pedido`) |
| **El diseño decía** | §5.1: el cupón del pedido entra por `⟕ uso_cupon ⟕ cupon`, como una sola magnitud. |

**La realidad.** Hay dos, y difieren:

```
uso_cupon ................................ 564 filas / 564 pedidos  (1:1)
Σ uso_cupon.monto_descontado ............. $50.727,89
pedidos con monto_descuento > 0 .......... 562
Σ pedido.monto_descuento ................. $50.590,25
                                    diferencia $   137,64  (2 pedidos)
```

La diferencia son los **pedidos 20 y 21**, legacy: tienen su fila en `uso_cupon` pero el script 72
no les tocó la cabecera porque **no tienen fila en `pago`** (excepción ya declarada en `CLAUDE.md`).

**Cómo se resolvió.** Las dos columnas viajan **por separado y no se reconcilian**: `monto_cupon` es
lo que el cupón dice haber descontado, `monto_descuento` es lo que el pedido efectivamente
descontó. OTD-GER-05 («qué cupones se usaron y cuánto costaron») se sirve de `monto_cupon`, que es
la pregunta que hace; el dinero del pedido sigue siendo `total`.

**Qué habría roto.** Cargar solo una de las dos —o forzarlas a coincidir— habría tomado partido en
silencio sobre dos pedidos reales, y GER-05 habría reportado un coste de campaña $137,64 por debajo
del canje registrado, sin forma de saber por qué.

---

## C2.5 · Los tramos de tiempo no pueden ir en `Float32`

| | |
|---|---|
| **Fase** | 2 (`fact_pedido`) |
| **El diseño decía** | §5.1: `Nullable(Float32)` para las cuatro columnas de horas del ciclo. |

**La realidad.** El criterio de aceptación del proyecto es la **igualdad exacta** contra
PostgreSQL, y el comparador de `validar_dwh.py` **rechaza todo float por construcción** («pasar por
float es exactamente el error que este script existe para detectar»). Con `Float32`, los tramos
serían la única medida de la tabla **imposible de validar**.

**Cómo se resolvió.** `Nullable(Decimal(12,2))`. Desviación deliberada del diseño, no descuido.

**Qué habría roto.** Nada en los datos; el fallo era **del método**. LOG-12 se habría publicado sin
que nadie pudiera demostrar que sus cuatro promedios coinciden con PostgreSQL — que es el único
motivo por el que este pipeline se cree a sí mismo.

---

## C2.6 · Hay un sexto hito real en el ciclo del pedido

| | |
|---|---|
| **Fase** | 2 (`fact_pedido`) |
| **El diseño decía** | §5.1: los hitos a pivotar son cinco — confirmado, pagado, facturado, despachado, entregado. |

**La realidad.** El ciclo tiene **siete** estados con registro histórico, y dos de ellos son del
tramo de bodega que el diseño colapsó en uno:

```
confirmado ....... 4.083 pedidos
pagado ........... 3.906
facturado ........ 3.872
en_preparacion ... 2.883     ← el hito que el diseño no contemplaba
preparado ........ 2.868
despachado ....... 2.868
entregado ........ 3.696
```

`en_preparacion` (entrada a la cola de bodega) y `preparado` (picking terminado) **no son el mismo
instante**, y el diseño solo tenía sitio para uno.

**Cómo se resolvió.** `fecha_en_preparacion` viaja como columna propia. Los tres tramos de LOG-12
se miden sobre **`preparado`** —de modo que cada tramo empieza donde acaba el anterior y los tres
suman el ciclo—, y quien quiera separar «espera en la cola» de «tiempo de picking» ya tiene el dato
sin volver a PostgreSQL.

**Qué habría roto.** LOG-12 habría medido «pago → preparación» mezclando dos hechos distintos, sin
posibilidad de descomponer un cuello de botella de bodega en sus dos mitades: la cola y el trabajo.

---

## C2.7 · Las cuatro etapas de LOG-12 no se miden sobre la misma población

| | |
|---|---|
| **Fase** | 2 (`fact_pedido`) |
| **El diseño decía** | §5.1: cuatro tramos de horas sobre la misma tabla, presentados juntos — implícitamente, sobre los mismos pedidos. |

**La realidad.** Cada tramo tiene su propio denominador, y uno de ellos rompe la secuencia:

```
pago → preparado ............. 2.868 pedidos
preparado → despachado ....... 2.856
despachado → entregado ....... 2.727
ciclo total (pago → entrega) . 3.696     ← más que cualquiera de sus partes
```

**828 pedidos llegaron a `entregado` sin que el seed les registrara el paso por `despachado`.** Por
eso el ciclo total mide sobre 3.696 y el tramo de última milla sobre 2.727.

**Cómo se resolvió.** Cada fila del informe declara **su propio `pedidos_medidos`**. Se verificó
además que ningún hito va hacia atrás (0 pedidos con preparado antes del pago, despacho antes de
preparado o entrega antes del despacho), así que ningún tramo sale negativo.

**Qué habría roto.** Ésta es la más traicionera de la Fase 2 después de C2.1. Cuatro promedios
puestos en fila, calculados sobre denominadores distintos y presentados sin decirlo, hacen que **un
cuello de botella parezca estar donde no está**: basta con que la etapa con menos pedidos medidos
tenga una mezcla distinta para que suba o baje sin que nada haya cambiado en la operación.

---

# Fase 3A — el ciclo de compras

## C3.1 · La cadena OC → recepción → factura → CxP no es 1:1 completa

> El supuesto que el encargo de esta fase mandaba verificar explícitamente. **Se verificó, y falla
> — pero no por donde se esperaba.**

| | |
|---|---|
| **Fase** | 3A (`fact_orden_compra`) |
| **El diseño decía** | §5.4, literal: «unificar en una fila el ciclo completo: orden → recepción → factura → cuenta por pagar. **Las cuatro son 1:1 (probado, §3.1)**». |

**La realidad.** La *multiplicidad* sí se sostiene: ninguna OC tiene dos recepciones ni dos
facturas, ninguna factura tiene dos CxP. Lo que **no** se sostiene es la *cobertura*: los conteos
son idénticos y **los conjuntos no lo son**.

```
órdenes de compra .............................. 865
OC con recepción ............................... 839
OC con factura ................................. 839      ← mismo número...
OC con recepción Y factura ..................... 838      ← ...distinto conjunto

máx. recepciones por OC ........................ 1
OC con más de una factura ...................... 0
facturas con más de una CxP .................... 0
facturas sin orden de compra ................... 0
```

Las dos filas que sobran son ambas del desarrollo de 2026-07-18 y rompen la cadena **en direcciones
opuestas**:

| Documento | OC | Estado OC | Qué le falta | Importe |
|---|---|---|---|---|
| `FC-20260710-25356` | 8 · `OC-20260710-74970` | `confirmada` | **factura sin recepción** — salta la compuerta «sin recibir completo no se factura» | $34,50 |
| `RM-20260718-100010` | 20 · `OC-20260718-100009` | `recibida` | **recepción sin factura** — el ciclo se dejó a medias | $54,63 |

**Cómo se resolvió.** La extracción parte **siempre de `orden_compra`** y llega a recepción,
factura y CxP por `LEFT JOIN` encadenado: 865 filas pase lo que pase, con NULL donde el documento
no existe. Y la validación **compara los conjuntos, no los conteos**: se valida por separado
`con_recepcion`, `con_factura` y `con_ambos`, precisamente porque los dos primeros coinciden en 839
mientras describen OCs distintas.

**Qué habría roto.** Dos cosas, según cómo se hubiera escrito el JOIN confiando en el 1:1:

1. Con `orden_compra ⋈ recepcion ⋈ factura` (INNER, que es lo natural si las cuatro son 1:1), la
   tabla sale con **838 filas facturadas** y **desaparecen $34,50** del gasto de compras. OTD-COM-04
   y la balanza GER-02 quedan descuadradas contra `factura_compra` — y el descuadre es de treinta y
   cuatro dólares sobre veintidós millones: **imposible de ver, imposible de explicar** cuando
   alguien lo note seis meses después.
2. Un control de aceptación escrito como «839 recepciones = 839 facturas ⇒ la cadena está completa»
   **pasa en verde** con los conjuntos mal. Es el mismo mecanismo de C1.1: el conteo cuadra por
   casualidad.

---

## C3.2 · El rechazo en puerta no siempre se descuenta de lo recibido

| | |
|---|---|
| **Fase** | 3A (`fact_compra_linea`) |
| **El diseño decía** | §5.5 lista `cantidad_pedida`, `cantidad_recibida`, `cantidad_rechazada` y `pct_rechazo Decimal(6,2)` — **sin declarar sobre qué se calcula el porcentaje**. La lectura natural, y la única que las tres columnas sugieren, es «rechazadas ÷ pedidas». |

**La realidad.** `cantidad_recibida` y `cantidad_rechazada` **no guardan una relación constante con
lo pedido**. Sobre las 92 líneas con rechazo:

```
recibida + rechazada = pedida .... 49 líneas   el rechazo se DESCONTÓ de lo aceptado
recibida + rechazada > pedida .... 37 líneas   el rechazo va ENCIMA (recibida = pedida)
recibida + rechazada < pedida ....  6 líneas   rechazo Y entrega parcial a la vez
```

Un caso real del segundo grupo (línea 649, `OC-20251121-100338`): `cantidad = 7`,
`cantidad_recibida = 7`, `cantidad_rechazada = 3`. El proveedor entregó **10**, se aceptaron 7 y se
rechazaron 3.

**Cómo se resolvió.** El denominador es **lo que físicamente llegó**, `recibida + rechazada`, que
está bien definido en los tres grupos:

```sql
pct_rechazo = 100 * cantidad_rechazada / NULLIF(cantidad_recibida + cantidad_rechazada, 0)
```

Queda declarado en el DDL, en el docstring y en el control de validación, para que el siguiente que
lo lea no tenga que deducirlo.

**Qué habría roto.** OTD-COM-07 («mercancía rechazada en puerta, por proveedor y motivo»). Con el
denominador «pedidas», esa línea da **3/7 = 42,9 %** cuando la verdad es **3/10 = 30,0 %**: el
porcentaje se infla en las **37 líneas** del grupo aditivo, y siempre hacia arriba. En el agregado
la diferencia parece menor (0,1559 % contra 0,1542 %), pero COM-07 **no se lee en el agregado**: se
lee por proveedor, y un proveedor cuyos rechazos caigan en el grupo aditivo aparece
sistemáticamente peor que otro idéntico cuyo almacén registró el rechazo de la otra manera. **El
informe existe para comparar proveedores, y ése es justo el eje que se corrompe.**

---

## C3.3 · `motivo_rechazo` trae 6 valores donde el negocio tiene 5

| | |
|---|---|
| **Fase** | 3A (`fact_compra_linea`) |
| **El diseño decía** | §5.5: `motivo_rechazo LowCardinality(String)`, tratado como un catálogo cerrado que se carga tal cual. |

**La realidad.** Es **texto libre**, y ya tiene una entrada escrita a mano:

```
Empaque danado en transito ............ 25
Producto con defecto de fabrica ....... 21
Fecha de caducidad proxima ............ 19
No coincide con especificacion ........ 16
Unidades incompletas en caja .......... 10
cajas mojadas en el transporte ........  1     ← tecleado en la app
                                        ───
                                        92 líneas con rechazo
```

El sexto valor procede de `RM-20260718-100010` (usuario 9, 2026-07-18): una recepción hecha **desde
la aplicación real** durante el desarrollo del script 45, no del seed. Es el mismo hallazgo que
`motivo_fallo` en la Fase 2 ([C2.1](#c21-el-cobro-fallido-no-tiene-fecha-de-pago-ni-pedido) es
otro, pero la §5.3 ya había tropezado con esto): **conviven el catálogo y el texto libre**.

**Cómo se resolvió.** Mismo patrón que la Fase 2, y por las mismas razones: normalización en
`transformar()` —en Python, no en un `CASE` de SQL— con lista blanca, mapa de sinónimos y **regla
de escape**: un valor no previsto se carga como `'Otro'`, se escribe en el log y se cuenta en
`etl_ejecucion.excepciones`. Un `CASE` puede mapear pero no puede avisar.

Con **una diferencia deliberada respecto de la Fase 2**: allí el canónico es un *código*
(`tarjeta_rechazada`) porque el origen ya guardaba códigos y el texto libre era la anomalía. Aquí
el origen guarda **frases legibles** y el canónico es la frase. Convertirlas a
`empaque_danado_transito` obligaría a cada informe a traducir de vuelta para mostrar algo leíble y
no compraría nada: se normaliza el vocabulario, no se cambia el idioma.

`cajas mojadas en el transporte → Empaque danado en transito` es una decisión de criterio y se
declara como tal: es la misma incidencia (daño del embalaje en el traslado) escrita por una persona
en vez de elegida de una lista. Las alternativas —dejarla cruda o mandarla a `'Otro'`— dan
**igualmente** una sexta categoría en COM-07, y encima una que no significa nada.

**Qué habría roto.** COM-07 mostraría **seis motivos donde el negocio tiene cinco**, con el sexto
representando una sola unidad. Es un informe que se lee mal exactamente donde debe leerse bien: la
tabla de motivos de rechazo es su contenido entero.

---

## C3.4 · La fecha de recepción necesita conversión de zona (§8.3)

| | |
|---|---|
| **Fase** | 3A (`fact_orden_compra`) |
| **El diseño decía** | §5.4 define `dias_ciclo_real` = «recepción − emisión» y `dias_desvio_promesa` = «recepción − esperada», sin más. §8.6 ya advertía del riesgo de zona horaria en general, pero §5.4 no lo aplica a esta resta concreta. |

**La realidad.** Los tres operandos **no son del mismo tipo**: `recepcion_mercancia.fecha_recepcion`
es `timestamptz`, mientras `orden_compra.fecha_emision` y `fecha_entrega_esperada` son `date`
puros. Restarlos exige convertir el primero a día — y el día depende de la zona:

```
recepciones ................................................. 839
  cuyo día en UTC ≠ su día en America/Guayaquil ..............   5
```

Cinco recepciones registradas después de las 19:00 hora local caen al **día siguiente** en UTC.

**Cómo se resolvió.** La conversión es explícita en el SELECT, nunca implícita:

```sql
(r.fecha_recepcion AT TIME ZONE 'America/Guayaquil')::date - oc.fecha_emision
```

**Qué habría roto.** OTD-COM-06 (días reales del ciclo) y OTD-COM-05 (cumplimiento de la promesa)
con **un día de más en 5 de 839 órdenes**. Un error del 0,6 % en un promedio de 10,81 días no se ve
nunca — pero COM-05 clasifica en «cumplió / no cumplió» con el corte en `desvio <= 0`, y ahí un día
**cambia el lado**: hoy hay 825 pares comparables con desvíos entre −7 y +10, y varios están
exactamente en 0. Una orden que llegó puntual pasaría a contarse como incumplimiento del proveedor.

---

## C3.5 · Dos proveedores no tienen ciudad

| | |
|---|---|
| **Fase** | 3A (`dim_proveedor`) |
| **El diseño decía** | §4.4: la dimensión lleva `ciudad`, sin previsión de ausencia. |

**La realidad.** `proveedor.ciudad_id` es nullable y **2 de los 11 lo tienen en NULL** — los
proveedores 1 (`GlobalSport`) y 2 (`DeportAndina`), los dos legacy anteriores al seed, que entre
ambos suman **48 órdenes de compra**.

**Cómo se resolvió.** Centinela `'sin_ciudad'`, como en `dim_cliente` (`'sin_direccion'`) y
`dim_producto` (`'sin_marca'`). La columna es `LowCardinality(String)` y se agrupa en los informes.

**Qué habría roto.** Poco, y por eso está en 🟡 — pero un `NULL` dentro de un `GROUP BY` se lee como
una categoría más, y en una dimensión de 11 filas dos huecos son el 18 %.

---

## C3.6 · La factura de compra no vale lo que la orden

| | |
|---|---|
| **Fase** | 3A (`fact_orden_compra`) |
| **El diseño decía** | §5.4 pone en la misma fila `subtotal / monto_impuesto / total` («de la orden») y `factura_total` (para COM-04 y GER-02), sin advertir que **no son intercambiables**. |

**La realidad.** Difieren en **119 de las 838** órdenes con ambos documentos:

```
Σ total de órdenes de compra ......... $23.007.732,68
Σ total de facturas de compra ........ $22.467.387,27
órdenes cuyo total ≠ el de su factura .......... 119
diferencia acumulada en esas 119 ....... −$226.070,31
```

La causa es legítima y es el propio flujo del sistema: **72 órdenes quedaron en
`recibida_parcial`** y el proveedor factura **lo que entregó**, no lo que se le pidió (de 10.682
unidades pedidas en esas órdenes llegaron 9.264).

**Cómo se resolvió.** Las dos magnitudes viajan en columnas distintas y el docstring declara cuál
responde a qué: **el gasto de compras es `factura_total`** (lo que se debe), y el total de la orden
es el compromiso emitido. Se validan por separado contra sus tablas de origen.

**Qué habría roto.** OTD-COM-04 («gasto de compras por proveedor y por mes») y el lado devengado de
GER-02 sumarían el total de la orden y reportarían **$540.345,41 de gasto que nunca se facturó** —
$226.070,31 de las parciales más $314.275,10 de las 26 órdenes sin factura. Sobre $22,47 M es un
**+2,4 %**: perfectamente plausible, y suficiente para que la balanza de GER-02 no cuadre nunca
contra contabilidad sin que nadie sepa por dónde empezar a buscar.

---

## C3.7 · El detalle de recepción cubre 2.855 de 2.949 líneas, y el resto no es cero

| | |
|---|---|
| **Fase** | 3A (`fact_compra_linea`) |
| **El diseño decía** | §5.5: grano de línea de orden «enriquecida con su recepción», 2.949 filas de las cuales 2.855 con línea de recepción. **Aquí el diseño acertó en las cifras**; lo que no dice es qué hacer con las 94 restantes. |

**La realidad.** Las 94 líneas sin recepción pertenecen a las 26 órdenes que nunca se recibieron
—13 `cancelada`, 7 `confirmada`, 6 `enviada`— y suman **2.372 unidades pedidas por $273.265,18**
que **jamás llegaron**:

```
                 líneas   pedidas   recibidas   rechazadas
recibida ......... 2.611   109.305     109.209          169
recibida_parcial ..  244    10.682       9.264           16
cancelada .........   53     1.331           0            0
confirmada ........   25       620           0            0
enviada ...........   16       421           0            0
```

Se verificó además que la relación línea↔recepción es **estrictamente 1:1 donde existe** (0 líneas
con dos recepciones) y que `orden_compra_detalle.cantidad_recibida` **coincide siempre** con
`recepcion_detalle.cantidad_recibida` (0 discrepancias en las 2.855), de modo que la denormalización
no introduce una segunda verdad.

**Cómo se resolvió.** `LEFT JOIN` y `COALESCE(…, 0)` en las tres cantidades, con `pct_rechazo = 0` y
`completa = 0`. Las 94 líneas **se cargan**: son la demanda que el proveedor no sirvió, y OTD-COM-07
las necesita como base de comparación.

**Qué habría roto.** Un `INNER JOIN` contra `recepcion_detalle` habría dado **2.855 filas donde el
diseño pide 2.949** —el conteo de aceptación lo atrapa— pero, peor, habría hecho **invisible el
100 % de incumplimiento**: un proveedor cuya orden se canceló entera desaparece del informe en vez
de aparecer con cero unidades servidas.

---

# Fase 3B — el kardex y la reconstrucción del inventario mensual

## C3B.1 · `naturaleza = 'ajuste'` NO son los ajustes: la apertura del almacén va dentro

> La corrección más cara de todo el documento si se pasa por alto, y la más fácil de cometer:
> el filtro equivocado es el que el propio esquema pone delante.

| | |
|---|---|
| **Fase** | 3B (`fact_movimiento_inventario`, y sobre todo OTD-INV-10) |
| **El diseño decía** | §2.3 manda OTD-INV-10 («mermas y sobrantes acumulados por período y motivo») contra `fact_movimiento_inventario`, y §5.6 modela la columna `naturaleza` con los valores `entrada / salida / transferencia / **ajuste**`. La lectura obvia —y la única que la tabla sugiere— es que los ajustes son `naturaleza = 'ajuste'`. |

**La realidad.** Bajo esa naturaleza conviven dos cosas que no tienen nada que ver:

```
tipo_movimiento con naturaleza 'ajuste' .................. 399 movs / 34.437 uds
  ├─ entrada_ajuste ← referencia_tipo 'inventario_inicial' . 343 movs / 34.210 uds
  ├─ entrada_ajuste ← referencia_tipo 'ajuste_inventario' ..  20 movs /     90 uds
  └─ salida_ajuste  ← referencia_tipo 'ajuste_inventario' ..  36 movs /    137 uds

ajustes de inventario REALES ............................. 56 movs / 227 uds / neto −47
```

**La apertura de inventario se registró como `entrada_ajuste`.** Son los 343 movimientos con que el
almacén nació con saldo (scripts 74-78, documentados en `CLAUDE.md`): no son un sobrante de nada, son
el punto de partida.

**Cómo se resolvió.** El filtro correcto es `referencia_tipo = 'ajuste_inventario'`, que es además lo
que el catálogo táctico ya decía. Y para que la consulta correcta sea también la fácil, el ETL
**precalcula la columna `es_ajuste_real UInt8`**: INV-10 la usa directamente y el error deja de estar
al alcance de un descuido. Las dos cifras viajan juntas en `validar_dwh.py` (`ajustes_reales` = 56 y
`naturaleza_ajuste` = 399) precisamente para que la distancia entre ellas siga siendo visible.

**Qué habría roto.** OTD-INV-10 entero. Con el filtro «obvio», los sobrantes pasan de **90 a 34.300
unidades**: un factor de **380×**. El informe se pinta perfectamente, ordena por unidades, y muestra
un almacén con decenas de miles de unidades aparecidas de la nada — que un gerente leería como un
descontrol de inventario catastrófico o, peor, como un dato normal del sistema. No hay ninguna suma
que no cuadre, ningún JOIN que falle y ninguna excepción: solo un informe que responde otra pregunta.

---

## C3B.2 · `ajuste_motivo` es texto libre con un prefijo de máquina dentro

| | |
|---|---|
| **Fase** | 3B (`fact_movimiento_inventario`) |
| **El diseño decía** | §5.6: `ajuste_motivo` `LowCardinality(String)`, «de `ajuste_inventario` cuando `referencia_tipo='ajuste_inventario'` → **INV-10**», es decir, una etiqueta lista para agrupar. El catálogo táctico lo refuerza: «53 ajustes sobre **7 motivos tipificados**». |

**La realidad.** No existe tabla `motivo_ajuste`. `ajuste_inventario.motivo` es un `text` libre, y
—como la cabecera del ajuste no tiene detalle de líneas— el seed le metió dentro el SKU y la cantidad:

```
[SKU-P1340 x4] Merma reportada por el operador de bodega · ANULADO: El conteo posterior…
└──── prefijo de máquina ────┘                                └────── sufijo ──────┘

valores DISTINTOS en crudo ....... 53   (uno por ajuste: el SKU va dentro)
valores tras limpiar ............. 11   (8 del seed + 3 tecleados en la app)
motivos que el catálogo suponía ...  7
```

**Cómo se resolvió.** Se limpia en `transformar()` —Python, no un `CASE`— quitando el prefijo
`[SKU xN]` y el sufijo `· ANULADO: …` (el motivo de la anulación no es el motivo del ajuste, y el
estado ya viaja en su propia columna).

**Y aquí NO se mapean sinónimos, a diferencia de [C3.3](#c33-motivo_rechazo-trae-6-valores-donde-el-negocio-tiene-5).**
Es una distinción deliberada: quitar una decoración que puso una máquina es una **limpieza**; decidir
que «Merma por producto danado» y «Producto roto durante el almacenamiento» son el mismo motivo sería
una **opinión** sobre dos textos que una persona escribió a propósito. Se cargan los 11, el informe
agrupa además por `ajuste_tipo` —que sí es una columna controlada por el motor: `negativo` /
`positivo` / `conteo`— y un motivo no visto antes se carga tal cual y se registra en la bitácora.

**Qué habría roto.** OTD-INV-10 mostraría **53 «motivos» de una fila cada uno**, uno por ajuste, con
el SKU pegado delante. Un informe cuyo contenido entero es la tabla de motivos, con tantas filas como
hechos: no agrupa nada, no acumula nada y no responde «por motivo», que es literalmente lo que el
objetivo pide. Y la columna es `LowCardinality`, así que además se estaría usando el peor tipo posible
para 53 valores casi únicos.

---

## C3B.3 · 177 movimientos no tienen costo unitario, y son justo los que INV-10 necesitaría

| | |
|---|---|
| **Fase** | 3B (`fact_movimiento_inventario`, con efecto en OTD-INV-10) |
| **El diseño decía** | §5.6: `costo_unitario` `Decimal(14,2)`, «el del movimiento, **no el vigente de la variante**» — sin admitir ausencia. El aviso es correcto y necesario; lo que falta es que en 177 casos no hay ninguno. |

**La realidad.**

```
movimientos sin costo_unitario ............... 177 de 13.287
  ├─ referencia 'transferencia_bodega' ....... 121   mover no valoriza
  └─ referencia 'ajuste_inventario' ..........  56   ajustar tampoco
```

Es coherente con el negocio: una transferencia entre bodegas no cambia el valor del inventario, y un
ajuste no tiene precio de compra. Pero la coincidencia es incómoda: **los 56 movimientos sin costo son
exactamente los 56 que OTD-INV-10 mide.**

**Cómo se resolvió.** Se cargan con `costo_unitario = 0` y `valor_movimiento = 0`, **marcados con
`sin_costo = 1`**. Sin esa marca, un movimiento sin valorizar es indistinguible de uno gratuito y
cualquier promedio de costos incluiría 177 ceros que tiran la media hacia abajo sin que se vea.

Y la consecuencia se asume donde toca: **INV-10 no valoriza con el costo del movimiento**, porque no
existe. Su modo financiero une `dim_producto.costo` (el VIGENTE) y arrastra por tanto la misma
salvedad que INV-09, declarada en pantalla.

**Qué habría roto.** El catálogo pide INV-10 «valorizado solo Gerente, Administrador». Valorizándolo
con `costo_unitario` —que es lo que §5.6 sugiere— el informe habría reportado **$0,00 de merma en el
100 % de los motivos**: un almacén donde perder mercancía no cuesta nada. Un cero perfecto, coherente
en todas las filas y sin un solo error.

---

## C3B.4 · 21.122 filas, no 26.700: el cartesiano completo inventa 5.592 ceros

| | |
|---|---|
| **Fase** | 3B (`fact_stock_mensual`) |
| **El diseño decía** | §5.7: «Grano: (mes, bodega, variante). **19 meses × 1.406 posiciones ≈ 26.700 filas**», y su paso 2 pide resolver los meses sin movimiento «con un **producto cartesiano contra los 19 meses**». |

**La realidad.** El cartesiano completo fabrica filas para posiciones que todavía no existían:

```
pares (variante, bodega) .............................. 1.406
pares cuyo primer movimiento cae en el primer mes .....   515   ← solo el 37 %
filas del cartesiano completo (19 × 1.406) ............ 26.714
filas reales (desde el primer movimiento de cada par) . 21.122
                                           diferencia    5.592   (21 %)
```

Un `stock_cierre = 0` en marzo de 2025 para una variante que entró al catálogo en octubre **no
significa «había cero unidades»: significa «esa posición no existía»**. Son dos hechos distintos y la
tabla solo puede representar uno.

**Cómo se resolvió.** La malla se genera desde `min(mes)` de **cada par** hasta el último mes del
período, sin huecos. Es también la definición de continuidad que valida `stock_continuidad` en
`validar_dwh.py` (0 pares con huecos, 0 pares que no llegan al cierre).

**Qué habría roto.** OTD-INV-09 es una **serie de evolución**, y ahí 5.592 ceros no son ruido: se
concentran todos al principio. La curva del capital inmovilizado arrancaría aplanada y subiría sola
según se van incorporando posiciones, contando como «crecimiento del capital en bodega» lo que en
realidad es «crecimiento del catálogo». La pregunta del objetivo —«¿se está llenando o vaciando de
capital la bodega?»— recibiría un «llenándose» que es un artefacto del método. Y ninguna suma delata
el problema: los ceros suman cero.

---

# Fase 3C — la última milla y las incidencias de entrega

## C3C.1 · La zona horaria decide el día, y con él los tres plazos del envío

> [C3.4](#c34-la-fecha-de-recepción-necesita-conversión-de-zona-83) ya avisó de esto en la Fase 3A y
> afectaba a 5 órdenes de 839. Aquí afecta a **569 envíos de 2.727** y mueve el promedio del informe.
> La misma corrección deja de ser una nota al pie cuando la tabla entera son plazos.

| | |
|---|---|
| **Fase** | 3C (`fact_envio`; OTD-LOG-04 sobre todo, y OTD-LOG-03 de refilón) |
| **El diseño decía** | §5.8 define `dias_transito` como «`fecha_entrega_real − fecha_despacho`» y `dias_desvio_promesa` como «`fecha_entrega_real − fecha_entrega_estimada`», sin mencionar conversión alguna. §8.6 advierte del problema en general, pero §5.8 no lo aplica. |

**La realidad.** `fecha_despacho` y `fecha_entrega_real` son `timestamptz`; `fecha_entrega_estimada`
es un `date` puro. Restar días obliga a decidir **en qué zona se resuelve el día**, y las dos
respuestas difieren:

```
envíos cuyo DÍA DE DESPACHO cambia entre UTC y America/Guayaquil ...... 597
envíos cuyo DÍA DE ENTREGA cambia .....................................   6

envíos con `dias_transito` DISTINTO según la zona ..... 569 de 2.727  (20,9 %)
tránsito promedio en America/Guayaquil ............... 3,9754 días
tránsito promedio en UTC ............................. 3,7675 días   (−5,2 %)
```

**El desajuste es asimétrico, y eso es lo que lo hace peligroso.** El despacho ocurre por la tarde
hora local, así que en UTC cae al día siguiente; la entrega ocurre por la mañana y casi nunca se
desplaza. El error no se reparte entre acortar y alargar: **acorta sistemáticamente el tránsito**.

**Cómo se resolvió.** Las TRES restas convierten la zona explícitamente, y la expresión del día vive
en **una sola constante** (`_DIA_ENTREGA` / `_DIA_DESPACHO` de `fact_envio.py`) en vez de repetirse
tres veces. El motivo de aislarla es concreto: aplicar la conversión en dos restas y olvidarla en la
tercera produce un informe **coherente consigo mismo** —los números se sostienen entre sí— y
equivocado. La carga aborta además si aparece cualquier `dias_transito` negativo, que es el síntoma
de una conversión aplicada a medias.

**Qué habría roto.** OTD-LOG-04 entero: el informe compara transportistas por días de tránsito, y
**uno de cada cinco envíos cambia de valor**. El promedio pasa de 3,98 a 3,77 días — una cifra
perfectamente creíble, del orden esperado, que nadie tendría motivo para volver a comprobar. Sobre
OTD-LOG-03 el daño es menor (1.704 → 1.702 entregas a tiempo) porque la fecha prometida ya es un
`date`; pero son los mismos dos envíos que cambian de lado en un informe de cumplimiento, y ahí el
veredicto es binario.

---

## C3C.2 · 24 envíos no tienen tarifa, y no son envíos gratis

| | |
|---|---|
| **Fase** | 3C (`fact_envio`; la serie temporal del costo de envío) |
| **El diseño decía** | §5.8 declara `costo Decimal(14,2)` y `peso_total_kg Decimal(10,3)` sin admitir ausencia, y anota «2.848 con costo» como si los 24 restantes fueran ruido. El catálogo lo repite: «`envio.costo > 0` en 2.848 de 2.872, $32.723,25». |

**La realidad.** Los 24 no son un residuo disperso: son un bloque identificable.

```
envíos con costo = 0 ............................. 24
envíos con peso_total_kg NULL .................... 24
envíos con AMBOS ausentes ........................ 24   ← son exactamente los mismos
ids ....................................... 1 a 24 (los 24 primeros de la tabla)
despachados ....................... del 4 al 20 de julio de 2026
```

Son los envíos creados a mano contra la aplicación durante el desarrollo, **antes de que el cálculo
de tarifa por zona y peso estuviera en su sitio** (script 54). Un costo de 0 aquí no significa «este
envío salió gratis», significa «este envío nunca se tarifó». Son dos hechos distintos y la columna
los escribe igual.

**Cómo se resolvió.** Se cargan con `costo = 0`, `peso_total_kg = 0` y la marca **`sin_tarifa = 1`**
—mismo criterio que `sin_costo` en el kardex
([C3B.3](#c3b3-177-movimientos-no-tienen-costo-unitario-y-son-justo-los-que-inv-10-necesitaría))—, y
`costo_por_kg` queda en **NULL y no en 0**: un costo por kilo de cero se promediaría con los demás y
abarataría la serie sin dejar rastro. La carga verifica además que ningún envío marcado `sin_tarifa`
traiga un costo distinto de cero.

**Qué habría roto.** La serie temporal del costo de envío, y justo en su último punto. Los 24 caen
todos dentro de **julio de 2026**, que tiene 109 envíos:

```
costo medio de julio 2026 CON los 24 ceros ....... $7,5930
costo medio de julio 2026 SIN ellos .............. $9,7369     ← −22,0 %
```

El mes final de la serie —el que se mira primero, y el que marca la tendencia— aparecería **un 22 %
más barato** por un artefacto de la carga. En un informe cuyo propósito declarado es revisar las
tarifas que se cobran al cliente, esa es exactamente la conclusión equivocada: parecería que el
transporte se está abaratando.

---

## C3C.3 · Los valores de `accion` son los verbos del API, no lo que guarda la base

| | |
|---|---|
| **Fase** | 3C (`fact_novedad_envio`; OTD-LOG-05) |
| **El diseño decía** | §5.9, literal: «`accion` (`reprogramar` / `devolver_almacen`)». |

**La realidad.** Esos son los nombres de las **operaciones** del API —`POST …/novedades/{id}/reprogramar`
y `/devolver-almacen`—, no los valores que quedan escritos. Lo que guarda la columna es el participio:

```
devuelto_almacen ....... 120      (intentos 1 a 3)
reprogramada ...........  49      (intentos 1 a 2)
(NULL, sin resolver) ...   7
                        ────
                         176

SELECT count(*) FROM novedad_envio WHERE accion = 'devolver_almacen'  →  0
```

**Un filtro escrito desde el diseño casa con CERO filas.** Y no falla: devuelve una tabla vacía, que
en un informe de problemas de entrega se lee perfectamente como «no hubo devoluciones al almacén».
Es el patrón de toda esta bitácora, esta vez a un guion bajo y un tiempo verbal de distancia.

**Cómo se resolvió.** La lista blanca (`ACCIONES_CONOCIDAS`) se toma **de los datos y no del
documento**, y la carga valida que los valores cargados sean exactamente los conocidos: uno nuevo se
carga tal cual, se cuenta y se registra en `etl_ejecucion.excepciones` en vez de pasar en silencio.
El NULL de las 7 sin resolver se etiqueta `'sin_resolver'` —no se descarta— porque son precisamente
las que no tienen `horas_hasta_resolucion`, y un tiempo medio de resolución calculado solo sobre las
cerradas es el sesgo clásico de mirar únicamente lo que terminó.

**Qué habría roto.** OTD-LOG-05 pregunta «cuántos problemas ocurren, cuántos intentos toman y **cómo
terminan**»: el desenlace es media pregunta. Con el valor del diseño, la categoría que agrupa **120 de
las 176 novedades — el 68 %, y la única que acaba en pérdida de la venta —** desaparecería del
informe. Las otras 49 se seguirían viendo, así que la tabla no saldría vacía ni resultaría sospechosa:
saldría diciendo que las incidencias de entrega se resuelven reprogramando en el 100 % de los casos.

---

### Reincidencia declarada, sin número propio

§5.9 especifica `horas_hasta_resolucion` como `Nullable(Float32)`. Se carga `Nullable(Decimal(12,2))`
por la misma razón ya registrada en
[C2.5](#c25-los-tramos-de-tiempo-no-pueden-ir-en-float32) para los tramos de `fact_pedido`: el
comparador de `validar_dwh.py` **rechaza todo float por construcción**, y con `Float32` esta sería la
única medida de la tabla imposible de validar contra PostgreSQL.

No lleva número porque no es una corrección nueva — es la misma, aplicada otra vez. Se anota aquí
porque el diseño la repite en cada tabla que mide tiempo, y quien implemente la Fase 4 se la volverá
a encontrar: **el criterio del proyecto es que ninguna medida viaje en coma flotante**, y esa regla
gana sobre el tipo que declare el diseño.

---

# Fase 4 — posventa, soporte y marketing

## C4.1 · El reembolso tiene DOS orígenes, y no dicen lo mismo

| | |
|---|---|
| **Fase** | 4 (`fact_devolucion`; OTD-LOG-10) |
| **El diseño decía** | §5.10 declara el origen como «… ⟕ **`reembolso`** ⟕ `cliente`» y a la vez pone en la tabla las columnas `monto_reembolsado` / `metodo_reembolso` / `fecha_reembolso`, que **no son de `reembolso`: son de `devolucion`**. Da por hecho que son el mismo dato. |

**La realidad.** Son dos registros distintos del mismo hecho, y difieren:

```
devolucion.monto_reembolsado poblado ....  86 devoluciones / $44.695,33
filas en la tabla `reembolso` ...........  85 devoluciones / $44.525,63
                                 diferencia   1 devolución / $   169,70

reembolsos cuyo monto NO coincide con la cabecera ....... 0
asientos de reembolso sin monto en su devolución ........ 0
```

La que sobra es la **devolución 8** (`DV-20260716-53942`, cerrada, del 2026-07-16, el día
en que se construyó el RMA): tiene su monto en la cabecera y ninguna fila en `reembolso`.

Y hay una asimetría que decide cuál sirve: **la VÍA del reembolso (`metodo_reembolso`) solo
existe en `devolucion`.** La tabla `reembolso` guarda el asiento —`pago_id`, estado,
referencia— pero no por dónde se devolvió el dinero, que es literalmente media pregunta de
OTD-LOG-10 («cuánto se reembolsó, **por qué vía** y por qué motivo»).

**Cómo se resolvió.** Las dos magnitudes viajan **por separado y no se reconcilian**, mismo
criterio que [C2.4](#c24-monto_cupon-y-monto_descuento-no-son-la-misma-cifra) con el cupón:
`monto_reembolsado` es lo que el RMA dice haber devuelto (86) y `reembolso_registrado` +
`monto_reembolso_asiento` marcan si además existe el asiento de tesorería (85). LOG-10 usa el
de la devolución —porque es el único que trae la vía—, lo **declara en `salvedad`** y saca la
columna «Sin asiento» en cada fila para que la diferencia sea consultable.

**Qué habría roto.** OTD-LOG-10 tomando `reembolso` como origen se quedaría **sin la columna
`metodo_reembolso`**, que es su eje principal, y tendría que inventarse un agrupador o
mostrar solo el total. Tomando solo la cabecera y llamándola «reembolsos pagados», reportaría
$169,70 que tesorería no tiene registrados. Ninguna de las dos cifras es falsa; presentar una
sin decir cuál lo es.

---

## C4.2 · El ciclo completo del RMA solo es medible en 35 de 196

> El supuesto que decide si OTD-LOG-07 mide algo o mide una esquina.

| | |
|---|---|
| **Fase** | 4 (`fact_devolucion`; OTD-LOG-07) |
| **El diseño decía** | §5.10: `dias_ciclo_total` = «cierre − solicitud → **LOG-07**», sin decir sobre cuántas devoluciones existe ese cierre. Y anota que «161 de 196 tienen ≥3 pasos fechados», lo que sugiere una cobertura amplia. |

**La realidad.** Los 161 con tres pasos son ciertos —el diseño acertó ahí— pero el CIERRE es
otra cosa:

```
devoluciones .......................... 196
con hito 'cerrada' ....................  35     ← el 17,9 %
con desenlace TERMINAL ................  53     (35 cerradas + 18 rechazadas)
con ≥3 pasos fechados ................. 161     ← esto sí, como decía §5.10

cobertura de los demás hitos:
  aprobada 143 · en_transito 131 · recibida 121 · inspeccionada 107 · reembolsada 86
```

Una devolución **rechazada** es un ciclo que terminó: `rechazada` es terminal por diseño del
RMA y no tiene salida. Medir «cuántos días tarda una devolución» solo sobre las 35 cerradas
descarta 18 desenlaces reales y —peor— descarta justo **los más rápidos**, porque rechazar no
exige recibir la mercancía ni inspeccionarla.

**Cómo se resolvió.** Se cargan las DOS medidas: `dias_ciclo_total` (cierre − solicitud, 35,
la del diseño) y **`dias_hasta_desenlace`** (cierre **o** rechazo − solicitud, 53). El informe
muestra las dos, cada una con su `n_` al lado, y el sobre lleva `salvedad` explicando que son
promedios sobre poblaciones distintas.

De paso, otro supuesto pequeño que no se sostuvo y se resolvió sin número propio: **5 de las
196 no tienen registro histórico de `solicitada`** (son legacy anteriores al script 38). La
`fecha_solicitud` se toma por tanto de `devolucion.fecha_creacion`, que está en las 196 y
coincide exactamente con el hito en las 191 donde existe. Tomarla del historial habría dejado
5 devoluciones sin período — el mismo fallo de
[C2.1](#c21-el-cobro-fallido-no-tiene-fecha-de-pago-ni-pedido).

**Qué habría roto.** OTD-LOG-07 entero. Con la definición del diseño y sin declarar la base,
el informe publica un promedio de días de ciclo calculado sobre **el 17,9 % de las
devoluciones** y presentado como si fuera el ciclo del RMA. Ni una suma falla, ni una fila
sale vacía: sale un número plausible que describe una esquina del proceso. Y como las
rechazadas son las rápidas, el sesgo va en una sola dirección: el ciclo parece **más lento**
de lo que es.

---

## C4.3 · Un ticket no tiene categoría, y el JOIN interno del diseño lo tira

| | |
|---|---|
| **Fase** | 4 (`fact_ticket`; OTD-SOP-04 y los cinco compuestos de Soporte) |
| **El diseño decía** | §5.12: origen = «`ticket_soporte` **⋈** `categoria_ticket` ⟕ `usuario` ⟕ …», con JOIN interno contra la categoría, y describe la columna como «**8, todas con casos**». |

**La realidad.** Las 8 categorías tienen casos, sí. Lo que el diseño no contempla es que
`ticket_soporte.categoria_ticket_id` es **nullable**:

```
tickets ...................... 248
con categoría ................ 247
SIN categoría ................   1     ← el JOIN interno lo tira
```

**Cómo se resolvió.** `LEFT JOIN` y centinela `'sin_categoria'`, mismo criterio que
`'sin_ciudad'` en [C3.5](#c35-dos-proveedores-no-tienen-ciudad). Un ticket sin clasificar no
es un ticket que no existe: es precisamente el que hay que ir a clasificar, y aparece en el
informe con su etiqueta.

**Qué habría roto.** Un ticket menos sobre 248 es un **0,4 %**. Ningún conteo escandaliza,
ninguna suma falla, y SOP-04 («tickets por categoría») publicaría 247 como si fueran todos —
mientras el único ticket sin clasificar, que es el que exige una acción, desaparece
exactamente del informe que existe para encontrarlo. Es la mecánica de
[C1.1](#c11-factura_venta-no-es-11-con-el-pedido) con el signo cambiado.

---

## C4.4 · Unir `fact_resena` a `dim_producto` por `producto_id` multiplica filas

| | |
|---|---|
| **Fase** | 4 (`fact_resena`; OTD-VEN-11) |
| **El diseño decía** | §5.13 avisa del grano —«la reseña se ata al **producto**, no a la variante… por eso une a `dim_producto` por `producto_id` y no por `producto_variante_id`»— pero deja implícito que **ese JOIN es seguro**. El aviso es correcto y la conclusión, falsa. |

**La realidad.** `dim_producto` tiene grano de VARIANTE. Unir por `producto_id` devuelve una
fila por cada variante del producto:

```
productos ............................. 1.214
variantes ............................. 1.221
productos con más de una variante .....     7   (hasta 3)
reseñas de esos productos .............     3

filas de fact_resena ..................   344
filas si se uniera a dim_producto .....   347     ← +3, sin error
```

**Cómo se resolvió.** La tabla **denormaliza** `producto_nombre`, `categoria` y `marca` desde
`producto`, y **nunca se une a `dim_producto`**. El control cruzado con la dimensión se hace
por EXISTENCIA sobre los `producto_id` DISTINTOS y no por unión, precisamente para no
reproducir el error dentro de la validación. La cifra del fan-out evitado (347) viaja en
`sql_controles` para que la nota no envejezca.

**Qué habría roto.** Tres reseñas sobre 344 son un 0,9 %: la calificación media se mueve unas
milésimas y nadie lo nota. Lo que sí cambia es que **la reseña de un producto de tres
variantes pesa el triple** que la de cualquier otro, y VEN-11 es un ranking de productos por
calificación. No hay error, no hay excepción, no hay suma que no cuadre: solo un orden
ligeramente equivocado y para siempre, en el eje que el informe existe para ordenar.

---

## C4.5 · «Resuelto» y «cerrado» no son el mismo hecho

| | |
|---|---|
| **Fase** | 4 (`fact_ticket`; OTD-SOP-02, SOP-03 y SOP-07) |
| **El diseño decía** | §5.12: `fecha_cierre Nullable(DateTime)` — «**76 cierres**». La cifra es exacta; lo que no dice es que hay 120 tickets que el negocio considera atendidos. |

**La realidad.** Solo `cerrado` escribe `fecha_cierre`. `resuelto` es un paso previo — el
cliente todavía puede responder y reabrirlo (grant de `UPDATE(estado)` a `grp_cliente`,
script 37):

```
estado 'cerrado' ..................  76     ← y los 76 tienen fecha_cierre
estado 'resuelto' .................  44     ← ninguno la tiene
'resuelto' + 'cerrado' ............ 120
tickets con fecha_cierre ..........  76
tickets con cierre anterior a su creación ....  0
```

**Cómo se resolvió.** Los tres informes de tiempos miden con la FECHA —no hay instante que
restar en los 44— y la tabla lleva **las dos** columnas: `fecha_cierre` (76) y
`resuelto_por_estado` (120). Cada fila del informe muestra ambas y el sobre lo declara en
`salvedad`.

**Qué habría roto.** Nada en las cifras, y ése es el problema. Un lector que sepa que «hay 120
tickets resueltos» y lea «tiempo medio de resolución: 49 horas» supone que ese promedio los
describe a todos. Describe a 76. Sin la columna del denominador, la diferencia entre las dos
poblaciones —el 37 % de lo atendido— es invisible.

---

## C4.6 · La «primera respuesta» cuesta 51 tickets y 1,35 h según cómo se defina

| | |
|---|---|
| **Fase** | 4 (`fact_ticket`; OTD-SOP-06) |
| **El diseño decía** | §5.12 adopta la definición del catálogo —«el primer mensaje cuyo autor es del equipo (`usuario_id` poblado) y que el cliente puede ver (`es_interno = false`)», 193 de 248— y advierte, con razón, que «cualquier otra da otro resultado igual de defendible». **Lo que no hace es medir la otra.** Una advertencia sin cifra no se puede poner en una pantalla. |

**La realidad.** La diferencia no es teórica: es una base distinta Y un tiempo distinto.

```
tickets con ALGÚN mensaje del equipo ................ 244
  cuya primera intervención es una NOTA INTERNA .....  32
  sin ninguna respuesta visible (solo notas) ........  51
tickets con primera respuesta VISIBLE ............... 193     ← la adoptada

retraso medio entre la primera nota interna y la
primera respuesta visible, en esos 32 ............. +1,35 h
```

**Cómo se resolvió.** Se mantiene la definición del catálogo —es la que mide lo que el cliente
recibió— y se cargan **las dos**: `fecha_primera_respuesta` / `horas_primera_respuesta` (la
estricta) y `fecha_primer_mensaje_equipo` / `horas_hasta_mensaje_equipo` (la laxa). El informe
las muestra en columnas contiguas y la definición viaja **literal en `salvedad`**, para que se
lea en la pantalla y no en el diseño. El control de la carga verifica que la distancia entre
las dos siga siendo exactamente los 51 «solo notas internas»: si deja de serlo, la advertencia
de la pantalla habría dejado de ser cierta.

**Qué habría roto.** OTD-SOP-06 publicado con la definición laxa mediría sobre **244** tickets
en vez de 193, y con un tiempo **sistemáticamente menor**. Los dos números son ciertos y
ninguno delata al otro. Sin la definición escrita al lado, el informe no es reproducible: dos
personas que lo recalculen obtendrán cifras distintas y las dos tendrán razón.

---

## C4.7 · Los valores de `origen` tampoco son los del diseño

> Segunda reincidencia exacta de [C3C.3](#c3c3-los-valores-de-accion-son-los-verbos-del-api-no-lo-que-guarda-la-base),
> una fase más tarde y en otra tabla. De ahí que la regla ya no sea una nota sino un criterio:
> **la lista blanca sale del CHECK del motor, nunca del documento.**

| | |
|---|---|
| **Fase** | 4 (`fact_devolucion_proveedor`; OTD-COM-09) |
| **El diseño decía** | §5.14, literal: «`origen LowCardinality(String)` (`inspeccion_rma` / `recepcion_compra`)». |

**La realidad.** El CHECK de `item_defectuoso` admite otros dos valores:

```
CHECK (origen IN ('rma', 'recepcion'))

    rma ........... 36 ítems / 56 uds
    recepcion .....  2 ítems /  3 uds

SELECT count(*) FROM item_defectuoso WHERE origen = 'inspeccion_rma'  →  0
```

**Cómo se resolvió.** `ORIGENES_CONOCIDOS = {'rma', 'recepcion'}`, tomada de los datos, con
aviso y recuento en la bitácora si apareciera uno nuevo. El control de la carga incluye
`origen_segun_diseno`, que **debe dar 0 en los dos motores** y es la prueba viva de esta
corrección: el día que deje de serlo, el diseño tenía razón y la nota sobra.

**Qué habría roto.** Un filtro escrito desde el diseño casa con **cero filas** y no falla:
devuelve una tabla vacía, que en un informe de recuperaciones se lee perfectamente como «no
hemos devuelto nada al proveedor». Peor aún que en C3C.3, donde al menos quedaban 49 filas
visibles: aquí el informe entero se vacía sin dar un solo error.

---

## C4.8 · La detección del ítem defectuoso NO precede a su devolución

| | |
|---|---|
| **Fase** | 4 (`fact_devolucion_proveedor`; OTD-COM-09) |
| **El diseño decía** | §5.14 pide `dias_hasta_resolucion Nullable(Float32)` en una tabla cuyo grano es el ÍTEM y cuya fecha declarada es `fecha_deteccion`. La lectura natural —y la única que las dos columnas sugieren— es «resolución menos detección». |

**La realidad.** Esa resta **sale negativa en 18 de los 28 ítems agrupados**:

```
ítems agrupados en una devolución ............................ 28
  detectados DESPUÉS de crearse la devolución que los agrupa .. 19
  detectados DESPUÉS de que esa devolución ya estuviera resuelta 18
```

Caso real: `DP-20250320-112429` se registró el 2025-03-20 y se resolvió el 2025-04-01, y **14
de sus 15 ítems** llevan fecha de detección de hasta 2026-05-25 — más de un año después de
cobrarse su nota de crédito. Es un artefacto del seed, que agrupó ítems en devoluciones
retrodatadas sin respetar la cronología.

Lo que **sí** está ordenado es el ciclo de la devolución en sí:

```
devoluciones con envío anterior al registro ....... 0
con resolución anterior al envío .................. 0
con resolución anterior al registro ............... 0
ciclo registro → resolución: media 7,30 días (0,00 a 12,66)
```

**Cómo se resolvió.** `dias_hasta_resolucion` mide el **ciclo de la devolución** —
`fecha_resolucion − fecha_devolucion`—, que además es la pregunta de COM-09 («cuánto tardamos
en recuperar») y no la espera del ítem en el pool. La anomalía no se oculta: viaja en la
columna **`deteccion_posterior`** (19 ítems) y su recuento se valida contra PostgreSQL, para
que quien mire el pool sepa que sus fechas no ordenan el proceso.

**Qué habría roto.** La primera carga **abortó por esto**, que es exactamente el trabajo de la
validación. Si el control no hubiera existido, COM-09 habría mostrado un ciclo medio
**negativo** — o, si alguien lo hubiera «arreglado» con un `GREATEST(…, 0)`, un tiempo de
recuperación de **cero días**, que sobre 6 casos parecería una eficiencia notable en vez de un
error de método.

---

### Reincidencias declaradas, sin número propio

**Los tramos en `Decimal` y no en `Float32`.** §5.10, §5.12, §5.13 y §5.14 especifican
`Float32` para días y horas. Se cargan `Nullable(Decimal(12,2))` por lo ya registrado en
[C2.5](#c25-los-tramos-de-tiempo-no-pueden-ir-en-float32): el comparador de `validar_dwh.py`
rechaza todo float por construcción. Cuarta fase consecutiva en que el diseño lo repite y
cuarta en que se corrige.

**El alias de un agregado no puede llamarse como su columna.** Ya anotado en la Fase 1, y
volvió a costar cinco consultas de esta fase: `sum(venta_neta) AS venta_neta`,
`sum(descuento_total) AS descuento_total`, `sum(monto_reembolsado) AS monto_reembolsado`,
`sum(reclamos) AS reclamos` y `avgIf(dias_hasta_desenlace, …) AS dias_hasta_desenlace` fallan
todas con `ILLEGAL_AGGREGATION`. No es silencioso —el motor lo rechaza y el informe da 500,
que es la degradación correcta desde la Fase 1— pero conviene tenerlo delante al escribir un
agregado nuevo.

**El LEFT JOIN de ClickHouse rellena con el DEFECTO del tipo, no con NULL.** Registrado en la
Fase 3B y decisivo otra vez en OTD-GER-07: una promoción sobre un producto que nunca se vendió
recibe una fila fantasma con `fecha_pedido = 1970-01-01`, anterior a cualquier ventana, que se
contaría como una venta del «antes». El guardia `lv.producto_id > 0` está en las dos
condiciones de la comparación por eso.

---

# Fase 5 — la conexión de los informes de Compras

> Esta fase **no carga ni una fila**: conecta a las tablas ya validadas los siete objetivos de
> Compras que quedaban. Aun así aparecen dos supuestos fallidos, y los dos son del mismo tipo
> que todo lo anterior: **una columna que existe, que se puede usar, y que responde otra
> pregunta.**

## C5.1 · El mes del gasto de compras no es el mes de la orden

| | |
|---|---|
| **Fase** | 5 (OTD-COM-04, sobre `fact_orden_compra`) |
| **El diseño decía** | §5.4 modela la tabla con una columna `mes` `Date` —el mes de `fecha_emision` de la ORDEN— y le asigna «OTD-COM-04: gasto de compras por proveedor y **por mes**» sin decir en ningún momento de qué mes habla. La tabla tiene una sola columna que se llama «mes» y el informe pide agrupar por mes: la lectura obvia es usarla. |

**La realidad.** La orden y su factura casi nunca son del mismo mes:

```
facturas de compra .............................................. 839
  cuyo mes COINCIDE con el de su orden .......................... 479
  en un mes DISTINTO al de su orden ............................. 360   (42,9 %)
```

Y el efecto sobre la serie no es marginal. Agrupando por el mes de la orden en vez de por el de
la factura, **$4.628.932,62 cambian de mes** — el 20,6 % de los $22,47 M de gasto:

```
mes        por mes de ORDEN     por mes de FACTURA      desvío
2025-01      $3.459.601,81         $2.266.890,08       +52,6 %
2025-06        $766.800,60         $1.066.035,30       −28,1 %
2026-06        $549.712,65         $1.011.135,22       −45,6 %
2026-07        $334.888,16           $629.024,53       −46,8 %
```

**El total anual es idéntico en las dos versiones.** Es exactamente lo que hace peligroso el
error: no hay ninguna suma que no cuadre, ningún control de aceptación que salte y ningún JOIN
que falle. Solo la FORMA de la curva es otra.

**Cómo se resolvió.** OTD-COM-04 agrupa por `toStartOfMonth(fecha_factura)` y sus filtros de
fecha son de la factura, no de la orden. La columna `mes` sigue siendo la correcta —y la que
usan— para COM-05 y COM-06, que hablan de la orden. El informe lo DICE en su campo `salvedad`,
porque un usuario que filtra «desde enero» tiene derecho a saber qué fecha se está filtrando.

**Qué habría roto.** OTD-COM-04 es un informe de **evolución**: su razón de ser es la curva. Con
el mes de la orden, enero de 2025 aparece un 52,6 % más alto y el último mes de la serie un
46,8 % más bajo, es decir, un arranque inflado y una caída final. Un gerente leería
«el gasto de compras se está desplomando» sobre un dato cuyo total es correcto al centavo. Y el
lado devengado de GER-02 quedaría comparando ingresos del mes contra gastos de otro.

---

## C5.2 · Las «259 líneas incompletas» son tres cosas distintas

| | |
|---|---|
| **Fase** | 5 (OTD-COM-11) |
| **El diseño decía** | Aquí el que se equivoca no es `DISENO_ETL_CLICKHOUSE.md` sino el **catálogo táctico** (§4), que declara el soporte de datos de OTD-COM-11 como «`orden_compra_detalle.cantidad/cantidad_recibida` — **259 de 2.949 líneas con recepción menor a la pedida**». La cifra es cierta y la conclusión que invita a sacar no lo es. |

**La realidad.** Ese filtro mete en el mismo saco tres situaciones que no significan lo mismo:

```
                          líneas   pedidas   recibidas   faltantes
recibida / recibida_parcial  165    26.692      25.178       1.514   ← el proveedor sirvió de menos
confirmada / enviada          41     1.041           0       1.041   ← todavía viene de camino
cancelada                     53     1.331           0       1.331   ← se anuló: nunca hubo que servirla
                             ───                             ─────
                             259                             3.886
```

**Solo 165 son un incumplimiento del proveedor.** Las otras 94 líneas suman **2.372 unidades que
nunca llegaron a deberse** — son las mismas 94 de [C3.7](#c37-el-detalle-de-recepción-cubre-2855-de-2949-líneas-y-el-resto-no-es-cero),
vistas desde el otro lado.

**Cómo se resolvió.** El informe tiene un filtro `alcance` cuyo valor por defecto es
`entregadas` (órdenes en `recibida` o `recibida_parcial`), y las otras dos situaciones son
valores explícitos —`en_camino`, `canceladas`, `todas`— y no una omisión silenciosa. Quien
quiera la cifra del catálogo la obtiene eligiendo `todas`, y sabiendo lo que está mirando.

**Qué habría roto.** OTD-COM-11 existe **para comparar proveedores**, y con las 259 líneas el
ranking se invierte por completo:

```
                                    solo entregadas        todas
Comercial El Costeno .............. 99,71 %  (el mejor)   91,77 %  (el PEOR)
Distribuidora Deportiva Andina .... 94,30 %  (el peor)    94,08 %
```

Comercial El Costeno pasa de ser el proveedor más fiable al menos fiable **habiendo dejado de
servir 27 unidades**: lo hunden 4 órdenes que Compras canceló (352 uds) y 3 que todavía vienen
de camino (455 uds) — 807 unidades que él nunca tuvo que entregar, contra las 27 que sí falló.
El informe no da error, las sumas cuadran, y la conclusión —«dejemos de comprarle»— es
exactamente la contraria a la correcta.

---

### Reincidencia de la Fase 5

**El alias de un agregado no puede llamarse como su columna.** Tercera fase consecutiva. Aquí
costó el KPI de OTD-COM-03: `countIf(a_tiempo = 1) AS a_tiempo` seguido de
`sumIf(monto, a_tiempo = 0)` da `ILLEGAL_AGGREGATION`, y el informe respondió 500 — que es la
degradación correcta y la que hizo visible el fallo en la primera prueba por API. Los conteos de
ese resumen van prefijados con `n_`.

**Dos trampas NUEVAS, del lado de Java y no de ClickHouse**, que conviene dejar escritas porque
las dos producen SQL sintácticamente roto en tiempo de EJECUCIÓN y ninguna la ve el compilador:

1. **Un bloque de texto de Java recorta el espacio final de cada línea.** `"""SELECT """ + col`
   produce `SELECTpr.razon_social`. La parte variable de un SELECT se concatena con una cadena
   normal, no con un bloque de texto.
2. **`String.formatted()` interpreta el contenido ENTERO del bloque, comentarios incluidos.** El
   patrón de fecha de ClickHouse `'%d/%m/%Y'` hay que escribirlo `'%%d/%%m/%%Y'`… y el comentario
   que lo explicaba, con un «%d» suelto dentro, tumbó la consulta con
   `IllegalFormatConversionException: d != java.lang.String`. La explicación de la trampa fue la
   trampa.

---

# Apéndice · Supuestos del diseño que SÍ se sostuvieron

Registrar solo los fallos daría una impresión falsa. Éstos se verificaron con el mismo rigor y
resultaron correctos:

| Supuesto | Sección | Verificación |
|---|---|---|
| Ninguna OC tiene dos recepciones ni dos facturas | §5.4 | máx. 1 recepción/OC · 0 OC multifactura · 0 factura con 2 CxP |
| No existe estado `'aprobada'`: aprobar deja la orden en `'confirmada'` | §5.4 | estados reales: `recibida` 767 · `recibida_parcial` 72 · `cancelada` 13 · `confirmada` 7 · `enviada` 6 |
| 865 órdenes · 839 recepciones · 849 con fecha esperada · 825 pares comparables | §9.4 | exactos, los cuatro |
| Facturas de compra por $22.467.387,27 | §9.4 | exacto al centavo |
| 2.949 líneas de compra, 2.855 con recepción | §5.5 | exactos |
| `dim_proveedor` = 11 filas | §4.4 | 11, y las 865 OC se reparten entre las 11 sin huérfanas |
| `dias_credito` es la referencia del vencimiento pactado | §4.4 | `cxp.fecha_vencimiento = factura.fecha_emision + proveedor.dias_credito` en las **839** |
| Un producto tiene una sola categoría principal | §4.2 | máx. 1 categoría/producto · 1.221 filas sin fan-out |
| `transaccion_pago` es 1:1 con `pago` | §5.3 | los 4.079 pagos tienen exactamente una transacción |
| `uso_cupon` es 1:1 con el pedido | §5.1 | 564 filas / 564 pedidos distintos |
| El orden invertido de `fact_compra_linea` | §5.5 | confirmado como necesario: COM-12 es una serie por producto |
| El kardex cubre **1.406 pares (variante, bodega)** = las 1.406 posiciones de `inventario` | §5.6 | exacto, y **sin huérfanos en ninguna dirección** (0 pares sin posición, 0 posiciones sin historia) |
| 13.287 movimientos y los **9 tipos** en uso | §5.6 | exactos |
| **El atajo de la reconstrucción**: leer `stock_nuevo` en vez de recalcular | §5.7 | la cadena está íntegra en los 13.287: la ecuación `stock_nuevo = stock_anterior + cantidad×factor` se cumple siempre, ningún eslabón roto, todas las cadenas arrancan en 0, cero saldos negativos. Y Σ(cantidad×factor) por par = `inventario.stock_actual` en los **1.406** |
| El desempate `(fecha, movimiento_id)` de `argMax` | §5.7 | necesario: hay movimientos con la misma marca de tiempo dentro de un par |
| Partición por AÑO y no por mes en `fact_stock_mensual` | §5.7 | confirmado: 19 particiones de ~1.100 filas serían demasiado pequeñas |
| **`fact_envio` necesita grano propio: hay pedidos con varios envíos** | §5.8 | confirmado, y es lo que impide fundirla en `fact_pedido`: **máx. 4 envíos** en un pedido (PED-20260705-86955), 2 pedidos con más de uno |
| **La resolución de zona por precedencia ciudad > provincia > país** | §5.8 | los cuatro conteos **exactos**: 181 ciudad · 596 provincia · 2.078 país · 17 sin dirección = 2.872 |
| 2.872 envíos · 2.727 entregados · 2.723 con ambas fechas · 2.848 con costo | §5.8 | exactos, los cuatro |
| Todo envío se liga a un pedido de `fact_pedido` | §5.8 | **0 huérfanos**, en las dos direcciones del control cruzado |
| 176 novedades, hasta 3 por envío, sobre los 5 tipos del CHECK | §5.9 | exactos: 173 envíos afectados, `intento_numero` de 1 a 3, los 5 tipos en uso |
| `novedad_envio.pedido_id` no diverge del `envio.pedido_id` | §5.9 | coinciden en las 176 (se carga el del envío igualmente: el enlace canónico es ese) |
| «Resuelta» por fecha y «resuelta» por estado dicen lo mismo | §5.9 | **0** en estado `resuelta` sin fecha · **0** en `abierta` con fecha |

**Cuadre contable cruzando el almacén** (el control que ata las tres tablas de esta fase con
`fact_flujo_caja`, cargada en la Fase 2):

```
Σ facturas de compra ........... $22.467.387,27
Σ pagos a proveedor ............ $16.084.462,74
                                 ───────────────
                        saldo     $ 6.382.924,53
Σ cuenta_por_pagar.saldo_pendiente $ 6.382.924,53      ← diferencia $0,00
```

---

## Cómo se añade una entrada

1. Se detecta el supuesto fallido **durante la implementación**, no después.
2. Se mide contra PostgreSQL. **Una entrada sin cifra no entra.**
3. Se escribe aquí con las cinco filas del formato, y **la quinta —«qué habría roto»— es
   obligatoria**: si no se puede nombrar el informe concreto que habría salido mal, probablemente
   no es una corrección sino una nota de implementación, y su sitio es el docstring del módulo.
4. Se añade al índice con su gravedad.
5. Se enlaza desde el docstring del módulo que la descubrió, para que quien lea el código encuentre
   el porqué y quien lea la bitácora encuentre el código.
