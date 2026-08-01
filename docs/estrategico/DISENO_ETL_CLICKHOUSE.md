# Diseño del pipeline ETL PostgreSQL → ClickHouse — RetailMind

**Universidad Técnica Estatal de Quevedo (UTEQ)** · Facultad de Ciencias de la Ingeniería
**Asignatura**: Construcción de Software — 6.º semestre
**Proyecto**: RetailMind — comercio minorista multicanal de ticket alto (Quevedo, Los Ríos, Ecuador)
**Documento**: diseño de la capa analítica de destino · **Fecha: 2026-07-30** · **Modo: SOLO LECTURA**

> **Alcance y garantía de no escritura.** Este documento **diseña en papel** el pipeline que llevará
> los datos operativos de PostgreSQL a ClickHouse para alimentar los **39 objetivos tácticos
> COMPUESTOS** del catálogo. **No se construyó nada**: no se creó ninguna tabla, no se escribió una
> sola fila en PostgreSQL ni en ClickHouse, no se levantó ningún servicio y no se modificó ningún
> archivo de código (ni de `retailmind/`, ni de `analytics/`, ni del backend). Toda verificación
> contra la base se hizo con `SELECT` a través del MCP `retailmind` de solo lectura. El único
> archivo creado es este documento.
>
> **Documentos de entrada**: `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` (versión 4, qué informes
> existen y cuáles son compuestos) y `docs/estrategico/DIAGNOSTICO_CLICKHOUSE.md` (qué hay hoy en la
> instancia columnar y por qué no sirve).

---

## 1. Resumen ejecutivo

| Pregunta | Respuesta |
|---|---|
| ¿Cuántos objetivos compuestos hay que servir? | **39** (de los 69 del catálogo; los otros 30 son SIMPLES y ya se resuelven en PostgreSQL por pantalla) |
| ¿Cuántas tablas columnares de destino? | **19**: **5 dimensiones + 14 tablas de hechos**. Una tabla por informe habrían sido 39; el grano —no el informe— decide cuántas hacen falta |
| ¿Dónde viven? | Base **nueva** `retailmind_dwh`, separada de la `retailmind` legada, que queda congelada como archivo (§6.3 del diagnóstico) |
| ¿Estrategia de carga? | **Full refresh atómico** de todas las tablas, con `EXCHANGE TABLES`. No es la salida perezosa: es la **correcta** para este modelo, porque el 60 % del dato *muta* después de creado y una carga incremental por fecha lo perdería (§6.1) |
| ¿Cuánto tarda una corrida completa? | Segundos. El universo entero son **~90.000 filas de hechos** — menos que un solo lote de inserción de los que ya usa el ETL viejo |
| ¿Orquestación recomendada? | **Camino en dos pasos**: escribir las tareas como scripts autónomos e idempotentes movidos por un `run_etl.py` (**2–3 h**, sirve desde el día uno), y **encima** montar Airflow como envoltorio (**+1 a 1,5 días**). Los DAG quedan de 15 líneas y la decisión es reversible |
| ¿Piloto? | **`fact_venta_linea` de extremo a extremo**, validada contra `pedido_detalle` con las cifras de control de §9.2 |

### 1.1 El enfoque, en un párrafo

Se reconstruye **todo** el contenido analítico desde PostgreSQL, que es la única fuente que refleja
el negocio actual. No se migra ni se reinterpreta nada del ClickHouse legado: el diagnóstico
demostró que el 96,2 % es relleno sintético huérfano y el resto un dataset de e-commerce global
ajeno al negocio. El modelo de destino es un **star schema de grano atómico**: los hechos se cargan
a nivel de línea/documento, **no preagregados por mes**, salvo una única excepción justificada
(`fact_stock_mensual`, §5.14). Esa decisión es deliberada — con 90.000 filas ClickHouse responde
cualquier agregación en milisegundos, y el grano atómico permite que **una misma tabla sirva a
siete informes distintos** con siete `GROUP BY` diferentes, en vez de exigir una tabla por pregunta.
Preagregar aquí no compraría velocidad; solo multiplicaría tablas y congelaría las preguntas que se
pueden hacer.

### 1.2 Corrección de una premisa del encargo: no hay corte B2B/B2C que modelar

El encargo indica que «el criterio canónico B2B/B2C es `pedido.tipo_venta` (mayoreo/menudeo)».
**Verificado en modo lectura el 2026-07-30, la premisa falla dos veces**, y la segunda es la
importante.

**(1) La columna no existe.** `pedido` tiene 19 columnas y ninguna es `tipo_venta`; una búsqueda
sobre las 110 tablas del esquema no encuentra ninguna columna `tipo_venta`, `mayoreo` ni
equivalente. Lo que sí existe está vacío: `grupo_cliente` 0 filas, `segmento_cliente` 0 filas,
`cliente_segmento` 0 filas, y los **72 clientes** tienen `grupo_cliente_id` NULL.

**(2) La clasificación tampoco es derivable — y esto la cierra.** El diagnóstico
`docs/estrategico/DIAGNOSTICO_SEGMENTO_CLIENTE.md` (2026-07-30, solo lectura) contrastó las siete
dimensiones que separarían un mayorista de un minorista y concluyó con veredicto **(c) POBLACIÓN
HOMOGÉNEA**: **10.378 de 10.384 líneas de pedido (99,94 %) piden entre 1 y 4 unidades** —techo
histórico 12 por línea y 24 por pedido, o sea **no existe compra de volumen**—; el ticket es
unimodal log-normal sin segunda cresta; la mezcla de categorías difiere **0,62 pp** como máximo
entre el cliente grande y el pequeño y el método de pago **1,44 pp**; y no hay soporte estructural
(**0 RUC en 3.887 facturas**, cero listas de precios por grupo, cero crédito a cliente). El negocio
real es **comercio minorista multicanal de ticket alto** ($1.400,06 por pedido sobre $276,36 por
unidad). El corte B2B/B2C **no está pendiente de captura: no existe**, y el catálogo táctico lo
registra como **DESCARTADO** (§12), no como brecha.

Consecuencia para este diseño, y se aplica sin excepción:

1. **`pedido.canal`** (`web` / `tienda` / `telefono`) se modela como lo que es —**el medio de entrada
   del pedido**— y así se etiqueta en todas las tablas. Nunca se rotula «B2B/B2C».
2. `dim_cliente` lleva una columna **`segmento`** alimentada desde `cliente.grupo_cliente_id`, que
   resuelve a `'sin_segmentar'` en los 72 clientes. **No se conserva como hueco a la espera de
   llenarse, sino como registro de que no hay segmentación** — mismo criterio con que OTD-VEN-16
   expone su columna «Clientes con segmento registrado» en 0. Su valor constante es información:
   cualquier informe que agrupe por ella devolverá una sola fila, que es la verdad del negocio.
3. **Ninguna tabla de hechos lleva dimensión de segmento**, y ninguna consulta del pipeline debe
   producir un reparto B2B/B2C. La propuesta **OTD-VEN-17** (venta por segmento) quedó
   **DESCARTADA** el 2026-07-30 y no forma parte de los 39 objetivos COMPUESTOS que este pipeline
   alimenta.

Si el negocio quisiera de todos modos un corte de cliente, el único **honesto** con estos datos es
**RFM por valor** (top 20 % de facturación = 14 clientes = 68,76 % del ingreso), que se calcularía
como **regla derivada explícita** en el ETL sobre `dim_cliente` y se rotularía *«clientes de alto
valor»* — **nunca «B2B»**, porque la concentración medida es de **frecuencia** y no de tipo de
comprador (los diez mayores clientes tienen tickets medios de $1.301 a $1.512, alrededor de la media
global). Este diseño no inventa la clasificación, porque hacerlo produciría un informe que miente
con precisión de dos decimales.

---

## 2. Inventario de los 39 objetivos compuestos

Extraídos del catálogo v4 (§3 a §8). Verificación aritmética por departamento: Ventas 10 + Compras 7
+ Inventario 3 + Logística 8 + Soporte 5 + Gerencia 6 = **39**.

### 2.1 Ventas (10)

| ID | Qué necesita responder | Tabla(s) columnar(es) que lo sirven |
|---|---|---|
| OTD-VEN-03 | Los 10 productos más vendidos del período elegido | `fact_venta_linea` + `dim_producto` |
| OTD-VEN-04 | Los 10 productos «hueso»: sin venta o con más días desde la última | `dim_producto` ⟕ `fact_venta_linea` + `fact_movimiento_inventario` |
| OTD-VEN-05 | Cuánto compra cada cliente: total, nº de pedidos, última compra | `fact_pedido` + `dim_cliente` |
| OTD-VEN-06 | Evolución de la venta mes a mes y por categoría | `fact_venta_linea` |
| OTD-VEN-07 | Valor promedio del pedido, por período y por canal | `fact_pedido` |
| OTD-VEN-09 | Mezcla de formas de cobro y cómo cambia en el tiempo | `fact_flujo_caja` (`sentido='ingreso'`) |
| OTD-VEN-11 | Calificación de cada producto y su evolución | `fact_resena` + `dim_producto` |
| OTD-VEN-12 | Cobros en línea fallidos y su motivo | `fact_flujo_caja` (`estado='fallido'`) |
| OTD-VEN-13 | Evolución mensual de la participación de cada canal | `fact_pedido` |
| OTD-VEN-14 | Dinero devuelto al mes y su % sobre la venta | `fact_devolucion` + `fact_pedido` |

### 2.2 Compras (7)

| ID | Qué necesita responder | Tabla(s) columnar(es) que lo sirven |
|---|---|---|
| OTD-COM-03 | Puntualidad de pago a proveedor: antes o después del vencimiento | `fact_flujo_caja` (`sentido='egreso'`) |
| OTD-COM-04 | Gasto de compras por proveedor y por mes | `fact_orden_compra` |
| OTD-COM-05 | Cumplimiento del plazo prometido: promesa vs llegada real | `fact_orden_compra` |
| OTD-COM-06 | Días reales del ciclo de compra, exista o no promesa | `fact_orden_compra` |
| OTD-COM-07 | Mercancía rechazada en puerta, por proveedor y motivo | `fact_compra_linea` |
| OTD-COM-09 | Monto recuperado por defectuosos (crédito o reposición) | `fact_devolucion_proveedor` — *REQUIERE VOLUMEN* |
| OTD-COM-12 | Evolución del costo de compra por producto y proveedor | `fact_compra_linea` |

### 2.3 Inventario / Bodega (3)

| ID | Qué necesita responder | Tabla(s) columnar(es) que lo sirven |
|---|---|---|
| OTD-INV-04 | Qué categorías rotan y cuáles se quedan paradas, por período | `fact_movimiento_inventario` + `fact_stock_mensual` |
| OTD-INV-09 | Evolución mensual del capital inmovilizado en bodega | `fact_stock_mensual` |
| OTD-INV-10 | Mermas y sobrantes acumulados por período y motivo | `fact_movimiento_inventario` |

### 2.4 Logística / Despacho (8)

| ID | Qué necesita responder | Tabla(s) columnar(es) que lo sirven |
|---|---|---|
| OTD-LOG-03 | Cumplimiento de la fecha prometida, por transportista | `fact_envio` |
| OTD-LOG-04 | Días reales de tránsito, por transportista y período | `fact_envio` |
| OTD-LOG-05 | Problemas de entrega: tipo, intentos y desenlace | `fact_novedad_envio` |
| OTD-LOG-07 | Días de ciclo de la devolución, de solicitud a cierre | `fact_devolucion` |
| OTD-LOG-08 | Por qué devuelven y qué pasa con esa mercancía | `fact_devolucion_linea` + `fact_devolucion` |
| OTD-LOG-09 | De cada 100 envíos, cuántos terminan en devolución, mes a mes | `fact_envio` + `fact_devolucion` |
| OTD-LOG-10 | Reembolsos pagados: cuánto, por qué vía y por qué motivo | `fact_devolucion` |
| OTD-LOG-12 | Tiempo por etapa del ciclo del pedido (cuello de botella) | `fact_pedido` (hitos pivotados) |

### 2.5 Soporte (5)

| ID | Qué necesita responder | Tabla(s) columnar(es) que lo sirven |
|---|---|---|
| OTD-SOP-02 | Cumplimiento del tiempo prometido según urgencia | `fact_ticket` |
| OTD-SOP-03 | Tiempo de resolución por tipo de problema | `fact_ticket` |
| OTD-SOP-06 | Horas hasta la primera respuesta del equipo | `fact_ticket` |
| OTD-SOP-07 | Tiempo de resolución por agente | `fact_ticket` |
| OTD-SOP-08 | Productos que más reclamos y devoluciones generan | `fact_ticket` + `fact_devolucion_linea` |

### 2.6 Gerencia / Dirección (6)

