# Catálogo de objetivos tácticos por departamento — RetailMind

**Versión 3 — sincronizada con el estado real del sistema el 2026-07-26.** Reemplaza por completo
la tabla de 25 objetivos del documento TA11 original y actualiza la versión ampliada del
2026-07-21/22. Toda cita de tabla.columna y todo conteo de este catálogo fue **re-verificado vía
MCP contra la base real `retailmind`** (PostgreSQL, **110 tablas** en el esquema `public`, conteo
confirmado el 2026-07-26 — eran 109 antes de que el script 48 creara `meta_venta`).

Qué cambió respecto de la versión anterior, y por qué:

1. **La factibilidad se recalculó por completo.** Entre el 2026-07-22 y el 2026-07-25 se cerraron
   las seis brechas de sistema que quedaban (scripts 46-53) y se sembró la operación histórica de
   19 meses (scripts 55-84). Objetivos que antes decían «REQUIERE CAMBIO EN EL SISTEMA» o
   «REQUIERE VOLUMEN DE DATOS» hoy devuelven filas reales. **No queda ninguna brecha de sistema
   abierta**: los 6 objetivos en REQUIERE CAMBIO pasaron a FACTIBLE HOY, y 15 de los 17 en
   REQUIERE VOLUMEN también. Solo 2 siguen esperando volumen (OTD-COM-09 y OTD-GER-07), con su
   motivo declarado.
2. **OTD-LOG-11 se reclasificó de COMPUESTO a SIMPLE**, aplicando la regla de la sección 2 al
   informe que efectivamente se construyó (foto agregada por zona y transportista, sin serie
   temporal). Ver nota en la sección 6.
3. **Reencuadre del negocio**: RetailMind se caracteriza como **distribuidora mayorista B2B**, no
   como comercio minorista. La narrativa de cada jefatura se ajustó a ese marco (venta por
   volumen a cliente corporativo recurrente, margen de distribución, abastecimiento como centro
   de costo dominante). Los datos no cambian; cambia la lectura de negocio que los enmarca.
4. **Se marca qué objetivos ya tienen reporte en pantalla implementado** (columna «Reporte»):
   **28 de los 29 informes SIMPLES** están construidos y consultables en la aplicación, en los
   seis departamentos.

---

## 1. Nota metodológica

El proceso aplicado es el que se enseñó en clase, en este orden:

1. **Primero se definen los informes.** Cada jefe departamental actúa como proveedor de objetivos
   tácticos: dice qué necesita para dirigir y controlar su área, en lenguaje de negocio.
2. **Después se le pregunta a la base de datos** si puede responder cada informe: se verifica
   tabla por tabla y columna por columna que el dato exista **y esté poblado** (no basta que la
   columna esté declarada en el esquema).
3. **Si la base dice que no**, se identifica exactamente qué falta y se corrige en TRES capas
   antes de construir el ETL: la base de datos (columna o tabla), el backend (el flujo que debe
   escribir el dato) y el formulario de la aplicación (donde el usuario lo captura). No basta con
   agregar la columna: si nadie la llena desde la pantalla, el informe seguirá vacío.
4. **Los informes SIMPLES se construyen sobre la BDR** y se consultan por pantalla.
5. Solo entonces los informes compuestos se llevan a la base columnar (ClickHouse) mediante el
   pipeline ETL orquestado con Airflow.

El catálogo se sometió además a una **auditoría de cobertura**: se levantó a ciegas un
cuestionario de 78 preguntas de negocio (una por cada decisión que un jefe de área necesita
tomar, sin mirar el catálogo ni el esquema), se cruzó pregunta por pregunta contra el catálogo y
contra la base real, y de los 20 puntos ciegos detectados se incorporaron los 11 de mayor valor
de dirección; los 9 restantes quedaron registrados con su motivo en Decisiones de alcance.

Este documento aplica ese proceso: define **68 objetivos**, los contrasta uno a uno contra la
base real y marca **66** como respondibles hoy, **0** que exijan un cambio en el sistema y **2**
que aún esperan volumen de datos. Los informes tácticos se consultan **por pantalla**, con
filtros y registros visibles; no se entregan como PDF descargable (los documentos operativos —
facturas, guías, comprobantes — siguen siendo PDF).

### 1.1 El negocio que se dirige: distribuidora mayorista B2B

RetailMind opera como **distribuidora mayorista ecuatoriana con sede en Quevedo**: compra
volumen a proveedores, almacena en dos bodegas y revende a clientes corporativos y minoristas
recurrentes por tres canales (mostrador, teléfono y tienda en línea). Esa caracterización no es
decorativa — condiciona qué informe táctico tiene sentido:

- **El abastecimiento pesa más que la venta.** El histórico verificado muestra $22,47 M
  facturados en compras contra $5,72 M vendidos, y $6,38 M de saldo abierto en cuentas por pagar
  sobre 276 cuentas vivas. Por eso Compras tiene 12 objetivos y la mitad de ellos miran deuda,
  puntualidad y cumplimiento del proveedor: en una distribuidora, el margen se gana comprando.
- **El margen es de distribución, no de detalle.** El costo por variante se sitúa en bandas
  mayoristas por categoría (script 67), de modo que los objetivos de rentabilidad (OTD-GER-03,
  OTD-GER-10) miden puntos de margen sobre volumen, no ticket unitario.
- **El cliente es recurrente y de volumen**, no un comprador ocasional: 69 de 72 clientes tienen
  pedidos y el ticket promedio se forma sobre 10.384 líneas en 4.083 pedidos. Eso vuelve
  significativos los objetivos de comportamiento por cliente (OTD-VEN-05) y de descuento
  negociado (OTD-GER-05, OTD-GER-11), que en un minorista de paso serían ruido.
- **El capital vive en la bodega.** El inventario valorizado asciende a $22,02 M — varias veces
  la venta anual —, lo que explica que Inventario concentre siete de sus diez objetivos en el
  control del presente (existencias, mínimos, máximos, kardex) y que el sobre-stock (OTD-INV-08,
  184 filas hoy) sea una pregunta de dirección y no un detalle operativo.

## 2. Nota sobre el esquema de IDs (OTD- vs. OT-)

En el repositorio ya existen los objetivos tácticos **OT-01, OT-02, OT-07 y OT-08**, heredados
del documento EVF04 y citados en `specs/001-007`: su alcance es la **tienda del cliente**
(experiencia de navegación, retención, identidades, vistas por rol). Este catálogo usa el prefijo
**OTD-** (Objetivo Táctico **Departamental**) precisamente para no colisionar con ese universo:
los OTD- son necesidades de dirección y control de las jefaturas de área del back-office, un
nivel y un alcance distintos de los OT- de EVF04, que siguen vigentes en su propio ámbito.
Prefijos: `OTD-VEN` (Ventas), `OTD-COM` (Compras), `OTD-INV` (Inventario/Bodega), `OTD-LOG`
(Logística/Despacho), `OTD-SOP` (Soporte), `OTD-GER` (Gerencia/Dirección, que incluye Marketing).

Los departamentos son **seis**: Ventas, Compras, Inventario/Bodega, Logística/Despacho, Soporte
y Gerencia/Dirección (que dirige también Marketing). El número de objetivos por departamento no
es una cuota: sale de lo que cada área realmente necesita dirigir, por eso es asimétrico.

**Regla de clasificación SIMPLE / COMPUESTO** (resuelve explícitamente la ambigüedad del
enunciado, donde «ventas por vendedor» y «ventas por cajero» — consultas casi idénticas —
aparecían en lados distintos):

- **SIMPLE (S)**: responde sobre el **estado actual** del área. Puede contar o sumar sobre la
  foto de hoy, pero **NO recorre histórico ni compara períodos**. Se resuelve con una consulta
  directa a PostgreSQL (BDR).
- **COMPUESTO (C)**: requiere **recorrer datos históricos, comparar entre períodos, o cruzar
  varias fuentes con agregación**. Se procesa en la BD Columnar (ClickHouse) vía el pipeline
  ETL orquestado con Airflow.

Dos precisiones para los casos limítrofes: (1) sumar o contar sobre el presente no convierte un
informe en compuesto — «ventas por vendedor» es SIMPLE mientras totalice la cartera actual, y
solo se vuelve COMPUESTO cuando compara mes contra mes; (2) una consulta puntual de detalle que
lista filas antiguas sin agregarlas (el historial de movimientos de UN producto, el registro de
auditoría filtrado) sigue siendo SIMPLE: es una consulta directa filtrada, no un barrido agregado
del histórico.

Aplicada esta regla, los 68 objetivos se verificaron uno a uno. Los **ocho** simples que
contienen agregación (OTD-VEN-02, OTD-VEN-15, OTD-COM-11, OTD-INV-07, OTD-LOG-11, OTD-SOP-04,
OTD-SOP-05, OTD-GER-01) agregan sobre la foto presente sin comparar períodos y permanecen
SIMPLES. **Un solo objetivo cambió de clasificación en toda la vida del catálogo**: OTD-LOG-11
(ver sección 6).

La segregación financiera del sistema se respeta en la columna de destinatarios: **Bodega y
Despacho nunca ven montos de dinero**, solo cantidades y estados.

**Columna «Reporte»** en las tablas que siguen: `✔ implementado` = el informe existe en la
aplicación bajo `/operativo/informes/{departamento}` y se consulta por pantalla con filtros;
`—` = pendiente (compuestos, que son trabajo de ETL, o el único simple aún no construido).

---

## 3. VENTAS (OTD-VEN)

