# Catálogo de objetivos tácticos por departamento — RetailMind

Versión ampliada tras la auditoría de cobertura (2026-07-21). Reemplaza por completo la tabla de
25 objetivos del documento TA11 vigente (`docs/build_t11.py`). Toda cita de tabla.columna de este
catálogo fue verificada contra la base real `retailmind` (PostgreSQL, 109 tablas en el esquema
`public`, conteo confirmado vía MCP el 2026-07-21) apoyándose en
`docs/tactico/INVENTARIO_DATOS_TACTICO.md` y en consultas de catálogo propias de esta tarea.
Esta versión incorpora 11 de los 20 puntos ciegos detectados por la auditoría del cuestionario
de negocio (`CUESTIONARIO_NEGOCIO.md` + `CRUCE_CUESTIONARIO_CATALOGO.md`); los 9 restantes
quedan documentados con su motivo en la sección Decisiones de alcance.

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
4. Solo entonces los informes compuestos se llevan a la base columnar (ClickHouse) mediante el
   pipeline ETL orquestado con Airflow.

El catálogo se sometió además a una **auditoría de cobertura**: se levantó a ciegas un
cuestionario de 78 preguntas de negocio (una por cada decisión que un jefe de área necesita
tomar, sin mirar el catálogo ni el esquema), se cruzó pregunta por pregunta contra el catálogo y
contra la base real, y de los 20 puntos ciegos detectados se incorporaron los 11 de mayor valor
de dirección; los 9 restantes quedaron registrados con su motivo en Decisiones de alcance.

Este documento aplica ese proceso: define **68 objetivos** (57 de la primera ronda + 11
incorporados por la auditoría), los contrasta uno a uno contra la base real, marca **45** como
respondibles hoy, **6** que exigen un cambio en el sistema (sección Cierre de brechas, con las
tres capas) y **17** que solo esperan volumen de datos reales. Sincronización 2026-07-22: las
brechas de sistema de OTD-COM-05, OTD-INV-08, OTD-LOG-10 y OTD-LOG-11 fueron cerradas en las
tres capas, verificadas y compiladas (sección 11.0); OTD-LOG-10 pasó a FACTIBLE HOY y las otras
tres a REQUIERE VOLUMEN DE DATOS, porque el mecanismo ya escribe el dato pero el histórico aún
no se puebla. Los informes tácticos se consultan
**por pantalla**, con filtros y registros visibles; no se entregan como PDF descargable (los
documentos operativos — facturas, guías, comprobantes — siguen siendo PDF).

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
del histórico. Aplicada esta regla, los 68 objetivos se verificaron uno a uno; en particular
los siete simples que contienen agregación (OTD-VEN-02, OTD-VEN-15, OTD-COM-11, OTD-INV-07,
OTD-SOP-04, OTD-SOP-05, OTD-GER-01) agregan sobre la foto presente sin comparar períodos y
permanecen SIMPLES. **Ningún objetivo de la primera ronda cambió de clasificación.** De los 11
incorporados por la auditoría, 9 son COMPUESTOS (recorren histórico, comparan períodos o cruzan
fuentes con agregación) y 2 son SIMPLES por veredicto de la auditoría de clasificación
(2026-07-21): OTD-VEN-15 acumula la venta del período EN CURSO contra una meta fija — suma
sobre el presente, sin comparar períodos — y OTD-COM-11 compara lo pedido contra lo recibido
sobre las líneas de orden vigentes — consulta de estado actual con conteo por proveedor.

La segregación financiera del sistema se respeta en la columna de destinatarios: **Bodega y
Despacho nunca ven montos de dinero**, solo cantidades y estados.

---

## 3. VENTAS (OTD-VEN)

El jefe de ventas dirige la cartera de pedidos de los tres canales (mostrador, teléfono y tienda
en línea), el desempeño de su equipo de vendedores, el comportamiento de compra de los clientes
y la voz del cliente sobre los productos (reseñas y preguntas). Es el área con más objetivos
porque concentra el ingreso del negocio.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado | Factibilidad | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|
| OTD-VEN-01 | Ver toda la cartera de pedidos y en qué paso del proceso está cada uno hoy (confirmado, pagado, facturado, en preparación, despachado, entregado). | X | — | BDR | `pedido.estado_pedido_id` → `estado_pedido.codigo/nombre` (11 estados), `pedido.numero/fecha_pedido/canal/total` (34 pedidos) | FACTIBLE HOY | Tabla con filtros por estado y canal — Vendedor, Gerente, Administrador |
| OTD-VEN-02 | Controlar cuántos pedidos registra cada vendedor y por cuánto dinero, para evaluar el cumplimiento individual. | X | — | BDR | `pedido.vendedor_id` (FK a `usuario.nombre/apellido`; poblada en 17/34 — NULL solo en pedidos en línea, por diseño), `pedido.total` | FACTIBLE HOY | Tabla de vendedores ordenada por monto — Gerente, Administrador (el Vendedor ve solo lo propio) |
| OTD-VEN-03 | Conocer los 10 productos que más se venden — el «producto estrella» — en el período elegido. | — | X | BDColumnar | `pedido_detalle.producto_variante_id/cantidad/precio_unitario` (43 líneas) + `producto_variante.producto_id` → `producto.nombre` | FACTIBLE HOY | Barras de los 10 primeros, con filtro de período — Gerente, Vendedor, Compras, Analista, Administrador |
| OTD-VEN-04 | Conocer los 10 productos que no se venden o llevan más tiempo sin venderse — el «producto hueso» — para liquidarlos o dejar de comprarlos. | — | X | BDColumnar | `producto_variante` (1221 variantes) cruzada contra `pedido_detalle.producto_variante_id`; última salida por venta en `movimiento_inventario.fecha_creacion` + `tipo_movimiento.codigo='salida_venta'` (43 salidas) | FACTIBLE HOY | Tabla de rezagados con días sin venta — Gerente, Compras, Analista, Administrador |
| OTD-VEN-05 | Saber cuánto compra cada cliente: total gastado, número de pedidos y fecha de la última compra — mirar el negocio desde el cliente, no solo desde la venta. | — | X | BDColumnar | `pedido.cliente_id/total/fecha_pedido` (34 pedidos, cliente_id poblado en 34/34) + `cliente.nombre/apellido/email` | REQUIERE VOLUMEN — solo 2 clientes reales (cuentas demo) | Tabla de clientes ordenada por monto acumulado — Gerente, Vendedor, Analista, Administrador |
| OTD-VEN-06 | Ver cómo evolucionan las ventas mes a mes y por categoría de producto. | — | X | BDColumnar | `pedido.fecha_pedido` + `pedido_detalle.cantidad/precio_unitario/monto_descuento` + `producto_categoria.categoria_id` → `categoria.nombre` (11 categorías) | FACTIBLE HOY | Líneas por mes con desglose por categoría — Gerente, Analista, Administrador |
| OTD-VEN-07 | Conocer el valor promedio de cada pedido por período y por canal de venta (mostrador, teléfono, tienda en línea). | — | X | BDColumnar | `pedido.total/fecha_pedido/canal` (34 pedidos: 17 web, 9 teléfono, 8 tienda) | FACTIBLE HOY | Tarjetas y línea temporal por canal — Gerente, Analista, Administrador |
| OTD-VEN-08 | Detectar los carritos de compra que los clientes dejaron a medias sin llegar a pagar. | X | — | BDR | `carrito.estado/fecha_actualizacion` + `carrito_item.producto_variante_id/cantidad` (19 carritos) | REQUIERE VOLUMEN — los 19 carritos están en estado 'convertido'; hoy no hay ni un carrito activo o abandonado que mostrar | Tabla de carritos inactivos con antigüedad — Gerente, Vendedor, Administrador |
| OTD-VEN-09 | Saber con qué formas de pago cobran las ventas (efectivo, tarjeta, transferencia) y cómo cambia esa mezcla en el tiempo. | — | X | BDColumnar | `pago.metodo_pago_id` → `metodo_pago.nombre/tipo`, `pago.monto/fecha_pago` (26 pagos) | FACTIBLE HOY | Participación por forma de pago, por mes — Gerente, Analista, Administrador |
| OTD-VEN-10 | Atender a tiempo la voz del cliente: reseñas en espera de aprobación y preguntas sobre productos sin responder. | X | — | BDR | `resena.estado` (4 reseñas: 2 'pendiente', 2 'aprobada'), `pregunta_producto.estado` (1) y existencia de `respuesta_pregunta` (1) | FACTIBLE HOY | Cola de moderación con antigüedad — Administrador, Gerente (moderadores del sistema) |
| OTD-VEN-11 | Conocer la calificación que los clientes dan a cada producto y cómo evoluciona. | — | X | BDColumnar | `resena.calificacion/producto_id/fecha_creacion/compra_verificada` | REQUIERE VOLUMEN — solo 4 reseñas sobre 1214 productos | Ranking de productos por calificación — Gerente, Vendedor, Analista |
| OTD-VEN-12 | Saber cuántos cobros en línea fallan y por qué motivo, para no perder ventas en el paso del pago. | — | X | BDColumnar | Columnas existen (`pago.estado`, `transaccion_pago.tipo/respuesta_pasarela`) pero registran un único valor: 'completado' en 26/26 y 'captura' en 26/26 — los intentos fallidos nunca se guardan | REQUIERE CAMBIO EN EL SISTEMA | Tabla de intentos fallidos por motivo y período — Gerente, Administrador |
| OTD-VEN-13 | Saber cuánto vende cada canal — mostrador, teléfono y tienda en línea — y qué parte de la venta total pone cada uno, por período. | — | X | BDColumnar | `pedido.canal/total/fecha_pedido` — poblados en los 34 pedidos: 17 web ($12 865,11), 9 teléfono ($6 130,19), 8 tienda ($4 817,40); verificado vía MCP 2026-07-21 | FACTIBLE HOY | Participación de cada canal en la venta, por mes — Gerente, Vendedor, Analista, Administrador |
| OTD-VEN-14 | Saber cuánto dinero devuelven los clientes al mes y qué porcentaje de la venta representa, para frenar a tiempo si se dispara. | — | X | BDColumnar | `devolucion.monto_total/fecha_creacion` (7/7 pobladas por el trigger de totales, todas > 0) contra `pedido.total/fecha_pedido` (34 pedidos) | FACTIBLE HOY | Valor devuelto y porcentaje sobre la venta, mensual — Gerente, Administrador, Analista (Bodega y Despacho NO: es dinero) |
| OTD-VEN-15 | Seguir la venta acumulada del período contra la meta que se fijó, para reaccionar a media quincena y no enterarse al cierre del mes. | X | — | BDR | La venta real ya existe (`pedido.total/fecha_pedido`), pero NO existe ninguna tabla de metas o presupuesto en el esquema (verificado vía catálogo del esquema 2026-07-21: ninguna tabla de metas/presupuesto/objetivo) | REQUIERE CAMBIO EN EL SISTEMA | Avance de venta contra la meta del período — Gerente, Vendedor, Administrador |