| ID | Qué necesita responder | Tabla(s) columnar(es) que lo sirven |
|---|---|---|
| OTD-GER-02 | Balanza mensual: dinero que entra por ventas vs sale a proveedores | `fact_pedido` + `fact_orden_compra` (devengado) · `fact_flujo_caja` (caja) |
| OTD-GER-03 | Ganancia por categoría y período | `fact_venta_linea` |
| OTD-GER-05 | Descuento otorgado por cupón y período | `fact_pedido` |
| OTD-GER-07 | Efecto de las promociones: ventas antes vs durante | `fact_venta_linea` + `dim_promocion_producto` — *REQUIERE VOLUMEN* |
| OTD-GER-10 | Margen real producto por producto | `fact_venta_linea` + `dim_producto` |
| OTD-GER-11 | Descuento total entregado por mes y por producto | `fact_venta_linea` + `fact_pedido` |

**Nota sobre los dos objetivos en REQUIERE VOLUMEN.** COM-09 (8 devoluciones a proveedor, 6
resueltas) y GER-07 (123 líneas promocionadas frente a 4.133 de línea base) se **modelan igual que
los demás** —su tabla y su transformación quedan especificadas— pero se construyen al final (Fase 4,
§9.5) y su informe debe declarar la muestra en pantalla. El pipeline no es la causa de su debilidad
y tampoco la puede curar.

**Fuera de los 39, servido de regalo.** El catálogo declara en su §6 que «la serie temporal del
costo de envío queda para ClickHouse». No es uno de los 39 (OTD-LOG-11 es SIMPLE), pero
`fact_envio` la sirve **sin ninguna tabla adicional**: es un `GROUP BY toStartOfMonth(fecha_despacho)`
sobre columnas que la tabla ya lleva.

---

## 3. Criterio de agrupación: por qué 19 tablas y no 39

La regla aplicada, en una línea: **una tabla por GRANO, no una tabla por informe.**

Dos informes comparten tabla cuando comparten el grano y el conjunto de medidas. Dos informes
exigen tablas distintas cuando su grano difiere, porque mezclarlos produce doble conteo. El caso
canónico está en Ventas: `fact_pedido` (4.083 filas, una por pedido) y `fact_venta_linea` (10.384
filas, una por línea) **no pueden ser la misma tabla** — sumar `pedido.total` sobre las líneas lo
contaría 2,5 veces en promedio. En cambio VEN-03, VEN-06, GER-03, GER-10 y GER-11 son todos
`GROUP BY` distintos sobre el **mismo** grano de línea, y por eso comparten `fact_venta_linea`.

Rendimiento de la agrupación, medido en informes servidos por tabla:

| Tabla | Informes que sirve |
|---|---:|
| `fact_pedido` | 8 |
| `fact_venta_linea` | 7 |
| `fact_ticket` | 5 |
| `fact_devolucion` | 4 |
| `fact_envio` | 3 |
| `fact_orden_compra` | 3 |
| `fact_flujo_caja` | 3 |
| `fact_movimiento_inventario` | 3 |
| `fact_compra_linea` | 2 |
| `fact_devolucion_linea` | 2 |
| `fact_stock_mensual` | 2 |
| `fact_novedad_envio`, `fact_resena`, `fact_devolucion_proveedor` | 1 cada una |

**Tres tablas sirven un solo informe cada una, y se mantienen a propósito.** No es una concesión: es
la misma regla aplicada al revés. `fact_novedad_envio` (176 filas, hasta 3 novedades por envío),
`fact_resena` (344 filas) y `fact_devolucion_proveedor` (38 ítems) tienen granos que **no caben** en
ninguna otra tabla. Aplanarlas dentro de `fact_envio`, `fact_venta_linea` o `fact_compra_linea`
multiplicaría filas y rompería todas las demás sumas de esas tablas. Se conservan por grano, no por
capricho del informe.

### 3.1 Cuatro consolidaciones que sí se hicieron, y su prueba

Cada una se verificó contra la base antes de decidirla; ninguna se asumió del esquema:

| Consolidación | Prueba (MCP, 2026-07-30) | Tabla que se ahorra |
|---|---|---|
| **`uso_cupon` → dentro de `fact_pedido`** | `max(usos por pedido) = 1`. El `UNIQUE uso_cupon.pedido_id` del script 40 garantiza un cupón por pedido: el grano es idéntico | `fact_uso_cupon` |
| **`factura_venta` → dentro de `fact_pedido`** | `max(facturas por pedido) = 2`, pero **un solo pedido** lo alcanza (el 24662: factura anulada + reemisión). Se toma la factura `estado <> 'anulada'` | `fact_factura_venta` |
| **`factura_compra` + `cuenta_por_pagar` + `recepcion_mercancia` → dentro de `fact_orden_compra`** | Las tres son **1:1 con la orden**: `max` de facturas por OC = 1, de CxP por factura = 1, de recepciones por OC = 1. Un solo documento lógico | 3 tablas |
| **`reembolso` → dentro de `fact_devolucion`** | `max(reembolsos por devolución) = 1` | `fact_reembolso` |
| **`pago` + `pago_proveedor` → `fact_flujo_caja`** | Grano idéntico (*un monto, en una fecha, con una contraparte*) y medida idéntica. La columna `sentido` los distingue | `fact_pago_proveedor` |

La quinta merece defensa aparte, porque es la única que une dos tablas de origen distintas.
**`fact_flujo_caja`** existe porque OTD-GER-02 pide literalmente «barras enfrentadas entradas vs
salidas por mes»: con dos tablas separadas eso es un `FULL JOIN` de dos agregados; con una sola es
`GROUP BY mes, sentido`. Y VEN-09, VEN-12 y COM-03 salen filtrando `sentido`. Las columnas que solo
aplican a un lado (`motivo_fallo` al ingreso; `fecha_vencimiento` y `dias_desvio` al egreso) quedan
nulas en el otro, que en una base columnar **no cuesta nada**: una columna nula se comprime a casi
cero y no se lee si la consulta no la nombra.

**Dónde se trazó la línea.** Se consolida cuando la *medida* es la misma (un monto en una fecha).
**No** se consolidan documentos distintos: no existe un `fact_documento` que mezcle pedidos y
órdenes de compra, aunque ambos tengan número, fecha y total. Serían medidas que no se pueden sumar
juntas, y una tabla así invita a errores en la primera consulta que alguien escriba.

### 3.2 Dimensiones: solo cuatro entidades merecen tabla

En una base columnar, **denormalizar es lo correcto**. Los `JOIN` en ClickHouse son más caros que
leer una columna extra, y `LowCardinality(String)` guarda un diccionario que hace que repetir 4.083
veces el texto `"Servientrega"` ocupe prácticamente lo mismo que repetir un `UInt8`.

Por eso solo hay dimensión cuando la entidad tiene **varios atributos** que se consultan de formas
distintas: producto (1.221 variantes × marca, categoría, precio, costo), cliente (72), proveedor (11)
y el calendario. Todo lo demás —transportista (5), bodega (2), método de pago (3), estado, canal,
categoría de ticket (8), motivo de devolución (4), agente de soporte— **viaja denormalizado dentro
del hecho** como `LowCardinality(String)`. Montar `dim_transportista` para cinco nombres sería
ceremonia: costaría una tabla, un `JOIN` en cada consulta y una tarea más en el DAG, a cambio de
nada.

---

## 4. Diseño de las dimensiones (5 tablas)

Convenciones de todo el modelo:

- Base de destino: **`retailmind_dwh`**. La base `retailmind` legada **no se toca**: queda congelada
  como archivo y los 7 servicios de `analytics/` siguen apuntando a ella hasta que se decida
  migrarlos. El ETL nuevo no puede dañar lo viejo.
- Todos los `DateTime` se declaran **con zona horaria explícita `America/Guayaquil`**. Es la misma
  zona a la que se ancló `esta_en_horario()` en PostgreSQL, y omitirla es la vía directa a que los
  cortes mensuales se muevan un día (§8.6).
- El dinero es **`Decimal(14,2)`**, nunca `Float64`. Sumar 10.384 flotantes produce centavos de
  diferencia contra PostgreSQL, y el criterio de validación de §9.2 es la igualdad **al centavo**.
- Ninguna columna de la clave de ordenamiento es `Nullable`. Donde el origen admite nulo y la
  columna entra al `ORDER BY`, se sustituye por un centinela (`0`, `'sin_dato'`).
- Toda tabla lleva `fecha_carga DateTime` = momento de la corrida del ETL. Es la única columna que
  no viene de PostgreSQL, y es la que permite auditar qué versión del dato está viendo el informe.

### 4.1 `dim_fecha` — calendario

- **Propósito**: dar etiquetas de período consistentes y, sobre todo, **hacer visibles los meses sin
  actividad**. El catálogo ya documenta este problema en OTD-GER-01, que tuvo que emitir una fila
  «Día sin movimiento» a mano.
- **Grano**: un día. 2025-01-01 → 2026-12-31 = **730 filas**.
- **Origen**: ninguno — se **genera** con `numbers()` en ClickHouse. No consulta PostgreSQL.

| Columna | Tipo | Nota |
|---|---|---|
| `fecha` | `Date` | clave |
| `anio` | `UInt16` | |
| `mes` | `UInt8` | |
| `mes_inicio` | `Date` | `toStartOfMonth`, la clave de unión real de los informes mensuales |
| `mes_etiqueta` | `LowCardinality(String)` | `'2026-07'` — **ya formateada como texto** |
| `trimestre` | `UInt8` | |
| `dia_semana` | `UInt8` | 1 = lunes |
| `dia_semana_nombre` | `LowCardinality(String)` | |
| `es_fin_semana` | `UInt8` | |

`ENGINE = MergeTree ORDER BY fecha` · sin partición (730 filas).

> **Por qué `mes_etiqueta` viaja como texto ya formateado.** Es una lección cara ya pagada por este
> proyecto: un `date` puro serializado «AAAA-MM-DD» lo interpreta el formateador del frontend como
> UTC y **resta un día**. La regla vigente en `PATRON_INFORMES.md` §11 es que las fechas-día del
> resumen viajan con `to_char` y tipo `texto`. Aquí se aplica igual.
>
> **Alternativa nativa, declarada.** ClickHouse resuelve los huecos de una serie con
> `ORDER BY mes WITH FILL STEP INTERVAL 1 MONTH` sin ninguna dimensión de fecha. `dim_fecha` se
> conserva porque hace explícito el modelo dimensional que pide la asignatura y porque los atributos
> de día de semana habilitan sin trabajo los candidatos PC-02 de la próxima ronda del catálogo.

### 4.2 `dim_producto` — el producto en su grano real: la variante

- **Propósito**: atributos del producto para todos los cortes por categoría, marca y producto, y —
  crítico— **el universo completo de variantes**, que es lo que permite responder OTD-VEN-04
  (productos que *no* se venden): sin esta tabla no hay contra qué hacer el anti-join.
- **Grano**: una **variante** (`producto_variante.id`). **1.221 filas.** El grano es la variante y no
  el producto porque es la variante la que se vende, se mueve en el kardex y se compra — y porque
  el id público del catálogo del cliente ya es el de la variante.
- **Origen**: `producto_variante` ⟕ `producto` ⟕ `marca` ⟕ `producto_categoria` ⟕ `categoria`.

| Columna | Tipo | Origen / transformación |
|---|---|---|
| `producto_variante_id` | `UInt32` | `producto_variante.id` |
| `sku` | `String` | |
| `producto_id` | `UInt32` | |
| `producto_nombre` | `String` | `producto.nombre` |
| `slug` | `String` | `producto.slug` — es también el **puente con el ClickHouse legado** (§4.4 del diagnóstico) |
| `marca` | `LowCardinality(String)` | `marca.nombre`; `'sin_marca'` si NULL |
| `categoria` | `LowCardinality(String)` | `categoria.nombre` vía `producto_categoria` |
| `categoria_id` | `UInt16` | |
| `precio` | `Decimal(14,2)` | vigente |
| `costo` | `Decimal(14,2)` | **vigente, no histórico** (§8.3) |
| `margen_catalogo_pct` | `Decimal(6,2)` | `(precio-costo)/precio*100`, calculado en la carga |
| `peso_kg` | `Decimal(10,3)` | |
| `activo` | `UInt8` | |
| `fecha_carga` | `DateTime('America/Guayaquil')` | |

`ENGINE = ReplacingMergeTree(fecha_carga) ORDER BY producto_variante_id` · sin partición.

**Transformación verificada, no supuesta**: `max(categorías por producto) = 1` y **1.214 de 1.214
productos** tienen categoría principal. La denormalización de categoría es por tanto **1:1 y sin
pérdida** — no hay que elegir entre varias ni inventar una regla de desempate. Se comprobó antes de
diseñar, precisamente porque `producto_categoria` tiene un `es_principal` que sugiere lo contrario.

### 4.3 `dim_cliente` — 72 filas