El jefe de ventas dirige la cartera de pedidos mayoristas de los tres canales (mostrador,
teléfono y tienda en línea B2B), el desempeño de su equipo de vendedores, el comportamiento de
compra de la cartera de clientes corporativos recurrentes y la voz del cliente sobre los
productos (reseñas y preguntas). Es el área con más objetivos porque concentra el ingreso del
negocio: 4.083 pedidos por $5.716.436,55 en 19 meses (2.213 web, 1.030 mostrador, 840 teléfono).

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado (MCP 2026-07-26) | Factibilidad | Reporte | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|---|
| OTD-VEN-01 | Ver toda la cartera de pedidos y en qué paso del proceso está cada uno hoy (confirmado, pagado, facturado, en preparación, despachado, entregado). | X | — | BDR | `pedido.estado_pedido_id` → `estado_pedido.codigo/nombre` (11 estados), `pedido.numero/fecha_pedido/canal/total` (**4.083 pedidos**) | FACTIBLE HOY | ✔ implementado | Tabla con filtros por estado y canal — Vendedor, Gerente, Administrador |
| OTD-VEN-02 | Controlar cuántos pedidos registra cada vendedor y por cuánto dinero, para evaluar el cumplimiento individual. | X | — | BDR | `pedido.vendedor_id` (FK a `usuario.nombre/apellido`; **1.870 pedidos con vendedor, 9 vendedores distintos** — NULL solo en pedidos en línea, por diseño), `pedido.total` | FACTIBLE HOY | ✔ implementado | Tabla de vendedores ordenada por monto — Gerente, Administrador (el Vendedor ve solo lo propio) |
| OTD-VEN-03 | Conocer los 10 productos que más se venden — el «producto estrella» — en el período elegido. | — | X | BDColumnar | `pedido_detalle.producto_variante_id/cantidad/precio_unitario` (**10.384 líneas**, 834 variantes distintas vendidas) + `producto_variante.producto_id` → `producto.nombre` | FACTIBLE HOY | — (ETL) | Barras de los 10 primeros, con filtro de período — Gerente, Vendedor, Compras, Analista, Administrador |
| OTD-VEN-04 | Conocer los 10 productos que no se venden o llevan más tiempo sin venderse — el «producto hueso» — para liquidarlos o dejar de comprarlos. | — | X | BDColumnar | `producto_variante` (1.221 variantes) cruzada contra `pedido_detalle.producto_variante_id` (**387 variantes sin una sola venta**); última salida en `movimiento_inventario.fecha_creacion` + `tipo_movimiento.codigo='salida_venta'` | FACTIBLE HOY | — (ETL) | Tabla de rezagados con días sin venta — Gerente, Compras, Analista, Administrador |
| OTD-VEN-05 | Saber cuánto compra cada cliente: total gastado, número de pedidos y fecha de la última compra — mirar el negocio desde el cliente, no solo desde la venta. | — | X | BDColumnar | `pedido.cliente_id/total/fecha_pedido` + `cliente.nombre/apellido/email` — **72 clientes, 69 de ellos con pedidos** repartidos en 19 meses (antes solo 2 cuentas demo) | FACTIBLE HOY | — (ETL) | Tabla de clientes ordenada por monto acumulado — Gerente, Vendedor, Analista, Administrador |
| OTD-VEN-06 | Ver cómo evolucionan las ventas mes a mes y por categoría de producto. | — | X | BDColumnar | `pedido.fecha_pedido` (**19 meses: 2025-01 a 2026-07**) + `pedido_detalle.cantidad/precio_unitario/monto_descuento` + `producto_categoria.categoria_id` → `categoria.nombre` (11 categorías) | FACTIBLE HOY | — (ETL) | Líneas por mes con desglose por categoría — Gerente, Analista, Administrador |
| OTD-VEN-07 | Conocer el valor promedio de cada pedido por período y por canal de venta (mostrador, teléfono, tienda en línea). | — | X | BDColumnar | `pedido.total/fecha_pedido/canal` (4.083 pedidos: 2.213 web, 1.030 tienda, 840 teléfono) | FACTIBLE HOY | — (ETL) | Tarjetas y línea temporal por canal — Gerente, Analista, Administrador |
| OTD-VEN-08 | Detectar los carritos de compra que los clientes dejaron a medias sin llegar a pagar. | X | — | BDR | `carrito.estado` + `COALESCE(fecha_actualizacion, fecha_creacion)` + `carrito_item.producto_variante_id/cantidad` — **290 carritos: 216 abandonados y 54 activos** (el trigger touch no dispara en los abandonados, de ahí el COALESCE) | FACTIBLE HOY | ✔ implementado | Tabla de carritos inactivos con antigüedad — Gerente, Vendedor, Administrador |
| OTD-VEN-09 | Saber con qué formas de pago cobran las ventas (efectivo, tarjeta, transferencia) y cómo cambia esa mezcla en el tiempo. | — | X | BDColumnar | `pago.metodo_pago_id` → `metodo_pago.nombre/tipo` (3 métodos en uso), `pago.monto/fecha_pago` (**4.079 pagos, $5.467.791,59 cobrados**) | FACTIBLE HOY | — (ETL) | Participación por forma de pago, por mes — Gerente, Analista, Administrador |
| OTD-VEN-10 | Atender a tiempo la voz del cliente: reseñas en espera de aprobación y preguntas sobre productos sin responder. | X | — | BDR | `resena.estado` (**344 reseñas, 53 pendientes**), `pregunta_producto.estado` (**49 preguntas, 13 pendientes**) y existencia de `respuesta_pregunta` (29) | FACTIBLE HOY | ✔ implementado | Cola de moderación con antigüedad — Administrador, Gerente (moderadores del sistema) |
| OTD-VEN-11 | Conocer la calificación que los clientes dan a cada producto y cómo evoluciona. | — | X | BDColumnar | `resena.calificacion/producto_id/fecha_creacion/compra_verificada` — **344 reseñas sobre 268 productos distintos, en 18 meses** | FACTIBLE HOY | — (ETL) | Ranking de productos por calificación — Gerente, Vendedor, Analista |
| OTD-VEN-12 | Saber cuántos cobros en línea fallan y por qué motivo, para no perder ventas en el paso del pago. | — | X | BDColumnar | Brecha CERRADA (script 52): `pago.estado` registra hoy **'completado' y 'fallido' (176 fallidos)** y `transaccion_pago.tipo` distingue 'autorizacion' (176, todas de pagos fallidos) de 'captura' (3.903); `respuesta_pasarela` guarda el motivo — **6 motivos reales**: fondos_insuficientes 40, datos_incorrectos 38, tarjeta_rechazada 37, error_pasarela 31, limite_excedido 28 | FACTIBLE HOY | — (ETL) | Tabla de intentos fallidos por motivo y período — Gerente, Administrador |
| OTD-VEN-13 | Saber cuánto vende cada canal — mostrador, teléfono y tienda en línea — y qué parte de la venta total pone cada uno, por período. | — | X | BDColumnar | `pedido.canal/total/fecha_pedido` — poblados en los 4.083 pedidos: web $3.073.238,46 / tienda $1.438.538,94 / teléfono $1.204.659,15 | FACTIBLE HOY | — (ETL) | Participación de cada canal en la venta, por mes — Gerente, Vendedor, Analista, Administrador |
| OTD-VEN-14 | Saber cuánto dinero devuelven los clientes al mes y qué porcentaje de la venta representa, para frenar a tiempo si se dispara. | — | X | BDColumnar | `devolucion.monto_total/fecha_creacion` (**196 devoluciones, $95.693,89**, mantenido por el trigger `fn_recalcular_total_devolucion`) contra `pedido.total/fecha_pedido` | FACTIBLE HOY | — (ETL) | Valor devuelto y porcentaje sobre la venta, mensual — Gerente, Administrador, Analista (Bodega y Despacho NO: es dinero) |
| OTD-VEN-15 | Seguir la venta acumulada del período contra la meta que se fijó, para reaccionar a media quincena y no enterarse al cierre del mes. | X | — | BDR | Brecha CERRADA (script 48 + 84): tabla **`meta_venta` (anio, mes, departamento, monto_meta, fijada_por, activo)** con **133 filas = 7 departamentos × 19 meses**; la venta real se calcula desde `factura_venta` no anulada del mes | FACTIBLE HOY | ✔ implementado | Avance de venta contra la meta del período — Gerente, Vendedor, Administrador |

## 4. COMPRAS (OTD-COM)