## 4. COMPRAS (OTD-COM)

El jefe de compras dirige la relación con proveedores: órdenes y sus aprobaciones, recepciones de
mercancía, deuda y pagos, y la devolución de mercancía defectuosa al proveedor. Necesita saber a
quién comprarle, cuánto debe, si paga a tiempo y si le entregan a tiempo.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado | Factibilidad | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|
| OTD-COM-01 | Ver las órdenes de compra que esperan aprobación y el estado de cada orden en curso. | X | — | BDR | `orden_compra.estado/numero/fecha_emision/total` + `proveedor.razon_social` (17 órdenes: confirmada 1, enviada 2, recibida 12, recibida_parcial 2; re-verificado vía MCP 2026-07-22) | FACTIBLE HOY | Tabla con filtros por estado y proveedor — Compras, Gerente, Administrador |
| OTD-COM-02 | Controlar cuánto le debemos a cada proveedor y qué cuotas están por vencer o ya vencieron. | X | — | BDR | `cuenta_por_pagar.saldo_pendiente/fecha_vencimiento/estado/proveedor_id` (14 cuentas) + `proveedor.razon_social` | FACTIBLE HOY | Tabla de vencimientos con semáforo de fechas — Compras, Gerente, Administrador |
| OTD-COM-03 | Saber si pagamos a los proveedores a tiempo: pagos hechos antes o después de la fecha de vencimiento, por proveedor y por mes. | — | X | BDColumnar | `pago_proveedor.fecha_pago/monto/cuenta_por_pagar_id` (10 pagos) vs `cuenta_por_pagar.fecha_vencimiento` — ambas 100 % pobladas | FACTIBLE HOY | Puntualidad de pago por proveedor y mes — Compras, Gerente, Administrador, Analista |
| OTD-COM-04 | Conocer cuánto gastamos en compras por proveedor y por mes. | — | X | BDColumnar | `factura_compra.total/fecha_emision/proveedor_id` (14 facturas; universo actual de 2 proveedores) | FACTIBLE HOY | Barras por proveedor con evolución mensual — Compras, Gerente, Administrador, Analista |
| OTD-COM-05 | Saber si cada proveedor cumple el compromiso que pactó: comparar la fecha de entrega que prometió al confirmar la orden contra el día en que la mercancía llegó de verdad, para detectar a quién incumple su palabra. | — | X | BDColumnar | Brecha de sistema CERRADA (2026-07-21/22): el formulario de orden de compra ya captura y persiste `orden_compra.fecha_entrega_esperada`, validada contra la fecha de emisión. Poblada en 1 de 17 órdenes (la primera creada con el flujo nuevo, aún sin recepción): todavía no existe ni un par promesa/llegada que comparar contra `recepcion_mercancia.fecha_recepcion` (verificado vía MCP 2026-07-22) | REQUIERE VOLUMEN — el flujo escribe el dato; faltan órdenes con fecha prometida que lleguen a recibirse | Cumplimiento de plazo por proveedor — Compras, Gerente |
| OTD-COM-06 | Medir el tiempo real observado del ciclo de compra: cuántos días tarda en la práctica la mercancía en llegar desde que emitimos la orden, exista o no una fecha prometida de por medio. | — | X | BDColumnar | `orden_compra.fecha_emision` + `recepcion_mercancia.fecha_recepcion/orden_compra_id` (14 recepciones reales) | FACTIBLE HOY | Días de ciclo de compra por proveedor y período — Compras, Gerente, Analista |
| OTD-COM-07 | Conocer cuánta mercancía llega en mal estado y se rechaza en la puerta al recibirla, por proveedor y por motivo. | — | X | BDColumnar | `recepcion_detalle.cantidad_rechazada/motivo_rechazo/cantidad_recibida` | REQUIERE VOLUMEN — solo 1 de 22 líneas de recepción tiene rechazo registrado | Porcentaje rechazado por proveedor — Compras, Gerente; Bodega lo ve en cantidades, sin montos |
| OTD-COM-08 | Ver los artículos defectuosos pendientes de devolver al proveedor y en qué paso va cada devolución. | X | — | BDR | `item_defectuoso.estado/origen/cantidad/proveedor_id` (3 ítems), `devolucion_proveedor.numero/estado` (2 devoluciones) | FACTIBLE HOY | Tablero del pool de defectuosos y devoluciones en curso — Compras, Gerente; Bodega en cantidades, sin montos |
| OTD-COM-09 | Saber cuánto recuperamos de los proveedores por mercancía defectuosa: crédito a favor o reposición de producto. | — | X | BDColumnar | `devolucion_proveedor.tipo_resolucion/monto_credito/fecha_resolucion` + `item_defectuoso.costo_unitario` | REQUIERE VOLUMEN — solo 2 devoluciones a proveedor registradas (módulo del 2026-07-18) | Monto recuperado por proveedor y período — Compras, Gerente, Administrador |
| OTD-COM-10 | Comparar a qué proveedor conviene comprarle cada producto: costo, plazo de entrega y proveedor preferido. | X | — | BDR | La tabla `producto_proveedor` (con `costo`, `tiempo_entrega_dias`, `cantidad_minima`, `es_preferido`) existe pero tiene 0 filas, pese a 22 líneas de compra reales | REQUIERE CAMBIO EN EL SISTEMA | Ficha comparativa de proveedores por producto — Compras, Gerente |
| OTD-COM-11 | Detectar qué proveedores entregan incompleto: comparar lo que se pidió contra lo que de verdad llegó, línea por línea y por proveedor. | X | — | BDR | `orden_compra_detalle.cantidad/cantidad_recibida` (7 de 25 líneas con recepción menor a la pedida — una pertenece a la orden nueva aún en tránsito —, re-verificado vía MCP 2026-07-22) + `orden_compra.proveedor_id/fecha_emision` → `proveedor.razon_social` | FACTIBLE HOY | Líneas incompletas y porcentaje de cumplimiento por proveedor — Compras, Gerente; Bodega en cantidades, sin montos |
| OTD-COM-12 | Saber si está subiendo el costo de lo que compramos: cómo cambia el precio que cobra el proveedor por cada producto entre una compra y la siguiente. | — | X | BDColumnar | `orden_compra_detalle.precio_unitario/producto_variante_id` (11 precios distintos en las 25 líneas, re-verificado vía MCP 2026-07-22) + `orden_compra.fecha_emision/proveedor_id`; cada línea de compra conserva su precio a esa fecha | FACTIBLE HOY | Evolución del costo de compra por producto y proveedor — Compras, Gerente, Analista |