- **Grano**: un cliente. **Origen**: `cliente` (⟕ `usuario` para fecha de alta).

| Columna | Tipo | Nota |
|---|---|---|
| `cliente_id` | `UInt32` | |
| `nombre_completo` | `String` | `nombre \|\| ' ' \|\| apellido` |
| `email` | `String` | |
| `ciudad` / `provincia` | `LowCardinality(String)` | de su dirección predeterminada; `'sin_direccion'` si no tiene |
| **`segmento`** | `LowCardinality(String)` | **`'sin_segmentar'` en los 72, valor constante por diseño** — la segmentación de cliente no existe ni es derivable (§1.2). No agrupar por ella esperando dos poblaciones |
| `tipo_identificacion` | `LowCardinality(String)` | `'cedula'` en los 72; el RUC sería la señal de cliente empresa |
| `fecha_alta` | `DateTime('America/Guayaquil')` | |
| `activo` | `UInt8` | |

`ENGINE = ReplacingMergeTree(fecha_carga) ORDER BY cliente_id`.

### 4.4 `dim_proveedor` — 11 filas

`proveedor_id`, `razon_social`, `nombre_comercial`, `ruc`, `ciudad`, `dias_credito UInt16`,
`activo`. `ENGINE = ReplacingMergeTree(fecha_carga) ORDER BY proveedor_id`.

`dias_credito` no es adorno: es la referencia contra la cual OTD-COM-03 juzga si el vencimiento
pactado se respetó.

### 4.5 `dim_promocion_producto` — puente promoción ↔ producto

- **Propósito**: única razón de ser, OTD-GER-07 («ventas antes vs durante la promoción»). Es un
  **puente con ventana temporal**: sin él, la pregunta «¿esta línea se vendió dentro de la ventana de
  una promoción que cubría este producto?» no tiene respuesta.
- **Grano**: una pareja (promoción, producto). **232 filas** sobre 24 promociones.
- **Origen**: `promocion_producto` ⋈ `promocion`.

| Columna | Tipo |
|---|---|
| `promocion_id` `UInt32`, `promocion_nombre` `String`, `producto_id` `UInt32` | |
| `fecha_inicio` / `fecha_fin` | `DateTime('America/Guayaquil')` |
| `tipo_descuento` `LowCardinality(String)`, `valor` `Decimal(14,2)` | |
| `prioridad` `UInt8`, `acumulable` `UInt8`, `activo` `UInt8` | |

`ENGINE = ReplacingMergeTree(fecha_carga) ORDER BY (producto_id, fecha_inicio)` — ordenada por
producto primero porque la consulta de GER-07 entra siempre por producto y luego acota la ventana.

---

## 5. Diseño de las tablas de hechos (14 tablas)

### 5.1 `fact_pedido` — cabecera de la venta y ciclo del pedido

- **Propósito**: la venta vista **por documento**, con su cupón, su factura y **los hitos de su
  recorrido**. Es la tabla más solicitada del modelo: 8 informes.
- **Grano**: **un pedido**. **4.083 filas.**
- **Alimenta**: OTD-VEN-05, VEN-07, VEN-13, VEN-14 (denominador), **LOG-12**, GER-02 (ingreso
  devengado), GER-05, GER-11 (capa de cupón).
- **Origen**: `pedido` ⋈ `estado_pedido` ⟕ `usuario` (vendedor) ⟕ `uso_cupon` ⟕ `cupon` ⟕
  `factura_venta` ⟕ `historial_estado_pedido` (pivotado) ⟕ `direccion`→`ciudad`→`provincia`.

| Columna | Tipo | Transformación |
|---|---|---|
| `pedido_id` | `UInt32` | |
| `numero` | `String` | |
| `fecha_pedido` | `DateTime('America/Guayaquil')` | |
| `mes` | `Date` | `toStartOfMonth(fecha_pedido)` — precalculada, es la clave de casi todo `GROUP BY` |
| `cliente_id` | `UInt32` | |
| `canal` | `LowCardinality(String)` | `web` / `tienda` / `telefono`. **El medio, no el segmento** |
| `estado` | `LowCardinality(String)` | `estado_pedido.codigo` |
| `es_cancelado` | `UInt8` | `estado='cancelado'`. Los informes de ingreso **excluyen cancelados**: son los 159 que separan $5.716.436,55 de $5.498.570,35 |
| `vendedor` | `LowCardinality(String)` | `'(canal en línea)'` cuando `vendedor_id` es NULL — por diseño, el autor del checkout es el cliente |
| `subtotal`, `monto_descuento`, `monto_impuesto`, `costo_envio`, `total` | `Decimal(14,2)` | tal cual |
| `lineas` `UInt16`, `unidades` `UInt32` | | agregados desde `pedido_detalle` |
| `codigo_cupon` | `LowCardinality(String)` | `''` si no hubo. **1:1 probado** |
| `monto_cupon` | `Decimal(14,2)` | `uso_cupon.monto_descontado` |
| `factura_numero` `String`, `factura_total` `Decimal(14,2)`, `fecha_factura` `DateTime` | | de la factura **no anulada** |
| `fecha_pagado`, `fecha_facturado`, `fecha_preparado`, `fecha_despachado`, `fecha_entregado` | `Nullable(DateTime(...))` | **pivote** de `historial_estado_pedido`: `minIf(fecha, codigo=…)` |
| `horas_pago_a_preparacion`, `horas_preparacion_a_despacho`, `horas_despacho_a_entrega`, `horas_ciclo_total` | `Nullable(Float32)` | diferencias entre hitos |
| `ciudad_entrega`, `provincia_entrega` | `LowCardinality(String)` | |

```sql
ENGINE = MergeTree
PARTITION BY toYYYYMM(fecha_pedido)
ORDER BY (fecha_pedido, canal, cliente_id)
```

**Por qué ese orden acelera lo que sirve.** Los ocho informes filtran **siempre** por rango de
fechas: con `fecha_pedido` de prefijo, ClickHouse descarta particiones enteras y dentro de cada una
salta granulos por el índice disperso. `canal` va segundo porque VEN-07 y VEN-13 agrupan por él, y
un `GROUP BY` sobre una columna ya ordenada evita rehashear. `cliente_id` va tercero para VEN-05.
El orden inverso (`cliente_id` primero) obligaría a leer todas las particiones en cualquier consulta
por período — que son todas.

> **Decisión de diseño: LOG-12 no tiene tabla propia.** El primer impulso es una
> `fact_etapa_pedido` en formato largo, una fila por transición (24.608). Se descartó porque **el
> grano de la pregunta es el pedido**, no la transición: el informe pide «cuántas horas del pago a
> la preparación», que es un atributo *del pedido*. Pivotar los cinco hitos dentro de `fact_pedido`
> elimina una tabla y una unión, y el `avg()` por tramo sale directo. El precio, declarado: la lista
> de hitos queda **fijada en la transformación**, de modo que un estado nuevo en `estado_pedido`
> exige tocar el ETL. Es asumible: los 11 estados están cerrados por CHECK y el tramo de salida
> quedó estabilizado con el script 39. Cobertura verificada: **3.906 pedidos con hito `pagado` y
> 3.696 con `entregado`** sobre 24.608 registros de historial.

### 5.2 `fact_venta_linea` — la venta al grano de línea

- **Propósito**: la venta vista **por producto**. Es la tabla del margen y del descuento.
- **Grano**: **una línea de pedido**. **10.384 filas.**
- **Alimenta**: OTD-VEN-03, VEN-04, VEN-06, GER-03, GER-07, GER-10, GER-11.
- **Origen**: `pedido_detalle` ⋈ `pedido` ⋈ `producto_variante` ⟕ `producto`/`categoria`/`marca`
  ⟕ `factura_venta_detalle`.

| Columna | Tipo | Transformación |
|---|---|---|
| `pedido_detalle_id` `UInt64`, `pedido_id` `UInt32`, `numero_pedido` `String` | | |
| `fecha_pedido` `DateTime(...)`, `mes` `Date` | | de la cabecera |
| `canal`, `estado_pedido`, `es_cancelado` | `LowCardinality`/`UInt8` | denormalizados: evitan unir a `fact_pedido` en el 90 % de las consultas |
| `cliente_id` | `UInt32` | |
| `producto_variante_id` `UInt32`, `sku` `String`, `producto_nombre` `String` | | |
| `categoria`, `marca` | `LowCardinality(String)` | |
| `cantidad` | `UInt32` | |
| `precio_unitario` | `Decimal(14,2)` | precio **a la fecha de la venta** — lo conserva la línea |
| `subtotal_bruto` | `Decimal(14,2)` | `cantidad * precio_unitario` |
| `descuento_promocion` | `Decimal(14,2)` | `pedido_detalle.monto_descuento` |
| `descuento_cupon_prorrateado` | `Decimal(14,2)` | `factura_venta_detalle.monto_descuento − pedido_detalle.monto_descuento` |
| `descuento_total` | `Decimal(14,2)` | suma de las dos capas |
| `monto_impuesto` | `Decimal(14,2)` | |
| `venta_neta` | `Decimal(14,2)` | `subtotal_bruto − descuento_total` |
| `costo_unitario` | `Decimal(14,2)` | `producto_variante.costo` **vigente** (§8.3) |
| `costo_total` | `Decimal(14,2)` | `cantidad * costo_unitario` |
| `margen` | `Decimal(14,2)` | `venta_neta − costo_total` |
| `margen_pct` | `Decimal(6,2)` | |
| `tuvo_promocion` | `UInt8` | `descuento_promocion > 0` |

```sql
ENGINE = MergeTree
PARTITION BY toYYYYMM(fecha_pedido)
ORDER BY (fecha_pedido, categoria, producto_variante_id)
```

**Por qué ese orden.** VEN-06 y GER-03 agrupan por (mes, categoría) — las dos primeras columnas del
orden, así que la agregación recorre datos ya contiguos. VEN-03 y GER-10 rematan por variante, que
va tercera. Poner `producto_variante_id` primero optimizaría la ficha de *un* producto a costa de
los cinco informes de período, que son la mayoría.

**La transformación de los dos descuentos es el punto delicado, y está verificada.** Los scripts
72-73 escriben tres lugares distintos: `pedido.monto_descuento` (cupón, en la cabecera),
`pedido_detalle.monto_descuento` (promoción, en la línea) y `factura_venta_detalle.monto_descuento`
(**las dos capas ya prorrateadas al producto**). Comprobado sobre los 657 pedidos con algún
descuento: en **651** se cumple `Σ factura_detalle.descuento = pedido.monto_descuento +
Σ pedido_detalle.monto_descuento`. Es esa identidad la que permite despejar la porción de cupón por
línea, que es justo lo que OTD-GER-11 pide («descuento total por mes **y por producto**»). Los **6
pedidos que no cuadran** son los legacy ya declarados en `CLAUDE.md` (20 y 21, con factura pero sin
fila en `pago`, más el 24662 de la factura anulada): el ETL los marca con
`descuento_cupon_prorrateado = 0` y los **cuenta en un contador de excepciones** que se registra en
la bitácora de la corrida, en vez de dejarlos pasar en silencio.

### 5.3 `fact_flujo_caja` — todo el dinero que se mueve, en los dos sentidos

- **Propósito**: la caja del negocio en una sola tabla. Cobros de clientes y pagos a proveedores
  comparten grano y medida; los separa una columna.
- **Grano**: **un movimiento de dinero** (un `pago` o un `pago_proveedor`). **4.079 + 902 = 4.981
  filas.**
- **Alimenta**: OTD-VEN-09, VEN-12, COM-03, y el lado *caja* de GER-02.
- **Origen**: `pago` ⋈ `metodo_pago` ⟕ `transaccion_pago` ⟕ `pedido` **UNION ALL**
  `pago_proveedor` ⋈ `cuenta_por_pagar` ⋈ `factura_compra` ⟕ `proveedor`.

| Columna | Tipo | Ingreso | Egreso |
|---|---|---|---|
| `movimiento_id` | `UInt64` | `pago.id` | `pago_proveedor.id` |
| `sentido` | `LowCardinality(String)` | `'ingreso'` | `'egreso'` |
| `fecha` `DateTime(...)`, `mes` `Date` | | `fecha_pago` | `fecha_pago` |
| `monto` | `Decimal(14,2)` | ✔ | ✔ |
| `estado` | `LowCardinality(String)` | `completado` / `fallido` | `completado` |
| `metodo_pago` | `LowCardinality(String)` | ✔ | ✔ |
| `contraparte_tipo` | `LowCardinality(String)` | `'cliente'` | `'proveedor'` |
| `contraparte_id` `UInt32`, `contraparte_nombre` `String` | | cliente | proveedor |
| `documento_id` `UInt32`, `documento_numero` `String` | | pedido | factura de compra |
| `canal` | `LowCardinality(String)` | canal del pedido | `''` |
| `motivo_fallo` | `LowCardinality(String)` | ✔ **normalizado** | `''` |
| `fecha_vencimiento` | `Nullable(Date)` | — | ✔ |
| `dias_desvio_vencimiento` | `Nullable(Int16)` | — | `fecha_pago − fecha_vencimiento`; **negativo = anticipado** |
| `a_tiempo` | `Nullable(UInt8)` | — | `dias_desvio <= 0` |