El jefe de compras dirige el motor de costo de la distribuidora: órdenes y aprobaciones,
recepciones de mercancía por volumen, deuda con proveedores y devolución de mercancía
defectuosa. En un mayorista el margen se gana comprando, y las cifras lo confirman: 865 órdenes,
839 facturas por **$22.467.387,27**, $16.084.462,74 pagados y **$6.382.924,53 de saldo abierto**
en 276 cuentas por pagar vivas. Necesita saber a quién comprarle, cuánto debe, si paga a tiempo
y si le entregan completo y a tiempo.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado (MCP 2026-07-26) | Factibilidad | Reporte | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|---|
| OTD-COM-01 | Ver las órdenes de compra que esperan aprobación y el estado de cada orden en curso. | X | — | BDR | `orden_compra.estado/numero/fecha_emision/total` + `proveedor.razon_social` (**865 órdenes en 19 meses, 11 proveedores**). El estado sintético `pendiente_aprobacion` agrupa 'borrador'+'enviada': aprobar deja la orden en 'confirmada', no existe estado 'aprobada' | FACTIBLE HOY | ✔ implementado | Tabla con filtros por estado y proveedor — Compras, Gerente, Administrador |
| OTD-COM-02 | Controlar cuánto le debemos a cada proveedor y qué cuotas están por vencer o ya vencieron. | X | — | BDR | `cuenta_por_pagar.saldo_pendiente/fecha_vencimiento/estado/proveedor_id` (**839 cuentas, 276 con saldo abierto, $6.382.924,53**) + `proveedor.razon_social`. Conviven dos clasificaciones: `estado` (columna real) y `situacion` (recalculada hoy contra `fecha_vencimiento`) | FACTIBLE HOY | ✔ implementado | Tabla de vencimientos con semáforo de fechas — Compras, Gerente, Administrador |
| OTD-COM-03 | Saber si pagamos a los proveedores a tiempo: pagos hechos antes o después de la fecha de vencimiento, por proveedor y por mes. | — | X | BDColumnar | `pago_proveedor.fecha_pago/monto/cuenta_por_pagar_id` (**902 pagos, $16.084.462,74**) vs `cuenta_por_pagar.fecha_vencimiento` — ambas 100 % pobladas | FACTIBLE HOY | — (ETL) | Puntualidad de pago por proveedor y mes — Compras, Gerente, Administrador, Analista |
| OTD-COM-04 | Conocer cuánto gastamos en compras por proveedor y por mes. | — | X | BDColumnar | `factura_compra.total/fecha_emision/proveedor_id` (**839 facturas, $22.467.387,27, 11 proveedores, 19 meses**) | FACTIBLE HOY | — (ETL) | Barras por proveedor con evolución mensual — Compras, Gerente, Administrador, Analista |
| OTD-COM-05 | Saber si cada proveedor cumple el compromiso que pactó: comparar la fecha de entrega que prometió al confirmar la orden contra el día en que la mercancía llegó de verdad, para detectar a quién incumple su palabra. | — | X | BDColumnar | Brecha CERRADA (2026-07-21/22) y **ya poblada**: `orden_compra.fecha_entrega_esperada` en **849 de 865 órdenes**, con **825 pares promesa/llegada comparables** contra `recepcion_mercancia.fecha_recepcion` | FACTIBLE HOY | — (ETL) | Cumplimiento de plazo por proveedor — Compras, Gerente |
| OTD-COM-06 | Medir el tiempo real observado del ciclo de compra: cuántos días tarda en la práctica la mercancía en llegar desde que emitimos la orden, exista o no una fecha prometida de por medio. | — | X | BDColumnar | `orden_compra.fecha_emision` + `recepcion_mercancia.fecha_recepcion/orden_compra_id` (**839 recepciones**) | FACTIBLE HOY | — (ETL) | Días de ciclo de compra por proveedor y período — Compras, Gerente, Analista |
| OTD-COM-07 | Conocer cuánta mercancía llega en mal estado y se rechaza en la puerta al recibirla, por proveedor y por motivo. | — | X | BDColumnar | `recepcion_detalle.cantidad_rechazada/motivo_rechazo/cantidad_recibida` — **92 de 2.855 líneas con rechazo registrado y motivo, sobre 6 motivos distintos** (antes: 1 de 22) | FACTIBLE HOY | — (ETL) | Porcentaje rechazado por proveedor — Compras, Gerente; Bodega lo ve en cantidades, sin montos |
| OTD-COM-08 | Ver los artículos defectuosos pendientes de devolver al proveedor y en qué paso va cada devolución. | X | — | BDR | `item_defectuoso.estado/origen/cantidad/proveedor_id` (**38 ítems, 10 pendientes**), `devolucion_proveedor.numero/estado` (8 devoluciones) | FACTIBLE HOY | ✔ implementado | Tablero del pool de defectuosos y devoluciones en curso — Compras, Gerente; Bodega en cantidades, sin montos |
| OTD-COM-09 | Saber cuánto recuperamos de los proveedores por mercancía defectuosa: crédito a favor o reposición de producto. | — | X | BDColumnar | `devolucion_proveedor.tipo_resolucion/monto_credito/fecha_resolucion` + `item_defectuoso.costo_unitario` — **8 devoluciones, 6 resueltas** (3 reposición + 3 nota de crédito por $4.196,85), repartidas en apenas 6 meses distintos | REQUIERE VOLUMEN — el flujo escribe todo, pero 6 resoluciones sobre 11 proveedores y 19 meses no sostienen un agregado por proveedor y período | — (ETL) | Monto recuperado por proveedor y período — Compras, Gerente, Administrador |
| OTD-COM-10 | Comparar a qué proveedor conviene comprarle cada producto: costo, plazo de entrega y proveedor preferido. | X | — | BDR | Brecha CERRADA (script 51) y **poblada**: `producto_proveedor` con **1.106 ofertas de 11 proveedores**, 1.093 con `tiempo_entrega_dias` y 1.040 marcadas `es_preferido` (antes: 0 filas) | FACTIBLE HOY | ✔ implementado | Ficha comparativa de proveedores por producto — Compras, Gerente |
| OTD-COM-11 | Detectar qué proveedores entregan incompleto: comparar lo que se pidió contra lo que de verdad llegó, línea por línea y por proveedor. | X | — | BDR | `orden_compra_detalle.cantidad/cantidad_recibida` (**259 de 2.949 líneas con recepción menor a la pedida**) + `orden_compra.proveedor_id/fecha_emision` → `proveedor.razon_social` | FACTIBLE HOY | — (único simple pendiente) | Líneas incompletas y porcentaje de cumplimiento por proveedor — Compras, Gerente; Bodega en cantidades, sin montos |
| OTD-COM-12 | Saber si está subiendo el costo de lo que compramos: cómo cambia el precio que cobra el proveedor por cada producto entre una compra y la siguiente. | — | X | BDColumnar | `orden_compra_detalle.precio_unitario/producto_variante_id` (**2.949 líneas en 19 meses**) + `orden_compra.fecha_emision/proveedor_id`; cada línea de compra conserva su precio a esa fecha | FACTIBLE HOY | — (ETL) | Evolución del costo de compra por producto y proveedor — Compras, Gerente, Analista |

## 5. INVENTARIO / BODEGA (OTD-INV)

El jefe de bodega dirige el activo más grande de la distribuidora: **$22.024.063,50 de mercancía
almacenada** en 1.406 posiciones de stock repartidas en dos bodegas. Dirige qué hay, dónde está,
qué falta, qué sobra, qué se mueve y qué se pierde — y cómo evoluciona en el tiempo el capital
almacenado. Por la segregación financiera del sistema, su tablero trabaja en cantidades; los
objetivos con dinero de este bloque se muestran a Gerencia y Administración.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado (MCP 2026-07-26) | Factibilidad | Reporte | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|---|
| OTD-INV-01 | Detectar los productos que están por debajo de su tope mínimo y hay que reponer. | X | — | BDR | `inventario.stock_actual` vs `inventario.stock_minimo` — **1.227 de 1.406 posiciones con mínimo definido y 162 bajo mínimo hoy** (script 54 cargó el dato maestro). El default 0 significa «sin mínimo definido», por eso el informe exige `stock_minimo > 0` | FACTIBLE HOY | ✔ implementado | Lista de reposición con faltante por bodega — Bodega, Compras, Gerente, Administrador |
| OTD-INV-02 | Consultar las existencias actuales de cada producto en cada bodega, incluyendo lo apartado para pedidos. | X | — | BDR | `inventario.stock_actual/stock_reservado/bodega_id` (**1.406 filas**) + `bodega.nombre` (2 bodegas) | FACTIBLE HOY | ✔ implementado | Tabla de existencias con buscador — Bodega, Compras, Vendedor, Gerente, Administrador |
| OTD-INV-03 | Revisar la historia completa de entradas y salidas de cualquier producto: qué se movió, cuándo, cuánto y por qué razón. | X | — | BDR | `movimiento_inventario.cantidad/stock_anterior/stock_nuevo/fecha_creacion/referencia_tipo` + `tipo_movimiento.codigo/nombre/factor` — **13.287 movimientos, los 9 tipos en uso** | FACTIBLE HOY | ✔ implementado | Kardex por producto con filtro de tipo y fecha — Bodega, Gerente, Administrador |
| OTD-INV-04 | Saber qué categorías de producto rotan más y cuáles se quedan paradas en bodega, por período. | — | X | BDColumnar | `movimiento_inventario.cantidad/fecha_creacion/tipo_movimiento_id` (13.287) + `producto_categoria.categoria_id` → `categoria.nombre` (1.214 asignaciones, 11 categorías) | FACTIBLE HOY | — (ETL) | Rotación por categoría y período — Gerente, Analista, Administrador; Bodega en cantidades |
| OTD-INV-05 | Controlar la mercancía perdida o sobrante detectada en los ajustes de inventario y sus motivos. | X | — | BDR | `ajuste_inventario.tipo/motivo/estado/fecha_aplicacion` — **53 ajustes (50 aplicados, 3 anulados) repartidos en 19 meses** + movimientos `entrada_ajuste`/`salida_ajuste` del kardex, enlazados por `referencia_tipo` | FACTIBLE HOY | ✔ implementado | Lista de ajustes con motivo y cantidades — Bodega, Gerente, Administrador |
| OTD-INV-06 | Seguir las transferencias de mercancía entre bodegas: cuáles van en camino y cuáles ya se recibieron. | X | — | BDR | `transferencia_bodega.estado/fecha_envio/fecha_recepcion/bodega_origen_id/bodega_destino_id` — **71 transferencias en los 4 estados** (57 recibidas, más en tránsito, pendientes y canceladas), en 19 meses | FACTIBLE HOY | ✔ implementado | Tabla de transferencias por estado — Bodega, Gerente, Administrador |
| OTD-INV-07 | Saber cuánto dinero hay parado en mercancía almacenada, por categoría y por bodega. | X | — | BDR | `inventario.stock_actual` × `producto_variante.costo` (costo poblado en las 1.221 variantes, con bandas mayoristas del script 67) + `producto_categoria`/`categoria.nombre` — **$22.024.063,50 valorizados** | FACTIBLE HOY | ✔ implementado | Valor del inventario por categoría/bodega — Gerente, Administrador, Analista (Bodega NO: es dinero) |
| OTD-INV-08 | Detectar productos con demasiada existencia — por encima del tope máximo deseado — para no enterrar dinero en mercancía de más. | X | — | BDR | Brecha CERRADA y **poblada** (PUT `/api/inventario/niveles` + script 54): `inventario.stock_maximo` en **1.227 de 1.406 posiciones, con 184 sobre-stock hoy** (antes: 0/1227, 100 % NULL) | FACTIBLE HOY | ✔ implementado | Lista de sobre-stock por bodega — Bodega, Compras, Gerente |
| OTD-INV-09 | Ver cómo evoluciona mes a mes el dinero inmovilizado en la mercancía almacenada, para saber si la bodega se está llenando o vaciando de capital. | — | X | BDColumnar | Reconstrucción del stock al cierre de cada mes: `inventario.stock_actual` (1.406 filas) menos los movimientos posteriores del kardex — `movimiento_inventario.cantidad/fecha_creacion` (13.287, con `stock_anterior/stock_nuevo` como respaldo encadenado por `(fecha_creacion, id)`) y el signo de `tipo_movimiento.factor` — valorizado con `producto_variante.costo`. Usa el costo vigente: no existe histórico de costos (ver Decisiones de alcance) | FACTIBLE HOY | — (ETL) | Línea mensual del valor almacenado por bodega y categoría — Gerente, Administrador, Analista (Bodega NO: es dinero) |
| OTD-INV-10 | Conocer las mermas (mercancía perdida) y los sobrantes acumulados por período y por motivo, para atacar las causas de la pérdida. | — | X | BDColumnar | `ajuste_inventario.tipo/motivo/fecha_aplicacion` (**53 ajustes sobre 7 motivos tipificados, en 19 meses**) + kardex `movimiento_inventario.cantidad` con `tipo_movimiento.codigo` 'salida_ajuste'/'entrada_ajuste', enlazados por `referencia_tipo='ajuste_inventario'` | FACTIBLE HOY | — (ETL) | Acumulado por motivo y mes — Bodega en cantidades; valorizado solo Gerente, Administrador |

