# Correcciones al diseño del ETL — bitácora de supuestos que no se sostuvieron

> Documento vivo. Acompaña a `DISENO_ETL_CLICKHOUSE.md` **y a
> `DISENO_NIVEL_ESTRATEGICO.md`**, y los **corrige**; no los sustituye.
> Última actualización: **2026-08-17** (Fase 6 — las cinco correcciones que provocó el **USO real de
> la aplicación**: una orden recibida en dos actos y un producto sin peso. Clase nueva de supuesto
> fallido; ver la cabecera de la Fase 6).

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

De los **62 supuestos fallidos** registrados, la mayoría comparte una forma:

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
| [CE1.1](#ce11-el-embudo-del-recorrido-no-es-monótono-si-se-cuenta-el-hito-a-secas) | E1-A | El embudo se cuenta con `countIf(fecha_X IS NOT NULL)` (estratégico §4, T-1) | 🔴 **fuga del 26 % en la etapa equivocada** |
| [CE1.2](#ce12-la-tasa-de-rechazo-del-cobro-en-línea-no-es-computable-el-intento-fallido-no-tiene-canal) | E1-A | «Tasa de rechazo del cobro **en línea**» (estratégico §4, T-1) | 🔴 **0 % de rechazo en los tres canales** |
| [CE1.3](#ce13-las-dos-cifras-del-pareto-del-diseño-están-calculadas-sobre-los-pedidos-cancelados) | E1-A | 49,34 % / 68,76 % del top de clientes (estratégico §3.3) | 🟡 dos denominadores con el mismo nombre |
| [CE1.4](#ce14-el-fan-out-de-c44-son-4-filas-no-3) | E1-A | El fan-out de C4.4 es 344 → 347 (estratégico §4, T-3) | 🟡 corrección de una corrección |
| [CE1.5](#ce15-factura_venta_detallemonto_descuento-no-es-el-cupón-incluye-la-promoción) | E1-A | El campo de la factura ES la capa de cupón (estratégico §4, T-2) | 🟠 **la promoción contada dos veces** |
| [CE1.6](#ce16-la-categoría-de-producto-no-se-puede-filtrar-a-grano-de-pedido) | E1-A | T-1 se filtra por categoría (estratégico §4, T-1) | 🟠 filtro mudo o importe mezclado |
| [CE2.1](#ce21-dim_fecha-no-tiene-fecha_carga-y-todo-el-sistema-da-por-hecho-que-sí) | E1-B | Las 19 tablas llevan `fecha_carga` (ETL §6.2) | 🔴 **el tablero que vigila el dato no abre** |
| [CE2.2](#ce22-preparación-en-los-tramos-del-ciclo-es-el-hito-preparado-no-en_preparacion) | E1-B | «preparación» = el estado `en_preparacion` | 🟡 nombre con dos lecturas, 15 pedidos |
| [CE2.3](#ce23-el-embudo-del-retorno-al-almacén-termina-en-cero-y-no-es-un-fallo-del-dato) | E1-B | El retorno al almacén acaba en `fact_devolucion` (estratégico §4, T-4) | 🟠 **120 pedidos sin rastro posterior** |
| [CE2.4](#ce24-sumar-filas_escritas-de-una-corrida-duplica-el-total) | E1-B | `etl_ejecucion` solo guarda tareas de tabla (estratégico §4, T-7) | 🔴 **el doble de filas y una alarma falsa** |
| [CE3.1](#ce31-los-métodos-de-previsión-son-cuatro-y-no-tres) | E2 | `metodo` tiene tres valores (estratégico §5.1.7) | 🟡 dos categorías con una previsión inventada |
| [CE3.2](#ce32-k--2-encoge-demasiado-y-suspende-el-criterio-de-aceptación-del-propio-diseño) | E2 | El encogimiento estacional es `k ≈ 2` (§5.1.3) | 🔴 **banda al 100 %: el modelo se rechaza a sí mismo** |
| [CE3.3](#ce33-la-cobertura-medida-sobre-6-puntos-no-distingue-nada) | E2 | La cobertura se juzga sobre el backtest del total (§5.1.6) | 🔴 **criterio de rechazo sin poder de decisión** |
| [CE3.4](#ce34-el-mes-truncado-no-sobrevive-a-la-publicación) | E2 | La tabla de §5.1.7 basta para la pantalla | 🟠 **un artefacto del corte leído como caída del negocio** |
| [CE3.5](#ce35-la-banda-no-se-ensancha-siempre-y-exigirlo-aborta-la-carga) | E2 | «La banda se ensancha con el horizonte» (§5.1.9, regla 5) | 🟠 15 series abortan la publicación con razón |
| [CE3.6](#ce36-el-informe-de-previsión-no-cabe-en-0-clases-java) | E2 | 0 clases Java nuevas (§5.1.8) | 🟡 dos copias del mismo modelo divergiendo |
| [CE4.1](#ce41-la-ventana-estable-escrita-como-fecha-caduca-en-la-corrida-siguiente) | E3 | «la ventana desde enero de 2026» (§5.2.10) | 🔴 **la alerta se INVIERTE: el 2.º cliente sale como el más perdido** |
| [CE4.2](#ce42-el-lift-no-se-divide-por-una-tasa-base-constante-y-un-origen-no-tiene-ninguna) | E3 | Lift = precisión@10 ÷ **9,4 %** (§5.2.6) | 🔴 **lift falso en dos de los tres orígenes** |
| [CE4.3](#ce43-la-ventana-de-entrenamiento-del-backtest-tiene-que-rodar-con-el-origen) | E3 | «3 orígenes dentro de la ventana estable» (§5.2.6) | 🟠 se mide un estimador que nunca se publica |
| [CE4.4](#ce44-el-lift-no-sale-10-sale-199--y-sin-su-valor-p-es-un-titular-falso) | E3 | «la expectativa razonable es lift ≈ 1,0» (§5.2.6) | 🔴 **un resultado del azar publicado como éxito** |
| [CE4.5](#ce45-los-clientes-sin-muestra-son-los-candidatos-más-fuertes-y-el-modelo-los-expulsa) | E3 | λ se calcula sobre los clientes con historia (§5.2.3) | 🔴 **el que se fue de verdad no aparece jamás** |
| [CE4.6](#ce46-el-recorte-del-vendedor-no-puede-hacerse-con-el-mismo-mecanismo-de-otd-ven-02) | E3 | «el mismo mecanismo de OTD-VEN-02» (§5.2.8) | 🟠 el almacén no tiene `vendedor_id` |
| [CE4.7](#ce47-la-foto-fechada-no-acumula-historia--y-el-backtest-no-la-necesita) | E3 | Sin `fecha_calculo` el backtest no tiene contra qué medirse (§5.2.7) | 🟡 justificación falsa de una columna correcta |
| [CE4.8](#ce48-las-dependencias-declaradas-no-producen-dos-columnas-que-la-propia-tabla-exige) | E3 | `depende_de = {fact_pedido, dim_cliente}` (§5.2.5) | 🟠 **dos columnas de contexto a cero, en silencio** |
| [C6.1](#c61-una-orden-de-compra-puede-tener-dos-recepciones-y-ya-las-tiene) | 6 | «máx. recepciones por OC = 1» (medido, C3.1) | 🔴 **el grano se rompe: +1 orden, +$2.415** |
| [C6.2](#c62-el-lateral-de-agregados-fanea-por-dentro-y-el-conteo-de-filas-no-lo-ve) | 6 | «No hay fan-out posible» en el LATERAL del detalle | 🔴 **+4 líneas y +48 unidades en una tabla con el grano correcto** |
| [C6.3](#c63-recepcion_detalle-no-es-11-con-la-línea-de-la-orden) | 6 | `recepcion_detalle` es 1:1 con `orden_compra_detalle` | 🔴 **+1 fila, +12 uds, +$1.020** |
| [C6.4](#c64-un-null-legítimo-contra-una-columna-decimal-no-nullable-tumba-la-carga-sin-decir-dónde) | 6 | `peso_kg` siempre poblada (lo estaba, en el seed) | 🔴 **la tabla no se publica y el error no nombra la columna** |
| [C6.5](#c65-el-control-de-motivos-reimplementaba-el-mapa-pero-no-la-regla-de-escape) | 6 | El control traduce el sinónimo y con eso basta | 🟠 **control que acierta por casualidad** |

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

# Fase E1-A — los tres primeros tableros de dirección

> Primera fase del **nivel estratégico**. **No carga ni una fila**: monta T-1 (Omnicanal), T-2
> (Rentabilidad y Rotación) y T-3 (Cliente y Posventa) sobre las 19 tablas ya validadas.
>
> A partir de aquí la bitácora corrige **dos** documentos: `DISENO_ETL_CLICKHOUSE.md` y
> `DISENO_NIVEL_ESTRATEGICO.md`. Cada entrada dice cuál.
>
> El patrón cambia de forma en este nivel y conviene decirlo. En las fases de carga el supuesto
> fallido era casi siempre **una columna que responde otra pregunta**. Aquí aparece uno nuevo:
> **una cifra correcta calculada sobre una población distinta de la que el lector supone**. No hay
> JOIN que multiplique ni suma que no cuadre — hay un denominador que nadie escribió.

## CE1.1 · El embudo del recorrido NO es monótono si se cuenta el hito a secas

| | |
|---|---|
| **Fase** | E1-A (T-1, elemento «Embudo del recorrido») |
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-1: «Embudo de 5 pasos · `fact_pedido` · `countIf(fecha_pagado IS NOT NULL)`, **etc.**». El «etc.» es la trampa: invita a repetir el mismo patrón con `fecha_facturado`, `fecha_despachado` y `fecha_entregado`. |

**La realidad.** Contando la marca de tiempo de cada hito, los pasos NO decrecen:

```
creados ............................................ 4.083
con fecha_pagado ................................... 3.906
con fecha_facturado ................................ 3.872
con fecha_despachado ............................... 2.868   ← se hunde
con fecha_entregado ................................ 3.696   ← y se recupera
   pedidos ENTREGADOS sin marca de despacho ..........  969
```

**969 pedidos llegaron a entregarse sin que nadie registrara la hora del despacho.** No es un dato
nuevo —la Fase 2 ya lo midió para OTD-LOG-12, que declara `pedidos_medidos` distinto en cada
etapa— pero allí producía un promedio sobre menos casos, y aquí produce **una fuga que no existe**.

Dibujado tal cual, el embudo enseña una caída del **26 %** en el despacho (2.868 sobre 3.872) que
se «recupera» sola en la entrega. Un embudo cuyo cuarto escalón es más estrecho que el quinto no
es un embudo: es una figura imposible que un ojo entrenado leería como el cuello de botella.

**Cómo se resolvió.** Cada paso cuenta a los pedidos que alcanzaron **ese hito o cualquiera
posterior**: un pedido entregado pasó por el despacho aunque nadie apuntara la hora. La serie sí
decrece, y el bloque lo DECLARA en su salvedad con el número de registros incompletos:

```
Pedido creado 4.083 → Cobrado 3.907 → Facturado 3.884 → Despachado 3.837 → Entregado 3.696
```

Lo que el registro incompleto sí impide es medir el **tiempo** de esa etapa, y eso se sigue
haciendo aparte con su propio denominador. `validar_tableros.py` comprueba la monotonía como un
control más: si un paso sube respecto del anterior, la verificación falla.

**Qué habría roto.** D-06.2 es literalmente «cuál de los tres puntos de caída se ataca este
trimestre». Con el embudo de hitos, la respuesta sería «el despacho», que es la etapa donde el
problema no está: no se pierden 1.004 pedidos ahí, se pierden **47**. La inversión iría a reforzar
una operación que funciona mientras la fuga real —los 176 del primer escalón, de los que 159 son
cancelaciones— sigue intacta. Es la misma clase de error que C2.7, un nivel más arriba: allí
falseaba un promedio, aquí falsea la forma del embudo.

---

## CE1.2 · La «tasa de rechazo del cobro EN LÍNEA» no es computable: el intento fallido no tiene canal

| | |
|---|---|
| **Fase** | E1-A (T-1, KPI de cabecera) |
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-1, KPI: «venta del período · ticket medio · % de la venta por web · **tasa de rechazo del cobro en línea** · clientes omnicanales». Y §3.1, D-06.3, da por sentado que `fact_flujo_caja` «ya trae los 176 intentos fallidos con su motivo normalizado» — lo cual es cierto, pero no dice nada del canal. |

**La realidad.** El canal de un cobro **no es una columna de `pago`**: sale del PEDIDO
(`COALESCE(p.canal, '')` en el extractor). Y un intento rechazado no tiene pedido:

```
intentos de cobro fallidos ......................... 176
  sin pedido_id .................................... 176   (el 100 %)
  y por tanto sin canal ........................... 176   (el 100 %)
```

Es la **misma causa** que C2.1 ya registró para la fecha —el intento se anota antes de que el
pedido exista— aplicada a otra columna. La consecuencia, en cambio, es peor: una fecha ausente
vacía un informe y se nota; un canal ausente produce **una tasa de rechazo del 0,00 % en los tres
canales**, que es una cifra perfectamente plausible y perfectamente falsa.

**Cómo se resolvió.** El KPI publica la tasa **global** —176 sobre 4.079 intentos, **4,31 %**— y
su nota dice por qué no está partida por canal. Además, el **filtro de canal del tablero no se
aplica al bloque de cobros rechazados**, y el bloque lo declara: aplicándolo, los 176 se irían al
suelo y la lectura sería «en este canal no hay rechazos».

**Qué habría roto.** D-06.3 decide «qué medios de cobro se ofrecen, se retiran o se renegocian».
Con la tasa por canal, la tienda en línea —el canal que concentra el 53,87 % de la venta y **el
100 % de los rechazos reales**, porque los tres canales internos cobran contra un pedido ya
creado— aparecería con cero rechazos. La conclusión sería que la pasarela funciona perfectamente y
que no hay nada que renegociar.

---

## CE1.3 · Las dos cifras del Pareto del diseño están calculadas sobre los pedidos CANCELADOS

| | |
|---|---|
| **Fase** | E1-A (T-3, curva de valor del cliente) |
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §3.3: «el top 10 % de clientes (7 de 69) pone el **49,34 %** del ingreso y el top 20 % (14) el **68,76 %**». La misma sección §1 usa como cifra de venta $5.716.436,55 en unos sitios y $5.498.570,35 en otros. |

**La realidad.** Las dos cifras salen de sumar **todos** los pedidos, cancelados incluidos. Sobre
la base que el propio tablero usa —y que el diseño declara correcta en todas partes: los
cancelados miden intención, no venta— dan otra cosa:

```
base                        clientes   ingreso        top 7      top 14
todos los pedidos              69     $5.716.436,55   49,34 %    68,76 %   ← las cifras del diseño
solo los NO cancelados         69     $5.498.570,35   49,05 %    68,69 %   ← lo que publica T-3
```

La diferencia es de **0,29 y 0,07 puntos**: irrelevante para la conclusión de negocio, y
exactamente por eso peligrosa. Nadie la habría notado, y el documento habría quedado con dos
denominadores conviviendo bajo el mismo nombre —«el ingreso»— a tres párrafos de distancia.

**Cómo se resolvió.** T-3 excluye los cancelados en TODO el tablero, el KPI publica **68,69 %** y
su nota escribe el numerador, el denominador y el número de clientes que entran en el corte.
`validar_tableros.py` recalcula el corte igual que el tablero (`ceil(0,2 × n)`) y compara.

**Qué habría roto.** Nada por sí sola: la decisión D-08.2 —dónde se pone el corte de cliente
preferente— no cambia por siete centésimas. Lo que rompe es **la confianza en el propio
documento**: si dos cifras del mismo párrafo usan bases distintas sin decirlo, ninguna otra cifra
del documento se puede usar sin volver a medirla. Se registra por eso, y no por su magnitud.

---

## CE1.4 · El fan-out de C4.4 son 4 filas, no 3

| | |
|---|---|
| **Fase** | E1-A (T-3, calificación por producto) |
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-3: «⚠️ **JAMÁS unir a `dim_producto`** (C4.4): la dimensión es por variante y multiplicaría **344 → 347** sin error». |

**La realidad.** La advertencia es correcta; la cifra no. Medido hoy:

```
reseñas en la tabla ............................ 344
tras unir a la dimensión por producto_id ....... 348   (+4)
productos con más de una variante ...............  7   (5 con dos, 2 con tres)
```

`5 × 1 + 2 × 2 = 9` variantes sobrantes; caen 4 reseñas sobre productos de ese grupo, de ahí el +4.

**Cómo se resolvió.** El bloque se sirve **solo** de `fact_resena`, que ya denormaliza nombre,
categoría y marca precisamente para que este JOIN no haga falta. `validar_tableros.py` mide las
dos cifras y publica la distancia en la salida, para que la próxima vez no haya que redescubrirla.

**Qué habría roto.** Lo mismo que C4.4 anunció —el ranking de calificación y su nota media—, y por
eso no es una corrección nueva sino una **corrección de la corrección**. Se registra porque una
bitácora cuyo cometido es evitar que se repita un error no puede llevar dentro un número
equivocado: el que compare 344 con 347 dará por buena una diferencia de una fila.

---

## CE1.5 · `factura_venta_detalle.monto_descuento` NO es el cupón: incluye la promoción

| | |
|---|---|
| **Fase** | E1-A (T-2, descuento entregado) |
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-2: «Descuento entregado por mes y categoría · `fact_venta_linea` · `descuento_promocion` + `descuento_cupon_prorrateado`», presentándolas como «dos capas» independientes. Y §5.1 del diseño del ETL habla del cupón «prorrateado en `factura_venta_detalle.monto_descuento`». |

**La realidad.** El descuento de la línea de FACTURA es el descuento **total** de esa línea —la
promoción ya está dentro—, y el cupón prorrateado se obtiene **despejándolo**:

```
cupon = GREATEST(factura_venta_detalle.monto_descuento − pedido_detalle.monto_descuento, 0)
```

Tomando el campo de la factura tal cual como «capa de cupón», la promoción se cuenta **dos veces**:

```
capa de promoción ..................................  $5.384,09
capa de cupón, despejada ........................... $50.332,99   ← correcto
capa de cupón, leyendo el campo a secas ............ $55.717,08   ← +$5.384,09
descuento entregado total .......................... $55.720,57  vs  $61.104,66  (+9,66 %)
```

Y la venta neta cae en la misma cantidad, porque se resta un descuento que no existe.

**Cómo se resolvió.** El tablero **no toca la factura**: lee `fact_venta_linea`, donde el ETL ya
hizo el despeje. La entrada se registra porque **el error lo cometió la verificación**: la primera
versión de `validar_tableros.py` reprodujo la fórmula «obvia» y acusó al tablero de una diferencia
de $5.384,09 que era del propio control. Un verificador equivocado es peor que no tenerlo —habría
llevado a «corregir» un tablero correcto—, y por eso su fórmula lleva ahora el despeje comentado.

**Qué habría roto.** D-07.3 fija el techo de descuento del trimestre. Con las dos capas mal
separadas, el cupón parecería un **9,7 %** más caro de lo que es y la promoción, invisible dentro
de él: se recortaría la palanca equivocada. Y como el descuento arrastra su IVA —el total cae
1,15× lo descontado—, el efecto de recortar la palanca que no era tarda un trimestre en verse.

---

## CE1.6 · La categoría de producto no se puede filtrar a grano de pedido

| | |
|---|---|
| **Fase** | E1-A (T-1, filtros) |
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-1: «**Filtros**: rango de meses · canal · **categoría**», sobre un tablero cuyos seis elementos salen de `fact_pedido` y `fact_flujo_caja`. |

**La realidad.** Ninguna de las dos tablas tiene categoría, y no puede tenerla: la categoría es un
atributo de la **línea**, y un pedido mezcla varias. Las dos salidas ingenuas fallan de formas
distintas:

- **Ignorar el filtro**: la pantalla ofrece un campo que no hace nada. Al escribir «Belleza» las
  cifras no cambian y se leen como «Belleza es todo el negocio».
- **Aplicarlo con un semi-join y sumar el pedido entero**: el pedido entra completo, con las demás
  categorías dentro, y la «venta de Belleza» incluye lo que no es Belleza.

**Cómo se resolvió.** Se aplica el semi-join —un pedido entra si contiene **al menos una línea** de
esa categoría— y el tablero **declara la consecuencia en una salvedad que solo aparece cuando el
filtro está puesto**: «el pedido entra ENTERO y sus importes incluyen las demás categorías; para el
reparto exacto por categoría, T-2 mide a grano de línea». Un embudo no admite medias unidades: un
pedido está en el paso o no está, y por eso la alternativa de repartir proporcionalmente ni siquiera
existe aquí.

**Qué habría roto.** D-06.1 reparte capacidad —personal de mostrador, líneas de teléfono— entre
canales. Filtrado por una categoría pequeña, el importe arrastrado de las demás categorías del
mismo pedido infla su peso, y un canal que vende esa categoría junto a otras parece
desproporcionadamente fuerte en ella. La salvedad convierte un dato engañoso en un dato acotado.

---

### Reincidencias declaradas, sin número propio

**C3B.5 (`lagInFrame` rellena con el DEFECTO del tipo, no con NULL) vuelve en T-2.** La serie de
capital inmovilizado necesita la variación mes a mes, y con la comprobación «hay valor anterior si
`anterior != 0`» el primer mes publicaría su capital entero como una subida del +100 %. La frontera
se marca con `row_number() > 1`, igual que en COM-12, y `validar_tableros.py` comprueba
explícitamente que el primer mes llega con la variación **NULA**.

**El alias de agregado con el nombre de su columna (Fase 1) reincidió SEIS veces en una sola
tarde.** `sum(monto) AS monto`, `sum(venta_neta) AS venta_neta`, `sum(descuento_total) AS
descuento_total`, `sum(unidades_reingresadas) AS unidades_reingresadas` y `any(corte) AS corte`
producen todos `ILLEGAL_AGGREGATION` en cuanto una ventana o un porcentaje vuelven a nombrar la
columna: ClickHouse sustituye el alias por su definición y anida el agregado. La regla operativa,
ya sin excepciones, es **prefijar `t_` todo alias de agregado y reponer el nombre del contrato en
el SELECT exterior**. No llega a corrección porque revienta con un error explícito y en la primera
petición: es ruidoso, que es justo lo contrario de lo que esta bitácora persigue.

**`devolucion_detalle` no guarda la variante.** Se llega a ella por `pedido_detalle`. Es una nota
de implementación y no una corrección —el JOIN inexistente da un error de columna, no una cifra
equivocada—, pero se apunta porque el cruce «producto que reclama y devuelve» de T-3 y cualquier
verificación de devoluciones por producto tienen que pasar por ahí.

**`24:00:00` es una hora válida en PostgreSQL y psycopg2 la convierte a `00:00` en silencio.**
No afecta a ningún tablero —no toca el almacén—, pero mordió a la prueba de la matriz rol ×
tablero y merece quedar escrito. La cobertura 24/7 del rol SOPORTE está declarada así:

```
grupo_horario id 64 · grp_soporte · sábado · 00:00:00 → 24:00:00
```

`datetime.time` de Python no puede representar las 24:00, así que el driver la entrega como
`00:00`. El script ensanchaba la ventana horaria para poder probar a GERENTE y ANALISTA un sábado
por la tarde y la restauraba en un `finally`; al devolver el valor leído, `00:00 → 00:00` violó el
`CHECK (hora_inicio < hora_fin)` y **la restauración reventó A MITAD**, con seis filas ya devueltas
y la séptima abierta. El fallo fue ruidoso, se detectó y se corrigió a mano en el momento — pero el
patrón es el de siempre: *leer un valor y volver a escribirlo* parece una operación neutra y no lo
es cuando el driver no puede representar el valor. Las horas se leen y se escriben con `::text` en
los dos sentidos, y la restauración se **verifica releyendo** antes de darla por buena.

---

# Fase E1-B — los cuatro tableros de dirección restantes

> Cierra el nivel estratégico de tablero: **T-4 Operación y Última Milla**, **T-5 Costo de la
> Operación**, **T-6 Abastecimiento** y **T-7 Gobierno del Dato**. Con los tres de E1-A son
> **7 tableros y las 19 decisiones de dashboard**. **No carga ni una fila** y **no crea ninguna
> tabla**: las 19 del almacén bastaron, como el diseño anticipó.
>
> Aparecen cuatro supuestos fallidos y los cuatro son del mismo tipo nuevo que estrenó E1-A: **una
> cifra correcta calculada sobre una población distinta de la que el lector supone**, o **un
> nombre que significa otra cosa**. Ninguno rompe un JOIN. Dos de ellos —CE2.2 y CE2.4— los cazó
> la verificación y no el código, que es exactamente para lo que existe.

## CE2.1 · `dim_fecha` no tiene `fecha_carga`, y todo el sistema da por hecho que sí

| | |
|---|---|
| **Fase** | E1-B (T-7, antigüedad del dato) |
| **El diseño decía** | `DISENO_ETL_CLICKHOUSE.md` §6.2 establece `fecha_carga` como columna de control de TODAS las tablas del modelo, y el nivel estratégico se apoya en ello sin excepción: la marca de agua «Datos al …» de los siete tableros sale de un `max(fecha_carga)`, y `DISENO_NIVEL_ESTRATEGICO.md` §4 pide para T-7 «antigüedad del dato · `etl_ejecucion` + **`fecha_carga` de cada tabla**». |

**La realidad.** De las 19 tablas del modelo, **18 llevan el sello y una no**:

```
tablas del modelo ....................................... 19
  con fecha_carga ....................................... 18
  SIN fecha_carga ....................................... 1   ← dim_fecha
```

`dim_fecha` es el calendario y se **GENERA dentro de ClickHouse** con `numbers()`: nunca consulta
PostgreSQL, así que no hay carga que sellar. La consecuencia no es un nulo — es un error duro:

```
Code: 47. DB::Exception: Unknown expression or function identifier `fecha_carga`
in scope SELECT 'dim_fecha' AS tabla, max(fecha_carga) ... (UNKNOWN_IDENTIFIER)
```

**Cómo se resolvió.** El cálculo de frescura corre sobre las **18 con sello** y el calendario se
publica igualmente en la lista, con su casilla de antigüedad **vacía** y marcado como generado en
el almacén. No se omite: una lista de 18 tablas en el tablero que existe para vigilar las 19
invitaría a preguntarse cuál falta y por qué. Y la marca de agua conjunta —el `min` de los `max`—
también excluye el calendario, que no puede quedarse rezagado.

**Qué habría roto.** T-7 entero, y de la peor manera para lo que ese tablero es: la consulta
revienta, el servicio la propaga como 500 —correctamente, porque no es una caída de la analítica—
y el **tablero cuyo trabajo es avisar de que el dato no es confiable se queda sin responder**. La
única pantalla que puede detectar una carga fallida no se abre. Los otros seis tableros no lo
notaron porque su marca de agua nombra solo las tablas que ellos leen, y ninguno lee el
calendario.

---

## CE2.2 · «Preparación» en los tramos del ciclo es el hito `preparado`, no `en_preparacion`

| | |
|---|---|
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-4: «Tiempo por etapa del ciclo · `fact_pedido` · **Los 4 tramos** · cada fila declara `pedidos_medidos` (2.868 / 2.856 / 2.727 / 3.696)». Las columnas se llaman `horas_pago_a_preparacion` y `horas_preparacion_a_despacho`, y el ciclo del pedido tiene un estado llamado literalmente `en_preparacion`. |

**La realidad.** «Preparación» en esos dos nombres significa el hito **`'preparado'`** —picking
TERMINADO— y no `'en_preparacion'` —picking EMPEZADO—. Son dos hitos distintos y no coinciden:

```
pedidos con hito 'en_preparacion' y cobro .............. 2.883
pedidos con hito 'preparado'     y cobro .............. 2.868   ← el del tramo
                                                          −15
```

La elección es correcta y está razonada en el ETL: con `preparado`, cada tramo empieza exactamente
donde acaba el anterior y los tres cubren el ciclo sin huecos ni solapes. Lo que falla es el
NOMBRE, que admite las dos lecturas.

**Cómo se resolvió.** Las etiquetas del tablero dejan de decir «preparación»: ahora son **«Del
cobro al picking terminado»** y **«Del picking terminado al despacho»**, y la salvedad del bloque
lo escribe con las dos cifras. El nombre de la columna del almacén no se toca —está cargado y
validado— pero la pantalla ya no hereda su ambigüedad.

**Qué habría roto.** Nada visible, y por eso está aquí: 15 pedidos sobre 2.868 mueven el promedio
del tramo en la tercera cifra decimal. Lo que sí rompió fue **la verificación**: el control de
`validar_tableros.py` replicó el tramo con `en_preparacion` —la lectura natural del nombre— y
acusó al tablero de una diferencia de 15 pedidos que era del propio control. Es la segunda vez que
pasa (CE1.5 fue la primera): cuando un verificador independiente y el código difieren, lo primero
que hay que preguntarse es cuál de los dos entendió mal el nombre.

---

## CE2.3 · El embudo del retorno al almacén termina en CERO, y no es un fallo del dato

| | |
|---|---|
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-4: «Devoluciones al almacén por incidencia · **Embudo** · `fact_novedad_envio` **+ `fact_devolucion`** · Directa». Nombrar las dos tablas da por hecho que la mercancía devuelta al almacén acaba en una devolución registrada. |

**La realidad.** No hay ni una:

```
envíos despachados ................................... 2.872
con alguna incidencia de entrega ......................  173
devueltos al almacén ..................................  120
pedidos que quedaron sin entregar .....................  120
   con una devolución registrada después ..............    0   ← el JOIN da cero
```

Y es coherente con cómo está construido el sistema: devolver al almacén **no reingresa stock** —el
kardex solo se mueve tras la inspección de bodega, criterio heredado del RMA— y el reembolso queda
pendiente de soporte. O sea que **la mercancía de 120 pedidos volvió físicamente y no existe en
ningún registro posterior**: ni está disponible para vender, ni consta que se haya devuelto el
dinero al cliente.

**Cómo se resolvió.** El paso se publica **con su cero** y con una nota que explica qué significa,
en vez de omitirse por estar vacío. La salvedad del bloque lo dice entero. El embudo se verifica
además como monótono, igual que el de T-1 (CE1.1).

**Qué habría roto.** Las dos salidas alternativas eran peores que el cero. Omitir el paso —lo
natural cuando un JOIN no devuelve nada— habría dejado un embudo de cuatro escalones que termina
en «120 pedidos sin entregar» y se lee como un final ordenado. Publicarlo sin nota habría dado un
cero mudo, que en un tablero se lee como «aquí no pasa nada». **La brecha ES el hallazgo**: es
exactamente lo que D-09.4 —dónde se ataca la pérdida física— tiene que ver, y el diseño la había
supuesto cubierta.

---

## CE2.4 · Sumar `filas_escritas` de una corrida DUPLICA el total

| | |
|---|---|
| **El diseño decía** | `DISENO_NIVEL_ESTRATEGICO.md` §4, T-7: «Salud de la última corrida del ETL · **Semáforo por tabla + histórico** · `etl_ejecucion` · **Estado, filas, excepciones y duración por tarea**» y el KPI «**tablas publicadas en la última corrida**». La lectura obvia es agrupar por tarea y sumar. |

**La realidad.** `etl_ejecucion` no guarda solo tablas. Tiene **dos pseudo-tareas**:

- **`corrida`** es el sobre de la ejecución entera, escribe **DOS filas** —una `en_curso` al
  empezar y otra `exito` al acabar— y su `filas_escritas` es **la suma de todas las tablas**;
- **`validar_dwh`** es la puerta de control, y sus «filas» son los 44 controles, no datos.

De ahí que la suma ingenua salga **exactamente al doble**:

```
suma de las 19 tablas del modelo ................. 64.085
+ fila «corrida», que repite ese mismo total ..... 64.085
+ fila «validar_dwh» (44 controles) ..............     44
                                                   ───────
suma ingenua ..................................... 128.214
```

**Cómo se resolvió.** Todos los agregados excluyen `corrida` y `validar_dwh`, y el estado de cada
tarea se colapsa con `argMax(…, inicio)` para quedarse con el ÚLTIMO. El bloque marca cada fila con
su tipo —tabla, control o corrida— para que las tres se vean sin mezclarse en ningún conteo.
`validar_tableros.py` comprueba que el KPI de filas coincide con la suma de las TABLAS y que la
fila `corrida` no se quedó en `en_curso`.

**Qué habría roto.** Dos cosas, y las dos en el tablero cuyo único trabajo es decir si el dato es
fiable. **La primera**: el KPI «filas publicadas» habría dicho 128.214 donde hay 64.085 — una
cifra perfectamente plausible que nadie mira dos veces, y que además es el DOBLE. **La segunda es
peor**: listadas en crudo, la tarea `corrida` aparece dos veces, una de ellas eternamente
`en_curso` junto a la misma tarea ya terminada. Una alarma falsa permanente en el semáforo entrena
a la dirección a ignorarlo, y a partir de ahí la alarma verdadera tampoco se ve.

---

### Reincidencias declaradas, sin número propio

**El alias de agregado con el nombre de su columna volvió a aparecer OCHO veces**, en los cuatro
tableros: `sum(costo) AS costo`, `countIf(sin_tarifa = 1) AS sin_tarifa`,
`countIf(a_tiempo = 1) AS a_tiempo`, `min(inicio) AS inicio`,
`sumIf(excepciones, …) AS excepciones` y `sumIf(filas, …) AS filas`. Con E1-A van **catorce**. La
regla ya no admite excepciones: **todo alias de agregado lleva prefijo `t_` y el nombre del
contrato se repone en el SELECT exterior**. Y hay una variante nueva que conviene nombrar aparte,
porque el arreglo intuitivo es justo el equivocado: en `argMax(resultado, inicio)` el segundo
argumento tiene que ser la **columna cruda**, no el alias del `min(inicio)` del mismo nivel —
renombrar el alias no basta, hay que dejar de usarlo ahí dentro. No llega a corrección porque
revienta con un error explícito en la primera petición: es ruidoso, que es lo contrario de lo que
esta bitácora persigue.

**C3C.3 (la lista blanca sale de los DATOS) se aplicó por adelantado y se comprobó.** Los
desenlaces de la incidencia de entrega son `reprogramada`, `devuelto_almacen` y `sin_resolver` —los
participios, no los verbos del API que declara el diseño— y los orígenes del ítem defectuoso son
`rma` y `recepcion`, no `inspeccion_rma`/`recepcion_compra`. Ninguno de los dos bloques enumera
nada: agrupan y dejan que los valores aparezcan. La verificación compara además el conjunto
publicado contra el `SELECT DISTINCT` de la base, de modo que un valor nuevo se ve en vez de
desaparecer.

---

# Fase E2 — la previsión de demanda

> El primer modelo del nivel estratégico: `fact_prevision_demanda`, la tabla **20** del almacén y
> la primera que contiene filas con **fecha futura**. Descomposición estacional con factores
> encogidos, ajustada a nivel de total y de categoría, y desagregada a la variante por cuota.
>
> Los seis supuestos fallidos de esta fase son de un tipo que no había aparecido antes. Hasta
> ahora el diseño se equivocaba sobre **los datos** —una relación 1:1 que no lo era, un enum cuyos
> valores eran otros—. Aquí se equivoca sobre **su propio método**: dos de sus decisiones
> metodológicas (el encogimiento y el criterio de cobertura) se contradicen entre sí, y aplicarlas
> juntas hace que el modelo suspenda su propio examen. Eso solo se ve ejecutándolo.
>
> Los seis se detectaron **durante la implementación**, cuatro de ellos por una validación que
> abortó la publicación. Ninguno llegó a la pantalla.

## CE3.1 · Los métodos de previsión son CUATRO y no tres

| | |
|---|---|
| **Fase** | E2 (`fact_prevision_demanda`, columna `metodo`) |
| **El diseño decía** | §5.1.7: «`metodo` · `LowCardinality(String)` · `descomposicion` / `linea_base_estacional` / `top_down_categoria`». Tres valores, cerrados. |

**La realidad.** El propio §5.1.1 clasifica dos categorías como **ruido** y dice que «se excluyen o
se agrupan»: Ropa Mujer (**9 meses con venta / 28 uds** / CV 1,358) y Ropa Hombre (**1 mes / 5
uds**). Pero las dos **existen como categoría** y aparecen en el filtro de la pantalla, así que la
tabla tiene que decir algo sobre ellas. Con los tres métodos del diseño, las únicas salidas eran
publicar una `descomposicion` sobre 9 puntos —o sobre 1— con su banda, o hacerlas desaparecer.

Y el mockup del propio §5.1.9 ya dibujaba la respuesta correcta sin darle nombre:

```
│ Ropa Mujer          2     [ — ]                 9        —    sin prev. │
```

**Cómo se resolvió.** Cuarto valor, `sin_prevision`: previsión y banda a cero, `mape` a cero,
`meses_historia` con su cifra real (9 y 1) y la pantalla escribiendo «sin previsión individual».
La lista blanca de la validación enumera los cuatro y aborta si aparece un quinto.

**Qué habría roto.** D-11.1 es el plan de compra. Una categoría con **28 unidades en 19 meses** y
una previsión con banda al lado de Abarrotes —**4.665 uds**— se lee como una serie más, con la
misma autoridad visual. Hacerla desaparecer es igual de malo por el motivo simétrico: quien filtra
por Ropa Mujer y no ve nada concluye que el filtro está roto, no que la categoría no es
previsible.

---

## CE3.2 · `k = 2` encoge demasiado, y suspende el criterio de aceptación del propio diseño

| | |
|---|---|
| **Fase** | E2 (`modelos/prevision_demanda.py`, factores estacionales) |
| **El diseño decía** | §5.1.3: «factor_mes · ratio a la media móvil centrada, ENCOGIDO: `f̂ = (n·f + k)/(n + k)` con n = observaciones de ese mes (1 o 2) y **k ≈ 2**». |

**La realidad.** Es la corrección más importante de la fase, porque con `k = 2` **el modelo no se
puede publicar**, y no por poco.

Con n(m) = 1 —los cinco meses de agosto a diciembre—, `k = 2` deja al dato **un tercio** del peso.
Medido contra el generador del seed (`60_seed_bloque_b_ventas.sql` línea 63, normalizado a media 1):

| Mes | Generador | `k = 2` | `k` estimado |
|---|---|---|---|
| Enero | 0,717 | 0,864 | **0,634** |
| Mayo | 1,195 | 1,152 | **1,325** |
| Diciembre | **1,482** | 1,075 | **1,482** |

Con `k = 2` el diciembre del modelo es **1,075 donde la verdad es 1,482**: la estacionalidad
prácticamente ha desaparecido. Y esto no se queda en el factor. Un modelo sesgado deja residuos
grandes, y la banda se calcula desde ellos:

```
                        k = 2 fijo        k estimado
σ relativo                 0,214             0,118
banda del primer mes       ±41 %             ±23 %
COBERTURA medida          100,0 %            87,6 %
MAPE total (crudo)         11,48 %            8,78 %
```

**La cobertura del 100 % SUSPENDE el criterio de rechazo n.º 3 de §5.1.6**, que exige entre 65 % y
90 % y dice, con razón, que «un intervalo que acierta el 100 % es tan malo como uno que acierta el
40 %: el primero es inútilmente ancho». Es decir: **aplicando §5.1.3 al pie de la letra, §5.1.6
rechaza el resultado.** Las dos secciones del diseño se contradicen y solo ejecutándolas se ve.

**Cómo se resolvió.** La fuerza del encogimiento **se estima de los datos** en vez de fijarla. El
peso óptimo de un encogimiento hacia la media es conocido (Stein / Bayes empírico) y equivale
exactamente a la fórmula del diseño con `k = σ²/τ²`:

- **σ²** — varianza del RUIDO de una razón, estimada DENTRO de cada mes del calendario con los seis
  meses que se repiten;
- **τ²** — varianza REAL entre los doce factores, descontando la parte que ya explica el ruido.

Sobre esta serie sale **k = 0,175**, recortado al suelo declarado de **0,25**. O sea: los datos
piden **menos** encogimiento del que el suelo permite, y el suelo se conserva a propósito como la
cautela que §5.1.3 pretendía — un mes visto una sola vez nunca entra crudo. El error medio absoluto
de los doce factores contra el generador queda en **0,048**.

Cuando no hay estacionalidad por encima del ruido, τ² tiende a cero, k se dispara al techo y todos
los factores acaban en 1 — que es la respuesta correcta. Ocurre de hecho con Ropa Mujer (k = 20).

> **Y hay un segundo hallazgo dentro de éste, que fue el que hizo falsa la primera estimación.**
> §5.1.3 dice «nivel: suavizado exponencial simple sobre la serie desestacionalizada», y calcular
> las razones dividiendo por ese nivel **filtrado** es lo natural. Es un error: un nivel local tiene
> que ser **estacionalmente neutro**, que es la propiedad por la que el método clásico usa una media
> móvil CENTRADA de doce meses. El nivel exponencial persigue la subida de mayo y la bajada de
> junio, de modo que al dividir por él la estacionalidad ya se ha comido a sí misma. Medido: la
> varianza DENTRO de cada mes sale mayor que la varianza ENTRE meses, el `k` empírico se dispara a
> su techo y los doce factores acaban **entre 0,98 y 1,02** — un modelo sin ninguna estacionalidad
> sobre una serie que la tiene escrita en el generador. La media móvil centrada tampoco sirve aquí
> (con 18 puntos solo produce razón para los 6 meses centrales, y enero a junio —donde vive la
> mayor amplitud— se quedarían sin factor). Se usa el nivel GLOBAL de la serie ya normalizada al
> año base, que sí es neutro.

**Qué habría roto.** Los tres consumidores del modelo. D-10.1 fija metas: una previsión de
diciembre un **28 % por debajo** de la estacionalidad real fija una meta que se supera sola. D-11.1
es el plan de compra y D-07.5 el nivel objetivo de stock: comprar diciembre con el factor 1,075 en
vez de 1,482 es planificar un desabastecimiento en el mes más alto del año. Y la banda del ±41 %
que lo acompañaba no habría avisado de nada, porque es tan ancha que **contiene cualquier cosa** —
justo el fallo que el criterio de cobertura existe para detectar.

---

## CE3.3 · La cobertura medida sobre 6 puntos no distingue nada

| | |
|---|---|
| **Fase** | E2 (criterio de rechazo n.º 3) |
| **El diseño decía** | §5.1.6: «Cobertura del intervalo: aproximadamente el 80 % de los valores reales debe caer dentro de la banda del 80 %», y el rechazo si sale del rango 65 %–90 %. Va escrito junto al backtest del **nivel total**. |

**La realidad.** El backtest del nivel total produce, por construcción, **6 puntos**: tres orígenes
(2026-04, 2026-05, 2026-06) prediciendo hasta el final de la serie. Sobre 6 puntos, una banda
**perfectamente calibrada al 80 %** da los 6 aciertos con probabilidad 0,8⁶ = **26 %**, y da 5 o 6
aciertos —o sea, ≥83 %, fuera o al borde del rango— más de la mitad de las veces.

Dicho de otro modo: **el criterio aplicado a esa muestra rechaza una banda correcta una de cada
cuatro veces y no detecta una mala**. No mide lo que dice medir.

**Cómo se resolvió.** La cobertura se evalúa sobre el conjunto **AGRUPADO** de puntos del backtest
—el total, las 8 categorías previsibles y las 159 variantes con historia larga—, que son **881
puntos**. Medido: **772 dentro, 87,6 %**. El parte de la corrida publica los tres niveles por
separado para que se pueda ver de dónde sale:

```
total + categorías      40 / 54    74,1 %
variantes              730 / 827   88,3 %
AGRUPADO               772 / 881   87,6 %   ← el que decide
```

**Qué habría roto.** Nada en la pantalla: habría roto la **publicación**. Con el criterio sobre 6
puntos, la carga aborta con `100,0 % fuera de rango` en un modelo cuya banda está bien calibrada, y
la tabla se queda con la previsión de la corrida anterior — indefinidamente, y sin que el mensaje
de error apunte a la causa real. Es el modo de fallo más caro de esta fase: un criterio de calidad
que impide publicar lo que es correcto enseña a saltárselo.

---

## CE3.4 · El mes truncado no sobrevive a la publicación

| | |
|---|---|
| **Fase** | E2 (`fact_prevision_demanda`, columnas) |
| **El diseño decía** | §5.1.7 enumera las columnas de la tabla, y §5.1.2 decide que el último mes, por estar incompleto, **se excluye del entrenamiento y se muestra aparte en el gráfico**. |

**La realidad.** Las columnas de §5.1.7 **no permiten saber si eso ocurrió**. La tabla publica
siempre los tres meses siguientes al último mes observado, se haya excluido o no: por fuera, los
dos casos son **idénticos**. La pantalla, que es la que tiene que «mostrarlo aparte», no tiene con
qué distinguirlos.

Y no se puede deducir del calendario. El primer intento fue precisamente ése —«si la previsión
arranca a dos meses del último dato observado, es que ese mes se excluyó»— y es falso: el desfase
se aplica al elegir **qué puntos se publican**, no a dónde empieza la tabla.

Se añaden cuatro columnas, y ésta es la que importa:

| Columna | Por qué |
|---|---|
| **`horizonte_efectivo`** | los meses que separan la previsión del último mes **entrenado**, que son uno más que los del último mes **observado** cuando ése se excluyó. Es el único rastro de la decisión de §5.1.2, y además explica por qué la banda del primer mes es más ancha de lo que su horizonte 1 haría esperar: se calculó para una distancia de 2 |
| `horizonte` | §5.1.8 declara un filtro «horizonte (1-3 meses)» y con solo `mes` no se puede resolver |
| `mae_backtest` | §5.1.6 pide DOS métricas —«MAPE y MAE en unidades, interpretable para compras»— y la tabla guardaba una. Un MAPE del 45 % no le dice a Compras cuántas unidades puede equivocarse |
| `mape_linea_base` | sin la vara al lado, el error por fila (regla 4) es un número sin referencia: 24 % es bueno o malo según lo que sacara el ingenuo en ESA serie, que aquí va de 10 % a 72 % |

**Cómo se resolvió.** Además de la columna, **el mes truncado se DETECTA y no se escribe**: se
compara el día del mes más alto con pedidos del último mes contra la mediana de los anteriores.
Escribir «julio de 2026» en el código funcionaría exactamente una vez. Medido: julio de 2026 cubre
**1/1,227** del mes (22 de 27 días comparables), y el control `prevision_ancla` de `validar_dwh.py`
verifica esa decisión **desde PostgreSQL, por su cuenta**, comparando el desfase que la base implica
contra el que la tabla publicó.

**Qué habría roto.** El gráfico de §5.1.9 —regla 1, la serie histórica y la previsión juntas—
pintaría julio de 2026 como un punto normal de la línea continua. Julio cae de **1.567 uds en mayo
a 996**, y una parte de esa caída es que faltan cinco días de ventas. Un director que mira esa
curva ve el negocio desplomándose justo antes de la previsión, y la previsión —que sí sabe que el
mes está corto— parece optimista sin motivo. Es un artefacto del corte de los datos leído como una
caída del negocio.

---

## CE3.5 · La banda no se ensancha siempre, y exigirlo aborta la carga

| | |
|---|---|
| **Fase** | E2 (validación de la tabla) |
| **El diseño decía** | §5.1.9, regla 5: «La banda se ensancha con el horizonte y con la escasez de observaciones del mes previsto. Que agosto tenga la banda más ancha que mayo **es la información**, no un defecto del gráfico». |

**La realidad.** Es cierto como afirmación sobre la incertidumbre y **falso como invariante fila a
fila**. Las dos formas obvias de escribirlo abortan la publicación de un resultado correcto:

| Cómo se escribe el invariante | Series que lo violan | Por qué lo violan, con razón |
|---|---|---|
| en **UNIDADES** (`superior − inferior` crece) | **15** | su previsión BAJA a lo largo del horizonte. Si octubre espera la mitad de unidades que agosto, su banda es más estrecha en unidades y más ancha en incertidumbre **a la vez** |
| en **RELATIVO**, fila a fila | **16** | el ruido de conteo aporta `1/valor` a la varianza relativa: una variante que pasa de 2 a 10 unidades previstas estrecha su banda relativa aunque el horizonte crezca |

Las 15 y las 16 son, en su práctica totalidad, series publicadas con la **línea base estacional**,
cuyo nivel es el del mismo mes del año pasado: un salto arbitrario entre un mes y el siguiente.
Ninguna afirmación monótona sobre su anchura es cierta.

**Cómo se resolvió.** El invariante se exige **donde la regla habla**: serie a serie sobre las
`descomposicion` —el total y las 8 categorías, que son las que se pintan en el gráfico—, cuyo nivel
se mueve suavemente y cuyo ruido de conteo es despreciable. Ahí se cumple en **9 de 9**. Sobre el
resto se comprueba lo que sí es cierto: la banda relativa **MEDIA** crece con el horizonte, medido
en los dos métodos que tienen escalado propio:

```
descomposicion       0,862 → 1,044 → 1,200
top_down_categoria   1,734 → 1,800 → 1,835
```

Es un control débil por fila y fuerte por tabla: si alguien quitara el `√h` del modelo, esta media
se quedaría plana y saltaría aquí.

> **Dentro de esta corrección apareció un defecto real**, y por eso la validación merecía la pena:
> dos series publicaban `0 uds [0 – 0]` —una previsión de cero con **banda de anchura cero**, que
> es la afirmación de certeza más fuerte de toda la tabla, y precisamente sobre la serie de la que
> menos se sabe—. Ocurría en la línea base estacional de una variante que no vendió nada ese mes
> del año anterior. El semiancho lleva ahora un suelo de ruido de conteo (`max(valor, 1)`), de modo
> que esa fila dice «0, y podría llegar a 1 ó 2».

**Qué habría roto.** La publicación entera: 15 series bastan para que la carga aborte y la pantalla
se quede con la previsión del mes pasado. Y la banda `[0 – 0]` habría llegado a D-11.1 como una
instrucción de no comprar nada, con la autoridad de un intervalo que no admite discusión.

---

## CE3.6 · El informe de previsión no cabe en «0 clases Java»

| | |
|---|---|
| **Fase** | E2 (capa de aplicación) |
| **El diseño decía** | §5.1.8: «**Coste: 0 clases Java nuevas** —los dos servicios de departamento ya existen—, 1 bloque de definición por pantalla, 2 líneas en `SecurityConfig`». |

**La realidad.** Los dos servicios existen, sí, pero el informe es **el mismo** para Gerencia y para
Compras: el mismo SQL, los mismos KPI, la misma serie del gráfico, la misma salvedad. Lo único que
cambia es **quién puede verlo**, y los dos repartos no son uno subconjunto del otro:

```
/api/informes/gerencia/prevision-demanda   ADMIN · GERENTE · ANALISTA
/api/informes/compras/prevision-demanda    ADMIN · GERENTE · COMPRAS
```

Repartirlo entre `InformesGerenciaCompuestosService` e `InformesComprasCompuestosService` obliga a
duplicarlo. Dos copias de una consulta de previsión divergen en la primera corrección, y el síntoma
es peculiarmente malo: **las dos pantallas enseñan una banda distinta para el mismo mes**, y ninguna
de las dos parece rota.

**Cómo se resolvió.** UNA clase, `InformesPrevisionService`, que los dos controladores **ya
existentes** inyectan. Coste real: 1 clase Java, 0 controladores, 1 archivo de definición
compartido por los dos departamentos y **2 líneas de `SecurityConfig`** — enumeradas por nombre y
no apoyadas en el comodín de su departamento, porque cada comodín deja fuera al rol que la otra
ruta necesita. La matriz por API (16 celdas) verifica las dos.

**Qué habría roto.** No un dato: la coherencia entre dos pantallas que la dirección compara. D-10.1
fija la meta con la de Gerencia y D-11.1 compra con la de Compras; si divergen, la compra no cubre
la meta y nadie sabe cuál de las dos cifras creer.

---

### Reincidencias declaradas, sin número propio

**`String.formatted()` interpreta el bloque de texto ENTERO, comentarios y SQL incluidos** —ya
registrado en §18 del patrón de informes— y volvió a morder a la primera petición. La consulta de
la previsión lleva `formatDateTime(mes, '%Y-%m')` dentro, y el formateador lee ese `%Y` como un
especificador suyo: el endpoint devolvía **HTTP 400 con «Conversion = 'Y'»**, un error del usuario
por un fallo que no tiene nada que ver con la petición. La consulta se arma por **concatenación**.
El fallo es ruidoso y por eso no llega a corrección, pero la regla operativa queda: **un bloque de
texto con un patrón de fecha dentro no se pasa por `formatted()`**.

**El universo se valida contra PostgreSQL aunque la previsión no se pueda validar.** No es una
corrección sino la respuesta a una pregunta que la fase tuvo que resolver: una fila con fecha futura
no tiene contra qué compararse al centavo, que es el criterio de todo el resto del almacén. Lo que
sí se compara —y es lo único que de verdad falla aquí— es el **universo** (10 categorías, 159
variantes con ≥12 meses, 510 filas) y el **ancla** (la previsión arranca donde acaba la venta real).
El segundo detecta el modo de fallo propio de una predicción: una tabla **rancia**. Si entra un mes
de ventas y el modelo no vuelve a correr, la pantalla sigue enseñando con toda naturalidad la
previsión de un mes que **ya ocurrió**, con su banda intacta y sin dar ningún error.

---

# Fase E3 — la alerta de abandono de cliente

> El modelo entra sabiendo que su veredicto de viabilidad es **negativo** (§5.2.2): no hay etiqueta
> de abandono, el generador del seed no permite que nadie abandone, y la correlación medida entre
> el mejor predictor disponible y el resultado real es **0,039**. Lo que se implementa es un modelo
> del **proceso**, no uno aprendido. Las ocho correcciones de abajo no cambian ese veredicto:
> cambian **cómo se mide y cómo se publica**, que en un modelo que ya se sabe débil es lo único
> que separa una herramienta honesta de una que finge.

## CE4.1 · La ventana estable escrita como fecha caduca en la corrida siguiente

| | |
|---|---|
| **Fase** | E3 (`modelos/alerta_abandono.py`, `inicio_ventana`) |
| **El diseño decía** | §5.2.10, limitación 2: «**Se calcula solo sobre la ventana desde enero de 2026**». Y §5.2.3: «Para cada cliente i, sobre la ventana ESTABLE (desde 2026-01)». |

**La realidad.** La ventana no es un dato del negocio: es una consecuencia del **ancla**, que es
`max(fecha_pedido)` del almacén. Escribir «2026-01» funciona exactamente una vez. En la corrida
siguiente —el ETL es **semanal** por §5.2.5— el ancla se mueve y la ventana no, así que λ se
estimaría sobre un período cada vez más largo hasta volver a tragarse la rampa de cartera. Es el
mismo error que CE3.4 documentó al revés con el mes truncado: **lo que se declara tiene que ser el
criterio, no el mes**.

Y la rampa es real, medida: en la ventana completa (19 meses) el mayor comprador de un mes llega al
**100,0 %** de los pedidos —enero y febrero de 2025, cuando era el único registrado—; en la ventana
de 7 meses no pasa del **10,9 %**.

**Cómo se resolvió.** La ventana son los últimos `MESES_VENTANA = 7` meses **contados desde el mes
del ancla**, nunca una fecha. Con los datos de hoy da 2026-01-01 → 2026-07-22, que es exactamente
la ventana que el diseño declara; mañana se desplaza sola. Y como el parámetro sigue siendo un
número que alguien puede cambiar, la carga lleva un **guardia que ABORTA**: si algún mes de la
ventana elegida tiene un cliente por encima de `CONCENTRACION_MAXIMA = 25 %`, la tabla no se
publica. Probado: 7 meses → 10,9 % **publica**; 12 meses → 18,0 % publica; 19 meses → 100,0 %
**aborta**.

**Qué habría roto.** D-08.1 entera, y del peor modo posible: **al revés**. Con la ventana completa,
el cliente **54 (Milton Moreira Vera, $399.425 facturados, el segundo de la cartera)** acumula 283
pedidos, un intervalo medio de **1,96 días** y 74 de silencio — o sea **37,7 veces su propio
ritmo**, con probabilidad **4,4·10⁻¹⁷**. Sale como la alerta más fuerte del sistema. Con la ventana
estable son 4 pedidos, 1/λ = 31,5 días y **2,35 intervalos**: normal. Ninguna suma falla en el
primer caso; la lista se pinta perfectamente y señala como perdido al cliente que más compra.

*(Nota sobre la cifra del propio diseño: §5.2.1 dice «74 días de silencio, que son 43 de sus
propios intervalos» con probabilidad e⁻⁴³ ≈ 2·10⁻¹⁹. Medido con λ estimada desde su primera compra
de la ventana salen 37,7 intervalos y 4,4·10⁻¹⁷. La diferencia es de método de estimación, no de
fondo: la conclusión y el orden de magnitud del disparate son los mismos.)*

---

## CE4.2 · El lift no se divide por una tasa base constante, y un origen no tiene ninguna

| | |
|---|---|
| **Fase** | E3 (`modelos/alerta_abandono.py`, `Origen.lift`) |
| **El diseño decía** | §5.2.6: «**Lift sobre la tasa base**: precisión@10 ÷ **9,4 %**. Es la métrica que decide». |

**La realidad.** El 9,4 % es la tasa base **de un solo origen** —el que §5.2.1 usó para la prueba
C— y los otros dos no valen eso. Medidas sobre los tres orígenes: **5,8 % · 7,0 % · 12,1 %**
(3, 4 y 7 casos positivos sobre 52, 57 y 58 clientes evaluados). Dividir siempre por 9,4 % publica
un lift equivocado en dos de los tres.

Y hay un caso peor, que aparece en cuanto la ventana se fija en 2026-01 tal como el diseño la
escribe: el origen más antiguo (corte 2026-03-24, 40 clientes evaluados) tiene **CERO positivos**.
Ahí el lift no vale 0: **no existe**. Con la fórmula del diseño ese origen habría reportado
`0 ÷ 9,4 % = 0,00`, que se lee como «la alerta fue peor que el azar» cuando lo cierto es que **no
hubo azar que batir** — nadie dejó de comprar en esos 60 días.

**Cómo se resolvió.** Cada origen se divide por **su propia** tasa base; `Origen.lift` devuelve
`None` —y la consola imprime «n/d (0 positivos: no hay azar que batir)»— cuando no hay positivos. El
veredicto que viaja en la fila y en la cabecera de la pantalla es el **AGRUPADO** sobre los tres
orígenes (30 intentos, 167 evaluaciones, 14 positivos), que es la única cifra con muestra
suficiente para significar algo.

**Qué habría roto.** La cabecera de OTD-VEN-19, que es el entregable entero de esta fase. La regla 4
de §5.2.9 pone el lift **antes que la lista** precisamente para que el gerente lo use al decidir; un
lift calculado contra el denominador de otro origen es peor que no publicarlo, porque llega con la
autoridad de una medición.

---

## CE4.3 · La ventana de entrenamiento del backtest tiene que rodar con el origen

| | |
|---|---|
| **Fase** | E3 (`modelos/alerta_abandono.py`, `backtest`) |
| **El diseño decía** | §5.2.6: «backtest de origen móvil con **3 orígenes dentro de la ventana estable**, ventanas de prueba de 60 días». |

**La realidad.** «Dentro de la ventana estable» se lee como que la ventana es fija y lo que se mueve
es el corte. Hecho así, el origen más antiguo estima λ sobre **83 días** de historia frente a los
**203** de la corrida de producción: se está midiendo el error de un estimador **que nunca se
publica**. El efecto no es teórico — el lift agrupado pasa de **1,34** (ventana fija) a **1,99**
(ventana rodante), y ninguno de los dos números es «el error del modelo» si no se dice cuál de los
dos estimadores describe.

**Cómo se resolvió.** Cada origen entrena con `MESES_VENTANA` meses **acabados en su propio corte**,
igual que la corrida de producción acaba en el ancla. El modelo evaluado es el modelo publicado.

**Qué habría roto.** No una cifra de negocio: la credibilidad de la única métrica que esta fase
publica sobre sí misma. Un backtest que mide otro estimador es exactamente el patrón que esta
bitácora documenta cincuenta veces — todo coherente, todo plausible, y describiendo otra cosa.

---

## CE4.4 · El lift no sale 1,0: sale 1,99 — y sin su valor p es un titular falso

| | |
|---|---|
| **Fase** | E3 (`modelos/alerta_abandono.py`, `Backtest.p_valor`) |
| **El diseño decía** | §5.2.6: «dado que la correlación medida entre la señal y el resultado es 0,039, **la expectativa razonable es que el lift salga ≈ 1,0 sobre estos datos**». |

**La realidad.** Sale **1,99**: precisión@10 del **16,7 %** (5 aciertos de 30 intentos) contra una
tasa base del **8,4 %** (14 positivos de 167 evaluaciones). Leído solo, ese número dice que la
alerta acierta el **doble** que elegir al azar, y sería el titular de la pantalla.

Pero la muestra es la que es. Bajo la hipótesis de que la alerta no discrimina, la probabilidad de
obtener **5 o más** aciertos en 30 intentos con una tasa base del 8,4 % es **0,102**. El resultado
**no es distinguible del azar**, exactamente como el diseño anticipaba — solo que el lift, por sí
solo, dice lo contrario.

**Cómo se resolvió.** El backtest calcula la cola superior binomial exacta (`math.comb`, sin
dependencias nuevas), la tabla la publica en `p_valor_backtest` y la **tercera tarjeta de la
cabecera** responde literalmente «¿Supera al azar? **NO · p = 0,1019**». La salvedad lo repite con
palabras. El criterio declarado del código es `lift > 1 Y p < 0,05`, y hoy no se cumple.

**Qué habría roto.** D-08.1 en la dirección contraria a la temida. El diseño se preparó para que un
lift de 1,0 no se ocultara; lo que estuvo a punto de pasar es lo simétrico — **un 1,99 publicado
como si fuera un logro**, sobre 14 casos. La decisión de negocio («se publica con su lift a la
vista») se cumple igual: lo que se añade es que el lift no viaje solo.

---

## CE4.5 · Los clientes sin muestra son los candidatos más fuertes, y el modelo los expulsa

| | |
|---|---|
| **Fase** | E3 (`tablas/fact_alerta_cliente.py`, nivel `sin_muestra`) |
| **El diseño decía** | §5.2.3 calcula λ «para cada cliente i» y §5.2.9 dibuja la cabecera como «9 en alerta **de 69 clientes**». No hay ningún nivel para el cliente sin muestra, y §5.2.10 solo advierte que «con menos de 5 pedidos el ritmo es una conjetura». |

**La realidad.** λ exige un mínimo de pedidos **dentro de la ventana** (3, para que no sea el
inverso de un único intervalo observado), y **8 de los 69 clientes no llegan**. El problema no es
que sean 8: es **quiénes son**. Un cliente lleva 179 días sin comprar y por eso mismo solo tiene 1
pedido en la ventana ($10.955 facturados); otro lleva 94 días con 2 pedidos ($21.554). **Son los dos
silencios más largos de toda la cartera** y el modelo no puede opinar sobre ninguno, precisamente
porque su silencio es lo que los dejó sin muestra.

**Cómo se resolvió.** Se publican los **69**, no los 61 evaluables. Los 8 salen con
`nivel_alerta = 'sin_muestra'`, `tasa_diaria = 0`, `prob_silencio = 1,0` —«no hay evidencia de
silencio inusual» es lo que corresponde cuando no hay con qué medirlo; un 0 los pondría en cabeza
con la certeza más alta del sistema— y su **silencio REAL**, que es un hecho medible sin λ. La
cabecera lleva una tarjeta propia («Sin muestra para opinar: 8 clientes · el mayor silencio entre
ellos, 179 días») y la quinta limitación de la salvedad los nombra con su cifra.

**Qué habría roto.** D-08.1, de la forma más difícil de detectar de todas: **por omisión**. Una lista
de clientes en riesgo que nunca muestra al cliente que se fue de verdad se lee como una lista
completa. No falla ninguna suma, no falta ninguna columna, y el caso de uso entero desaparece.

---

## CE4.6 · El recorte del VENDEDOR no puede hacerse «con el mismo mecanismo de OTD-VEN-02»

| | |
|---|---|
| **Fase** | E3 (`fact_alerta_cliente.vendedores`, `InformesVentasCompuestosService`) |
| **El diseño decía** | §5.2.8: «El VENDEDOR entra porque es quien ejecuta el gesto comercial — y **se recorta a su cartera con el mismo mecanismo de OTD-VEN-02** (`alcance: "propio"` desde el JWT)». |

**La realidad.** El mecanismo de OTD-VEN-02 es `pedido.vendedor_id = <id del JWT>` **en
PostgreSQL**. En el almacén **no existe `vendedor_id`**: `fact_pedido` guarda `vendedor` como el
NOMBRE del usuario (o el centinela `(canal en línea)` para el checkout). El mecanismo citado no está
disponible, y añadir la columna a `fact_pedido` está fuera del alcance de la fase.

Hay además un hallazgo de fondo sobre lo que significa «su cartera»: **no es una partición**. No
existe asignación de cliente a vendedor en el sistema; la cartera solo puede derivarse de a quién ha
atendido cada uno, y en la ventana estable **54 de los 69 clientes fueron atendidos por 3 o más
vendedores distintos**. Verificado por API: el recorte deja al vendedor **50 de los 69 clientes**
—el 72 %—. Recorta de verdad, pero está lejos de ser una segmentación.

**Cómo se resolvió.** La tabla publica `vendedores Array(String)`, el conjunto de nombres que
atendieron al cliente **dentro de la ventana** (excluido el centinela del canal en línea, que no
forma cartera de nadie), y el informe filtra con `has(vendedores, ?)` usando el nombre del principal
—compuesto exactamente igual que en el ETL—. Como casar por nombre es frágil, `sql_controles`
comprueba contra PostgreSQL que **los nombres de los usuarios que venden son únicos** y ABORTA la
carga si dos coinciden. El aviso de la pantalla dice qué significa el recorte, en vez del texto de
VEN-02 («tus propias ventas»), que aquí describiría mal el filtro aplicado.

**Qué habría roto.** Un recorte que falla **abierto**: sin la columna, la salida obvia era no
recortar y dejar que el vendedor viera la cartera entera con su facturación. Y con dos homónimos,
seguiría fallando abierto sin dar error — de ahí el control.

---

## CE4.7 · La foto fechada no acumula historia — y el backtest no la necesita

| | |
|---|---|
| **Fase** | E3 (`tablas/fact_alerta_cliente.py`, patrón de carga) |
| **El diseño decía** | §5.2.7: «tiene que ser una **foto fechada**: sin `fecha_calculo`, la alerta de la semana pasada se pierde **y el backtest del mes que viene no tiene contra qué medirse**». |

**La realidad.** Las dos mitades de la frase son falsas por motivos distintos. La primera: el patrón
de carga del §6.2 es **reemplazo total** —staging `_new`, validar, `EXCHANGE TABLES`—, así que la
foto de la semana pasada se pierde **igualmente**, tenga la columna o no. Conservar historia
exigiría leer la tabla publicada antes de reemplazarla, y con ello el control de conteo dejaría de
ser `clientes` para pasar a ser `clientes × fotos`.

La segunda: **el backtest no necesita fotos pasadas**. Reconstruye cada origen desde `fact_pedido`,
que tiene todas las compras de la historia; por eso puede evaluar tres cortes en una corrida recién
instalada. La columna es correcta y se mantiene —la pantalla muestra cuándo se calculó, que no es lo
mismo que el ancla—, pero su justificación no lo era.

**Cómo se resolvió.** Se publica **la foto vigente** y se declara. `fecha_calculo` y `fecha_ancla`
son columnas distintas a propósito: la primera dice cuándo corrió el modelo, la segunda contra qué
día se midió el silencio, y son la misma cosa solo si el pipeline está al día.

**Qué habría roto.** Nada del dato; sí una decisión de arquitectura tomada sobre una premisa falsa.
Quien leyera §5.2.7 y quisiera «arreglar» la pérdida de historia acabaría convirtiendo la única
tabla del almacén con reemplazo total en una tabla acumulativa, y con ella el control de conteo que
protege a las otras veinte.

---

## CE4.8 · Las dependencias declaradas no producen dos columnas que la propia tabla exige

| | |
|---|---|
| **Fase** | E3 (`FactAlertaCliente.depende_de`) |
| **El diseño decía** | §5.2.5: «`TareaDerivada` en `etl/dwh/tablas/`, **`depende_de = {fact_pedido, dim_cliente}`**». |

**La realidad.** Con esas dos tablas no se pueden producir dos columnas que **la propia §5.2.7
declara**: `reclamos_abiertos` y `devoluciones_12m`. Salen de `fact_ticket` (248 filas) y
`fact_devolucion` (196), que no están en la lista. Y la §5.2.4 insiste —con razón— en que **no son
variables del modelo** sino contexto para el gesto comercial: precisamente por eso nadie las echaría
de menos si publicaran ceros.

**Cómo se resolvió.** `depende_de = (fact_pedido, dim_cliente, fact_ticket, fact_devolucion)`. El
orden topológico de `run_etl.py` lo deriva de ahí y coloca la tarea en la posición **21**, después
de las cuatro.

**Qué habría roto.** El gesto comercial, que es la decisión que D-08.1 toma. La pantalla habría
mostrado «0 reclamos abiertos» para un cliente con dos abiertos, y quien llamara lo haría sin saber
por qué se fue — que es la diferencia entre una llamada de recuperación y una que empeora las cosas.
Sin dependencia declarada el fallo además es **intermitente**: si `fact_ticket` corre después, la
alerta lee la tabla de ayer y a veces acierta.

---

### Reincidencias declaradas, sin número propio

**`String.formatted()` y los patrones de fecha, otra vez.** Ya registrado en §18 del patrón de
informes y de nuevo en la Fase E2. La consulta de OTD-VEN-19 lleva `formatDateTime(fecha_ancla,
'%d/%m/%Y')`, así que se arma por **concatenación** desde el principio. La regla operativa está
consolidada: **un bloque de texto con un patrón de fecha dentro no se pasa por `formatted()`**.

**El rol `retailmind_etl` no llega a `usuario_rol`.** El script 85 concede SELECT sobre 54 tablas y
la tabla puente del rol no está entre ellas, así que el control de unicidad de nombres no podía
partir de «los usuarios con rol VENDEDOR». Se parte de los usuarios que **realmente figuran** como
autor de un pedido (`pedido JOIN usuario ON u.id = p.vendedor_id`), que además es la población
correcta: un vendedor dado de alta que nunca vendió no puede colisionar en una cartera que no
existe. No es un supuesto fallido del diseño —el diseño no habla de esto— pero sí una restricción
que la fase siguiente encontrará igual.

---

# Fase 6 — cuando el USO de la aplicación invalida una medición

> **Esta fase es de una clase distinta a las cinco anteriores y por eso se separa.** En las Fases 1
> a E3 el supuesto fallido era del DISEÑO: algo escrito en papel que los datos desmintieron la
> primera vez que se midió. Aquí no. Aquí el supuesto **se midió, era CIERTO, se documentó con su
> cifra… y dejó de ser cierto después**, porque alguien usó la aplicación como está diseñada para
> usarse.
>
> Las tres primeras entradas son **la misma causa raíz vista en tres sitios**: el 2026-08-16 se
> registró una compra real (OC 920) recibida en **dos actos** —11 unidades aceptadas y 1 rechazada
> por «Caja dañada», y al día siguiente la que faltaba—. El dato operativo es **impecable**: una
> sola factura, una sola cuenta por pagar, y `orden_compra_detalle.cantidad_recibida = 12 = 11 + 1`.
> Lo que se rompió fue el ETL, que en tres consultas distintas daba por hecho que una orden se
> recibe de una vez.
>
> **La lección que generaliza**: una medición del tipo «hoy esta relación es 1:1» no es una
> propiedad del esquema, es una **foto de los datos**. `recepcion_mercancia` no tiene UNIQUE sobre
> `orden_compra_id` y el backend permite recibir en varios actos; el 1:1 era una casualidad del
> seed. Cuando un docstring diga «verificado: máx. 1», hay que leerlo como «hoy hay 1, y el motor
> no lo impide». La red que lo atrapó —igualdad exacta al centavo y control de grano— funcionó
> exactamente como se diseñó: **la corrida abortó, las tablas conservaron el dato del día anterior
> y ningún informe mostró una cifra inventada**.

## C6.1 · Una orden de compra puede tener DOS recepciones, y ya las tiene

| | |
|---|---|
| **Fase** | 6 (`fact_orden_compra`) |
| **El diseño decía** | No el diseño: **la corrección C3.1 de este mismo archivo**, que midió «máx. recepciones por OC = 1» y sobre esa cifra autorizó un `LEFT JOIN recepcion_mercancia`. |

**La realidad.** La OC 920 (`OC-20260816-114031`, emitida el 2026-08-16) tiene **dos** recepciones
`confirmada`: la 897 del 20:16 y la 898 del 20:19. El `LEFT JOIN` devolvía **134.591 filas para
134.590 órdenes** y con ellas: +1 en `con_recepcion`, `con_factura`, `con_ambos` y `con_esperada`,
**+$2.415,00** en `total_ordenes` y el total de la factura, la CxP y el saldo contados dos veces.

**Cómo se resolvió.** Constante `_RECEPCION_CANONICA`: la **última** recepción por
`(fecha_recepcion, id)`, vía `LEFT JOIN LATERAL … LIMIT 1`. La última y no la primera porque las
unidades de la fila son el TOTAL recibido (12), y emparejar ese total con la fecha de la primera
recepción afirmaría que las 12 estaban en bodega cuando solo había 11. Las **tres** cifras de plazo
del control (`pares_comparables`, `cumplieron`, `suma_dias_ciclo`) y las del control independiente
de `validar_dwh.py` se movieron al mismo grano: escritas con un `JOIN recepcion_mercancia` a secas,
aportaban dos pares para esa orden y **acusaban a la tabla de un descuadre que estaba en el propio
control**. Hoy la elección no cambia ni un número —las dos recepciones son del mismo día— pero
queda declarada porque la próxima vez sí lo cambiará.

**Qué habría roto.** OTD-COM-05 (`/cumplimiento-plazo`) y COM-06 (`/ciclo-compra`) cuentan órdenes
puntuales sobre una base que declaran; con la orden duplicada, una orden aporta **dos** veredictos.
Y OTD-COM-04 / GER-02 habrían inventado $2.415,00 de gasto: una cifra demasiado pequeña para verse
en $418 M y suficiente para que la balanza no cuadre nunca contra contabilidad.

---

## C6.2 · El LATERAL de agregados «sin fan-out posible» fanea por dentro

| | |
|---|---|
| **Fase** | 6 (`fact_orden_compra`) |
| **El diseño decía** | El docstring del módulo: «`lineas`, `unidades_pedidas`, `unidades_recibidas` y `unidades_rechazadas` se calculan en un LATERAL sobre el detalle. **No hay fan-out posible**». |

**La realidad.** El LATERAL unía `orden_compra_detalle` con `recepcion_detalle`, y la línea 2.957 de
la OC 920 tiene **dos** líneas de recepción. Dentro del LATERAL eso duplica la línea: `lineas`
contaba **3** donde hay 2, y `unidades_pedidas` sumaba la línea dos veces. Combinado con C6.1 (la
fila entera repetida), el descuadre final fue **+4 líneas, +48 unidades pedidas, +48 recibidas y +1
rechazada**.

Lo instructivo es que **el control de grano NO lo habría visto solo**: `count(*)` vs
`countDistinct(orden_compra_id)` mira la fila, y este fan-out ocurre *dentro* de una subconsulta que
sigue devolviendo una fila por orden. Lo atrapó la igualdad exacta de `lineas` contra
`count(*) FROM orden_compra_detalle`.

**Cómo se resolvió.** Dos LATERAL separados, según de qué documento es cada cifra: lo **pedido y lo
recibido** salen del detalle de la ORDEN (una fila por línea, pase lo que pase) y el **rechazo** de
`recepcion_detalle`, sumado sobre todas las recepciones de la orden.

**Qué habría roto.** OTD-COM-11 (`/entregas-incompletas`) compara pedido contra recibido por línea:
con la línea duplicada, una entrega completa aparece como servida al 200 %. Y el tablero T-6
(Abastecimiento) ordena proveedores por cumplimiento sobre esas mismas unidades.

---

## C6.3 · `recepcion_detalle` no es 1:1 con la línea de la orden

| | |
|---|---|
| **Fase** | 6 (`fact_compra_linea`) |
| **El diseño decía** | El docstring: «Verificado antes de confiar en el LEFT JOIN: `recepcion_detalle` es **estrictamente 1:1** con `orden_compra_detalle` donde existe (0 líneas con dos recepciones, 2.855 distintas sobre 2.855 filas)». |

**La realidad.** Hay **una** línea con dos: la 2.957. La tabla salió con **715.137 filas para
715.136 líneas**, +12 unidades pedidas y **+$1.020,00** de subtotal.

**Cómo se resolvió.** La recepción entra **AGREGADA** por línea (`_RECEPCION_AGREGADA`), no elegida:
una línea puede recibirse en varios actos y lo recibido es la SUMA de todos — que es exactamente lo
que mide el control (`SUM(cantidad_recibida) FROM recepcion_detalle`). Tres detalles que no son
obvios:

* **`lineas_recepcion` (un `count`) sustituye a `rd.id IS NOT NULL`** como prueba de «esta línea tuvo
  recepción». Un `SUM` sobre cero filas devuelve NULL y no distingue «recibí 0» de «no hubo
  recepción» — la misma trampa que C3B.5 documentó para el LEFT JOIN de ClickHouse, aquí en
  PostgreSQL.
* **El motivo no se puede sumar**: se toma el de la recepción que de verdad rechazó y, entre varias,
  la última por `id`.
* **«Completa» es propiedad de la LÍNEA, medida sobre el total recibido.** Por fila de recepción, la
  línea 2.957 —12 pedidas, servidas 11 + 1— no era completa en *ninguna* de sus dos recepciones,
  cuando la verdad es que se sirvió entera. Se corrigió la CONSULTA del control (en la tarea y en
  `validar_dwh.py`), no su umbral: sigue siendo igualdad exacta.

**Qué habría roto.** OTD-COM-07 (`/rechazos`) reparte 186 unidades rechazadas entre motivos y
proveedores; una línea de más desplaza el reparto. Y OTD-COM-12 (`/evolucion-costo`) es una serie
por `(variante, proveedor)` resuelta con `lagInFrame` sobre el orden físico: **dos filas idénticas
consecutivas inyectan una variación de precio del 0 % que no ocurrió**, y ese informe existe para
detectar exactamente lo contrario.

---

## C6.4 · Un NULL legítimo contra una columna Decimal no-Nullable tumba la carga sin decir dónde

| | |
|---|---|
| **Fase** | 6 (`dim_producto`) |
| **El diseño decía** | §4.2 lista `peso_kg Decimal(10,3)` sin `Nullable`, y en el seed la columna estaba poblada en las 1.221 variantes (script 54), así que la omisión no se notó. |

**La realidad.** `producto_variante.peso_kg` **es NULLABLE en PostgreSQL**, y la aplicación creó tres
variantes sin peso (ids 2427, 2428 y 2429, el 2026-08-12 y el 2026-08-16). La carga falló tres
corridas seguidas con:

    InvalidOperation: [<class 'decimal.ConversionSyntax'>]

que **no nombra la columna, ni la fila, ni la tabla**. La causa exacta: `clickhouse_connect` escribe
una columna Decimal con `int(Decimal(str(x)) * mult)`, y con `x = None` eso es `Decimal('None')`.
Reproducido en aislamiento antes de tocar nada.

**Cómo se resolvió.** `peso_kg Nullable(Decimal(10,3))`. **No** con `COALESCE(peso_kg, 0)`: por la
lección de C3B.5, un 0 es una afirmación —«no pesa»— y la verdad aquí es «no se sabe». Ninguna
consulta de informe ni de tablero lee esta columna (verificado), así que el NULL no propaga a
ninguna aritmética.

**Sigue latente una columna más allá**: `margen_catalogo_pct` sale de un `NULLIF(pv.precio, 0)` y
sería NULL —rompiendo la carga igual— si una variante tuviera `precio = 0`. Hoy ninguna la tiene y
**la aplicación no lo valida**.

**Qué habría roto.** Nada en pantalla, y eso es lo interesante: el patrón de carga atómica hizo su
trabajo y `dim_producto` conservó las 6.221 filas del día anterior. Pero las tres variantes nuevas
—y con ellas todo lo que se les comprara o vendiera— quedaban **invisibles para el almacén entero**,
porque `dim_producto` es la dimensión contra la que resuelven `fact_venta_linea`,
`fact_compra_linea`, `fact_resena` y `fact_devolucion_linea`. Un producto que se vende y no aparece
en ningún informe es peor que un informe vacío: el vacío se ve.

---

## C6.5 · El control de motivos reimplementaba el mapa pero NO la regla de escape

| | |
|---|---|
| **Fase** | 6 (`fact_compra_linea`, `validar_dwh.py`) |
| **El diseño decía** | §5.3 declara la regla de escape («lo no previsto cae en `'Otro'` y se registra»), y el control la reimplementa «a propósito, para que no sea una tautología» — pero solo tradujo el **sinónimo**, dejando pasar cualquier otro valor tal cual. |

**La realidad.** `motivo_rechazo` es TEXTO LIBRE y la aplicación escribió uno nuevo el 2026-08-16:
**«Caja dañada»**. Los motivos crudos pasaron de 6 a **7**. Python lo mandó a `'Otro'` y lo registró
—la regla de escape funcionó— pero el control decía «Caja dañada» donde el almacén dice «Otro» y
**fallaba por no expresar la regla completa**, no por un error de carga.

Y había una casualidad debajo: `motivos_normalizados` daba **6** (7 crudos − 1 sinónimo) contra los
**6** del destino (5 canónicos + `Otro`). **Cuadraba por coincidencia.** Con dos motivos nuevos
habría dado 7 contra 6.

**Cómo se resolvió.** La lista blanca de los cinco canónicos **más** el escape a `'Otro'`, escrita
en SQL en los dos sitios, independiente de la de Python. No es aflojar el control: si Python mandara
un motivo real a `'Otro'`, o metiera uno inventado en un cubo canónico, la fila sigue delatándolo.
El motivo nuevo **se deja en `'Otro'` a propósito**: decidir que «Caja dañada» es «Empaque danado en
transito» es una opinión sobre lo que quiso decir una persona, y el criterio del proyecto (C3.3) es
que quitar decoración de máquina es limpieza pero fusionar dos frases humanas es criterio. Queda
reportado para que Compras lo decida.

**Qué habría roto.** OTD-COM-07 muestra los motivos como categorías. Sin la regla en el control,
cada frase nueva que teclee un usuario de bodega aborta la corrida de `fact_compra_linea` —no por un
defecto real— y el almacén se queda con el dato de ayer hasta que alguien edite el mapa.

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