```sql
ENGINE = MergeTree
PARTITION BY toYYYYMM(fecha)
ORDER BY (fecha, sentido, metodo_pago)
```

`sentido` va segundo porque **todos** los informes de esta tabla lo filtran o lo agrupan; ponerlo
primero mataría el podado por fecha, que es más selectivo.

**Normalización obligatoria de `motivo_fallo`.** Los 176 intentos fallidos viven en
`transaccion_pago.respuesta_pasarela` (jsonb), y hay **6 valores distintos donde debería haber 5**:
conviven el código `tarjeta_rechazada` y el texto libre `"Tarjeta rechazada por el emisor"`. Sin un
mapa de normalización, OTD-VEN-12 muestra dos filas para el mismo motivo. El mapa
(`'Tarjeta rechazada por el emisor' → 'tarjeta_rechazada'`) va en la transformación, con una regla
de escape: **cualquier valor no previsto se carga como `'otro'` y se registra en la bitácora** —
nunca se descarta ni se silencia.

### 5.4 `fact_orden_compra` — la compra como un solo documento

- **Propósito**: unificar en una fila el ciclo completo de abastecimiento: orden → recepción →
  factura → cuenta por pagar. Las cuatro son 1:1 (probado, §3.1).
- **Grano**: **una orden de compra**. **865 filas.**
- **Alimenta**: OTD-COM-04, COM-05, COM-06, y el lado *devengado* de GER-02.
- **Origen**: `orden_compra` ⟕ `recepcion_mercancia` ⟕ `factura_compra` ⟕ `cuenta_por_pagar` ⋈
  `proveedor`.

| Columna | Tipo | Nota |
|---|---|---|
| `orden_compra_id` `UInt32`, `numero` `String` | | |
| `proveedor_id` `UInt16`, `proveedor` `LowCardinality(String)` | | |
| `bodega` | `LowCardinality(String)` | |
| `estado` | `LowCardinality(String)` | aprobar deja la orden en `'confirmada'`; **no existe `'aprobada'`** |
| `fecha_emision` `Date`, `mes` `Date` | | |
| `fecha_entrega_esperada` | `Nullable(Date)` | **849 de 865** |
| `fecha_recepcion` | `Nullable(DateTime(...))` | **839 recepciones** |
| `dias_ciclo_real` | `Nullable(Int16)` | recepción − emisión → **COM-06** |
| `dias_desvio_promesa` | `Nullable(Int16)` | recepción − esperada → **COM-05**, **825 pares comparables** |
| `cumplio_promesa` | `Nullable(UInt8)` | `dias_desvio_promesa <= 0` |
| `subtotal`, `monto_impuesto`, `total` | `Decimal(14,2)` | de la orden |
| `factura_numero` `String`, `fecha_factura` `Nullable(Date)`, `factura_total` `Decimal(14,2)` | | **COM-04**, **GER-02** |
| `cxp_monto_original`, `cxp_saldo_pendiente` | `Decimal(14,2)` | |
| `cxp_estado` `LowCardinality(String)`, `cxp_fecha_vencimiento` `Nullable(Date)` | | |
| `lineas` `UInt16`, `unidades_pedidas` `UInt32`, `unidades_recibidas` `UInt32`, `unidades_rechazadas` `UInt32` | | agregados del detalle |

```sql
ENGINE = MergeTree
PARTITION BY toYYYYMM(fecha_emision)
ORDER BY (fecha_emision, proveedor_id)
```

`proveedor_id` va segundo porque los cuatro informes agrupan por proveedor tras filtrar el período.

> **Ojo con GER-02.** La balanza compara $5,42 M de venta facturada contra **$22,47 M de compra
> facturada**. No es un error del pipeline: el stock sembrado equivale a ~6,8 años de rotación
> (documentado en `CLAUDE.md`, rebalanceo 74-78). El informe debe presentarlo como el hecho que es,
> no maquillarlo.

### 5.5 `fact_compra_linea` — la compra al grano de producto

- **Propósito**: qué se compró, a qué precio y cuánto llegó mal.
- **Grano**: **una línea de orden de compra**, enriquecida con su recepción. **2.949 filas** (2.855
  con línea de recepción).
- **Alimenta**: OTD-COM-07, COM-12.
- **Origen**: `orden_compra_detalle` ⋈ `orden_compra` ⟕ `recepcion_detalle` ⋈ `producto_variante`.

Columnas: `orden_compra_detalle_id`, `orden_compra_id`, `numero_oc`, `fecha_emision Date`,
`mes Date`, `proveedor_id`, `proveedor`, `producto_variante_id`, `sku`, `producto_nombre`,
`categoria`, `cantidad_pedida UInt32`, `cantidad_recibida UInt32`, `cantidad_rechazada UInt32`,
`motivo_rechazo LowCardinality(String)`, `pct_rechazo Decimal(6,2)`,
`precio_unitario Decimal(14,2)`, `subtotal Decimal(14,2)`, `completa UInt8`.

```sql
ENGINE = MergeTree
PARTITION BY toYYYYMM(fecha_emision)
ORDER BY (producto_variante_id, proveedor_id, fecha_emision)
```

**Aquí el orden se invierte a propósito, y es la única tabla del modelo donde ocurre.** OTD-COM-12
pregunta «cómo cambia el precio que cobra el proveedor por **cada producto** entre una compra y la
siguiente»: es una **serie por producto**, no un corte por período. Con
`(producto_variante_id, proveedor_id, fecha_emision)` las compras sucesivas de una misma variante a
un mismo proveedor quedan **físicamente contiguas y ya ordenadas en el tiempo**, que es exactamente
lo que necesitan `neighbor()` o `lagInFrame()` para calcular la variación entre compras — sin
ordenar nada en tiempo de consulta. COM-07 sí filtra por período, pero agrupa por proveedor sobre
2.949 filas: le sobra con la partición.

### 5.6 `fact_movimiento_inventario` — el kardex

- **Propósito**: todo el movimiento físico de mercancía. Es también el insumo del que se deriva
  `fact_stock_mensual`.
- **Grano**: **un movimiento de kardex**. **13.287 filas.**
- **Alimenta**: OTD-INV-04, INV-10, y la columna «última salida» de VEN-04.
- **Origen**: `movimiento_inventario` ⋈ `tipo_movimiento` ⋈ `producto_variante`/`categoria` ⋈
  `bodega` ⟕ `ajuste_inventario` (por `referencia_tipo`/`referencia_id`).

| Columna | Tipo | Nota |
|---|---|---|
| `movimiento_id` | `UInt64` | |
| `fecha` `DateTime(...)`, `mes` `Date` | | |
| `producto_variante_id` `UInt32`, `sku`, `producto_nombre`, `categoria`, `marca` | | |
| `bodega` | `LowCardinality(String)` | 2 bodegas |
| `tipo_movimiento` | `LowCardinality(String)` | los **9 códigos**, todos en uso |
| `naturaleza` | `LowCardinality(String)` | `entrada` / `salida` / `transferencia` / `ajuste` |
| `factor` | `Int8` | `+1` / `-1` |
| `cantidad` | `UInt32` | siempre positiva en el origen |
| `cantidad_con_signo` | `Int32` | `cantidad * factor` — **la que se suma** |
| `stock_anterior`, `stock_nuevo` | `Int32` | la cadena ya encadenada por `(fecha_creacion, id)` |
| `costo_unitario` | `Decimal(14,2)` | el del movimiento, **no el vigente de la variante** |
| `valor_movimiento` | `Decimal(14,2)` | `cantidad * costo_unitario` |
| `referencia_tipo` `LowCardinality(String)`, `referencia_id` `UInt64` | | |
| `ajuste_motivo`, `ajuste_tipo` | `LowCardinality(String)` | de `ajuste_inventario` cuando `referencia_tipo='ajuste_inventario'` → **INV-10** |

```sql
ENGINE = MergeTree
PARTITION BY toYYYYMM(fecha)
ORDER BY (producto_variante_id, bodega, fecha, movimiento_id)
```

**El orden replica la cadena del kardex, y eso no es estética.** La regla operativa del sistema es
que el kardex se encadena por `(fecha_creacion, id)` dentro de cada par `(variante, bodega)` — está
documentada en `CLAUDE.md` (scripts 78 y 79-84) y fue la causa de reescribir 2.234 filas en su
momento. Ordenar la tabla columnar por esa misma clave hace que **la reconstrucción de saldos de
§5.7 sea una lectura secuencial**, sin `ORDER BY` en tiempo de consulta. `cantidad_con_signo` se
precalcula porque INV-04 e INV-10 suman entradas y salidas juntas, y multiplicar por `factor` en
cada consulta invita a que alguien lo olvide una vez.

**Verificación de cardinalidad**: el kardex cubre exactamente **1.406 pares (variante, bodega)**,
que son las **1.406 posiciones de `inventario`**. No hay posición sin historia ni historia sin
posición — lo que hace segura la derivación de §5.7.

### 5.7 `fact_stock_mensual` — la única tabla preagregada del modelo

- **Propósito**: el saldo y el valor del inventario **al cierre de cada mes**. Es el único informe
  cuyo cálculo *no* es una agregación de un hecho atómico, sino una **reconstrucción**.
- **Grano**: **(mes, bodega, variante)**. 19 meses × 1.406 posiciones ≈ **26.700 filas.**
- **Alimenta**: OTD-INV-09 (el objetivo), OTD-INV-04 (stock promedio como denominador de rotación).
- **Origen**: **derivada dentro de ClickHouse** desde `fact_movimiento_inventario` ⋈ `dim_producto`.
  **No vuelve a consultar PostgreSQL.**

| Columna | Tipo |
|---|---|
| `mes` | `Date` (primer día del mes) |
| `bodega` `LowCardinality(String)`, `producto_variante_id` `UInt32` | |
| `sku`, `producto_nombre`, `categoria`, `marca` | |
| `stock_cierre` | `Int32` |
| `entradas_mes`, `salidas_mes` | `Int32` |
| `movimientos_mes` | `UInt16` |
| `costo_unitario` | `Decimal(14,2)` (vigente) |
| `valor_cierre` | `Decimal(14,2)` |
| `mes_sin_movimiento` | `UInt8` (saldo arrastrado, no recalculado) |

```sql
ENGINE = MergeTree
PARTITION BY toYear(mes)
ORDER BY (mes, bodega, categoria, producto_variante_id)
```

Partición **por año**, no por mes: 19 particiones de 1.400 filas ya serían demasiado pequeñas, y
ClickHouse penaliza el exceso de particiones más de lo que premia el podado.

**La transformación, que es la más sutil del pipeline.** El instinto es sumar
`cantidad_con_signo` desde el principio de los tiempos hasta el cierre de cada mes. Funciona, pero
es O(n²) y —peor— **repite un cálculo que el sistema ya hizo y garantiza**: cada movimiento lleva su
`stock_nuevo`, y la cadena está encadenada y arranca en 0. Entonces:

1. Para cada `(variante, bodega, mes)` **con** movimiento: `stock_cierre = argMax(stock_nuevo,
   (fecha, movimiento_id))` — el saldo que dejó el último movimiento del mes. Una pasada.
2. Para los meses **sin** movimiento: arrastrar el último saldo conocido. Se resuelve con un
   producto cartesiano contra los 19 meses y un `LAST_VALUE(...) IGNORE NULLS OVER (PARTITION BY
   variante, bodega ORDER BY mes ROWS UNBOUNDED PRECEDING)`, marcando la fila con
   `mes_sin_movimiento = 1`.
3. Valorizar con `dim_producto.costo`.

**Salvedad que hay que declarar en la pantalla del informe** (§8.3): la valorización usa el **costo
vigente**, no el costo histórico —que no existe en el sistema—. Por tanto INV-09 responde «cuántas
unidades había cada mes, valoradas a precio de hoy», que **no** es lo mismo que «cuánto valía la
bodega aquel mes». Es una serie de volumen valorizado a moneda constante. Dicho así es una respuesta
honesta y útil; presentado como «el valor histórico del inventario» sería falso.

### 5.8 `fact_envio` — la última milla

- **Grano**: **un envío**. **2.872 filas.** (Verificado: hasta **4 envíos por pedido** — por eso no
  se puede fundir en `fact_pedido`.)
- **Alimenta**: OTD-LOG-03, LOG-04, LOG-09 (denominador), y de regalo la serie temporal del costo de
  envío que el catálogo dejó pendiente en su §6.