## 6. LOGÍSTICA / DESPACHO (OTD-LOG)

El jefe de despacho dirige la última milla mayorista y el camino de regreso: la cola de despacho,
**2.872 envíos** con cinco transportistas, los problemas de entrega y todo el ciclo de
devoluciones de clientes (solicitud → revisión → retorno → inspección → reembolso → cierre). Por
la segregación financiera, Despacho ve estados, fechas y cantidades; el dinero (reembolsos, costo
de envío) va a Gerencia, Administración y Soporte.

**Nota de reclasificación (2026-07-26) — OTD-LOG-11 pasó de COMPUESTO a SIMPLE.** En la versión
anterior estaba marcado COMPUESTO por analogía con el resto del bloque. Al implementarlo se
comprobó que la pregunta que el jefe de despacho realmente hace («¿cuánto me cuesta llevar cada
envío, por zona y transportista?») se responde con una **foto agregada del presente**: agrupa
`envio.costo` por zona y transportista sobre el universo vigente, sin recorrer meses ni comparar
períodos. Aplicando literalmente la regla de la sección 2 —sumar sobre el presente no convierte
un informe en compuesto—, es SIMPLE, y así se construyó (9 filas agregadas, sin paginar). **La
serie temporal del costo de envío —cómo evoluciona la tarifa mes a mes, que sí es compuesta—
queda para ClickHouse** como parte del bloque de objetivos compuestos de logística.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado (MCP 2026-07-26) | Factibilidad | Reporte | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|---|
| OTD-LOG-01 | Ver la cola de pedidos listos y en espera de ser despachados. | X | — | BDR | `pedido.estado_pedido_id` → `estado_pedido.codigo` de los tres estados del tramo de salida (facturado / en_preparacion / preparado), `pedido.numero/fecha_pedido` — **48 pedidos en cola hoy** | FACTIBLE HOY | ✔ implementado | Cola de trabajo de despacho — Despacho, Gerente, Administrador |
| OTD-LOG-02 | Seguir los envíos: cuáles van en camino, cuáles ya se entregaron, cuáles volvieron, y con qué transportista y número de guía viaja cada uno. | X | — | BDR | `envio.estado/numero_guia/fecha_despacho` (**2.872 envíos, 2.727 con entrega real**) + `transportista.nombre` (**5 transportistas**) | FACTIBLE HOY | ✔ implementado | Tablero de envíos por estado y transportista — Despacho, Gerente, Administrador |
| OTD-LOG-03 | Saber si cumplimos la fecha de entrega prometida al cliente: de los envíos ya entregados, cuántos llegaron a más tardar el día prometido y cuántos llegaron tarde, por transportista. | — | X | BDColumnar | `envio.fecha_entrega_estimada` vs `envio.fecha_entrega_real` — **2.723 envíos con ambas fechas** + `envio.transportista_id` (5 transportistas) | FACTIBLE HOY | — (ETL) | Cumplimiento de fecha prometida por transportista — Despacho, Gerente, Analista |
| OTD-LOG-04 | Medir la duración real del tránsito — los días que pasan desde que el paquete sale de bodega hasta la puerta del cliente — para comparar transportistas entre sí, sin importar qué fecha se haya prometido. | — | X | BDColumnar | `envio.fecha_despacho` vs `envio.fecha_entrega_real` (ambas pobladas en los **2.727 envíos entregados**) | FACTIBLE HOY | — (ETL) | Días de tránsito promedio por transportista y período — Despacho, Gerente, Analista |
| OTD-LOG-05 | Controlar los problemas de entrega — cliente ausente, dirección equivocada, rechazo en la puerta, daño en el camino — cuántos ocurren, cuántos intentos toman y cómo terminan. | — | X | BDColumnar | `novedad_envio.tipo/estado/accion/fecha_registro/fecha_resolucion` — **176 novedades (169 resueltas) repartidas en 19 meses** sobre los 5 tipos del CHECK; los intentos se calculan como 1 + reprogramaciones | FACTIBLE HOY | — (ETL) | Problemas de entrega por tipo y desenlace — Despacho, Gerente, Soporte |
| OTD-LOG-06 | Ver las devoluciones de clientes en curso y en qué paso va cada una (solicitada, en revisión, aprobada, en camino de vuelta, recibida, inspeccionada, reembolsada, cerrada). | X | — | BDR | `devolucion.numero/estado/guia_retorno/fecha_creacion` (**196 devoluciones**, 91 en estados terminales) + `devolucion_detalle.resultado_inspeccion` (162 líneas inspeccionadas) | FACTIBLE HOY | ✔ implementado | Tablero del ciclo de devolución por estado — Despacho, Soporte, Gerente; Bodega ve su tramo de inspección, sin montos |
| OTD-LOG-07 | Medir cuántos días tarda una devolución de cliente desde que se solicita hasta que se cierra. | — | X | BDColumnar | `devolucion.fecha_creacion` + `historial_estado_devolucion.estado/fecha_creacion` — **1.008 registros de historial; 161 de las 196 devoluciones tienen 3 o más pasos fechados**, así que los tramos intermedios son medibles (antes: casos legacy migrados directo a 'cerrada') | FACTIBLE HOY | — (ETL) | Días de ciclo de devolución por período — Gerente, Soporte, Analista |
| OTD-LOG-08 | Saber por qué devuelven los clientes y qué pasa con esa mercancía: vuelve a venderse, resulta defectuosa o se rechaza sin reembolso. | — | X | BDColumnar | `devolucion.motivo_devolucion_id` → `motivo_devolucion.nombre` (**los 4 motivos en uso**) + `devolucion_detalle.resultado_inspeccion/cantidad` (**274 líneas, 162 con inspección registrada**) | FACTIBLE HOY | — (ETL) | Motivos de devolución y destino de la mercancía — Gerente, Soporte, Analista; Bodega en cantidades |
| OTD-LOG-09 | Saber, de cada 100 envíos, cuántos terminan en devolución, mes a mes. | — | X | BDColumnar | `envio.fecha_despacho` (2.872 envíos) vs `devolucion.pedido_id/fecha_creacion` (196 devoluciones), ambos con cobertura en los 19 meses | FACTIBLE HOY | — (ETL) | Proporción de devoluciones sobre envíos, mensual — Gerente, Analista; Despacho en conteos |
| OTD-LOG-10 | Controlar el dinero devuelto a los clientes: cuánto se reembolsó, por qué vía y por qué motivo. | — | X | BDColumnar | Brecha CERRADA (`DevolucionService.reembolsar`) y **poblada**: tabla `reembolso` con **85 filas, $44.525,63** en monto, vía y fecha (antes: 1 fila) | FACTIBLE HOY | — (ETL) | Reembolsos pagados por período y vía — Gerente, Administrador, Soporte (Despacho NO: es dinero) |
| OTD-LOG-11 | Saber cuánto nos cuesta llevar cada envío, por zona y transportista, para revisar las tarifas que cobramos al cliente. | X | — | BDR | Brecha CERRADA (`VentasService.costoEnvioPorTarifa`) y **poblada**: `envio.costo > 0` en **2.848 de 2.872 envíos, $32.723,25 en total**, con `peso_total_kg` en 2.848 (antes: 0.00 en 24/24). `producto_variante.peso_kg` poblado en las 1.221 variantes y `tarifa_envio.costo_por_kg` > 0 en las 3 tarifas (script 54). **La ZONA no es una columna del envío**: se resuelve desde la dirección del pedido por ciudad > provincia > país, la misma cadena de `asignarEnvioPorZona` | FACTIBLE HOY | ✔ implementado | Costo de envío por zona/transportista — Gerente, Administrador (Despacho NO: es dinero) |
| OTD-LOG-12 | Medir cuánto tarda un pedido en cada etapa del camino — del pago a la preparación, de la preparación al despacho y del despacho a la entrega — para encontrar el cuello de botella real y no suponerlo. | — | X | BDColumnar | `historial_estado_pedido.pedido_id/estado_pedido_id/fecha_creacion` (**24.608 registros que cubren los 4.083 pedidos**) + `estado_pedido.codigo` — cada transición quedó fechada, lo que permite medir cada tramo | FACTIBLE HOY | — (ETL) | Días u horas promedio por etapa del ciclo, por período — Despacho (tiempos y estados, sin dinero), Gerente, Analista |