## 5. INVENTARIO / BODEGA (OTD-INV)

El jefe de bodega dirige las existencias: qué hay, dónde está, qué falta, qué sobra, qué se
mueve y qué se pierde — y cómo evoluciona en el tiempo el capital almacenado y la mercancía
perdida. Por la segregación financiera del sistema, su tablero trabaja en cantidades; los
objetivos con dinero de este bloque se muestran a Gerencia y Administración.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado | Factibilidad | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|
| OTD-INV-01 | Detectar los productos que están por debajo de su tope mínimo y hay que reponer. | X | — | BDR | `inventario.stock_actual` vs `inventario.stock_minimo`. Precisión (re-verificado vía MCP 2026-07-22): el default 0 significa «sin mínimo definido» — 16 de 1227 filas tienen mínimo real (=2) y 1 está bajo mínimo hoy, así que el informe devuelve filas pero su cobertura crece conforme Bodega capture mínimos; el ciclo de captura completo existe desde el cierre de OTD-INV-08 (PUT `/api/inventario/niveles` + campo en el formulario de inventario, único flujo que escribe la columna) | FACTIBLE HOY | Lista de reposición con faltante por bodega — Bodega, Compras, Gerente, Administrador |
| OTD-INV-02 | Consultar las existencias actuales de cada producto en cada bodega, incluyendo lo apartado para pedidos. | X | — | BDR | `inventario.stock_actual/stock_reservado/bodega_id` + `bodega.nombre` (2 bodegas) | FACTIBLE HOY | Tabla de existencias con buscador — Bodega, Compras, Vendedor, Gerente, Administrador |
| OTD-INV-03 | Revisar la historia completa de entradas y salidas de cualquier producto: qué se movió, cuándo, cuánto y por qué razón. | X | — | BDR | `movimiento_inventario.cantidad/stock_anterior/stock_nuevo/fecha_creacion/referencia_tipo` + `tipo_movimiento.codigo/nombre` (98 movimientos, 9 tipos en uso) | FACTIBLE HOY | Kardex por producto con filtro de tipo y fecha — Bodega, Gerente, Administrador |
| OTD-INV-04 | Saber qué categorías de producto rotan más y cuáles se quedan paradas en bodega, por período. | — | X | BDColumnar | `movimiento_inventario.cantidad/fecha_creacion/tipo_movimiento_id` + `producto_categoria.categoria_id` → `categoria.nombre` (1214 asignaciones, 11 categorías) | FACTIBLE HOY | Rotación por categoría y período — Gerente, Analista, Administrador; Bodega en cantidades |
| OTD-INV-05 | Controlar la mercancía perdida o sobrante detectada en los ajustes de inventario y sus motivos. | X | — | BDR | `ajuste_inventario.tipo/motivo/estado/fecha_aplicacion` (3 ajustes) + movimientos `entrada_ajuste`/`salida_ajuste` del kardex | FACTIBLE HOY | Lista de ajustes con motivo y cantidades — Bodega, Gerente, Administrador |
| OTD-INV-06 | Seguir las transferencias de mercancía entre bodegas: cuáles van en camino y cuáles ya se recibieron. | X | — | BDR | `transferencia_bodega.estado/fecha_envio/fecha_recepcion/bodega_origen_id/bodega_destino_id` (10 transferencias, todas 'recibida') | FACTIBLE HOY | Tabla de transferencias por estado — Bodega, Gerente, Administrador |
| OTD-INV-07 | Saber cuánto dinero hay parado en mercancía almacenada, por categoría y por bodega. | X | — | BDR | `inventario.stock_actual` × `producto_variante.costo` (costo poblado en las 1221 variantes) + `producto_categoria`/`categoria.nombre` | FACTIBLE HOY | Valor del inventario por categoría/bodega — Gerente, Administrador, Analista (Bodega NO: es dinero) |
| OTD-INV-08 | Detectar productos con demasiada existencia — por encima del tope máximo deseado — para no enterrar dinero en mercancía de más. | X | — | BDR | Brecha de sistema CERRADA (2026-07-21/22): el endpoint PUT `/api/inventario/niveles` escribe `stock_minimo` y `stock_maximo`, con UI en la pantalla de ajustes de inventario. Pero `stock_maximo` sigue 100 % NULL (0/1227, verificado vía MCP 2026-07-22): nadie ha capturado topes todavía y el informe devolvería 0 filas | REQUIERE VOLUMEN — el mecanismo de captura existe; falta cargar los topes (dato maestro, ver auditoría de densidad) | Lista de sobre-stock por bodega — Bodega, Compras, Gerente |
| OTD-INV-09 | Ver cómo evoluciona mes a mes el dinero inmovilizado en la mercancía almacenada, para saber si la bodega se está llenando o vaciando de capital. | — | X | BDColumnar | Reconstrucción del stock al cierre de cada mes (verificada vía MCP): `inventario.stock_actual` (1227 filas) menos los movimientos posteriores del kardex — `movimiento_inventario.cantidad/fecha_creacion` (98, con `stock_anterior/stock_nuevo` poblados 98/98 como respaldo) y el signo de `tipo_movimiento.factor` (±1 en los 9 tipos) — valorizado con `producto_variante.costo` (poblado en 1221 variantes). Usa el costo vigente: no existe histórico de costos (ver Decisiones de alcance) | FACTIBLE HOY | Línea mensual del valor almacenado por bodega y categoría — Gerente, Administrador, Analista (Bodega NO: es dinero) |
| OTD-INV-10 | Conocer las mermas (mercancía perdida) y los sobrantes acumulados por período y por motivo, para atacar las causas de la pérdida. | — | X | BDColumnar | `ajuste_inventario.tipo/motivo/fecha_aplicacion` (3 ajustes: 2 negativos, 1 positivo; motivo y fecha poblados 3/3) + kardex `movimiento_inventario.cantidad` con `tipo_movimiento.codigo` 'salida_ajuste' (2) / 'entrada_ajuste' (1), enlazados por `movimiento_inventario.referencia_tipo='ajuste_inventario'` (3) | REQUIERE VOLUMEN — solo 3 ajustes y 3 movimientos de ajuste registrados | Acumulado por motivo y mes — Bodega en cantidades; valorizado solo Gerente, Administrador |