- **Origen**: `envio` ⋈ `pedido` ⋈ `transportista` ⟕ `metodo_envio` ⟕ `bodega` ⟕ la cadena
  `direccion`→`ciudad`→`provincia`→`pais`→`zona_envio` ⟕ conteo de `novedad_envio`.

| Columna | Tipo | Nota |
|---|---|---|
| `envio_id` `UInt32`, `numero` `String`, `pedido_id` `UInt32` | | |
| `transportista` | `LowCardinality(String)` | 5 |
| `metodo_envio`, `bodega_origen` | `LowCardinality(String)` | |
| **`zona`** | `LowCardinality(String)` | **derivada**, ver abajo |
| `ciudad_destino`, `provincia_destino` | `LowCardinality(String)` | |
| `estado` | `LowCardinality(String)` | |
| `fecha_despacho` `DateTime(...)`, `mes` `Date` | | |
| `fecha_entrega_estimada` | `Nullable(Date)` | |
| `fecha_entrega_real` | `Nullable(DateTime(...))` | **2.727 entregados** |
| `dias_transito` | `Nullable(Int16)` | real − despacho → **LOG-04** |
| `dias_desvio_promesa` | `Nullable(Int16)` | real − estimada → **LOG-03**, **2.723 pares** |
| `entregado_a_tiempo` | `Nullable(UInt8)` | |
| `costo` `Decimal(14,2)`, `peso_total_kg` `Decimal(10,3)` | | **2.848 con costo** |
| `costo_por_kg` | `Nullable(Decimal(10,4))` | |
| `novedades` `UInt8`, `tuvo_novedad` `UInt8` | | |

```sql
ENGINE = MergeTree
PARTITION BY toYYYYMM(fecha_despacho)
ORDER BY (fecha_despacho, transportista, zona)
```

Los tres informes comparan **transportistas dentro de un período**: el orden es literalmente la
forma de la pregunta.

**La zona es la transformación cara de esta tabla, y hay que hacerla en el ETL una vez.** `envio` no
tiene columna de zona: se resuelve por la cadena **ciudad > provincia > país** contra `zona_envio`,
la misma de `VentasService.asignarEnvioPorZona`. Resolución verificada sobre los 2.872 envíos:
**181 por ciudad, 596 por provincia, 2.078 por país y 17 sin dirección** (→ `'sin_zona'`). Agrupar
por país directamente, que es la simplificación tentadora, mandaría el 100 % a una sola fila y
volvería inútil el informe. El coste de tres `LEFT JOIN` con precedencia se paga **una vez por
corrida**, no una vez por consulta — que es precisamente para lo que sirve un ETL.

### 5.9 `fact_novedad_envio` — incidencias de entrega

- **Grano**: **una novedad**. **176 filas** (hasta 3 por envío). **Alimenta**: OTD-LOG-05.
- **Origen**: `novedad_envio` ⋈ `envio` ⋈ `pedido` ⟕ `transportista` ⟕ `usuario`.

Columnas: `novedad_id`, `envio_id`, `pedido_id`, `fecha_registro DateTime`, `mes Date`, `tipo`
(los 5 del CHECK), `estado`, `accion` (`reprogramar` / `devolver_almacen`), `transportista`, `zona`,
`intento_numero UInt8`, `fecha_resolucion Nullable(DateTime)`,
`horas_hasta_resolucion Nullable(Float32)`, `resuelta UInt8` (**169 de 176**).

```sql
ENGINE = MergeTree PARTITION BY toYYYYMM(fecha_registro) ORDER BY (fecha_registro, tipo, transportista)
```

### 5.10 `fact_devolucion` — el RMA completo, cabecera

- **Grano**: **una devolución**. **196 filas.** **Alimenta**: OTD-VEN-14, LOG-07, LOG-09
  (numerador), LOG-10.
- **Origen**: `devolucion` ⋈ `pedido` ⋈ `motivo_devolucion` ⟕ `historial_estado_devolucion`
  (pivotado) ⟕ `reembolso` ⟕ `cliente`.

| Columna | Tipo | Nota |
|---|---|---|
| `devolucion_id` `UInt32`, `numero` `String`, `pedido_id` `UInt32` | | |
| `cliente_id` `UInt32` | | |
| `fecha_solicitud` `DateTime(...)`, `mes` `Date` | | |
| `estado` | `LowCardinality(String)` | del ciclo de 8 |
| `motivo` | `LowCardinality(String)` | los **4** en uso |
| `monto_total` | `Decimal(14,2)` | lo mantiene un trigger; **nunca se escribe** — aquí solo se lee |
| `fecha_aprobacion`, `fecha_recepcion`, `fecha_inspeccion`, `fecha_cierre` | `Nullable(DateTime(...))` | pivote de `historial_estado_devolucion` (**1.008 registros**) |
| `dias_ciclo_total` | `Nullable(Float32)` | cierre − solicitud → **LOG-07** |
| `dias_hasta_aprobacion`, `dias_transito_retorno`, `dias_hasta_reembolso` | `Nullable(Float32)` | tramos; **161 de 196** tienen ≥3 pasos fechados |
| `monto_reembolsado` `Decimal(14,2)`, `metodo_reembolso` `LowCardinality(String)`, `fecha_reembolso` `Nullable(DateTime)` | | **85 reembolsos, $44.525,63** → **LOG-10** |
| `mes_pedido` `Date`, `canal_pedido` `LowCardinality(String)`, `total_pedido` `Decimal(14,2)` | | para el % de VEN-14 |
| `transportista_retorno`, `bodega_retorno` | `LowCardinality(String)` | |

```sql
ENGINE = MergeTree PARTITION BY toYYYYMM(fecha_solicitud) ORDER BY (fecha_solicitud, estado, motivo)
```

**Detalle de VEN-14 que decide si el informe es correcto**: el numerador (lo devuelto) se fecha por
`devolucion.fecha_creacion`, pero el denominador (la venta) por `pedido.fecha_pedido`. Como una
devolución de julio puede corresponder a un pedido de mayo, **el porcentaje depende de contra qué
mes se divida**. Por eso la tabla lleva **las dos** fechas (`mes` y `mes_pedido`) y el informe debe
declarar cuál usa. Este diseño recomienda `mes` (mes de la devolución) para «cuánto devuelven al
mes», que es la pregunta de control que hace el gerente.

### 5.11 `fact_devolucion_linea` — qué producto volvió y en qué estado

- **Grano**: **una línea de devolución**. **274 filas** (162 con inspección registrada).
  **Alimenta**: OTD-LOG-08, SOP-08 (lado devoluciones).
- **Origen**: `devolucion_detalle` ⋈ `devolucion` ⋈ `pedido_detalle` ⋈ `producto_variante`.

Columnas: `devolucion_detalle_id`, `devolucion_id`, `numero_devolucion`, `fecha_solicitud`, `mes`,
`producto_variante_id`, `sku`, `producto_nombre`, `categoria`, `marca`, `cantidad UInt32`,
`motivo LowCardinality(String)`, **`resultado_inspeccion LowCardinality(String)`**
(`apto_reventa` / `defectuoso` / `rechazado` / `'sin_inspeccionar'`), `reingresa_stock UInt8`
(solo `apto_reventa`), `monto_linea Decimal(14,2)`.

```sql
ENGINE = MergeTree PARTITION BY toYYYYMM(fecha_solicitud) ORDER BY (fecha_solicitud, categoria, producto_variante_id)
```

`reingresa_stock` se precalcula porque es la regla de negocio del RMA (solo lo apto vuelve al stock
vendible, con kardex `entrada_devolucion_cliente`) y LOG-08 pregunta exactamente eso: «qué pasa con
esa mercancía».

### 5.12 `fact_ticket` — soporte, cinco informes en una tabla

- **Grano**: **un ticket**. **248 filas.** **Alimenta**: OTD-SOP-02, SOP-03, SOP-06, SOP-07 y el
  lado reclamos de SOP-08. Es la tabla con mejor relación informes/filas del modelo.
- **Origen**: `ticket_soporte` ⋈ `categoria_ticket` ⟕ `usuario` (agente) ⟕ `cliente` ⟕
  `producto_variante` ⟕ `mensaje_ticket` (primera respuesta).

| Columna | Tipo | Nota |
|---|---|---|
| `ticket_id` `UInt32`, `numero` `String` | | `TICK-AAAA-NNNN` |
| `fecha_creacion` `DateTime(...)`, `mes` `Date` | | |
| `categoria` | `LowCardinality(String)` | **8, todas con casos** |
| `prioridad` | `LowCardinality(String)` | automática por categoría |
| `estado` | `LowCardinality(String)` | |
| `agente` | `LowCardinality(String)` | `'(sin asignar)'` si NULL — **es el dato accionable**, no un hueco |
| `cliente_id` `UInt32` | | |
| `producto_variante_id` `UInt32`, `producto_nombre` `String`, `categoria_producto` | | **142 de 248** → **SOP-08** |
| `fecha_limite` | `DateTime(...)` | **248 de 248** (script 49) |
| `fecha_cierre` | `Nullable(DateTime(...))` | **76 cierres** |
| `fecha_primera_respuesta` | `Nullable(DateTime(...))` | primer `mensaje_ticket` con `usuario_id` no nulo y `es_interno = false` — **193 de 248** |
| `horas_primera_respuesta` | `Nullable(Float32)` | → **SOP-06** |
| `horas_resolucion` | `Nullable(Float32)` | → **SOP-03**, **SOP-07** |
| `cumplio_sla` | `Nullable(UInt8)` | `fecha_cierre <= fecha_limite` → **SOP-02** |
| `horas_sla_comprometidas` | `UInt16` | 2/4/24/72 según prioridad |

```sql
ENGINE = MergeTree PARTITION BY toYYYYMM(fecha_creacion) ORDER BY (fecha_creacion, categoria, prioridad)
```

**La definición de «primera respuesta» es una decisión, no un dato.** Se adopta la del catálogo:
*el primer mensaje cuyo autor es del equipo (`usuario_id` poblado) y que el cliente puede ver
(`es_interno = false`)*. Una nota interna entre agentes **no** cuenta como respuesta al cliente, y
eso cambia el número: hay 523 mensajes y solo 193 tickets con primera respuesta así definida. La
definición debe quedar escrita en la pantalla del informe, porque cualquier otra da otro resultado
igual de defendible.

**Aviso sobre SOP-02.** Solo **76 de 248** tickets están cerrados. El cumplimiento de SLA sobre los
otros 172 es *desconocido*, no *incumplido*. El informe debe partir la base en «cerrados a tiempo /
cerrados tarde / aún abiertos dentro del plazo / **abiertos y ya vencidos**», que además es la
categoría accionable. Calcularlo sobre 248 como si todos hubieran cerrado produciría un número
falso.

### 5.13 `fact_resena` — la voz del cliente sobre el producto

- **Grano**: **una reseña**. **344 filas.** **Alimenta**: OTD-VEN-11.
- **Origen**: `resena` ⋈ `producto` ⟕ `categoria`/`marca` ⟕ `cliente`.

Columnas: `resena_id`, `fecha_creacion DateTime`, `mes Date`, `producto_id`, `producto_nombre`,
`categoria`, `marca`, `cliente_id`, `calificacion UInt8` (1-5), `compra_verificada UInt8`,
`estado LowCardinality(String)`, `moderada UInt8`, `dias_hasta_moderacion Nullable(Float32)`.

```sql
ENGINE = MergeTree PARTITION BY toYYYYMM(fecha_creacion) ORDER BY (producto_id, fecha_creacion)
```

**Orden por producto primero**, como en `fact_compra_linea` y por la misma razón: VEN-11 pide «la
calificación de cada producto y **cómo evoluciona**» — es una serie por producto. Con 344 filas la
diferencia es teórica; se hace así para que el criterio del modelo sea uno solo y no dependa del
tamaño de cada tabla.

**Nota de grano**: la reseña se ata al **producto**, no a la variante (así lo declara
`resena.producto_id`). Es la única tabla del modelo cuyo grano de producto es el padre, y por eso
une a `dim_producto` por `producto_id` y no por `producto_variante_id`. Vale la pena tenerlo
presente al cruzar VEN-11 con VEN-03.

### 5.14 `fact_devolucion_proveedor` — logística inversa hacia el proveedor

- **Grano**: **un ítem defectuoso** con su devolución y resolución. **38 filas** (8 devoluciones, 6
  resueltas). **Alimenta**: OTD-COM-09 — *REQUIERE VOLUMEN*.
- **Origen**: `item_defectuoso` ⟕ `devolucion_proveedor_detalle` ⟕ `devolucion_proveedor` ⟕
  `proveedor` ⋈ `producto_variante`.