## 7. SOPORTE (OTD-SOP)

El jefe de soporte dirige la atención de reclamos y consultas de la cartera corporativa: la
bandeja de tickets, la urgencia, la carga de su equipo y los tiempos de respuesta. Es un área más
pequeña que Ventas o Compras y su catálogo lo refleja: **248 tickets en 19 meses, 6 agentes, 8
categorías, todas con casos**.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado (MCP 2026-07-26) | Factibilidad | Reporte | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|---|
| OTD-SOP-01 | Ver los reclamos y consultas abiertos: cuántos hay, de qué tipo, qué tan urgentes son y quién atiende cada uno. | X | — | BDR | `ticket_soporte.numero/estado/prioridad/categoria_ticket_id/asignado_usuario_id` — **248 tickets, 128 vivos**, 6 agentes asignados | FACTIBLE HOY | ✔ implementado | Bandeja con filtros por estado, urgencia y categoría — Soporte, Gerente, Administrador |
| OTD-SOP-02 | Saber cuántos reclamos atendemos dentro del tiempo prometido al cliente según su urgencia. | — | X | BDColumnar | Brecha CERRADA (script 49): `ticket_soporte.fecha_limite` existe y está poblada en **248 de 248 tickets** (con backfill derivado del plazo por urgencia 2 h/4 h/24 h/72 h, que antes vivía solo en código) contra `fecha_cierre` (76 tickets cerrados) | FACTIBLE HOY | — (ETL) | Cumplimiento del tiempo prometido por período — Soporte, Gerente |
| OTD-SOP-03 | Medir cuántas horas o días tardamos en resolver cada tipo de problema. | — | X | BDColumnar | `ticket_soporte.fecha_creacion` vs `ticket_soporte.fecha_cierre` (**76 tickets cerrados**, antes 2) + `categoria_ticket.nombre` (8 categorías, todas con casos) | FACTIBLE HOY | — (ETL) | Tiempo de resolución por categoría — Soporte, Gerente, Analista |
| OTD-SOP-04 | Saber qué tipos de problema generan más reclamos, para atacar la causa de fondo. | X | — | BDR | `ticket_soporte.categoria_ticket_id` → `categoria_ticket.nombre` — **las 8 categorías tienen tickets** (antes 4 de 8 estaban vacías); el porcentaje se calcula sobre el total del período con `sum(count(*)) OVER ()` | FACTIBLE HOY | ✔ implementado | Distribución de tickets por categoría — Soporte, Gerente |
| OTD-SOP-05 | Repartir bien el trabajo del equipo: reclamos asignados y resueltos por cada persona de soporte. | X | — | BDR | `ticket_soporte.asignado_usuario_id` → `usuario.nombre/apellido` (**6 agentes**), `ticket_soporte.estado`; la fila «(sin asignar)» se emite aparte porque es el dato accionable | FACTIBLE HOY | ✔ implementado | Carga y cierre por agente — Soporte, Gerente, Administrador |
| OTD-SOP-06 | Medir cuánto tardamos en dar la primera respuesta al cliente que reclama: desde que abre su caso hasta que alguien del equipo le contesta por primera vez. | — | X | BDColumnar | `ticket_soporte.fecha_creacion` vs el primer `mensaje_ticket.fecha_creacion` cuyo autor es del equipo (`usuario_id` poblado) y visible al cliente (`es_interno = false`) — **193 de 248 tickets con primera respuesta del equipo**, sobre 523 mensajes | FACTIBLE HOY | — (ETL) | Horas hasta la primera respuesta, por período y urgencia — Soporte, Gerente |
| OTD-SOP-07 | Medir cuánto tarda cada persona del equipo de soporte en resolver los casos que se le asignan. | — | X | BDColumnar | `ticket_soporte.asignado_usuario_id` → `usuario.nombre/apellido` (6 agentes) + `ticket_soporte.fecha_creacion/fecha_cierre` (**76 cierres reales**, antes 2) | FACTIBLE HOY | — (ETL) | Tiempo de resolución por agente y período — Soporte, Gerente |
| OTD-SOP-08 | Saber qué productos generan más reclamos y más devoluciones, para pedir a Compras que revise el producto o a Ventas que corrija su descripción. | — | X | BDColumnar | Brecha CERRADA (script 50): `ticket_soporte.producto_variante_id` existe y está poblada en **142 de 248 tickets** (antes la columna no existía y el reclamo no se podía ligar a un producto). La otra mitad ya era factible: devoluciones por producto vía `devolucion_detalle.pedido_detalle_id` → `pedido_detalle.producto_variante_id` → `producto.nombre` (274 líneas) | FACTIBLE HOY | — (ETL) | Ranking de productos por reclamos y devoluciones — Soporte, Gerente, Compras |

## 8. GERENCIA / DIRECCIÓN (OTD-GER) — incluye Marketing