## 6. LOGÍSTICA / DESPACHO (OTD-LOG)

El jefe de despacho dirige la última milla y el camino de regreso: la cola de despacho, los
envíos con cada transportista, los problemas de entrega y todo el ciclo de devoluciones de
clientes (solicitud → revisión → retorno → inspección → reembolso → cierre). Por la segregación
financiera, Despacho ve estados, fechas y cantidades; el dinero (reembolsos, costo de envío) va a
Gerencia, Administración y Soporte.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado | Factibilidad | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|
| OTD-LOG-01 | Ver la cola de pedidos listos y en espera de ser despachados. | X | — | BDR | `pedido.estado_pedido_id` → `estado_pedido.codigo='preparado'`, `pedido.numero/fecha_pedido` | FACTIBLE HOY | Cola de trabajo de despacho — Despacho, Gerente, Administrador |
| OTD-LOG-02 | Seguir los envíos: cuáles van en camino, cuáles ya se entregaron, cuáles volvieron, y con qué transportista y número de guía viaja cada uno. | X | — | BDR | `envio.estado/numero_guia/fecha_despacho` (24 envíos: 11 en tránsito, 11 entregados, 2 devueltos) + `transportista.nombre` (2 transportistas) | FACTIBLE HOY | Tablero de envíos por estado y transportista — Despacho, Gerente, Administrador |
| OTD-LOG-03 | Saber si cumplimos la fecha de entrega prometida al cliente: de los envíos ya entregados, cuántos llegaron a más tardar el día prometido y cuántos llegaron tarde, por transportista. | — | X | BDColumnar | `envio.fecha_entrega_estimada` vs `envio.fecha_entrega_real` (11 entregas reales) + `envio.transportista_id` | FACTIBLE HOY | Cumplimiento de fecha prometida por transportista — Despacho, Gerente, Analista |
| OTD-LOG-04 | Medir la duración real del tránsito — los días que pasan desde que el paquete sale de bodega hasta la puerta del cliente — para comparar transportistas entre sí, sin importar qué fecha se haya prometido. | — | X | BDColumnar | `envio.fecha_despacho` vs `envio.fecha_entrega_real` (ambas pobladas en los 11 envíos entregados) | FACTIBLE HOY | Días de tránsito promedio por transportista y período — Despacho, Gerente, Analista |
| OTD-LOG-05 | Controlar los problemas de entrega — cliente ausente, dirección equivocada, rechazo en la puerta, daño en el camino — cuántos ocurren, cuántos intentos toman y cómo terminan. | — | X | BDColumnar | `novedad_envio.tipo/estado/accion/intento_numero/fecha_registro/fecha_resolucion` | REQUIERE VOLUMEN — solo 6 novedades registradas (módulo del 2026-07-18) | Problemas de entrega por tipo y desenlace — Despacho, Gerente, Soporte |
| OTD-LOG-06 | Ver las devoluciones de clientes en curso y en qué paso va cada una (solicitada, en revisión, aprobada, en camino de vuelta, recibida, inspeccionada, reembolsada, cerrada). | X | — | BDR | `devolucion.numero/estado/guia_retorno/fecha_creacion` (7 devoluciones: 6 cerradas, 1 inspeccionada) | FACTIBLE HOY | Tablero del ciclo de devolución por estado — Despacho, Soporte, Gerente; Bodega ve su tramo de inspección, sin montos |
| OTD-LOG-07 | Medir cuántos días tarda una devolución de cliente desde que se solicita hasta que se cierra. | — | X | BDColumnar | `devolucion.fecha_creacion` + `historial_estado_devolucion.estado/fecha_creacion` (18 registros de historial) | REQUIERE VOLUMEN — la mayoría de devoluciones son casos antiguos migrados directo a 'cerrada', sin tiempos intermedios reales | Días de ciclo de devolución por período — Gerente, Soporte, Analista |
| OTD-LOG-08 | Saber por qué devuelven los clientes y qué pasa con esa mercancía: vuelve a venderse, resulta defectuosa o se rechaza sin reembolso. | — | X | BDColumnar | `devolucion.motivo_devolucion_id` → `motivo_devolucion.nombre` (4 motivos) + `devolucion_detalle.resultado_inspeccion/cantidad` | REQUIERE VOLUMEN — solo 9 líneas de devolución inspeccionables | Motivos de devolución y destino de la mercancía — Gerente, Soporte, Analista; Bodega en cantidades |
| OTD-LOG-09 | Saber, de cada 100 envíos, cuántos terminan en devolución, mes a mes. | — | X | BDColumnar | `envio.fecha_despacho` (24 envíos) vs `devolucion.pedido_id/fecha_creacion` (7 devoluciones) | FACTIBLE HOY | Proporción de devoluciones sobre envíos, mensual — Gerente, Analista; Despacho en conteos |
| OTD-LOG-10 | Controlar el dinero devuelto a los clientes: cuánto se reembolsó, por qué vía y por qué motivo. | — | X | BDColumnar | Brecha de sistema CERRADA (2026-07-21/22): `DevolucionService.reembolsar` ya inserta la fila en `reembolso` (monto, vía, fecha, devolución), verificado end-to-end con rol GERENTE — la tabla tiene 1 fila real (devolución 9 reembolsada; verificado vía MCP 2026-07-22). Histórico mínimo pero el informe ya devuelve resultados | FACTIBLE HOY | Reembolsos pagados por período y vía — Gerente, Administrador, Soporte (Despacho NO: es dinero) |
| OTD-LOG-11 | Saber cuánto nos cuesta llevar cada envío, por zona y transportista, para revisar las tarifas que cobramos al cliente. | — | X | BDColumnar | Brecha de sistema CERRADA (2026-07-21/22): `VentasService.costoEnvioPorTarifa` calcula el costo desde `tarifa_envio` al despachar y persiste `envio.peso_total_kg` cuando hay peso disponible. Pero ningún despacho ha corrido desde el arreglo: `envio.costo` sigue en 0.00 en los 24 envíos históricos (verificado vía MCP 2026-07-22). Además el componente por peso está limitado por maestros vacíos: `tarifa_envio.costo_por_kg` = 0.00 en 3/3 y `producto_variante.peso_kg` 100 % NULL | REQUIERE VOLUMEN — el cálculo está implementado; faltan despachos nuevos que lo ejecuten (y cargar pesos/costo por kg para el componente por peso) | Costo de envío real vs cobrado, por zona/transportista — Gerente, Administrador (Despacho NO: es dinero) |
| OTD-LOG-12 | Medir cuánto tarda un pedido en cada etapa del camino — del pago a la preparación, de la preparación al despacho y del despacho a la entrega — para encontrar el cuello de botella real y no suponerlo. | — | X | BDColumnar | `historial_estado_pedido.pedido_id/estado_pedido_id/fecha_creacion` (177 registros que cubren los 34 pedidos, verificado vía MCP 2026-07-21) + `estado_pedido.codigo` — cada transición quedó fechada, lo que permite medir cada tramo | FACTIBLE HOY | Días u horas promedio por etapa del ciclo, por período — Despacho (tiempos y estados, sin dinero), Gerente, Analista |