Columnas: `item_defectuoso_id`, `devolucion_proveedor_id Nullable(UInt32)`, `numero String`,
`fecha_deteccion DateTime`, `mes Date`, `proveedor_id`, `proveedor`, `producto_variante_id`, `sku`,
`producto_nombre`, `categoria`, `cantidad UInt32`, `origen LowCardinality(String)`
(`inspeccion_rma` / `recepcion_compra`), `estado LowCardinality(String)`,
`tipo_resolucion LowCardinality(String)` (`nota_credito` / `reposicion`),
`costo_unitario Decimal(14,2)`, `valor_recuperado Decimal(14,2)`,
`fecha_resolucion Nullable(DateTime)`, `dias_hasta_resolucion Nullable(Float32)`.

```sql
ENGINE = MergeTree PARTITION BY toYYYYMM(fecha_deteccion) ORDER BY (fecha_deteccion, proveedor_id)
```

Se construye en la **Fase 4** (§9.5). Su informe debe mostrar la muestra (`n = 6 resoluciones sobre
11 proveedores y 19 meses`) junto al número, o inducirá a decisiones sobre ruido.

---

## 6. Estrategia de extracción y carga

### 6.1 La decisión de fondo: full refresh, y por qué **no** es la salida perezosa

El reflejo aprendido es «incremental por fecha». **Aquí sería incorrecto**, y la razón es del
dominio, no del tamaño:

**Más del 60 % del dato relevante muta después de creado.** Un pedido nace `pendiente` y llega a
`entregado` semanas más tarde; el `saldo_pendiente` de una cuenta por pagar baja con cada abono; un
ticket abierto en enero se cierra en marzo; una devolución recorre ocho estados; una factura se
anula. Un incremental que traiga «las filas con `fecha_creacion > última carga`» **jamás volvería a
mirar** esas filas y congelaría el pedido en `pendiente` para siempre. El informe mostraría números
que envejecen en silencio — el peor modo de fallo posible, porque nadie lo nota.

Y el tamaño lo hace gratis. Universo completo de hechos:

| Tabla | Filas |
|---|---:|
| `fact_pedido` | 4.083 |
| `fact_venta_linea` | 10.384 |
| `fact_flujo_caja` | 4.981 |
| `fact_orden_compra` | 865 |
| `fact_compra_linea` | 2.949 |
| `fact_movimiento_inventario` | 13.287 |
| `fact_stock_mensual` | ~26.700 |
| `fact_envio` | 2.872 |
| `fact_novedad_envio` | 176 |
| `fact_devolucion` | 196 |
| `fact_devolucion_linea` | 274 |
| `fact_ticket` | 248 |
| `fact_resena` | 344 |
| `fact_devolucion_proveedor` | 38 |
| **Total hechos** | **≈ 67.400** |
| Dimensiones | ~2.000 |

**Menos de 70.000 filas.** El ETL legado ya insertaba en lotes de 50.000: el data warehouse completo
de RetailMind cabe en **dos lotes**. Una corrida completa se mide en segundos, no en minutos.
Discutir estrategias incrementales sobre este volumen es optimizar lo que no cuesta a cambio de
introducir la clase de bug más difícil de detectar.

### 6.2 Cómo se carga sin que nadie vea una tabla a medias

Patrón por tabla, idéntico en las 19:

```
1. CREATE TABLE retailmind_dwh.fact_x_new  (misma definición, tabla vacía)
2. INSERT INTO fact_x_new  ← lotes desde PostgreSQL (o desde otra tabla del DWH, en las derivadas)
3. Validar: conteo y sumas de control contra el origen  →  si falla, se aborta y NO se publica
4. EXCHANGE TABLES fact_x AND fact_x_new     (atómico, instantáneo)
5. DROP TABLE fact_x_new
```

`EXCHANGE TABLES` es atómico en el motor de base `Atomic` (el de por defecto): **ningún informe ve
jamás una tabla vacía ni a medio llenar**, ni siquiera durante la carga. Si el paso 3 falla, la
tabla vieja sigue publicada y el informe muestra el dato de ayer — degradación correcta, no pantalla
rota. Este patrón hace además que **el ETL sea idempotente**: correrlo dos veces seguidas deja
exactamente el mismo estado, que es lo que permite reintentar sin pensar.

### 6.3 Tabla por tabla

| # | Tabla | Modo | Frecuencia | Detección de cambios | Nota |
|---|---|---|---|---|---|
| D1 | `dim_fecha` | Generada | Una vez / anual | — | No consulta PostgreSQL |
| D2 | `dim_producto` | Full | Diaria | — | 1.221 filas |
| D3 | `dim_cliente` | Full | Diaria | — | 72 filas |
| D4 | `dim_proveedor` | Full | Diaria | — | 11 filas |
| D5 | `dim_promocion_producto` | Full | Diaria | — | 232 filas |
| F1 | `fact_pedido` | Full | Diaria | — | **Muta**: estado, hitos, factura |
| F2 | `fact_venta_linea` | Full | Diaria | — | **Muta**: descuentos y costo |
| F3 | `fact_flujo_caja` | Full | Diaria | — | Dos orígenes en `UNION ALL` |
| F4 | `fact_orden_compra` | Full | Diaria | — | **Muta**: recepción, factura, saldo CxP |
| F5 | `fact_compra_linea` | Full | Diaria | — | **Muta**: `cantidad_recibida` |
| F6 | `fact_movimiento_inventario` | Full | Diaria | — | Append-only en la práctica; ver abajo |
| F7 | `fact_stock_mensual` | **Derivada** | Diaria, **después de F6** | — | `INSERT … SELECT` dentro del DWH |
| F8 | `fact_envio` | Full | Diaria | — | **Muta**: entrega, novedades |
| F9 | `fact_novedad_envio` | Full | Diaria | — | **Muta**: resolución |
| F10 | `fact_devolucion` | Full | Diaria | — | **Muta**: 8 estados |
| F11 | `fact_devolucion_linea` | Full | Diaria | — | **Muta**: inspección |
| F12 | `fact_ticket` | Full | Diaria | — | **Muta**: cierre, asignación |
| F13 | `fact_resena` | Full | Diaria | — | **Muta**: moderación |
| F14 | `fact_devolucion_proveedor` | Full | Diaria | — | **Muta**: resolución |

**Frecuencia recomendada: diaria a las 02:00** (con la advertencia crítica de §8.1 sobre el horario
de la base), más **un disparo manual** desde la UI de orquestación. Ningún informe compuesto
necesita el dato del día en curso: los 39 son de tendencia, ciclo o comparación de períodos. Un
desfase de hasta 24 h es no solo tolerable sino **deseable** — un cierre estable es más útil que uno
que se mueve mientras se lee.

Para la **feria**, además, el disparo manual es lo que se demuestra: se cambia algo en la app, se
pulsa «ejecutar», y el informe compuesto lo refleja. Eso es lo que hace visible el pipeline.

### 6.4 Cómo escalaría (para dejarlo indicado, no para construirlo)

El full refresh deja de ser gratis alrededor de **10 millones de filas** o cuando una corrida pase
de ~2 minutos. Con el crecimiento real de RetailMind (~4.000 pedidos en 19 meses) eso son décadas;
lo que sí lo adelantaría es reactivar la captura de eventos de navegación, que en el ClickHouse
legado ya generaba 2,8 M de filas. El camino, en orden:

1. **Recarga por partición.** Las tablas ya están particionadas por `toYYYYMM`. Se recargan solo los
   **últimos N meses** (`DROP PARTITION` + `INSERT` de esa ventana) y los meses cerrados no se
   tocan. Cambia una línea del `WHERE`, no el diseño. N = 3 cubre con holgura la ventana en que un
   pedido todavía muta.
2. **Marca de agua sobre `fecha_actualizacion`.** Casi todas las tablas operativas la tienen,
   mantenida por el trigger *touch*. Con `ReplacingMergeTree(fecha_actualizacion)` y `ORDER BY` por
   la clave natural, se insertan solo las filas cambiadas y el motor se queda con la última versión.
   **Trampa documentada**: el trigger *touch* no dispara en todos los casos —`CLAUDE.md` ya lo
   registra para los carritos abandonados, donde hubo que usar
   `COALESCE(fecha_actualizacion, fecha_creacion)`—. Antes de confiar en esa columna hay que
   auditar tabla por tabla dónde dispara y dónde no.
3. **CDC de verdad** (Debezium sobre la replicación lógica de PostgreSQL) solo si aparece un
   requisito de *near-real-time*. Hoy no existe: ningún objetivo táctico compuesto lo pide.

---

## 7. Orquestación

### 7.1 Diseño con Airflow

**Un solo DAG**, `retailmind_dwh_diario`, `schedule='0 2 * * *'`, `catchup=False`,
`max_active_runs=1`, `default_args={'retries': 2, 'retry_delay': timedelta(minutes=5)}`,
`default_timezone='America/Guayaquil'`.

```
                        ┌──────────────────────┐
                        │ verificar_origenes   │   PG responde + CH responde
                        └──────────┬───────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
          ┌──────────────────┐          ┌──────────────────┐
          │ TaskGroup: dims  │          │ (espera)         │
          │  dim_fecha       │          │                  │
          │  dim_producto    │          │                  │
          │  dim_cliente     │          │                  │
          │  dim_proveedor   │          │                  │
          │  dim_promo_prod  │          │                  │
          └────────┬─────────┘          └──────────────────┘
                   │  (las 5 en paralelo)
                   ▼
        ┌───────────────────────────────────────────────────────┐
        │ TaskGroup: hechos_base   (13 tareas EN PARALELO)      │
        │  fact_pedido · fact_venta_linea · fact_flujo_caja      │
        │  fact_orden_compra · fact_compra_linea                 │
        │  fact_movimiento_inventario · fact_envio               │
        │  fact_novedad_envio · fact_devolucion                  │
        │  fact_devolucion_linea · fact_ticket · fact_resena     │
        │  fact_devolucion_proveedor                             │
        └────────────────────────┬──────────────────────────────┘
                                 │
                                 ▼   (solo depende de fact_movimiento_inventario)
                    ┌────────────────────────────┐
                    │ fact_stock_mensual         │   derivada dentro de ClickHouse
                    └────────────┬───────────────┘
                                 ▼
                    ┌────────────────────────────┐
                    │ validar_cifras_control     │   §9.2 — falla ⇒ el DAG falla
                    └────────────┬───────────────┘
                                 ▼
                    ┌────────────────────────────┐
                    │ registrar_corrida          │   fila en etl_ejecucion
                    └────────────────────────────┘
```

- **Cada tarea es una llamada a un script autónomo**: `python -m etl.dwh.cargar --tabla fact_pedido`.
  El DAG **no contiene lógica de negocio** — solo orden y dependencias. Es la propiedad que hace
  reversible toda esta decisión.
- Las 13 tareas de hechos son mutuamente independientes: en paralelo. La única dependencia real del
  grafo es `fact_movimiento_inventario → fact_stock_mensual`, y **se ve en el grafo**, que es
  justamente lo que Airflow aporta como demostración.
- Sensor inicial (`verificar_origenes`) que falla rápido si PostgreSQL o ClickHouse no responden, en
  vez de dejar 13 tareas reventando con el mismo error.
- Conexiones como *Airflow Connections* (`retailmind_pg`, `retailmind_ch`), no como variables de
  entorno duplicadas. De paso saca del `.env` las credenciales que hoy están en claro, que es una
  falta de higiene ya señalada en el diagnóstico.

### 7.2 Qué cuesta montar Airflow, sin adornos

| Concepto | Realidad |
|---|---|
| **Servicios nuevos** | `airflow-postgres` (**metadata propia** — no se puede reutilizar la BD `retailmind`), `airflow-scheduler`, `airflow-apiserver`/`webserver`, `airflow-triggerer`, más un `airflow-init` de un disparo. **De 5 a 9 contenedores.** |
| **Memoria** | ~1,5–2 GB adicionales. Sobre un stack que ya corre ClickHouse, Spring Boot, Angular y PocketBase, en un portátil, es notorio |
| **Fricción específica de este proyecto** | **PostgreSQL corre LOCAL, fuera del compose.** Airflow en Docker tiene que alcanzar `localhost:5432` del anfitrión: exige `host.docker.internal` (o `extra_hosts`), `listen_addresses` y una línea en `pg_hba.conf` para la red del bridge. Es el punto donde más gente se queda atascada, y no aparece en ningún tutorial |
| **Fricción de Windows** | `AIRFLOW_UID`, permisos de los volúmenes montados, finales de línea en los scripts, y la zona horaria (por defecto UTC: si no se fija `America/Guayaquil`, el DAG de las 02:00 corre a las 21:00) |
| **Tiempo, primera vez** | **6–10 h** de montaje y depuración (`fernet_key`, elección de executor, migración de la metadata, usuario admin, serialización de DAGs, healthchecks) |
| **Tiempo de los DAG** | **3–4 h** *si* las tareas ya existen como scripts autónomos. Si hay que escribir la lógica dentro de operadores, multiplíquese |
| **Total realista** | **1,5–2 días** de trabajo efectivo para quien no ha operado Airflow antes |
| **Qué se gana** | Grafo visual, Gantt de duraciones, logs por tarea, reintentos automáticos, backfill, historial de corridas. **En una feria, el grafo del DAG ejecutándose es la mejor demostración de que existe un pipeline** — y cierra el pendiente declarado en `CLAUDE.md` («orquestación ETL con Airflow») |