La gerencia dirige la distribuidora completa: la foto consolidada del día, el equilibrio entre lo
que entra por ventas ($5,72 M) y lo que sale hacia proveedores ($22,47 M facturados), la ganancia
por línea de producto, las acciones de marketing mayorista (cupones de volumen, promociones,
campañas) y el control interno (quién hizo qué en el sistema y quién intenta entrar a él).

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado (MCP 2026-07-26) | Factibilidad | Reporte | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|---|
| OTD-GER-01 | Tener la foto del día del negocio: pedidos de hoy, dinero cobrado hoy y pendientes que necesitan decisión. | X | — | BDR | `pedido.fecha_pedido/total/estado_pedido_id` (4.083) + `pago.fecha_pago/monto` (4.079) + `factura_venta` (3.887) + `estado_pedido.nombre`. El bloque de pendientes es **al momento**, no del día consultado | FACTIBLE HOY | ✔ implementado | Tarjetas de resumen del día — Gerente, Administrador |
| OTD-GER-02 | Comparar mes a mes el dinero que entra por ventas contra el dinero que sale hacia proveedores — la balanza comercial interna del negocio. | — | X | BDColumnar | Facturado: `factura_venta.total/fecha_emision` (**3.887 facturas, $5.417.807,65** sin anuladas) vs `factura_compra.total/fecha_emision` (**839, $22.467.387,27**). En caja: `pago.monto/fecha_pago` (**$5.467.791,59**) vs `pago_proveedor.monto/fecha_pago` (**$16.084.462,74**) | FACTIBLE HOY | — (ETL) | Barras enfrentadas entradas vs salidas, por mes — Gerente, Administrador, Analista |
| OTD-GER-03 | Saber qué categorías de producto dejan más ganancia (venta menos costo), por período. | — | X | BDColumnar | `pedido_detalle.precio_unitario/cantidad/monto_descuento` (10.384 líneas) + `producto_variante.costo` (1.221 variantes, bandas mayoristas del script 67) + `producto_categoria`/`categoria.nombre` (11 categorías) | FACTIBLE HOY | — (ETL) | Ganancia por categoría y período — Gerente, Analista, Administrador |
| OTD-GER-04 | Ver los cupones de descuento activos, cuántos usos les quedan y cuándo vencen. | X | — | BDR | `cupon.codigo/activo/usos_actuales/usos_maximos/fecha_inicio/fecha_fin` — **33 cupones, 7 vigentes**. La `situacion` replica las TRES condiciones de canje de `DescuentosService` (activo + ventana + usos disponibles), no solo `activo` | FACTIBLE HOY | ✔ implementado | Tabla de cupones con vigencia y usos restantes — Gerente, Administrador |
| OTD-GER-05 | Saber qué cupones usaron efectivamente los clientes y cuánto descuento le costaron al negocio, por período. | — | X | BDColumnar | `uso_cupon.cupon_id/monto_descontado/fecha_creacion` — **564 usos de 25 cupones distintos, $50.727,89 otorgados, repartidos en 19 meses** (antes: 3 usos, $52,91) | FACTIBLE HOY | — (ETL) | Descuento otorgado por cupón y período — Gerente, Analista, Administrador |
| OTD-GER-06 | Ver las acciones de marketing vigentes: promociones (y los productos que abarcan), campañas y anuncios de la tienda. | X | — | BDR | `promocion.fecha_inicio/fecha_fin/activo` (**24 promociones, 6 vigentes**) + `promocion_producto.producto_id` (**232 asignaciones, las 24 promos con productos**) + `campana.estado/fecha_inicio/fecha_fin` (**18, 6 activas**) + `banner.activo/fecha_inicio` (**23, 8 activos**) — 20 elementos vigentes en total | FACTIBLE HOY | ✔ implementado | Panel de vigencias de marketing — Gerente, Administrador |
| OTD-GER-07 | Saber si las promociones hacen vender más, comparando las ventas del producto antes y durante la promoción. | — | X | BDColumnar | `pedido_detalle.monto_descuento` (>0 = línea con promoción aplicada) + `promocion.fecha_inicio/fecha_fin` + `promocion_producto` — hay **24 promociones con 232 productos** y línea base amplia (4.133 líneas de esos productos ANTES de su ventana), pero solo **123 líneas efectivamente promocionadas en 195 líneas vendidas durante ventana**: la muestra «durante» es demasiado pequeña frente a la base | REQUIERE VOLUMEN — densificarla exigiría reasignar ventas ya sembradas (limitación aceptada y declarada) | — (ETL) | Ventas antes/durante promoción — Gerente, Analista |
| OTD-GER-08 | Revisar quién hizo qué en el sistema: aprobaciones, despachos, registros y moderaciones, con autor, fecha y detalle del cambio. | X | — | BDR | `log_auditoria.usuario_id/tabla/accion/registro_id/datos_anteriores/datos_nuevos/fecha_creacion` — **7.073 registros sobre 10 tablas, en 19 meses**. **DATO SENSIBLE**: solo ADMIN/GERENTE | FACTIBLE HOY | ✔ implementado | Registro de acciones filtrable por usuario, tabla y fecha — Administrador, Gerente |
| OTD-GER-09 | Saber quién intentó entrar al sistema y falló, desde dónde y por qué. | X | — | BDR | Brecha CERRADA (script 53) y **poblada**: `log_acceso.exitoso/motivo_fallo/ip_origen/email_intentado/fecha_creacion` con **1.537 intentos, 201 fallidos sobre los 4 motivos de `LoginFallidoException`**, en 19 meses (antes: 0 filas). **DATO SENSIBLE**: solo ADMIN/GERENTE | FACTIBLE HOY | ✔ implementado | Intentos de acceso fallidos recientes — Administrador, Gerente |
| OTD-GER-10 | Conocer la ganancia real de cada producto — la diferencia entre lo que costó y lo que se cobró — además de la vista por categoría, para corregir precios producto por producto. | — | X | BDColumnar | `producto_variante.precio/costo` (ambos poblados en las 1.221 variantes) + `pedido_detalle.producto_variante_id/cantidad/precio_unitario/monto_descuento` (**10.384 líneas, 834 variantes con venta**). Usa el costo vigente: no existe histórico de costos (misma salvedad de OTD-GER-03 e INV-09) | FACTIBLE HOY | — (ETL) | Margen por producto con buscador y filtro de período — Gerente, Analista, Administrador |
| OTD-GER-11 | Controlar cuánto descuento se entrega en total cada mes — sumando promociones y cupones — y sobre qué productos recae, para que el descuento no se coma el margen sin que nadie lo mida. | — | X | BDColumnar | `pedido_detalle.monto_descuento` (**123 líneas con promoción**), `pedido.monto_descuento` (**562 pedidos con cupón**) y `uso_cupon.monto_descontado` (**564 usos, $50.727,89**), con el prorrateo por producto ya resuelto en `factura_venta_detalle.monto_descuento` (scripts 72-73) | FACTIBLE HOY | — (ETL) | Descuento total entregado por mes y por producto — Gerente, Analista, Administrador |

---

## 9. RESUMEN CUANTITATIVO

**Total: 68 objetivos tácticos** (57 de la primera ronda + 11 incorporados por la auditoría del
cuestionario de negocio).

| Clasificación | Cantidad | Porcentaje |
|---|---|---|
| Informes SIMPLES (BDR PostgreSQL) | 29 | 42,6 % |
| Informes COMPUESTOS (BDColumnar ClickHouse) | 39 | 57,4 % |

| Departamento | Objetivos | Simples | Compuestos | Reportes implementados |
|---|---|---|---|---|
| Ventas | 15 | 5 | 10 | 5 de 5 |
| Compras | 12 | 5 | 7 | 4 de 5 |
| Inventario / Bodega | 10 | 7 | 3 | 7 de 7 |
| Logística / Despacho | 12 | 4 | 8 | 4 de 4 |
| Soporte | 8 | 3 | 5 | 3 de 3 |
| Gerencia / Dirección (incl. Marketing) | 11 | 5 | 6 | 5 de 5 |
| **Total** | **68** | **29** | **39** | **28 de 29** |

Verificación aritmética: 15+12+10+12+8+11 = 68; simples 5+5+7+4+3+5 = 29; compuestos
10+7+3+8+5+6 = 39; 29+39 = 68. Reportes 5+4+7+4+3+5 = 28.

**Cambio respecto de la versión anterior**: eran 28 simples / 40 compuestos. OTD-LOG-11 se
reclasificó de COMPUESTO a SIMPLE (ver nota en la sección 6), lo que mueve una unidad de
Logística: de 3/9 a 4/8.

| Estado de factibilidad | Cantidad | Objetivos |
|---|---|---|
| FACTIBLE HOY | 66 | Todos salvo los dos listados abajo |
| REQUIERE CAMBIO EN EL SISTEMA | 0 | — (las 10 brechas históricas están cerradas) |
| REQUIERE VOLUMEN DE DATOS | 2 | COM-09, GER-07 |

Verificación aritmética: 66 + 0 + 2 = 68.

Evolución del estado de factibilidad a lo largo del proyecto:

| Corte | FACTIBLE HOY | REQUIERE CAMBIO | REQUIERE VOLUMEN |
|---|---|---|---|
| 2026-07-21 (catálogo ampliado) | 44 | 10 | 14 |
| 2026-07-22 (cierre de 4 brechas) | 45 | 6 | 17 |
| **2026-07-26 (esta versión)** | **66** | **0** | **2** |

El salto de 45 a 66 tiene dos causas verificadas: (a) el cierre de las 6 brechas de sistema
restantes con los scripts 46-53 (metas de venta, pagos fallidos, catálogo proveedor-producto,
fecha límite del ticket, producto en el ticket y registro de accesos), cada una en sus tres
capas; y (b) la siembra de 19 meses de operación histórica con los scripts 55-84, que llenó los
volúmenes que faltaban (4.083 pedidos, 865 órdenes de compra, 2.872 envíos, 248 tickets, 344
reseñas, 13.287 movimientos de kardex, 7.073 registros de auditoría y 1.537 intentos de acceso).

La asimetría entre departamentos es deliberada: Ventas y Logística (que carga todo el ciclo de
devoluciones de cliente) pesan más que Soporte; Compras pesa porque en una distribuidora
mayorista el abastecimiento es el centro de costo dominante; Inventario combina el control del
presente (existencias, kardex, ajustes) con la evolución del capital almacenado y de las mermas
en el tiempo (OTD-INV-09 y OTD-INV-10), mientras que Ventas, Logística y Gerencia concentran
compuestos porque sus decisiones son de evolución en el tiempo. De los 11 objetivos incorporados
por la auditoría, 9 son compuestos, lo que confirma el patrón: lo que a los jefes les faltaba no
era tanto ver el presente (eso el catálogo ya lo cubría casi por completo) sino medir tiempos de
ciclo, comparar períodos y consolidar dinero.

## 10. COBERTURA DE MÓDULOS

Ningún módulo operativo del sistema queda sin al menos un objetivo que lo reclame:

| Módulo operativo del sistema | Objetivos que lo cubren |
|---|---|
| Ciclo de venta (pedidos, estados, historial) | OTD-VEN-01, VEN-06, VEN-07, VEN-13, VEN-15, LOG-12, GER-01 |
| Vendedores / trazabilidad de autor del pedido | OTD-VEN-02 |
| Catálogo de productos y ranking de ventas | OTD-VEN-03, VEN-04, GER-10 |
| Clientes (visión desde el cliente) | OTD-VEN-05 |
| Tienda en línea: carrito y checkout | OTD-VEN-08 |
| Pagos de venta y formas de pago | OTD-VEN-09, VEN-12 |
| Reseñas y preguntas de producto (con moderación) | OTD-VEN-10, VEN-11 |
| Metas de venta por departamento y período | OTD-VEN-15 |
| Ciclo de compra (órdenes, aprobación) | OTD-COM-01, COM-05, COM-06, COM-11, COM-12 |
| Recepciones y rechazo en puerta | OTD-COM-07 |
| Cuentas por pagar y puntualidad de pago a proveedor | OTD-COM-02, COM-03 |
| Gasto de compras / facturas de compra | OTD-COM-04, GER-02 |
| Devolución a proveedor e ítems defectuosos | OTD-COM-08, COM-09 |
| Catálogo proveedor–producto | OTD-COM-10 |
| Inventario, stock mínimo/máximo y kardex | OTD-INV-01, INV-02, INV-03, INV-04, INV-07, INV-08, INV-09 |
| Ajustes de inventario (mermas/sobrantes) | OTD-INV-05, INV-10 |
| Transferencias entre bodegas | OTD-INV-06 |
| Despacho y envíos (transportistas, guías) | OTD-LOG-01, LOG-02, LOG-03, LOG-04 |
| Novedades de envío (incidencias de entrega) | OTD-LOG-05 |
| RMA / devoluciones de cliente (ciclo de 8 estados) | OTD-LOG-06, LOG-07, LOG-08, LOG-09, VEN-14, SOP-08 |
| Reembolsos a clientes | OTD-LOG-10 |
| Zonas y tarifas de envío | OTD-LOG-11 |
| Soporte (tickets, categorías, agentes, tiempos, SLA) | OTD-SOP-01 a SOP-08 |
| Marketing: cupones y sus usos | OTD-GER-04, GER-05, GER-11 |
| Marketing: promociones, campañas y banners | OTD-GER-06, GER-07 |
| Facturación de venta | OTD-GER-02 (lado de ingresos) |
| Auditoría central (`log_auditoria`) | OTD-GER-08 |
| Seguridad de acceso al sistema (`log_acceso`) | OTD-GER-09 |