## 7. SOPORTE (OTD-SOP)

El jefe de soporte dirige la atención de reclamos y consultas: la bandeja de tickets, la
urgencia, la carga de su equipo y los tiempos de respuesta. Es un área más pequeña que Ventas o
Compras y su catálogo lo refleja.

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado | Factibilidad | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|
| OTD-SOP-01 | Ver los reclamos y consultas abiertos: cuántos hay, de qué tipo, qué tan urgentes son y quién atiende cada uno. | X | — | BDR | `ticket_soporte.numero/estado/prioridad/categoria_ticket_id/asignado_usuario_id` (12 tickets: 4 abiertos, 3 en proceso, 2 esperando cliente, 1 resuelto, 2 cerrados) | FACTIBLE HOY | Bandeja con filtros por estado, urgencia y categoría — Soporte, Gerente, Administrador |
| OTD-SOP-02 | Saber cuántos reclamos atendemos dentro del tiempo prometido al cliente según su urgencia. | — | X | BDColumnar | No existe columna de fecha límite en `ticket_soporte`: el plazo prometido (2 h/4 h/24 h/72 h según urgencia) vive solo en código del backend, y `fecha_cierre` está poblada en apenas 2 de 12 tickets | REQUIERE CAMBIO EN EL SISTEMA | Cumplimiento del tiempo prometido por período — Soporte, Gerente |
| OTD-SOP-03 | Medir cuántas horas o días tardamos en resolver cada tipo de problema. | — | X | BDColumnar | `ticket_soporte.fecha_creacion` vs `ticket_soporte.fecha_cierre` + `categoria_ticket.nombre` | REQUIERE VOLUMEN — solo 2 de 12 tickets tienen fecha de cierre (los demás siguen abiertos) | Tiempo de resolución por categoría — Soporte, Gerente, Analista |
| OTD-SOP-04 | Saber qué tipos de problema generan más reclamos, para atacar la causa de fondo. | X | — | BDR | `ticket_soporte.categoria_ticket_id` → `categoria_ticket.nombre` (Devolución 5, Envíos 3, Facturación 2, Reclamo 1; 4 de 8 categorías sin tickets) | FACTIBLE HOY | Distribución de tickets por categoría — Soporte, Gerente |
| OTD-SOP-05 | Repartir bien el trabajo del equipo: reclamos asignados y resueltos por cada persona de soporte. | X | — | BDR | `ticket_soporte.asignado_usuario_id` → `usuario.nombre/apellido`, `ticket_soporte.estado` | FACTIBLE HOY | Carga y cierre por agente — Soporte, Gerente, Administrador |
| OTD-SOP-06 | Medir cuánto tardamos en dar la primera respuesta al cliente que reclama: desde que abre su caso hasta que alguien del equipo le contesta por primera vez. | — | X | BDColumnar | `ticket_soporte.fecha_creacion` vs el primer `mensaje_ticket.fecha_creacion` cuyo autor es del equipo (`mensaje_ticket.usuario_id` poblado) y visible al cliente (`es_interno = false`) — 8 de 12 tickets ya tienen respuesta visible del equipo (verificado vía MCP 2026-07-21) | FACTIBLE HOY | Horas hasta la primera respuesta, por período y urgencia — Soporte, Gerente |
| OTD-SOP-07 | Medir cuánto tarda cada persona del equipo de soporte en resolver los casos que se le asignan. | — | X | BDColumnar | `ticket_soporte.asignado_usuario_id` → `usuario.nombre/apellido` + `ticket_soporte.fecha_creacion/fecha_cierre` | REQUIERE VOLUMEN — solo 2 de 12 tickets tienen fecha de cierre (la misma limitante de OTD-SOP-03, ahora abierta por agente) | Tiempo de resolución por agente y período — Soporte, Gerente |
| OTD-SOP-08 | Saber qué productos generan más reclamos y más devoluciones, para pedir a Compras que revise el producto o a Ventas que corrija su descripción. | — | X | BDColumnar | Mitad ya posible: devoluciones por producto vía `devolucion_detalle.pedido_detalle_id` → `pedido_detalle.producto_variante_id` → `producto.nombre` (las 9 líneas de devolución enlazan a su producto, verificado vía MCP 2026-07-21). Mitad que exige cambio: los reclamos NO se pueden ligar a un producto — `ticket_soporte` solo referencia `cliente_id` y `pedido_id`, sin columna de producto (columnas verificadas 2026-07-21) | REQUIERE CAMBIO EN EL SISTEMA | Ranking de productos por reclamos y devoluciones — Soporte, Gerente, Compras |

## 8. GERENCIA / DIRECCIÓN (OTD-GER) — incluye Marketing

La gerencia dirige el negocio completo: la foto consolidada del día, el equilibrio entre lo que
entra por ventas y lo que sale hacia proveedores, la ganancia por línea de producto, las acciones
de marketing (cupones, promociones, campañas) y el control interno (quién hizo qué en el sistema
y quién intenta entrar a él).