### 7.3 La alternativa ligera de contingencia

Un orquestador propio de ~150 líneas: `retailmind/etl/dwh/run_etl.py`.

```python
TAREAS = [
    ("dim_fecha",        []),
    ("dim_producto",     []),
    ...
    ("fact_pedido",      ["dim_producto", "dim_cliente"]),
    ...
    ("fact_stock_mensual", ["fact_movimiento_inventario"]),
]
# recorrido en orden topológico, reintentos, log a etl.log, fila en etl_ejecucion
```

Se dispara desde **cualquiera** de estos, sin añadir un solo servicio:

- El contenedor `etl` **que ya existe en el compose** y hoy está ocioso con `tail -f /dev/null`. Se
  le cambia el `command` por un `cron` interno. Coste: dos líneas de YAML.
- El Programador de tareas de Windows, si se corre fuera de Docker.
- Un `@Scheduled` en el backend Spring, que además regalaría el botón «ejecutar ahora» dentro de la
  aplicación, junto a las pantallas de informes.

| | Airflow | Orquestador ligero |
|---|---|---|
| Montaje | 1,5–2 días | **2–3 h** |
| Servicios nuevos | +4 | **0** |
| RAM | +2 GB | **0** |
| Grafo visual, Gantt, backfill | ✔ | ✘ |
| Reintentos, logs, historial | ✔ (regalado) | ✔ (~30 líneas) |
| Riesgo de que falle la víspera de la feria | **medio** | **bajo** |
| Cierra el pendiente declarado del proyecto | ✔ | ✘ (habría que redactar el porqué) |

### 7.4 Recomendación

**Hacer los dos, en este orden — y ese orden es la recomendación entera.**

1. **Primero, las tareas como scripts autónomos e idempotentes**, cada una ejecutable a mano
   (`python -m etl.dwh.cargar --tabla fact_pedido`), movidas por `run_etl.py`. Esto es **el 80 % del
   trabajo real del ETL** y **hay que escribirlo igual** con cualquier orquestador. Al terminar este
   paso el pipeline ya funciona de punta a punta y la feria está cubierta.
2. **Después, Airflow como envoltorio.** Con las tareas ya hechas, cada operador es un `BashOperator`
   de una línea y el DAG completo son ~60 líneas. El coste baja de 1,5–2 días a **~1 día**, casi
   todo montaje de infraestructura.

Por qué esto y no elegir uno: **desacopla el riesgo de la infraestructura del riesgo del dato**. Si
Airflow no levanta la noche anterior —lo más probable que salga mal, por la conexión al PostgreSQL
local—, el `run_etl.py` sigue corriendo y la demostración se hace igual. Y si levanta, se presenta
el grafo y se cierra el pendiente del proyecto. **En ningún escenario se pierde trabajo**, porque lo
que se escribe primero es exactamente lo que Airflow necesitaría después.

**La decisión que queda al usuario** es solo si se hace el paso 2, y cuándo. En tiempo:
solo el paso 1 = **medio día** de orquestación (más el ETL en sí); pasos 1 y 2 = **día y medio a
dos días**. Ninguna de las dos rutas invalida a la otra.

---

## 8. Riesgos y decisiones abiertas

### 8.1 🔴 CRÍTICO — el ETL nocturno leería **cero filas**, y sin error

**Es el riesgo más grave de todo el diseño y no es evidente.** Verificado el 2026-07-30:

```
tabla: pedido · policyname: pol_horario · cmd: ALL
roles: {grp_administrador, grp_analista, grp_bodega, grp_compras,
        grp_despacho, grp_gerente, grp_vendedor}
qual:  esta_en_horario(fn_grupo_actual())
```

`cmd = ALL` **incluye SELECT**. Un ETL que se conecte con cualquiera de esos roles a las 02:00 no
recibe un 403 ni una excepción: **RLS filtra en silencio y devuelve cero filas**. El pipeline
terminaría «con éxito», publicaría tablas vacías y los 39 informes aparecerían en blanco sin un solo
mensaje de error en ningún log. Y hay un agravante: un rol nuevo **sin** política asociada tampoco
lee nada, porque el comportamiento por defecto de RLS es denegar.

**Mitigación, y es un prerrequisito de la Fase 0** (no una recomendación opcional):

- Un rol dedicado `retailmind_etl`: `LOGIN`, `BYPASSRLS`, `SELECT` sobre lo que el ETL lee y
  **ningún** privilegio de escritura. `BYPASSRLS` es lo que lo saca de `pol_horario` y de las
  políticas de cliente.
- **No** reutilizar `retailmind_app`: es `NOINHERIT` y sin privilegios de negocio por diseño — asume
  el rol del usuario por transacción. El ETL no tiene usuario.
- **No** usar `postgres` (superusuario) por comodidad: funciona porque tiene `BYPASSRLS`, pero le da
  al pipeline permiso para destruir la base operativa. Un ETL de solo lectura debe ser incapaz de
  escribir, no meramente abstenerse.
- Ese rol exige un script SQL nuevo (el 85). **Está fuera del alcance de este documento de diseño**,
  pero sin él el pipeline no funciona de noche.
- Y una defensa de fondo: **toda tarea valida su propio conteo contra el origen** (§6.2, paso 3).
  Una carga de 0 filas donde se esperaban 4.083 **aborta y no publica**. Aunque el rol quedara mal
  configurado, el informe seguiría mostrando el dato de ayer en vez de una pantalla vacía.

### 8.2 🔴 La segregación financiera **no la respalda ClickHouse**

En PostgreSQL el corte del dinero vive en tres capas: la ruta (`SecurityConfig`), el motor (GRANT
por columna + RLS) y a veces la propia consulta. **ClickHouse no tiene RLS por fila ni GRANT por
columna equivalentes en esta instalación**, y el ETL escribe todas las columnas de dinero en tablas
planas.

De los 39 compuestos, varios llevan dinero y excluyen a Bodega o a Despacho: VEN-14 («Bodega y
Despacho NO: es dinero»), LOG-10 («Despacho NO»), INV-09 («Bodega NO»), GER-02, GER-03, GER-10,
GER-11.

Consecuencia práctica, y hay que decirla sin rodeos: **la barrera de los informes compuestos será la
RUTA**, exactamente como ya ocurre —y está documentado— con INV-07, LOG-11 y GER-08, donde el motor
tampoco alcanza. Eso significa:

- Cada endpoint compuesto necesita su propia línea en `SecurityConfig`, de lo específico a lo
  general, con la misma disciplina del patrón vigente.
- Recomendación de defensa en profundidad, barata: **que la consulta tampoco seleccione la columna
  de dinero** cuando el rol no debe verla — el tercer lugar donde ya vive el corte, estrenado en
  COM-08. Dos capas de una sola línea cada una valen más que una.
- La matriz rol × informe debe **verificarse por API** al terminar, como se hizo con los 29 simples
  (8 endpoints × 8 roles). Es la única prueba que vale.

### 8.3 🟠 No existe costo histórico: tres informes miden con la regla de hoy

`producto_variante.costo` es **un único valor vigente**; no hay tabla de histórico. Afecta a
**OTD-GER-03**, **OTD-GER-10** e **INV-09**. Y no es hipotético: el script 67 ya reasignó los costos
de las 1.221 variantes en bloque.

Implicación incómoda: **cada corrida del ETL puede cambiar el pasado**. Si mañana alguien edita el
costo de una variante, el margen de enero de 2025 cambia en la siguiente carga. Nadie lo notaría a
menos que se advierta.

- **Se declara en la pantalla** de los tres informes: «márgenes calculados con el costo vigente».
- `fact_venta_linea.costo_unitario` **se congela en la carga**, de modo que al menos dentro de una
  corrida el número es coherente. Y `fecha_carga` deja constancia de a qué fecha corresponde el
  costo aplicado.
- **Alternativa disponible que conviene tener a la vista**: `movimiento_inventario.costo_unitario`
  **sí** guarda el costo del momento de cada movimiento. Un margen basado en el costo real de la
  salida de kardex sería históricamente correcto. Se descarta hoy porque cambia la definición del
  informe y hay que acordarlo con el negocio — pero es **la** puerta de salida a esta limitación, y
  el modelo ya carga esa columna en `fact_movimiento_inventario`.

### 8.4 🟠 Coherencia PostgreSQL ↔ ClickHouse: qué desfase se acepta

- **Desfase aceptado**: hasta **24 h** (carga diaria a las 02:00). Ningún compuesto lo sufre: los 39
  son de tendencia, ciclo o comparación de períodos.
- **Regla no negociable: ClickHouse NUNCA es fuente.** Nada del pipeline escribe hacia PostgreSQL.
  Ninguna decisión operativa (stock, precio, estado) se toma leyendo el DWH. Si los dos discrepan,
  **gana PostgreSQL** siempre, y el DWH se recarga.
- **Toda pantalla compuesta muestra la marca de agua** «Datos al …» con `max(fecha_carga)`. Un
  informe que no dice de cuándo es su dato es un informe que miente por omisión.
- **Degradación**: con ClickHouse apagado, los compuestos deben responder «analítica no disponible»
  y **no romper la aplicación** — el patrón ya está probado y blindado (`ClickHouseConfig` con
  timeouts acotados, `/api/health` en ~3,1 s con `status: UP, analytics: DEGRADED`). Se conserva tal
  cual: es el mismo criterio de que con Docker apagado todo el sistema funciona.
- **Bitácora de corridas**: tabla `etl_ejecucion` en el DWH (tarea, inicio, fin, filas leídas, filas
  escritas, resultado, mensaje). Es el equivalente del `carga_historial` que el ETL viejo ya usaba y
  la base de cualquier diagnóstico posterior.

### 8.5 🟠 Informes difíciles de modelar, y por qué

| Informe | Dificultad | Cómo se aborda |
|---|---|---|
| **INV-09** | Reconstruir el saldo al cierre de 19 meses × 1.406 posiciones. El balance final no sirve; hay que respetar la cronología, y las cadenas se encadenan por `(fecha_creacion, id)` | `argMax(stock_nuevo)` por mes + arrastre con ventana (§5.7). Se apoya en `stock_nuevo`, que el sistema **ya garantiza**, en vez de recalcularlo |
| **GER-07** | Doble problema: (a) `asof`-join entre líneas de venta y ventanas de promoción; (b) **123 líneas «durante» contra 4.133 de línea base** | `dim_promocion_producto` como puente con ventana. **La muestra se muestra junto al número.** Sigue en REQUIERE VOLUMEN |
| **LOG-12** | Pedidos que **saltan** estados (cancelados, no entregados) dejan tramos nulos | Hitos como `Nullable`; el informe promedia **solo sobre los pedidos que completaron el tramo** y publica el `n` de cada uno. Un `avg()` sobre nulos tratados como cero sería un número inventado |
| **VEN-04** | Necesita el universo de lo que **no** pasó: 387 variantes sin una sola venta | Solo se puede con `dim_producto` completa ⟕ `fact_venta_linea`. Es la razón de que `dim_producto` traiga las 1.221 variantes y no solo las 834 vendidas |
| **SOP-02** | Solo **76 de 248** tickets cerrados: el SLA de los otros 172 es *desconocido* | Cuatro categorías, no dos (§5.12). Nunca un porcentaje sobre 248 |
| **VEN-14** | El numerador se fecha por la devolución y el denominador por el pedido | Las **dos** fechas viajan en `fact_devolucion`; el informe declara cuál usa (§5.10) |
| **COM-09** | 6 resoluciones sobre 11 proveedores y 19 meses | Se modela igual y se construye al final; el informe declara la muestra |
| **LOG-11 (serie)** | La zona no está guardada: es una cadena de tres saltos con precedencia | Se resuelve **una vez, en el ETL** (§5.8). Verificado: 181/596/2.078/17 |

### 8.6 🟡 Zona horaria: el error que ya se pagó una vez

PostgreSQL guarda `timestamptz`; ClickHouse guarda un instante y **la zona vive en el tipo de la
columna**. Si se declara `DateTime` a secas, ClickHouse asume la del servidor —UTC en el
contenedor—, y `toStartOfMonth()` **manda los pedidos del 1 de mes de madrugada al mes anterior**.
Este proyecto ya sufrió la versión frontend de este bug (un `date` puro que la pantalla mostraba un
día antes). Mitigación: **todas** las columnas se declaran `DateTime('America/Guayaquil')`; las
etiquetas de mes viajan **precalculadas** en `mes Date` y `mes_etiqueta String`; y la validación de
§9.2 compara conteos **por mes**, que es donde este error se manifiesta primero.