**28 módulos cubiertos.** Los seis módulos que la auditoría de sincronización marcó como
huérfanos en el TA11 original quedan todos reclamados: RMA de cliente (LOG-06/07/08/09),
devolución a proveedor e ítems defectuosos (COM-08/09), novedades de envío (LOG-05), reseñas y
preguntas (VEN-10/11), auditoría central (GER-08) y puntualidad de pago a proveedor (COM-03).

## 11. CIERRE DE BRECHAS

### 11.0 Brechas CERRADAS y verificadas — las diez, con su dato ya poblado

Diez objetivos estuvieron en algún momento en REQUIERE CAMBIO EN EL SISTEMA. **Los diez fueron
resueltos en las tres capas (BD, backend y formulario), verificados contra el sistema real y hoy
devuelven filas.** El cierre de la brecha y la existencia del dato son dos cosas distintas: por
eso esta tabla separa cómo se cerró de qué muestra la base hoy.

| ID | Cómo se cerró (las tres capas) | Estado del dato hoy (MCP 2026-07-26) | Factibilidad |
|---|---|---|---|
| OTD-COM-05 | **BD**: `orden_compra.fecha_entrega_esperada` (ya existía). **Backend**: la creación de la orden la persiste y la valida contra la fecha de emisión. **Formulario**: campo «Fecha de entrega prometida» en la orden de compra | 849 de 865 órdenes con fecha prometida y **825 pares promesa/llegada** comparables | FACTIBLE HOY |
| OTD-INV-08 | **BD**: `inventario.stock_maximo` (ya existía). **Backend**: PUT `/api/inventario/niveles` escribe mínimo y máximo. **Formulario**: campos de niveles en la pantalla de inventario | 1.227 de 1.406 posiciones con tope máximo; **184 en sobre-stock** | FACTIBLE HOY |
| OTD-LOG-10 | **BD**: tabla `reembolso` (ya existía). **Backend**: `DevolucionService.reembolsar` inserta la fila. **Formulario**: la pantalla de reembolso ya capturaba monto y vía | **85 reembolsos, $44.525,63** | FACTIBLE HOY |
| OTD-LOG-11 | **BD**: `envio.costo`/`peso_total_kg` (ya existían). **Backend**: `VentasService.costoEnvioPorTarifa` calcula desde `tarifa_envio` al despachar. **Formulario**: sin captura (cálculo automático); el costo se muestra solo a roles con acceso a montos | **2.848 de 2.872 envíos con costo, $32.723,25**; pesos y costo por kg cargados (script 54) | FACTIBLE HOY |
| OTD-VEN-12 | **BD**: `pago.estado` y `transaccion_pago.tipo/respuesta_pasarela` (ya existían). **Backend**: la pasarela simulada registra el intento rechazado en transacción propia (REQUIRES_NEW) antes de devolver el error. **Formulario**: el checkout muestra el motivo del rechazo | **176 pagos fallidos** con 6 motivos distintos; 176 transacciones de tipo 'autorizacion' | FACTIBLE HOY |
| OTD-VEN-15 | **BD**: tabla NUEVA `meta_venta` (script 48) con grants, RLS y política de horario. **Backend**: `MetasVentaService` (CRUD + cálculo de avance). **Formulario**: captura de metas para Gerencia (período, departamento, monto) | **133 metas = 7 departamentos × 19 meses** | FACTIBLE HOY |
| OTD-COM-10 | **BD**: `producto_proveedor` (ya existía). **Backend**: se alimenta automáticamente desde la recepción de compra (script 51, función SECURITY DEFINER). **Formulario**: ficha de ofertas del proveedor | **1.106 ofertas de 11 proveedores**, 1.093 con plazo de entrega, 1.040 preferidas | FACTIBLE HOY |
| OTD-SOP-02 | **BD**: `ticket_soporte.fecha_limite` NUEVA (script 49) con grants por columna. **Backend**: `SoporteService` la persiste al crear y la recalcula al cambiar la prioridad; backfill derivado para el histórico. **Formulario**: sin captura — la bandeja pasa a leer la columna | **248 de 248 tickets con fecha límite**; 76 con cierre para contrastar | FACTIBLE HOY |
| OTD-SOP-08 | **BD**: `ticket_soporte.producto_variante_id` NUEVA (script 50) con grants de columna sin dinero a soporte. **Backend**: `SoporteService` escribe el producto al crear el ticket. **Formulario**: selector opcional de producto | **142 de 248 tickets ligados a un producto** | FACTIBLE HOY |
| OTD-GER-09 | **BD**: `log_acceso` (ya existía). **Backend**: el login escribe cada intento vía función SECURITY DEFINER en transacción propia (script 53), con los 4 motivos de `LoginFallidoException`. **Formulario**: ninguno — el login ya existía | **1.537 intentos, 201 fallidos**, 4 motivos, 19 meses | FACTIBLE HOY |

### 11.1 Objetivos en REQUIERE CAMBIO EN EL SISTEMA

**Ninguno.** Las diez brechas de la tabla 11.0 están cerradas en las tres capas. El método del
profesor —si falta el dato, corregirlo en base de datos, backend y formulario antes de construir
el ETL— se aplicó por completo sobre este catálogo.

### 11.2 Objetivos en REQUIERE VOLUMEN DE DATOS

En estos casos el esquema está completo y el flujo ya escribe el dato: no hay cambio de BD,
backend ni formulario. Lo que falta es densidad de operación antes de que el informe agregado sea
representativo.

| ID | Qué falta (conteo real que lo limita) | Cambio en BD / backend / formulario | Esfuerzo |
|---|---|---|---|
| OTD-COM-09 | 8 devoluciones a proveedor, 6 con resolución ($4.196,85 en notas de crédito y 3 reposiciones), repartidas en 6 meses distintos: un agregado «por proveedor y período» sobre 11 proveedores y 19 meses no discrimina | Ninguno — el ciclo completo (pool de defectuosos → devolución → nota de crédito/reposición) ya opera desde el script 45 | BAJO |
| OTD-GER-07 | 123 líneas efectivamente promocionadas frente a 4.133 líneas de línea base: la ventana «durante» es demasiado pequeña para sostener la comparación antes/durante producto por producto | Ninguno — promociones y descuentos por línea ya se escriben solos (scripts 40, 73). Densificarlo exigiría reasignar ventas ya sembradas: **limitación aceptada y declarada** | BAJO |

## 11.5 REPORTES SIMPLES IMPLEMENTADOS (28 de 29)

Los 28 informes SIMPLES construidos se consultan **por pantalla** en
`/operativo/informes/{departamento}`, todos bajo el mismo contrato
(`GET /api/informes/{departamento}/{informe}` devolviendo `{items, total, page, size, resumen}`).
No hay exportación a PDF: el nivel táctico se consulta con filtros y registros visibles. La
segregación financiera se respeta en las tres capas — `SecurityConfig` (ruta), motor
PostgreSQL (GRANTs por columna + RLS) y, cuando el motor no alcanza, la propia consulta.