| ID | Objetivo táctico | ¿SIMPLE? | ¿COMPUESTO? | Fuente | Soporte de datos verificado | Factibilidad | Dashboard y rol destinatario |
|---|---|---|---|---|---|---|---|
| OTD-GER-01 | Tener la foto del día del negocio: pedidos de hoy, dinero cobrado hoy y pendientes que necesitan decisión. | X | — | BDR | `pedido.fecha_pedido/total/estado_pedido_id` + `pago.fecha_pago/monto` (26 pagos) + `estado_pedido.nombre` | FACTIBLE HOY | Tarjetas de resumen del día — Gerente, Administrador |
| OTD-GER-02 | Comparar mes a mes el dinero que entra por ventas contra el dinero que sale hacia proveedores — la balanza comercial interna del negocio. | — | X | BDColumnar | Facturado: `factura_venta.total/fecha_emision` (30) vs `factura_compra.total/fecha_emision` (14). En caja: `pago.monto/fecha_pago` (26) vs `pago_proveedor.monto/fecha_pago` (10) | FACTIBLE HOY | Barras enfrentadas entradas vs salidas, por mes — Gerente, Administrador, Analista |
| OTD-GER-03 | Saber qué categorías de producto dejan más ganancia (venta menos costo), por período. | — | X | BDColumnar | `pedido_detalle.precio_unitario/cantidad/monto_descuento` + `producto_variante.costo` (poblado en 1221 variantes) + `producto_categoria`/`categoria.nombre` | FACTIBLE HOY | Ganancia por categoría y período — Gerente, Analista, Administrador |
| OTD-GER-04 | Ver los cupones de descuento activos, cuántos usos les quedan y cuándo vencen. | X | — | BDR | `cupon.codigo/activo/usos_actuales/usos_maximos/fecha_fin` (6 cupones) | FACTIBLE HOY | Tabla de cupones con vigencia y usos restantes — Gerente, Administrador |
| OTD-GER-05 | Saber qué cupones usaron efectivamente los clientes y cuánto descuento le costaron al negocio, por período. | — | X | BDColumnar | `uso_cupon.cupon_id/monto_descontado/fecha_creacion` | REQUIERE VOLUMEN — solo 3 usos de cupón registrados | Descuento otorgado por cupón y período — Gerente, Analista, Administrador |
| OTD-GER-06 | Ver las acciones de marketing vigentes: promociones (y los productos que abarcan), campañas y anuncios de la tienda. | X | — | BDR | `promocion.fecha_inicio/fecha_fin/activo` (1) + `promocion_producto.producto_id` (2) + `campana.estado/fecha_inicio/fecha_fin` (1) + `banner.activo/fecha_inicio` (1) | FACTIBLE HOY | Panel de vigencias de marketing — Gerente, Administrador |
| OTD-GER-07 | Saber si las promociones hacen vender más, comparando las ventas del producto antes y durante la promoción. | — | X | BDColumnar | `pedido_detalle.monto_descuento` (>0 = línea con promoción aplicada) + `promocion.fecha_inicio/fecha_fin` + `promocion_producto` | REQUIERE VOLUMEN — solo 1 promoción con 2 productos asociados | Ventas antes/durante promoción — Gerente, Analista |
| OTD-GER-08 | Revisar quién hizo qué en el sistema: aprobaciones, despachos, registros y moderaciones, con autor, fecha y detalle del cambio. | X | — | BDR | `log_auditoria.usuario_id/tabla/accion/registro_id/datos_anteriores/datos_nuevos/fecha_creacion` (39 registros reales) | FACTIBLE HOY | Registro de acciones filtrable por usuario, tabla y fecha — Administrador, Gerente |
| OTD-GER-09 | Saber quién intentó entrar al sistema y falló, desde dónde y por qué. | X | — | BDR | La tabla `log_acceso` (con `exitoso/motivo_fallo/ip_origen/email_intentado`) existe completa pero tiene 0 filas: el inicio de sesión nunca la escribe | REQUIERE CAMBIO EN EL SISTEMA | Intentos de acceso fallidos recientes — Administrador, Gerente |
| OTD-GER-10 | Conocer la ganancia real de cada producto — la diferencia entre lo que costó y lo que se cobró — además de la vista por categoría, para corregir precios producto por producto. | — | X | BDColumnar | `producto_variante.precio/costo` (ambos poblados en las 1221 variantes, verificado vía MCP 2026-07-21) + `pedido_detalle.producto_variante_id/cantidad/precio_unitario/monto_descuento` (43 líneas). Usa el costo vigente: no existe histórico de costos (misma salvedad documentada de OTD-GER-03 e INV-09) | FACTIBLE HOY | Margen por producto con buscador y filtro de período — Gerente, Analista, Administrador |
| OTD-GER-11 | Controlar cuánto descuento se entrega en total cada mes — sumando promociones y cupones — y sobre qué productos recae, para que el descuento no se coma el margen sin que nadie lo mida. | — | X | BDColumnar | `pedido_detalle.monto_descuento` (promociones por línea; hoy solo 2 líneas > 0), `pedido.monto_descuento` (cupones; 3 pedidos > 0) y `uso_cupon.monto_descontado` (3 usos, $52,91 en total), con el prorrateo por producto ya resuelto en `factura_venta_detalle.monto_descuento` (verificado vía MCP 2026-07-21) | REQUIERE VOLUMEN — solo 2 líneas con promoción y 3 usos de cupón registrados | Descuento total entregado por mes y por producto — Gerente, Analista, Administrador |

---

## 9. RESUMEN CUANTITATIVO

**Total: 68 objetivos tácticos** (57 de la primera ronda + 11 incorporados por la auditoría del
cuestionario de negocio).

| Clasificación | Cantidad | Porcentaje |
|---|---|---|
| Informes SIMPLES (BDR PostgreSQL) | 28 | 41 % |
| Informes COMPUESTOS (BDColumnar ClickHouse) | 40 | 59 % |

| Departamento | Objetivos | Simples | Compuestos |
|---|---|---|---|
| Ventas | 15 | 5 | 10 |
| Compras | 12 | 5 | 7 |
| Inventario / Bodega | 10 | 7 | 3 |
| Logística / Despacho | 12 | 3 | 9 |
| Soporte | 8 | 3 | 5 |
| Gerencia / Dirección (incl. Marketing) | 11 | 5 | 6 |

Verificación aritmética: 15+12+10+12+8+11 = 68; simples 5+5+7+3+3+5 = 28; compuestos
10+7+3+9+5+6 = 40; 28+40 = 68.

| Estado de factibilidad | Cantidad | Objetivos |
|---|---|---|
| FACTIBLE HOY | 45 | Todos los no listados abajo (incluye LOG-10, brecha cerrada el 2026-07-21/22) |
| REQUIERE CAMBIO EN EL SISTEMA | 6 | VEN-12, VEN-15, COM-10, SOP-02, SOP-08, GER-09 |
| REQUIERE VOLUMEN DE DATOS | 17 | VEN-05, VEN-08, VEN-11, COM-05, COM-07, COM-09, INV-08, INV-10, LOG-05, LOG-07, LOG-08, LOG-11, SOP-03, SOP-07, GER-05, GER-07, GER-11 |

Verificación aritmética: 45 + 6 + 17 = 68. (Antes de la sincronización del 2026-07-22 era
44 + 10 + 14: las brechas de COM-05, INV-08, LOG-10 y LOG-11 se cerraron en las tres capas;
LOG-10 ya devuelve resultados y las otras tres esperan que la operación genere el histórico.)