### 8.7 🟡 Decisiones abiertas que corresponden al usuario, no al diseño

1. ~~**B2B/B2C**~~ — **DECISIÓN CERRADA el 2026-07-30, ya no corresponde al usuario.** El
   diagnóstico `DIAGNOSTICO_SEGMENTO_CLIENTE.md` descartó la segmentación con veredicto (c)
   población homogénea: no está capturada **y no es derivable** (99,94 % de las líneas piden 1–4
   unidades; ninguna de las siete dimensiones separa dos poblaciones; 0 RUC en 3.887 facturas).
   `dim_cliente.segmento` vale `'sin_segmentar'` en los 72 clientes y **así se queda**: es el
   registro de que no hay segmentación, no una columna a la espera. Lo único que sigue abierto —y es
   otra pregunta— es si algún día se quiere un corte **RFM de valor** como regla derivada explícita
   (§1.2), que no es un corte B2B/B2C.
2. **Qué pasa con el ClickHouse legado**: se propone congelar `retailmind` como archivo. Descartarla
   liberaría ~830 MB (441 MiB de los cuales son telemetría del propio motor, que se regenera sola).
   Decisión posterior, sin impacto en este pipeline.
3. **Migrar o no los 7 servicios de `analytics/`**: hoy leen `fact_eventos` y muestran los 2,71 M de
   eventos sintéticos. No son parte de los 39 objetivos y quedan fuera de alcance, pero **siguen
   mostrando números falsos en pantalla** mientras nadie los toque.
4. **Reactivar la captura de eventos de navegación**: sin ella no hay embudo real (EX-7, y las tres
   desalineaciones del §5.3 del diagnóstico). Es una decisión de alcance, no una tarea de ETL, y
   ninguno de los 39 compuestos la necesita.
5. **Credenciales en claro en `.env`**: higiene ya señalada en el diagnóstico. Airflow Connections la
   resolvería de paso; el orquestador ligero no.

---

## 9. Plan de implementación por fases

Ninguna fase se ejecuta en este documento. El criterio de orden es: **primero una vertical completa
y validada, después ancho.**

### 9.1 Fase 0 — Prerrequisitos (medio día)

1. **Script 85: rol `retailmind_etl`** (`LOGIN`, `BYPASSRLS`, `SELECT` de solo lectura). **Sin esto
   el pipeline lee cero filas de noche** (§8.1). Es el primer paso, no un detalle.
2. Crear la base `retailmind_dwh` en ClickHouse. **No se toca `retailmind`.**
3. Esqueleto `retailmind/etl/dwh/` reutilizando lo que ya existe y sirve: `config/db_connection.py`,
   `config/clickhouse_connection.py`, `utils/load_tracker.py`, `utils/error_reporter.py` y la
   inserción por lotes. **Se reutilizan los patrones, no la lógica** — la lógica vieja va de
   PocketBase a un esquema muerto.
4. Tabla `etl_ejecucion` (bitácora de corridas) en el DWH.
5. Levantar ClickHouse desde el compose y confirmar `/ping`. **Hoy no hay contenedor**: el dato vive
   en el volumen `1m6datoscs_clickhouse_data`.

### 9.2 Fase 1 — Piloto: `fact_venta_linea` de extremo a extremo (1 día)

**Por qué esta tabla como piloto** y no otra: es la de mayor grano (10.384 filas, la mayor del
modelo), sirve **7 informes**, ejercita **todas** las mecánicas del pipeline en una sola pasada
—dimensión (`dim_producto`), denormalización de categoría y marca, la transformación no trivial de
las dos capas de descuento, el cálculo de margen contra el costo vigente, el particionado mensual y
el intercambio atómico— y su resultado es **verificable al centavo**. Si el piloto funciona, lo que
queda de las otras 13 tablas es repetición de un molde probado.

Alcance: `dim_fecha`, `dim_producto`, `fact_venta_linea` y **un** informe de punta a punta —
**OTD-VEN-06** (ventas mes a mes por categoría), porque ejercita a la vez la serie temporal y el
corte por dimensión.

**Cómo se valida — cifras de control tomadas de PostgreSQL hoy (2026-07-30):**

| Control | Valor esperado | Comprobación |
|---|---:|---|
| Filas cargadas | **10.384** | `count(*)` idéntico |
| Unidades vendidas | **20.687** | `sum(cantidad)` |
| Venta neta de línea | **$4.991.078,85** | `sum(cantidad*precio_unitario − monto_descuento)` |
| Costo total | **$3.844.509,33** | `sum(cantidad*costo)` |
| Líneas sin costo | **0** | ninguna variante sin costo |
| Meses con venta | **19** | ni uno de más ni de menos |
| Pedidos con descuento que cuadran | **651 de 657** | los 6 legacy, contados aparte |

Y el control **mes a mes**, que es el que detecta los errores de zona horaria (§8.6) — pedidos no
cancelados, primeros y últimos meses:

| Mes | Pedidos | Total |
|---|---:|---:|
| 2025-01 | 82 | $118.362,77 |
| 2025-12 | 322 | $436.948,83 |
| 2026-05 | 290 | $427.489,26 |
| 2026-07 | 220 | $274.330,67 |
| **Σ 19 meses** | **3.924** | **$5.498.570,35** |

**El criterio de aceptación es la igualdad exacta al centavo**, no la aproximación. La misma
consulta agregada, corrida en PostgreSQL y en ClickHouse, debe devolver la misma tabla. Cualquier
diferencia —un centavo, un pedido— **es un bug** y detiene la fase: será redondeo de `Float64` en
vez de `Decimal`, una zona horaria olvidada, o una fila perdida en un `JOIN`. Las tres se detectan
aquí o no se detectan nunca.

Se entrega además un **script de validación reutilizable** (`validar_dwh.py`) que ejecuta el par de
consultas contra ambos motores y reporta diferencias. Cada fase siguiente añade sus controles a ese
script, y la última tarea del DAG lo invoca (§7.1).

### 9.3 Fase 2 — El núcleo de la venta y el dinero (1–1,5 días)

`dim_cliente` · `fact_pedido` (incluidos los hitos de LOG-12) · `fact_flujo_caja`.

Cierra **VEN-05, VEN-07, VEN-09, VEN-12, VEN-13, LOG-12, GER-05** y la mitad de **GER-02**: **8
objetivos**. Se hace segunda porque `fact_pedido` es la tabla más solicitada del modelo y porque el
pivote de hitos y la normalización de motivos de fallo son las dos transformaciones que conviene
tener resueltas temprano.

Controles: 4.083 pedidos · 3.924 no cancelados · $5.498.570,35 · web 2.132 / tienda 990 / teléfono
802 · 4.079 cobros de los cuales **176 fallidos** con **5 motivos normalizados** (no 6) · 902 pagos
a proveedor por $16.084.462,74.

### 9.4 Fase 3 — Abastecimiento, inventario y logística (2 días)

`dim_proveedor` · `fact_orden_compra` · `fact_compra_linea` · `fact_movimiento_inventario` ·
`fact_stock_mensual` · `fact_envio` · `fact_novedad_envio`.

Cierra **COM-04, COM-05, COM-06, COM-07, COM-12, INV-04, INV-09, INV-10, LOG-03, LOG-04, LOG-05,
COM-03** y completa **GER-02**: **13 objetivos**.

Es la fase larga, por `fact_stock_mensual` (la reconstrucción de §5.7) y por la resolución de zona
de `fact_envio`. **Conviene atacar `fact_stock_mensual` sola y sin prisa**: es la única
transformación del pipeline que puede dar un número plausible y equivocado.

Controles: 865 órdenes · 825 pares promesa/llegada · 839 facturas por $22.467.387,27 · 13.287
movimientos · **el `stock_cierre` del último mes debe coincidir exactamente con
`inventario.stock_actual` en las 1.406 posiciones** (es la prueba definitiva de que la
reconstrucción es correcta) · 2.872 envíos, 2.723 con ambas fechas · zonas 181/596/2.078/17.

### 9.5 Fase 4 — Posventa, soporte y cierre (1–1,5 días)

`fact_devolucion` · `fact_devolucion_linea` · `fact_ticket` · `fact_resena` ·
`dim_promocion_producto` · `fact_devolucion_proveedor`.

Cierra **VEN-11, VEN-14, LOG-07, LOG-08, LOG-09, LOG-10, SOP-02, SOP-03, SOP-06, SOP-07, SOP-08,
GER-03, GER-10, GER-11, GER-07, COM-09**: los **16 restantes**.

Controles: 196 devoluciones · 85 reembolsos por $44.525,63 · 248 tickets, **76 cerrados**, **193 con
primera respuesta** · 344 reseñas · 232 pares promoción-producto.

Aquí entran los dos objetivos en REQUIERE VOLUMEN, al final y con su muestra declarada en pantalla.

### 9.6 Fase 5 — Orquestación (0,5 día · o 1,5–2 días con Airflow)

`run_etl.py` con orden topológico, reintentos y bitácora → **la ruta corta cubre la feria**. Encima,
si se decide, el DAG de Airflow (§7). La decisión sigue abierta hasta el último momento sin coste
adicional: es la propiedad que compra el orden de §7.4.

### 9.7 Fase 6 — Consumo desde la aplicación (fuera del alcance de este documento)

Los 39 informes compuestos se consultan por pantalla, igual que los 29 simples. Se anota lo que ya
se sabe para que no sorprenda: el patrón de `PATRON_INFORMES.md` (sobre único
`{items, total, page, size, resumen}` y una sola pantalla genérica) **se reutiliza tal cual**, con
tres diferencias que hay que resolver cuando llegue el momento — la fuente es ClickHouse y no
`pgJdbcTemplate`; **no hay `SET LOCAL ROLE` que respalde el corte financiero** (§8.2); y cada
pantalla debe mostrar la marca de agua «Datos al …».

### 9.8 Resumen del esfuerzo

| Fase | Contenido | Objetivos cerrados | Días |
|---|---|---:|---:|
| 0 | Prerrequisitos (rol ETL, base, esqueleto) | 0 | 0,5 |
| 1 | **Piloto** `fact_venta_linea` + VEN-06 | 1 | 1 |
| 2 | Venta y dinero | 8 | 1–1,5 |
| 3 | Compras, inventario, logística | 13 | 2 |
| 4 | Posventa, soporte, marketing | 16 | 1–1,5 |
| 5 | Orquestación ligera (+ Airflow) | — | 0,5 (+1 a 1,5) |
| | **Total** | **39** (VEN-03, VEN-04 y GER-* restantes caen con sus tablas) | **6–7 días** (**7,5–8,5** con Airflow) |

---

## 10. Limitaciones de este diseño

- **Es un diseño en papel.** No se ejecutó ninguna carga, no se creó ninguna tabla y no se midió
  ningún tiempo real de ClickHouse. Los volúmenes son conteos verificados en PostgreSQL; los tiempos
  de corrida son estimaciones fundadas en esos volúmenes, no mediciones.
- **Las claves de ordenamiento son razonadas, no medidas.** Cada `ORDER BY` se justifica por la forma
  de las consultas que sirve, pero no se ejecutó ningún `EXPLAIN` en ClickHouse. Con ~70.000 filas
  cualquier orden responde en milisegundos: la elección se juzgará de verdad si el volumen crece un
  orden de magnitud.
- **No se diseñó la capa de consumo.** Qué endpoints, qué servicio Java y cómo se autoriza cada
  informe compuesto queda para la Fase 6. Los riesgos conocidos están anotados en §8.2 y §9.7.
- **Dos objetivos siguen limitados por el dato, no por el diseño** (COM-09 y GER-07). Se modelan
  completos; su informe será débil hasta que haya volumen, y el pipeline no puede arreglarlo.
- **La igualdad de descuentos se verificó en agregado**, sobre los 657 pedidos con descuento, no
  pedido por pedido. Los 6 que no cuadran se identificaron por conteo y corresponden a los casos
  legacy ya declarados en `CLAUDE.md`; su listado nominal se producirá durante la Fase 1.
- **No se auditó dónde dispara el trigger *touch***. Solo importa si algún día se migra a carga
  incremental por marca de agua (§6.4); con full refresh es indiferente.
- **La premisa `pedido.tipo_venta` del encargo se contradijo con la base** (§1.2), y el diagnóstico
  del 2026-07-30 fue más lejos: **el corte B2B/B2C no existe en el negocio**, así que no hay
  columna que buscar. Si algún día apareciera una clasificación de cliente —porque el negocio
  cambie, no porque se agregue una columna—, este diseño se revisaría **solo en ese punto**: sería
  añadir una columna a `fact_pedido` y a `dim_cliente`, sin tocar ningún grano ni ninguna tabla.