| ID | Endpoint | Filtro principal | Volumen actual | Roles destinatarios |
|---|---|---|---|---|
| OTD-VEN-01 | `ventas/cartera-pedidos` | estado, canal, desde/hasta, buscar | 4.083 | Vendedor, Gerente, Admin |
| OTD-VEN-02 | `ventas/por-vendedor` | desde/hasta | 9 vendedores + fila del canal en línea | Gerente, Admin (Vendedor: solo lo propio) |
| OTD-VEN-08 | `ventas/carritos-abandonados` | estado, días mínimos | 216 abandonados | Gerente, Vendedor, Admin |
| OTD-VEN-10 | `ventas/moderacion` | tipo, días mínimos | 53 reseñas + 13 preguntas pendientes | Admin, Gerente |
| OTD-VEN-15 | `ventas/avance-meta` | período (mes) | 133 metas | Gerente, Vendedor, Admin |
| OTD-COM-01 | `compras/ordenes` | estado, proveedor, desde/hasta | 865 | Compras, Gerente, Admin |
| OTD-COM-02 | `compras/cuentas-por-pagar` | estado, situación, proveedor | 839 (276 vivas) | Compras, Gerente, Admin |
| OTD-COM-08 | `compras/defectuosos` | estado, origen, proveedor, buscar | 38 | Compras, Gerente, **Bodega** (sin montos: los corta el SQL) |
| OTD-COM-10 | `compras/catalogo-proveedor` | buscar, proveedor, oferta | 1.106 | Compras, Gerente |
| OTD-INV-01 | `inventario/bajo-minimo` | bodega, buscar | 162 de 1.406 | Bodega, Compras, Gerente, Admin |
| OTD-INV-02 | `inventario/stock-bodega` | bodega, situación, buscar | 1.406 | Bodega, Compras, Vendedor, Gerente, Admin |
| OTD-INV-03 | `inventario/kardex` | buscar, bodega, naturaleza, tipo, desde/hasta | 13.287 | Bodega, Gerente, Admin |
| OTD-INV-05 | `inventario/ajustes` | tipo, estado, motivo, bodega, desde/hasta | 53 | Bodega, Gerente, Admin |
| OTD-INV-06 | `inventario/transferencias` | estado, bodega, desde/hasta | 71 | Bodega, Gerente, Admin |
| OTD-INV-07 | `inventario/valor-inventario` | bodega, categoría | 19 filas ($22,02 M) | **CON MONTO**: Gerente, Admin, Analista |
| OTD-INV-08 | `inventario/sobre-stock` | bodega, buscar | 184 | Bodega, Compras, Gerente |
| OTD-LOG-01 | `logistica/cola-despacho` | estado, canal, transportista, buscar | 48 | Despacho, Gerente, Admin |
| OTD-LOG-02 | `logistica/envios` | estado, transportista, desde/hasta, buscar | 2.872 | Despacho, Gerente, Admin |
| OTD-LOG-06 | `logistica/devoluciones` | estado, motivo, desde/hasta, buscar | 196 | Despacho, Soporte, Gerente, Bodega (sin montos) |
| OTD-LOG-11 | `logistica/costo-envio` | zona, transportista, desde/hasta | 9 filas ($32.723,25) | **CON MONTO**: Gerente, Admin |
| OTD-SOP-01 | `soporte/bandeja` | estado, prioridad, categoría, agente, buscar | 248 (128 vivos) | Soporte, Gerente, Admin |
| OTD-SOP-04 | `soporte/por-categoria` | desde/hasta | 8 categorías | Soporte, Gerente |
| OTD-SOP-05 | `soporte/por-agente` | desde/hasta | 7 filas (6 agentes + «sin asignar») | Soporte, Gerente, Admin |
| OTD-GER-01 | `gerencia/foto-dia` | fecha | 20 filas agregadas | Gerente, Admin |
| OTD-GER-04 | `gerencia/cupones` | situación, tipo, buscar | 33 (7 vigentes) | Gerente, Admin |
| OTD-GER-06 | `gerencia/marketing` | tipo, vigencia, buscar | 65 (20 vigentes) | Gerente, Admin |
| OTD-GER-08 | `gerencia/auditoria` | usuario, tabla, acción, desde/hasta | 7.073 | **SENSIBLE**: Admin, Gerente |
| OTD-GER-09 | `gerencia/accesos` | resultado, correo, desde/hasta | 1.537 | **SENSIBLE**: Admin, Gerente |

**Pendiente: OTD-COM-11** (entregas incompletas por proveedor) — único informe simple del
catálogo aún no construido; su dato está verificado y disponible (259 líneas incompletas de
2.949). Los 39 objetivos COMPUESTOS no se implementan en este nivel: son trabajo del pipeline
ETL hacia ClickHouse.

El detalle de CÓMO se construyen (patrón, contrato, seguridad y lecciones por módulo) vive en
`docs/tactico/PATRON_INFORMES.md`.

## 12. DECISIONES DE ALCANCE (qué quedó fuera y por qué)

- **Trazabilidad por lote y vencimiento (FEFO)**: pospuesta deliberadamente en `ROADMAP.md`
  (2026-07-18); `lote` sigue con 0 filas y las FK están listas para esa fase. Ningún objetivo la
  reclama a propósito.
- **Ubicación física dentro de bodega (pasillo/estante)**: `ubicacion_bodega` sigue vacía. Es una
  necesidad operativa de picking, no de dirección táctica; se excluye del catálogo.
- **Segmentación de clientes (grupos, edad, género)**: `grupo_cliente`, `segmento_cliente` y
  `cliente_segmento` siguen con 0 filas, aunque el universo de clientes ya no es el limitante
  (72 clientes, 70 con fecha de nacimiento). La brecha es de captura, no de volumen: ninguna
  pantalla asigna cliente a segmento. Se reevaluará junto con OTD-VEN-05 cuando el negocio defina
  su política de segmentación mayorista (por ejemplo, por escalón de volumen anual).
- **Lista de deseos y comparador de productos**: `wishlist` (2 filas) y `comparacion` (0 filas)
  no sostienen hoy una decisión de dirección; la señal de demanda del cliente ya la aporta el
  módulo de recomendaciones (analítica, fuera de alcance de este catálogo).
- **Boletín de correos**: 1 suscriptor; la gestión existe pero no hay lista que dirigir todavía.
  Su vigencia queda representada dentro de OTD-GER-06.
- **Efectividad de campañas y banners por clics/visitas/conversión**: ni `campana` ni `banner`
  ni `promocion` tienen columnas de desempeño en PostgreSQL; ese dato vive (si existe) en
  ClickHouse/`analytics/`, que esta tarea tiene prohibido tocar. Solo se cataloga la vigencia
  (OTD-GER-06) y el efecto en ventas vía descuentos aplicados (OTD-GER-07).
- **Efectividad de las preguntas frecuentes (FAQ)**: `faq` (3 filas) no tiene ninguna relación
  con los tickets; medir si evitan reclamos exigiría instrumentación nueva sin demanda clara de
  la jefatura.
- **Calificación formal de proveedores y transportistas (puntaje)**: no existe columna de
  puntaje en `proveedor` ni `transportista`. Se decidió NO pedirla: el desempeño ya queda medido
  con hechos (OTD-COM-03 puntualidad de pago, OTD-COM-05 cumplimiento de plazo, OTD-COM-07
  rechazos, OTD-COM-11 entregas incompletas, OTD-LOG-03 entregas a tiempo), que son más
  defendibles que una nota subjetiva.
- **Costo histórico del producto**: `producto_variante.costo` es un único valor vigente, sin
  tabla de histórico. Los márgenes por período (OTD-GER-03 y OTD-GER-10) usan el costo actual;
  un histórico de costos es deuda conocida y se pospone hasta que la fase ETL lo exija. La
  evolución del precio de COMPRA por producto sí es medible hoy (OTD-COM-12), porque cada línea
  de orden conserva el precio pactado a esa fecha: son dos preguntas distintas.
- **Multimoneda y tipo de cambio**: `tipo_cambio` con 0 filas, una sola moneda; el negocio opera
  en una moneda y no hay pregunta táctica que lo necesite.
- **Informes tácticos en PDF**: decisión del profesor — los informes tácticos se consultan por
  pantalla con filtros; los PDF quedan solo para documentos operativos (facturas, guías,
  comprobantes).

### 12.1 Puntos ciegos de la auditoría NO incorporados (2026-07-21, revisados 2026-07-26)

De los 20 objetivos propuestos por el cruce del cuestionario, 11 entraron al catálogo (los
OTD- nuevos de esa versión) y estos 9 quedaron fuera. **La revisión del 2026-07-26 encuentra que
el motivo de seis de ellos —«universo de datos insuficiente»— ya no aplica**: hoy hay 72
clientes, 21 ciudades, 25 provincias, 19 meses de operación y 2.872 envíos. Se dejan fuera de
esta versión para no alterar el alcance del entregable, pero quedan como candidatos naturales de
la próxima ronda, no como descartes:

- **Patrón de ventas y despachos por día de la semana y hora (PC-02)**, **clientes nuevos vs
  recurrentes por mes (PC-03)**, **volumen y duración de las transferencias entre bodegas por
  mes (PC-10)**, **carga de despacho por bodega (PC-11)**, **pedidos y envíos por ciudad y zona
  de destino (PC-13)** y **clientes que reclaman una y otra vez (PC-19)**: el esquema ya los
  soporta y **el volumen dejó de ser el impedimento**. Candidatos de la próxima ampliación del
  catálogo; los seis serían COMPUESTOS.
- **Venta perdida por falta de existencias (PC-05)** y **reclamos reabiertos tras darse por
  resueltos (PC-16)**: ambos exigen instrumentación nueva (registrar el intento de compra que
  chocó con estante vacío; llevar historial de estados del ticket, hoy la reapertura
  sobreescribe sin rastro) para responder preguntas de prioridad ÚTIL/DESEABLE; se posponen
  hasta que su valor justifique el cambio.
- **Desenlace de los pedidos no entregados (PC-14)**: su flujo operativo (reembolso y destino
  de la mercancía devuelta al almacén, a cargo de soporte) es deuda ya declarada en
  `DEUDA_TECNICA.md` (Fase 6); el objetivo entra al catálogo cuando ese flujo exista — no tiene
  sentido reclamar un informe sobre un proceso que aún no se construye.
- **Análisis de canasta — productos que se compran juntos (EX-1)**: la señal de afinidad es
  terreno del módulo de recomendaciones (analítica en ClickHouse), fuera del reporting táctico
  departamental sobre la base transaccional.
- **Incrementalidad real de los cupones (EX-3)** — ¿el cupón trae venta nueva o regala margen a
  quien igual iba a comprar?: responderlo exige experimentación con grupo de control, más allá
  del reporting táctico; el insumo honesto disponible es OTD-GER-05 (uso y costo real de cada
  cupón).
- **Embudo de visitas a compra (EX-7)**: se calcula sobre los eventos de navegación de la tienda,
  que viven en la analítica de ClickHouse — el dashboard analítico del sistema ya incluye un
  embudo de conversión; no es reporting sobre la base transaccional.