La asimetría es deliberada: Ventas y Logística (que carga todo el ciclo de devoluciones de
cliente) pesan más que Soporte; Inventario combina el control del presente (existencias, kardex,
ajustes) con la evolución del capital almacenado y de las mermas en el tiempo (OTD-INV-09 y
OTD-INV-10), mientras que Ventas, Logística y Gerencia concentran compuestos porque sus
decisiones son de evolución en el tiempo. De los 11 objetivos incorporados por la auditoría,
9 son compuestos, lo que confirma el patrón: lo que a los jefes les faltaba no era tanto ver el
presente (eso el catálogo ya lo cubría casi por completo) sino medir tiempos de ciclo, comparar
períodos y consolidar dinero; los otros 2 (OTD-VEN-15 y OTD-COM-11) son simples porque agregan
sobre la foto del período en curso sin recorrer histórico.

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
| Ciclo de compra (órdenes, aprobación) | OTD-COM-01, COM-05, COM-06, COM-11, COM-12 |
| Recepciones y rechazo en puerta | OTD-COM-07 |
| Cuentas por pagar y puntualidad de pago a proveedor | OTD-COM-02, COM-03 |
| Gasto de compras / facturas de compra | OTD-COM-04, GER-02 |
| Devolución a proveedor e ítems defectuosos | OTD-COM-08, COM-09 |
| Catálogo proveedor–producto | OTD-COM-10 |
| Inventario, stock mínimo y kardex | OTD-INV-01, INV-02, INV-03, INV-04, INV-07, INV-08, INV-09 |
| Ajustes de inventario (mermas/sobrantes) | OTD-INV-05, INV-10 |
| Transferencias entre bodegas | OTD-INV-06 |
| Despacho y envíos (transportistas, guías) | OTD-LOG-01, LOG-02, LOG-03, LOG-04 |
| Novedades de envío (incidencias de entrega) | OTD-LOG-05 |
| RMA / devoluciones de cliente (ciclo de 8 estados) | OTD-LOG-06, LOG-07, LOG-08, LOG-09, VEN-14, SOP-08 |
| Reembolsos a clientes | OTD-LOG-10 |
| Zonas y tarifas de envío | OTD-LOG-11 |
| Soporte (tickets, categorías, agentes, tiempos) | OTD-SOP-01 a SOP-08 |
| Marketing: cupones y sus usos | OTD-GER-04, GER-05, GER-11 |
| Marketing: promociones, campañas y banners | OTD-GER-06, GER-07 |
| Facturación de venta | OTD-GER-02 (lado de ingresos) |
| Auditoría central (`log_auditoria`) | OTD-GER-08 |
| Seguridad de acceso al sistema | OTD-GER-09 |

**27 módulos cubiertos.** Los seis módulos que la auditoría de sincronización marcó como
huérfanos en el TA11 vigente quedan todos reclamados: RMA de cliente (LOG-06/07/08/09),
devolución a proveedor e ítems defectuosos (COM-08/09), novedades de envío (LOG-05), reseñas y
preguntas (VEN-10/11), auditoría central (GER-08) y puntualidad de pago a proveedor (COM-03).

## 11. CIERRE DE BRECHAS

### 11.0 Brechas CERRADAS y verificadas (2026-07-21/22)

Cuatro objetivos que estaban en REQUIERE CAMBIO EN EL SISTEMA fueron resueltos en las tres
capas (BD, backend y formulario), verificados contra el sistema real y compilados. Se dejan
aquí como constancia y salen de la tabla 11.1. Distinción importante: **el flujo está cerrado,
pero el dato histórico no existe todavía** — el arreglo no re-escribe las filas viejas, así que
tres de los cuatro pasan a REQUIERE VOLUMEN DE DATOS hasta que la operación los pueble.

| ID | Cómo se cerró | Estado del dato hoy (MCP 2026-07-22) | Nueva factibilidad |
|---|---|---|---|
| OTD-COM-05 | El formulario de orden de compra captura y persiste `orden_compra.fecha_entrega_esperada`, validada contra la fecha de emisión | 1 de 17 órdenes con fecha prometida (la primera del flujo nuevo, aún sin recepción); 0 pares promesa/llegada comparables | REQUIERE VOLUMEN |
| OTD-INV-08 | PUT `/api/inventario/niveles` escribe `stock_minimo` y `stock_maximo`, con UI en la pantalla de ajustes de inventario | `stock_maximo` sigue 0/1227 (100 % NULL): falta la carga deliberada de topes (dato maestro) | REQUIERE VOLUMEN |
| OTD-LOG-10 | `DevolucionService.reembolsar` inserta la fila en `reembolso`; verificado end-to-end con rol GERENTE | 1 fila real en `reembolso` (devolución 9 reembolsada); el informe ya devuelve resultados | FACTIBLE HOY |
| OTD-LOG-11 | `VentasService.costoEnvioPorTarifa` calcula el costo desde `tarifa_envio` al despachar y persiste `envio.peso_total_kg` cuando hay peso | `envio.costo` sigue 0.00 en 24/24: ningún despacho ha corrido desde el arreglo; `tarifa_envio.costo_por_kg` = 0.00 (3/3) y `producto_variante.peso_kg` 100 % NULL limitan el componente por peso | REQUIERE VOLUMEN |

### 11.1 Objetivos en REQUIERE CAMBIO EN EL SISTEMA (las tres capas)

El profesor lo enfatizó: no basta con la base de datos. Si falta un dato, hay que agregarlo
también al flujo que lo escribe y al formulario donde se captura.

| ID | Qué falta | Cambio en BD | Cambio en backend | Cambio en formulario/UI | Esfuerzo |
|---|---|---|---|---|---|
| OTD-VEN-12 | Los intentos de cobro fallidos nunca se registran (`pago.estado` = 'completado' en 26/26) | Ninguno — `pago.estado` y `transaccion_pago.tipo/respuesta_pasarela` ya existen | `CarritoService.pagarCheckoutOnline` / simulación de pasarela: al rechazar un pago (tarjeta inválida, fondos), registrar la transacción con tipo de rechazo y su motivo antes de devolver el error | El formulario de pago ya captura todo; solo mostrar el motivo del rechazo al cliente | MEDIO |
| OTD-VEN-15 | No existe ninguna tabla de metas de venta: la venta real está, pero no hay meta contra la cual compararla | Tabla NUEVA de metas por período y departamento (la única tabla nueva que pide el catálogo), con sus grants y política de horario según el patrón vigente | CRUD de metas (crear, editar, consultar el avance) en el backend | Formulario de captura de metas para Gerencia (período, departamento, monto) | MEDIO |
| OTD-COM-10 | Catálogo proveedor–producto: `producto_proveedor` con 0 filas | Ninguno — la tabla existe con `costo`, `tiempo_entrega_dias`, `es_preferido` | CRUD de producto-proveedor en `ComprasService` (o servicio propio); opcional: alimentarla automáticamente con el costo de cada recepción | Sección «Productos que ofrece» en la ficha del proveedor, con costo, plazo y marcador de preferido | MEDIO |
| OTD-SOP-02 | El tiempo prometido del ticket no es consultable: sin columna de fecha límite; el plazo por urgencia vive en código | `ALTER TABLE ticket_soporte ADD COLUMN fecha_limite timestamptz` (+ grants/RLS según patrón del script 37) | `SoporteService`: persistir la fecha límite al crear el ticket (hoy la calcula al vuelo) y recalcularla si Soporte/Admin cambia la prioridad | Ninguno de captura — la bandeja ya muestra «vence en/VENCIDO»; pasaría a leer la columna | MEDIO |
| OTD-SOP-08 | El reclamo no se puede ligar a un producto: `ticket_soporte` solo referencia cliente y pedido. La mitad de devoluciones por producto YA es factible hoy (`devolucion_detalle` → `pedido_detalle` → producto) y no espera al cambio | Columna opcional de producto en `ticket_soporte` (+ grants/RLS según patrón del script 37) | `SoporteService`: escribir el producto al crear el ticket cuando el cliente lo indique | Selector opcional de producto en el formulario de creación del ticket | BAJO |
| OTD-GER-09 | Intentos de entrada: `log_acceso` con 0 filas pese a columnas completas | Ninguno — la tabla existe (`exitoso`, `motivo_fallo`, `ip_origen`, `email_intentado`) | Flujo de login (Spring Security/JWT): insertar cada intento, exitoso o fallido, con correo intentado, IP y motivo | Ninguno — el formulario de inicio de sesión ya existe | BAJO |

### 11.2 Objetivos en REQUIERE VOLUMEN DE DATOS

En estos casos el esquema está completo y el flujo ya escribe el dato: no hay cambio de BD,
backend ni formulario. Lo que falta es operación real (el entorno actual es demo). El «cambio»
es operar el sistema o cargar datos reales antes de que el informe agregado sea representativo.

| ID | Qué falta (conteo real que lo limita) | Cambio en BD / backend / formulario | Esfuerzo |
|---|---|---|---|
| OTD-VEN-05 | Solo 2 clientes (cuentas demo) | Ninguno — registrar clientes reales | BAJO |
| OTD-VEN-08 | 19 carritos, todos 'convertido'; ni un abandono registrado | Ninguno — el estado existe; con tráfico real habrá carritos activos/abandonados que listar por inactividad | BAJO |
| OTD-VEN-11 | 4 reseñas sobre 1214 productos | Ninguno — operación real de la tienda | BAJO |
| OTD-COM-05 | Brecha de sistema cerrada 2026-07-21/22; `fecha_entrega_esperada` poblada en 1 de 17 órdenes, 0 pares promesa/llegada comparables | Ninguno — el formulario ya captura la fecha; faltan órdenes nuevas que lleguen a recepción | BAJO |
| OTD-COM-07 | 1 sola línea de recepción con rechazo (de 22) | Ninguno — el flujo de rechazo en puerta ya escribe cantidad y motivo | BAJO |
| OTD-INV-08 | Brecha de sistema cerrada 2026-07-21/22; `stock_maximo` sigue 0/1227 | Ninguno de sistema — cargar los topes máximos desde la pantalla de inventario (dato maestro: la operación no los llena sola) | BAJO |
| OTD-COM-09 | 2 devoluciones a proveedor (módulo nació el 2026-07-18) | Ninguno — flujo completo ya operativo | BAJO |
| OTD-INV-10 | Solo 3 ajustes de inventario y 3 movimientos de ajuste en el kardex | Ninguno — el flujo de ajuste y su rastro en el kardex ya escriben todo lo necesario | BAJO |
| OTD-LOG-05 | 6 novedades de envío (módulo del 2026-07-18) | Ninguno — flujo completo ya operativo | BAJO |
| OTD-LOG-07 | Historial de devolución con mayoría de casos legacy migrados directo a 'cerrada', sin tiempos intermedios | Ninguno — las devoluciones nuevas ya dejan historial paso a paso | BAJO |
| OTD-LOG-08 | 9 líneas de devolución con inspección | Ninguno | BAJO |
| OTD-LOG-11 | Brecha de sistema cerrada 2026-07-21/22; `envio.costo` sigue 0.00 en 24/24 porque ningún despacho ha corrido desde el arreglo | Ninguno de sistema — despachar pedidos nuevos; para el componente por peso, cargar `producto_variante.peso_kg` y `tarifa_envio.costo_por_kg` (datos maestros) | BAJO |
| OTD-SOP-03 | Fecha de cierre poblada en solo 2 de 12 tickets | Ninguno de sistema — es operación: cerrar los tickets resueltos | BAJO |
| OTD-SOP-07 | La misma limitante de SOP-03 (2 de 12 tickets con fecha de cierre), ahora abierta por agente | Ninguno — el ticket ya guarda agente asignado, creación y cierre | BAJO |
| OTD-GER-05 | 3 usos de cupón | Ninguno — el registro de uso es automático desde el script 40 | BAJO |
| OTD-GER-07 | 1 promoción con 2 productos | Ninguno — crear promociones reales | BAJO |
| OTD-GER-11 | Solo 2 líneas de pedido con descuento de promoción y 3 usos de cupón ($52,91 en total) | Ninguno — promociones y cupones ya escriben su descuento automáticamente (script 40), incluido el prorrateo por producto en la factura | BAJO |

## 12. DECISIONES DE ALCANCE (qué quedó fuera y por qué)

- **Trazabilidad por lote y vencimiento (FEFO)**: pospuesta deliberadamente en `ROADMAP.md`
  (2026-07-18); `lote` tiene 0 filas y las FK están listas para esa fase. Ningún objetivo la
  reclama a propósito.
- **Ubicación física dentro de bodega (pasillo/estante)**: `ubicacion_bodega` está vacía. Es una
  necesidad operativa de picking, no de dirección táctica; se excluye del catálogo.
- **Segmentación de clientes (grupos, edad, género)**: el esquema completo existe
  (`grupo_cliente`, `segmento_cliente`, `cliente.genero/fecha_nacimiento`) pero con 0 filas y
  solo 2 clientes demo; cualquier objetivo de segmentación sería doblemente infactible (dato no
  capturado + universo insuficiente). Cuando haya clientes reales, se reevaluará junto con
  OTD-VEN-05.
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
- **Parámetros de configuración de la tienda**: `configuracion_tienda` con 0 filas (los valores
  se resuelven en código); no sostiene ningún informe.
- **Informes tácticos en PDF**: decisión del profesor — los informes tácticos se consultan por
  pantalla con filtros; los PDF quedan solo para documentos operativos (facturas, guías,
  comprobantes).

### 12.1 Puntos ciegos de la auditoría NO incorporados (2026-07-21)

De los 20 objetivos propuestos por el cruce del cuestionario, 11 entraron al catálogo (los
OTD- nuevos de esta versión) y estos 9 quedaron fuera, cada uno con su motivo:

- **Patrón de ventas y despachos por día de la semana y hora (PC-02)**, **clientes nuevos vs
  recurrentes por mes (PC-03)**, **volumen y duración de las transferencias entre bodegas por
  mes (PC-10)**, **carga de despacho por bodega (PC-11)**, **pedidos y envíos por ciudad y zona
  de destino (PC-13)** y **clientes que reclaman una y otra vez (PC-19)**: el esquema ya los
  soporta (las fechas guardan la hora, cada pedido tiene su cliente, las transferencias
  registran envío y recepción, cada envío conoce su bodega, la cadena dirección→zona ya existe
  y cada ticket tiene su cliente), pero el universo actual de datos — 2 bodegas, 2 ciudades,
  2 clientes y unas 2 semanas de operación — los haría informes sin poder discriminante: toda
  respuesta caería en las mismas 2 categorías. Se reevaluarán cuando haya volumen real.
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
  departamental sobre la base transaccional; además, con 43 líneas de pedido el análisis no
  sería representativo.
- **Incrementalidad real de los cupones (EX-3)** — ¿el cupón trae venta nueva o regala margen a
  quien igual iba a comprar?: responderlo exige experimentación con grupo de control (comparar
  contra clientes que no recibieron el cupón), más allá del reporting táctico; el insumo honesto
  disponible es OTD-GER-05 (uso y costo real de cada cupón).
- **Embudo de visitas a compra (EX-7)**: se calcula sobre los eventos de navegación de la tienda,
  que viven en la analítica de ClickHouse — el dashboard analítico del sistema ya incluye un
  embudo de conversión; no es reporting sobre la base transaccional.
