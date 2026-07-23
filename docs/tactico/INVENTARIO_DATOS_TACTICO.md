# Inventario de datos operativos — nivel táctico (RetailMind / PostgreSQL)

Diagnóstico READ-ONLY del esquema `public` de la base `retailmind` (PostgreSQL, localhost:5432),
levantado vía MCP `mcp__retailmind__query` el 2026-07-21. Alcance: SOLO PostgreSQL (base
transaccional operativa). ClickHouse/`analytics/` quedan fuera de este inventario por instrucción
expresa. Todas las cifras de este documento provienen de `SELECT COUNT(*)`, `SELECT MIN/MAX(...)`,
`information_schema` y `SELECT ... LIMIT 5` reales ejecutados contra la base — ninguna es estimada.

## 1. Resumen ejecutivo

El esquema `public` tiene **109 tablas** reales (la cifra de "~103" de `CLAUDE.md` es una
aproximación desactualizada por el crecimiento de scripts 40-45; ver Notas de verificación).
De esas 109: **83 tienen al menos una fila** y **26 están completamente vacías (0 filas)**. De las
83 con datos, una porción relevante tiene menos de 5 filas (módulos muy recientes: `novedad_envio`,
`devolucion_proveedor*`, `item_defectuoso`, o funciones sin adopción real: `comparacion`,
`resena_util`). El inventario cubre 9 áreas de negocio (los 7 departamentos del documento táctico
T11 — Ventas, Compras, Inventario/Bodega, Logística/Despacho, Soporte, Gerencia/Dirección con
Marketing — más Clientes/CRM y Seguridad/Sistema/Referencia como bloques transversales necesarios
para explicar huecos). Se identificaron **7 huecos de severidad ALTA**. Veredicto de salud del
dato: el esquema está **completo y bien diseñado** para casi todas las preguntas tácticas del
documento T11 (triggers, RLS, trazabilidad de autor ya implementados), pero el **volumen real de
datos es de entorno de desarrollo/demo** (2 clientes, 2 proveedores, 2 transportistas, 2 bodegas):
cualquier informe agregado hoy sería una prueba de concepto del pipeline, no un reflejo de
operación real. El hueco de mayor impacto no es de esquema sino de **columnas existentes que la
aplicación nunca puebla** (`orden_compra.fecha_entrega_esperada`, `envio.costo`,
`inventario.stock_maximo`, `producto_variante.precio_comparacion`, todas 100% NULL).

## 2. Tabla global (109 tablas)

| Tabla | Área | Filas | Rango de fechas | ¿Alimenta informes? |
|---|---|---|---|---|
| carrito | Ventas | 19 | 2026-07-11 a 2026-07-18 | PARCIAL |
| carrito_item | Ventas | 23 | 2026-07-11 a 2026-07-18 | PARCIAL |
| comparacion | Ventas | 0 | sin datos | NO |
| comparacion_item | Ventas | 0 | sin datos | NO |
| estado_pedido | Ventas | 11 | catálogo, sin fecha relevante | SÍ (dimensión) |
| factura_venta | Ventas | 30 | 2026-07-04 a 2026-07-18 | SÍ |
| factura_venta_detalle | Ventas | 37 | 2026-07-04 a 2026-07-18 | SÍ |
| historial_estado_pedido | Ventas | 177 | 2026-07-04 a 2026-07-20 | SÍ |
| metodo_pago | Ventas | 3 | catálogo | SÍ (dimensión) |
| nota_pedido | Ventas | 3 | 2026-07-10 a 2026-07-11 | NO (muestra insuficiente) |
| pago | Ventas | 26 | 2026-07-12 a 2026-07-18 | SÍ |
| pasarela_pago | Ventas | 0 | sin datos | NO |
| pedido | Ventas | 34 | 2026-07-04 a 2026-07-18 | SÍ |
| pedido_detalle | Ventas | 43 | 2026-07-04 a 2026-07-18 | SÍ |
| pregunta_producto | Ventas | 1 | única (2026-07-10) | NO |
| reporte_resena | Ventas | 1 | única (2026-07-10) | NO |
| resena | Ventas | 4 | 2026-07-10 a 2026-07-18 | PARCIAL |
| resena_util | Ventas | 1 | única (2026-07-10) | NO |
| respuesta_pregunta | Ventas | 1 | única (2026-07-10) | NO |
| reserva_stock | Ventas/Inventario | 0 | sin datos | NO |
| transaccion_pago | Ventas | 26 | 2026-07-12 a 2026-07-18 | PARCIAL |
| wishlist | Ventas | 2 | 2026-07-11 a 2026-07-12 | NO |
| wishlist_item | Ventas | 7 | 2026-07-11 a 2026-07-17 | PARCIAL |
| cliente | Clientes/CRM | 2 | 2026-07-04 (única) | PARCIAL |
| cliente_segmento | Clientes/CRM | 0 | sin datos | NO |
| direccion | Clientes/CRM | 4 | 2026-07-04 a 2026-07-16 | PARCIAL |
| segmento_cliente | Clientes/CRM | 0 | sin datos | NO |
| grupo_cliente | Clientes/CRM | 0 | sin datos | NO |
| cupon | Marketing | 6 | fecha_inicio 2026-01-01 a 2026-08-01 | PARCIAL |
| uso_cupon | Marketing | 3 | 2026-07-17 a 2026-07-18 | NO |
| promocion | Marketing | 1 | 2026-07-10 a 2026-08-06 | NO |
| promocion_producto | Marketing | 2 | sin fecha propia | NO |
| campana | Marketing | 1 | 2026-07-15 a 2026-09-10 | NO |
| banner | Marketing | 1 | 2026-07-15, fin NULL | NO |
| newsletter_suscriptor | Marketing | 1 | única (2026-07-09) | NO |
| configuracion_tienda | Gerencia | 0 | sin datos | NO |
| contacto_proveedor | Compras | 0 | sin datos | NO |
| cuenta_por_pagar | Compras | 14 | fecha_vencimiento 2026-07-19 a 2026-08-11 | SÍ |
| devolucion_proveedor | Compras | 2 | 2026-07-18 | PARCIAL |
| devolucion_proveedor_detalle | Compras | 3 | 2026-07-18 | PARCIAL |
| factura_compra | Compras | 14 | 2026-07-04 a 2026-07-17 | SÍ |
| factura_compra_detalle | Compras | 22 | heredada de factura_compra | SÍ |
| historial_devolucion_proveedor | Compras | 8 | 2026-07-18 | PARCIAL |
| item_defectuoso | Compras | 3 | 2026-07-18 | PARCIAL |
| orden_compra | Compras | 16 | 2026-07-04 a 2026-07-18 | SÍ |
| orden_compra_detalle | Compras | 24 | heredada de orden_compra | SÍ |
| pago_proveedor | Compras | 10 | 2026-07-04 a 2026-07-12 | SÍ |
| producto_proveedor | Compras | 0 | sin datos | NO |
| proveedor | Compras | 2 | 2026-07-04 (única) | PARCIAL |
| recepcion_detalle | Compras | 22 | 2026-07-04 a 2026-07-18 | SÍ |
| recepcion_mercancia | Compras | 14 | 2026-07-04 a 2026-07-18 | SÍ |
| ajuste_inventario | Inventario/Bodega | 3 | 2026-07-08 a 2026-07-11 | PARCIAL |
| atributo | Inventario/Bodega | 2 | sin relevancia temporal | SÍ (dimensión) |
| bodega | Inventario/Bodega | 2 | sin relevancia temporal | SÍ (dimensión) |
| categoria | Inventario/Bodega | 11 | sin relevancia temporal | SÍ (dimensión) |
| etiqueta | Inventario/Bodega | 0 | sin datos | NO |
| impuesto | Inventario/Bodega | 2 | sin relevancia temporal | SÍ (dimensión) |
| inventario | Inventario/Bodega | 1227 | snapshot (fecha_actualizacion) | SÍ |
| lote | Inventario/Bodega | 0 | sin datos (deuda deliberada, ver Notas) | NO |
| marca | Inventario/Bodega | 35 | sin relevancia temporal | SÍ (dimensión) |
| movimiento_inventario | Inventario/Bodega | 98 | 2026-07-04 a 2026-07-18 | SÍ |
| producto | Inventario/Bodega | 1214 | 2026-07-04 a 2026-07-11 | SÍ |
| producto_categoria | Inventario/Bodega | 1214 | sin fecha propia | SÍ (dimensión) |
| producto_especificacion | Inventario/Bodega | 0 | sin datos | NO |
| producto_etiqueta | Inventario/Bodega | 0 | sin datos | NO |
| producto_imagen | Inventario/Bodega | 0 | sin datos | NO |
| producto_impuesto | Inventario/Bodega | 0 | sin datos | NO |
| producto_relacionado | Inventario/Bodega | 0 | sin datos | NO |
| producto_variante | Inventario/Bodega | 1221 | 2026-07-04 a 2026-07-11 | SÍ |
| tipo_movimiento | Inventario/Bodega | 9 | catálogo, sin relevancia temporal | SÍ (dimensión) |
| transferencia_bodega | Inventario/Bodega | 10 | 2026-07-04 a 2026-07-11 | SÍ |
| ubicacion_bodega | Inventario/Bodega | 0 | sin datos | NO |
| valor_atributo | Inventario/Bodega | 11 | sin relevancia temporal | SÍ (dimensión) |
| variante_valor_atributo | Inventario/Bodega | 30 | sin relevancia temporal | SÍ (dimensión) |
| devolucion | Logística/Despacho | 7 | 2026-07-04 a 2026-07-16 | PARCIAL |
| devolucion_detalle | Logística/Despacho | 9 | 2026-07-04 a 2026-07-16 | PARCIAL |
| envio | Logística/Despacho | 24 | despacho 2026-07-04 a 07-20; entrega real 07-12 a 07-20 | SÍ |
| envio_detalle | Logística/Despacho | 28 | 2026-07-04 a 2026-07-20 | PARCIAL |
| historial_estado_devolucion | Logística/Despacho | 18 | 2026-07-16 a 2026-07-18 | PARCIAL |
| metodo_envio | Logística/Despacho | 2 | catálogo | SÍ (dimensión) |
| motivo_devolucion | Logística/Despacho | 4 | catálogo | SÍ (dimensión) |
| novedad_envio | Logística/Despacho | 6 | 2026-07-18 a 2026-07-20 | NO (recién nacida) |
| reembolso | Logística/Despacho | 0 | sin datos | NO |
| seguimiento_envio | Logística/Despacho | 47 | 2026-07-04 a 2026-07-20 | SÍ |
| tarifa_envio | Logística/Despacho | 3 | catálogo | SÍ (dimensión) |
| transportista | Logística/Despacho | 2 | catálogo | PARCIAL |
| zona_envio | Logística/Despacho | 3 | catálogo | SÍ (dimensión) |
| categoria_ticket | Soporte | 8 | catálogo | SÍ (dimensión) |
| correlativo_ticket | Soporte | 1 | técnica, no analítica | NO |
| faq | Soporte | 3 | 2026-07-09 (mismo día) | NO |
| mensaje_ticket | Soporte | 21 | 2026-07-09 a 2026-07-18 | PARCIAL |
| ticket_soporte | Soporte | 12 | creación 07-09 a 07-18; cierre 07-09 a 07-15 (solo 2 filas) | PARCIAL |
| grupo_horario | Seguridad | 56 | configuración semilla, sin relevancia temporal | SÍ (dimensión/regla) |
| log_acceso | Seguridad | 0 | sin datos | NO |
| log_auditoria | Seguridad/Auditoría | 39 | 2026-07-09 a 2026-07-20 | SÍ |
| permiso | Seguridad | 0 | sin datos | NO |
| refresh_token | Seguridad | 0 | sin datos | NO |
| rol | Seguridad | 9 | sin relevancia temporal | SÍ (dimensión) |
| rol_permiso | Seguridad | 0 | sin datos | NO |
| token_recuperacion | Seguridad | 0 | sin datos | NO |
| usuario | Seguridad | 10 | fecha_creacion 2026-07-05 | SÍ |
| usuario_rol | Seguridad | 10 | sin relevancia temporal | SÍ (dimensión) |
| ciudad | Referencia/Geografía | 2 | sin relevancia temporal | SÍ (dimensión) |
| idioma | Referencia/Geografía | 2 | sin relevancia temporal | SÍ (dimensión) |
| moneda | Referencia/Geografía | 1 | sin relevancia temporal | SÍ (dimensión) |
| pais | Referencia/Geografía | 1 | sin relevancia temporal | SÍ (dimensión) |
| provincia | Referencia/Geografía | 25 | sin relevancia temporal | SÍ (dimensión) |
| tipo_cambio | Referencia/Geografía | 0 | sin datos | NO |
| traduccion | Referencia/Geografía | 0 | sin datos | NO |

## 3. Áreas de negocio

### 3.1 Ventas (pedidos, pagos, facturación, carrito/wishlist, reseñas)

**Tablas**: pedido, pedido_detalle, estado_pedido, historial_estado_pedido, factura_venta,
factura_venta_detalle, pago, transaccion_pago, metodo_pago, pasarela_pago, nota_pedido, carrito,
carrito_item, wishlist, wishlist_item, comparacion, comparacion_item, reserva_stock, resena,
resena_util, reporte_resena, pregunta_producto, respuesta_pregunta.

**Dimensiones disponibles**: `pedido.canal` (web/tienda/telefono), `estado_pedido.codigo`,
`pedido.vendedor_id` (FK directa a usuario, NULL si canal='web'), `pedido.transportista_id`,
`pedido.metodo_envio_id`, `pedido.fecha_pedido`, `pedido_detalle.producto_variante_id`,
`pedido_detalle.sku`, `pago.metodo_pago_id`→`metodo_pago.tipo`, `pago.estado`,
`transaccion_pago.tipo`, `resena.calificacion`, `resena.estado`, `carrito.estado`.

**Métricas disponibles**: `pedido.subtotal/monto_descuento/monto_impuesto/costo_envio/total`
(mantenidas por trigger, válidas como métrica de lectura), `pedido_detalle.cantidad/precio_unitario`,
`pago.monto`, `transaccion_pago.monto`, `factura_venta.total`, `resena.calificacion`.

**Preguntas que SÍ puede responder hoy**:
- ¿Cuántos pedidos entran por canal web vs tienda vs teléfono? (`pedido.canal`: 17 web / 9 telefono / 8 tienda de 34).
- ¿En qué estado está la cartera de pedidos ahora mismo? (join `pedido`→`estado_pedido`: entregado 8, facturado 8, devuelto 7, confirmado 5, despachado 3, no_entregado 2, pagado 1).
- ¿Cuánto tiempo tarda un pedido en pasar de un estado a otro? (`historial_estado_pedido.fecha_creacion` por `pedido_id`, 177 registros).
- ¿Qué vendedor generó más pedidos internos? (`pedido.vendedor_id`, aplica a 17/34 pedidos internos).
- ¿Qué método de pago se usa más? (join `pago`→`metodo_pago`, 26 pagos).
- ¿Cuántos carritos hay activos/abandonados? (`carrito.estado`, 19 filas, muestra piloto).

**Preguntas que NO puede responder hoy**:
- ¿Cuántos intentos de pago fallan y por qué? — `pago.estado` tiene un solo valor ('completado') en 26/26 filas y `transaccion_pago.tipo` un solo valor ('captura') en 26/26; no hay pagos rechazados/reintentados registrados.
- ¿Qué producto se compara más? — `comparacion`/`comparacion_item` están vacías (0 filas): la función nunca se usó.
- ¿Cuál es el ticket promedio por cliente en el tiempo (LTV)? — calculable técnicamente pero con solo 2 clientes reales no es representativo (falta volumen, no columna).

### 3.2 Clientes / CRM

**Tablas**: cliente, direccion, cliente_segmento, segmento_cliente, grupo_cliente.

**Dimensiones disponibles**: `cliente.acepta_marketing`, `cliente.activo`, `direccion.tipo`,
`direccion.ciudad_id`. Diseñadas pero sin datos: `cliente.genero`, `cliente.fecha_nacimiento`,
`cliente.grupo_cliente_id`.

**Métricas disponibles**: ninguna cuantitativa propia (es catálogo de identidad); las métricas de
cliente viven en `pedido`/`pago` (área Ventas).

**Preguntas que SÍ puede responder hoy**: ¿cuántas direcciones tiene registrado cada cliente y de
qué tipo? (`direccion.tipo`, 4 filas sobre 2 clientes).

**Preguntas que NO puede responder hoy**: ¿cómo segmento a mis clientes por edad/género/grupo? —
`cliente.fecha_nacimiento` y `cliente.genero` están NULL en el 100% de las 2 filas;
`cliente.grupo_cliente_id` NULL en el 100%; y `grupo_cliente`, `segmento_cliente`,
`cliente_segmento` están vacías (0 filas). El esquema de segmentación existe completo pero sin un
solo dato cargado. Además el universo real es de solo 2 clientes (las cuentas demo documentadas en
`CLAUDE.md`), insuficiente para cualquier segmentación estadística aunque se poblaran esas columnas.

### 3.3 Marketing (agrupado bajo "Gerencia/Dirección" en el documento táctico T11)

**Tablas**: cupon, uso_cupon, promocion, promocion_producto, campana, banner,
newsletter_suscriptor.

**Dimensiones disponibles**: `cupon.tipo_descuento` (porcentaje/monto_fijo), `cupon.activo`,
`promocion.tipo_descuento`, `campana.estado`, `campana.canal`.

**Métricas disponibles**: `cupon.usos_actuales` vs `usos_maximos`, `uso_cupon.monto_descontado`.

**Preguntas que SÍ puede responder hoy**: ¿qué cupones se usaron y cuánto descuento generaron? —
`uso_cupon` (3 usos reales con `monto_descontado`) y `cupon.usos_actuales/usos_maximos`.

**Preguntas que NO puede responder hoy**: ¿qué campaña/banner generó más tráfico o conversión? —
`campana`, `banner`, `promocion` tienen 1 fila cada una (setup de demo) y **ninguna de las tres
tiene columna de clics/impresiones/conversión** en el esquema de PostgreSQL; ese dato, si existe,
vive en ClickHouse/analytics, fuera de este inventario.

### 3.4 Compras (órdenes, cuentas por pagar, devolución a proveedor)

**Tablas**: orden_compra, orden_compra_detalle, proveedor, contacto_proveedor,
producto_proveedor, recepcion_mercancia, recepcion_detalle, factura_compra,
factura_compra_detalle, cuenta_por_pagar, pago_proveedor, devolucion_proveedor,
devolucion_proveedor_detalle, historial_devolucion_proveedor, item_defectuoso.

**Dimensiones disponibles**: `orden_compra.proveedor_id/bodega_id/estado`,
`orden_compra_detalle.producto_variante_id`, `cuenta_por_pagar.estado` (pendiente/parcial/pagada),
`item_defectuoso.origen` (rma/recepcion), `devolucion_proveedor.tipo_resolucion`
(nota_credito/reposicion).

**Métricas disponibles**: `orden_compra.subtotal/monto_impuesto/total`,
`orden_compra_detalle.cantidad/cantidad_recibida`,
`recepcion_detalle.cantidad_recibida/cantidad_rechazada`, `cuenta_por_pagar.monto_original/saldo_pendiente`,
`pago_proveedor.monto`, `item_defectuoso.costo_unitario`, `devolucion_proveedor.monto_credito`.

**Preguntas que SÍ puede responder hoy**:
- ¿Cuánto compramos a cada proveedor y en qué estado están las órdenes? (16 órdenes: confirmada 1, enviada 1, recibida 12, recibida_parcial 2, ninguna cancelada).
- ¿Qué % de la mercancía llega rechazada en puerta? (`recepcion_detalle.cantidad_rechazada`, 1 de 22 líneas).
- ¿Cuánto le debemos a cada proveedor y cuándo vence? (`cuenta_por_pagar.fecha_vencimiento`).
- ¿Pagamos a los proveedores a tiempo? — SÍ, `cuenta_por_pagar.fecha_vencimiento` vs `pago_proveedor.fecha_pago` (join por `cuenta_por_pagar_id`), ambas columnas verificadas y pobladas.

**Preguntas que NO puede responder hoy**:
- ¿Cumplen los proveedores el plazo de entrega pactado? — `orden_compra.fecha_entrega_esperada` está 100% NULL (0 de 16 filas pobladas); la columna existe pero el flujo de creación de OC nunca la escribe.
- ¿Qué proveedor conviene más por SKU (costo/tiempo)? — `producto_proveedor` está vacía (0 filas) pese a existir 22 líneas de compra reales.
- ¿Por qué se canceló una orden de compra? — no hay columna `motivo_cancelacion`; solo `observacion` genérica (37.5% NULL) y no hay ninguna orden cancelada hoy para confirmar su uso real.
- ¿Cuál es la calificación/desempeño de un proveedor? — `proveedor` no tiene columna de rating ni tabla de evaluación.

### 3.5 Inventario / Bodega / Catálogo de producto

**Tablas**: inventario, movimiento_inventario, tipo_movimiento, bodega, ubicacion_bodega,
ajuste_inventario, transferencia_bodega, lote, producto, producto_variante, categoria,
producto_categoria, marca, atributo, valor_atributo, variante_valor_atributo,
producto_especificacion, producto_imagen, producto_etiqueta, etiqueta, producto_relacionado,
producto_impuesto, impuesto.

**Dimensiones disponibles**: `tipo_movimiento.codigo` (9 tipos), `bodega_id` (solo 2 bodegas),
`producto_variante_id`, `categoria` (11), `marca` (35, todas usadas: 34 valores distintos sobre
1214 productos), `atributo`/`valor_atributo` (2 atributos, 11 valores, 30 asociaciones a
variantes).

**Métricas disponibles**: `movimiento_inventario.cantidad/stock_anterior/stock_nuevo/costo_unitario`,
`inventario.stock_actual/stock_reservado/stock_minimo`, `producto_variante.precio/costo/peso_kg`.

**Preguntas que SÍ puede responder hoy**:
- ¿Cuál es el kardex completo de un producto? (98 movimientos, distribución real: salida_venta 43, entrada_compra 22, entrada/salida_transferencia 10+10, entrada_devolucion_cliente 7, salida_ajuste 2, entrada_reposicion_proveedor 2, entrada_ajuste 1, salida_devolucion_proveedor 1).
- ¿Qué productos están bajo su stock mínimo? (`inventario.stock_minimo` vs `stock_actual`).
- ¿Cuál es el margen (precio-costo) por variante? (`producto_variante.precio` y `.costo`, ambos poblados en las 1221 variantes).

**Preguntas que NO puede responder hoy**:
- ¿Qué productos van a sobre-stockearse? — `inventario.stock_maximo` está 100% NULL (0 de 1227).
- ¿En qué pasillo/estante está el producto X? — `ubicacion_bodega` vacía (0 filas) e `inventario.ubicacion_bodega_id` 100% NULL.
- ¿Qué lote está por vencer (FEFO)? — `lote` vacía y `movimiento_inventario.lote_id`/`recepcion_detalle.lote_id` 100% NULL (deuda deliberada, documentada en `ROADMAP.md`, no es un bug).
- ¿Cuál era el costo del producto hace 3 meses? — no existe costo histórico, `producto_variante.costo` es un único valor actual sin tabla de histórico.
- ¿Qué imágenes/especificaciones/productos relacionados tiene la ficha? — `producto_imagen`, `producto_especificacion`, `producto_etiqueta`, `producto_impuesto`, `producto_relacionado` están todas vacías (0 filas).
- ¿Cuál era el precio de lista antes del descuento? — `producto_variante.precio_comparacion` 100% NULL (0 de 1221).

### 3.6 Logística / Despacho (incluye devolución de cliente / RMA)

**Tablas**: envio, envio_detalle, seguimiento_envio, transportista, metodo_envio, tarifa_envio,
zona_envio, novedad_envio, devolucion, devolucion_detalle, historial_estado_devolucion,
motivo_devolucion, reembolso.

**Dimensiones disponibles**: `envio.estado`, `envio.transportista_id`→`transportista.nombre`,
`envio.metodo_envio_id`, `envio.bodega_id`, `envio.despachado_por` (FK usuario),
`novedad_envio.tipo`/`.accion`, `devolucion.estado`, `motivo_devolucion.nombre`,
`devolucion_detalle.estado_producto/accion/resultado_inspeccion`.

**Métricas disponibles**: `envio.peso_total_kg`, `envio.costo` (ver hueco), `novedad_envio.intento_numero`,
`devolucion.monto_total` (trigger), `devolucion.monto_reembolsado`, `devolucion_detalle.cantidad`.

**Preguntas que SÍ puede responder hoy**:
- ¿Cuántos envíos están en tránsito vs entregados vs devueltos? (`envio.estado`: 11 en_transito, 11 entregado, 2 devuelto de 24).
- ¿Cuál transportista despachó más envíos? (`envio.transportista_id`; solo 2 transportistas: Tramaco Express, Servientrega).
- ¿Qué tipos de incidencia de entrega ocurren más? (`novedad_envio.tipo`: direccion_incorrecta 2, cliente_ausente 2, cliente_rechazo 1, zona_dificil_acceso 1, dano_en_transito 0 — solo 6 casos totales).
- ¿Cuántas devoluciones hay y en qué estado? (6 cerrada, 1 inspeccionada, de 7 totales — la mayoría legacy migrada directo a 'cerrada').
- ¿Qué transportista/zona entrega a tiempo? — SÍ, `envio.fecha_despacho`, `envio.fecha_entrega_estimada` y `envio.fecha_entrega_real` existen y están pobladas (11 entregas reales).

**Preguntas que NO puede responder hoy**:
- ¿Cuánto costó realmente cada envío? — `envio.costo` existe (numeric NOT NULL) pero está en 0.00 en el 100% de las 24 filas: nunca se calcula desde `tarifa_envio`.
- ¿Cuál transportista tiene mejor desempeño/calificación? — no existe ninguna columna de rating en `transportista`.
- ¿Cuánto se ha reembolsado realmente y por qué método? — la tabla `reembolso` (pensada para esto) tiene 0 filas; solo 1 de 7 devoluciones tiene el reembolso registrado directamente en columnas sueltas de `devolucion`.

### 3.7 Soporte

**Tablas**: ticket_soporte, mensaje_ticket, categoria_ticket, correlativo_ticket, faq.

**Dimensiones disponibles**: `categoria_ticket.nombre` (8 categorías), `ticket_soporte.prioridad`,
`ticket_soporte.estado`, `ticket_soporte.asignado_usuario_id`.

**Métricas disponibles**: conteo de mensajes por ticket (`mensaje_ticket`), tiempo de resolución
(calculado, ver §3.9 métricas temporales).

**Preguntas que SÍ puede responder hoy**:
- ¿Cuántos tickets hay por categoría? (Devolución 5, Envíos 3, Facturación 2, Reclamo 1; 4 de 8 categorías sin ningún ticket).
- ¿Cuántos tickets están abiertos/en proceso/cerrados? (abierto 4, en_proceso 3, cerrado 2, esperando_cliente 2, resuelto 1).

**Preguntas que NO puede responder hoy**:
- ¿Cuál es el SLA prometido (fecha límite) de cada ticket como dato consultable en BD? — `ticket_soporte` no tiene columna `fecha_limite`/`sla_vencimiento`; solo se puede derivar sumando `prioridad`+`fecha_creacion` con el mapeo prioridad→horas que vive en código backend, no en la BD.
- ¿Qué tan efectivas son las FAQ para evitar tickets? — `faq` (3 filas) no tiene ninguna relación ni contador de uso hacia `mensaje_ticket`/`ticket_soporte`.

### 3.8 Gerencia / Dirección (transversal — consolidado + auditoría)

**Tablas propias**: configuracion_tienda (vacía), log_auditoria (auditoría central).
Este departamento en el documento T11 consume principalmente vistas consolidadas de Ventas,
Compras, Marketing y Soporte (no tiene tablas operativas propias más allá de las dos citadas).

**Dimensiones disponibles**: `log_auditoria.tabla`, `log_auditoria.accion` (CHECK admite INSERT,
UPDATE, DELETE, LOGIN, LOGOUT, OTRO — en datos reales solo aparecen INSERT 17 y UPDATE 22 de 39
filas totales; LOGIN/LOGOUT/DELETE sin uso todavía), `log_auditoria.usuario_id`.

**Métricas disponibles**: conteo de acciones auditadas por tabla/usuario/periodo.

**Preguntas que SÍ puede responder hoy**: ¿qué acciones críticas se auditaron y quién las hizo?
(39 registros con `datos_anteriores`/`datos_nuevos` en jsonb, `fecha_creacion` 2026-07-09 a
2026-07-20). ¿Hay cupones activos y promociones vigentes ahora mismo? (`cupon.activo`,
`promocion.fecha_inicio/fecha_fin` — informe SIMPLE del documento T11).

**Preguntas que NO puede responder hoy**: ¿qué parámetros generales tiene configurada la tienda
(IVA, moneda default, etc.) según la BD? — `configuracion_tienda` está vacía (0 filas); esos
valores probablemente se resuelven por defaults en código, no están auditables desde la BD.

### 3.9 Seguridad / Sistema / Referencia geográfica (transversal)

**Tablas**: usuario, usuario_rol, rol, rol_permiso, permiso, refresh_token, token_recuperacion,
grupo_horario, log_acceso, pais, provincia, ciudad, moneda, tipo_cambio, idioma, traduccion.

**Dimensiones disponibles**: `usuario.activo/email_verificado`, `rol.codigo` (9 roles: ADMIN,
GERENTE, VENDEDOR, COMPRAS, BODEGA, DESPACHO, CLIENTE, ANALISTA, SOPORTE — verificado con
`usuario_rol` join `rol`, los 10 usuarios tienen exactamente 1 rol cada uno, asignación 1:1),
`grupo_horario.rol_grupo/dia_semana`, `provincia.pais_id`, `ciudad.provincia_id`.

**Métricas disponibles**: ninguna cuantitativa de negocio; son tablas de identidad/control de
acceso y catálogos de apoyo geográfico/idioma.

**Hallazgo de arquitectura importante (no es un hueco, es diseño verificado)**: `permiso` y
`rol_permiso` están **vacías (0 filas)** pese a que `rol` (9 filas) y `usuario_rol` (10 filas, 1:1
con los 10 usuarios) SÍ están pobladas y en uso real. Esto confirma lo documentado en `CLAUDE.md`:
el control de acceso efectivo del sistema **no** vive en este modelo de permisos a nivel de
aplicación, sino en los 9 roles nativos de PostgreSQL (`grp_administrador`, `grp_gerente`, etc.)
con GRANT/RLS/horario — `rol`/`usuario_rol` solo identifican qué rol de negocio tiene cada usuario
para fines de UI, mientras que `permiso`/`rol_permiso` quedaron sin implementar en la práctica.

**Preguntas que SÍ puede responder hoy**: ¿cuántos usuarios activos hay por rol? (10 usuarios, 9
roles definidos, cada usuario con exactamente 1 rol). ¿Qué se auditó en el sistema? (ver §3.8).

**Preguntas que NO puede responder hoy**: ¿quién intentó entrar y falló (seguridad)? —
`log_acceso` existe con las columnas correctas (`exitoso`, `motivo_fallo`, `ip_origen`) pero tiene
0 filas: no se está poblando en la práctica. ¿Cómo se distribuyen las ventas por provincia/ciudad
del país? — el catálogo geográfico solo tiene `pais`=1 fila, `ciudad`=2 filas, `provincia`=25 filas
cargadas (cobertura mínima, un solo país); cualquier análisis regional fino no tiene granularidad
real de datos de cliente/dirección para explotarlo (`direccion` solo tiene 4 filas).

## 4. Métricas con cálculo temporal entre dos fechas (verificadas)

Solo se listan las que se confirmó que **ambas** columnas de fecha existen y están pobladas:

- **Tiempo pedido → primer pago**: `pedido.fecha_pedido` y `pago.fecha_pago` (join por `pedido_id`).
- **Tiempo pedido → factura**: `pedido.fecha_pedido` y `factura_venta.fecha_emision`.
- **Tiempo entre transiciones de estado del pedido**: `historial_estado_pedido.fecha_creacion` (self-join consecutivo por `pedido_id`).
- **Vigencia de cupón vs uso real**: `cupon.fecha_inicio`/`cupon.fecha_fin` y `uso_cupon.fecha_creacion`.
- **Ciclo de compra (emisión → recepción)**: `orden_compra.fecha_emision` y `recepcion_mercancia.fecha_recepcion` (join por `orden_compra_id`); verificado sobre 14 órdenes con recepción (mayoría 0-1 días, datos de prueba).
- **Puntualidad de pago a proveedor**: `cuenta_por_pagar.fecha_vencimiento` vs `pago_proveedor.fecha_pago` (join por `cuenta_por_pagar_id`), ambas 100% pobladas.
- **Duración de resolución de devolución a proveedor**: `devolucion_proveedor.fecha_creacion` vs `fecha_resolucion` (solo 2 filas).
- **Tiempo de tránsito/entrega por transportista y zona**: `envio.fecha_despacho` y `envio.fecha_entrega_real` (11 envíos entregados).
- **Cumplimiento de fecha estimada vs real de entrega**: `envio.fecha_entrega_estimada` vs `envio.fecha_entrega_real`.
- **Tiempo de resolución de novedad de envío**: `novedad_envio.fecha_registro` y `novedad_envio.fecha_resolucion` (6/6 resueltas, muestra chica).
- **Tiempo de resolución de ticket de soporte**: `ticket_soporte.fecha_creacion` y `ticket_soporte.fecha_cierre` (solo 2 de 12 tickets con `fecha_cierre` poblado, consistente con solo 2 tickets en estado 'cerrado').

**Verificado que NO existe pese a ser candidato natural**: cumplimiento de plazo de entrega de
proveedor (`orden_compra.fecha_entrega_esperada` vs `recepcion_mercancia.fecha_recepcion`) — la
primera columna es 100% NULL sobre 16 filas, confirmado con `COUNT(fecha_entrega_esperada)=0`.
Días de ciclo de una devolución de cliente hasta el cierre — `devolucion` no tiene `fecha_cierre`
explícita, solo `fecha_actualizacion` genérica (tocada por trigger en cualquier cambio) o hay que
inferirlo del último registro de `historial_estado_devolucion` con estado='cerrada'.

## 5. Tablas vacías o subutilizadas

| Tabla | Filas | Implicación de negocio |
|---|---|---|
| comparacion / comparacion_item | 0 | Función "comparar productos" del catálogo nunca fue usada por ningún cliente |
| cliente_segmento / segmento_cliente / grupo_cliente | 0 | Segmentación de clientes (RFM, grupos con descuento) diseñada pero no operada, ni un dato cargado |
| pasarela_pago | 0 | No hay integración real de pasarela de pago; todo pago del checkout es simulado |
| reserva_stock | 0 | El mecanismo de reserva temporal de stock por carrito nunca dejó registro persistente |
| configuracion_tienda | 0 | No hay parámetros de tienda auditables desde BD (posiblemente resueltos por defaults en código) |
| contacto_proveedor | 0 | Sin contactos de proveedor registrados: no se puede saber a quién llamar en cada proveedor |
| producto_proveedor | 0 | Sin catálogo proveedor→SKU pese a 22 líneas de compra reales: no se puede comparar costo/tiempo entre proveedores por producto |
| etiqueta / producto_etiqueta | 0 | Catálogo de etiquetado de producto sin ningún dato |
| lote | 0 | Trazabilidad por lote/vencimiento (FEFO) pospuesta deliberadamente (deuda documentada en `ROADMAP.md`), no es un bug |
| producto_especificacion / producto_imagen / producto_impuesto / producto_relacionado | 0 | Ficha de producto del catálogo de tienda incompleta: sin especificaciones técnicas, sin imágenes, sin impuestos por producto, sin relacionados — afecta más a la tienda que a los informes tácticos |
| ubicacion_bodega | 0 | Sin ubicaciones físicas dentro de bodega: picking sin dirección de estante/pasillo |
| reembolso | 0 | El flujo de reembolso simulado documentado en `CLAUDE.md` no está insertando en esta tabla transaccional; solo 1 de 7 devoluciones tiene el dato disperso en columnas de `devolucion` |
| log_acceso | 0 | Sin trazabilidad de intentos de login (exitosos o fallidos) pese a que la tabla y sus columnas de seguridad existen completas |
| permiso / rol_permiso | 0 | Vestigial: el control de acceso real vive en los roles nativos de PostgreSQL (no es un hueco, es hallazgo de arquitectura, ver §3.9) |
| refresh_token / token_recuperacion | 0 | Tablas técnicas de sesión JWT/recuperación de contraseña sin uso reciente registrado (no aportan valor de reporting de negocio) |
| tipo_cambio | 0 | Sin histórico de tasas de cambio: sistema opera en una sola moneda (`moneda` = 1 fila) |
| traduccion | 0 | Sin contenido traducido pese a existir 2 idiomas configurados: la internacionalización no está en uso real |
| novedad_envio (6), item_defectuoso (3), devolucion_proveedor* (2-3), historial_devolucion_proveedor (8) | <10 | Módulos muy recientes (2026-07-18) con datos de humo, insuficientes para cualquier tendencia agregada todavía |
| proveedor (2), transportista (2), bodega (2), cliente (2), atributo (2), impuesto (2) | 2 | Universo mínimo: cualquier informe "por proveedor/transportista/bodega/cliente" tiene solo 2 categorías — no es un hueco de columna, es volumen de datos de entorno demo |

## 6. Campos nulos o de valor único

| Columna | Tabla | Hallazgo (evidencia) |
|---|---|---|
| `pago.estado` | pago | 1 valor distinto ('completado') en 26/26 filas — no hay pagos rechazados/pendientes/reembolsados |
| `transaccion_pago.tipo` | transaccion_pago | 1 valor distinto ('captura') en 26/26 filas — sin reintentos ni devoluciones de transacción |
| `pago.pasarela_pago_id` | pago | 26/26 NULL (100%) — ningún pago ligado a pasarela real |
| `cliente.genero` | cliente | 2/2 NULL (100%) |
| `cliente.fecha_nacimiento` | cliente | 2/2 NULL (100%, MIN y MAX ambos NULL) |
| `cliente.grupo_cliente_id` | cliente | 2/2 NULL (100%) |
| `pedido.transportista_id` / `metodo_envio_id` | pedido | 20/34 NULL (59%) — coherente: pedidos internos previos a asignación automática por zona |
| `pedido.vendedor_id` | pedido | 17/34 NULL (50%) — coherente por diseño: NULL cuando canal='web' |
| `orden_compra.fecha_entrega_esperada` | orden_compra | 16/16 NULL (100%) — columna existe, nunca se puebla |
| `inventario.stock_maximo` | inventario | 1227/1227 NULL (100%) |
| `inventario.ubicacion_bodega_id` | inventario | 1227/1227 NULL (100%) |
| `producto_variante.precio_comparacion` | producto_variante | 1221/1221 NULL (100%) |
| `movimiento_inventario.lote_id` | movimiento_inventario | 98/98 NULL (100%) |
| `recepcion_detalle.lote_id` | recepcion_detalle | 22/22 NULL (100%) |
| `proveedor.ciudad_id` | proveedor | 2/2 NULL (100%) — la ciudad solo existe como texto libre en `direccion`, no normalizada para proveedor |
| `proveedor.sitio_web` | proveedor | 2/2 NULL (100%) |
| `recepcion_detalle.motivo_rechazo` | recepcion_detalle | 21/22 NULL; solo 1 valor distinto en la única fila con rechazo |
| `ajuste_inventario.estado` | ajuste_inventario | 1 valor distinto ('aplicado') en 3/3 filas — CHECK admite también 'borrador'/'anulado' pero sin evidencia de uso |
| `orden_compra.estado` | orden_compra | 0/16 filas en 'cancelada' o 'borrador' pese a que el CHECK las admite |
| `envio.costo` | envio | 1 valor distinto (0.00) en 24/24 filas — columna NOT NULL nunca calculada desde `tarifa_envio` |
| `novedad_envio.estado` | novedad_envio | 1 valor distinto ('resuelta') en 6/6 filas — 0 novedades abiertas actualmente |
| `transportista.email` | transportista | 2/2 NULL |
| `transportista.fecha_actualizacion` | transportista | 2/2 NULL (nunca actualizados desde creación) |
| `categoria_ticket` (4 de 8) | ticket_soporte (join) | Sugerencia, Producto defectuoso, Problema con pedido, Consulta general: 0 tickets asociados a cada una |
| `log_auditoria.accion` | log_auditoria | Solo 2 de los 6 valores admitidos por el CHECK tienen datos: UPDATE 22, INSERT 17 (LOGIN/LOGOUT/DELETE/OTRO sin uso) |
| `permiso` / `rol_permiso` | — | 0 filas cada una — ver §3.9, vestigial por diseño (roles nativos de PostgreSQL hacen el trabajo real) |

## 7. Huecos de datos

| Hueco | Área afectada | Qué falta exactamente (tabla/columna) | Severidad |
|---|---|---|---|
| Universo de clientes real insuficiente para CRM/segmentación | Clientes/CRM | No falta columna (`grupo_cliente`, `segmento_cliente`, `cliente_segmento`, `cliente.genero/fecha_nacimiento` existen); falta que se carguen filas — hoy solo 2 clientes demo | ALTA |
| Sin trazabilidad de fallos/reintentos de pago | Ventas/Finanzas | `pago.estado` y `transaccion_pago.tipo` de valor único en 26/26 filas; no hay evidencia de que el flujo real llegue a escribir otros valores | ALTA |
| Sin cumplimiento de plazo de entrega de proveedor | Compras | `orden_compra.fecha_entrega_esperada` 100% NULL (0/16); imposible responder "¿qué proveedor entrega a tiempo?" | ALTA |
| Sin catálogo proveedor↔SKU pese a compras reales | Compras | `producto_proveedor` vacía (0 filas) con 22 líneas de compra ya registradas; sin `es_preferido`/costo/tiempo de entrega por proveedor | ALTA |
| Costo de envío nunca calculado | Logística | `envio.costo` = 0.00 en 24/24 filas pese a existir `tarifa_envio` para calcularlo | ALTA |
| Reembolsos sin tabla transaccional real | Logística/RMA | `reembolso` vacía (0 filas); el único reembolso conocido vive disperso en 4 columnas de `devolucion` (id=8) | ALTA |
| Sin calificación/desempeño de transportista | Logística | `transportista` no tiene columna de rating ni tabla de evaluación; solo se puede derivar indirectamente cruzando fecha estimada vs real | ALTA |
| Sin costo histórico de producto | Inventario/Compras | `producto_variante.costo` es un único valor "a hoy"; no existe tabla de histórico de costo | MEDIA |
| Sin calificación de proveedor | Compras | `proveedor` no tiene columna de rating/scorecard; se podría derivar de puntualidad de pago o % rechazo en recepción, pero no hay campo explícito | MEDIA |
| Sin motivo dedicado de cancelación de orden de compra | Compras | No hay `orden_compra.motivo_cancelacion`; solo `observacion` genérica (37.5% NULL) sin garantía semántica | MEDIA |
| Sin alertas de sobre-stock | Inventario | `inventario.stock_maximo` 100% NULL (0/1227) | MEDIA |
| Sin ubicación física dentro de bodega | Inventario | `ubicacion_bodega` vacía (0 filas), `inventario.ubicacion_bodega_id` 100% NULL | MEDIA |
| SLA de ticket no es columna consultable | Soporte | `ticket_soporte` no tiene `fecha_limite`/`sla_vencimiento`; el mapeo prioridad→horas vive en código backend, no en BD | MEDIA |
| Sin trazabilidad de intentos de login | Seguridad | `log_acceso` vacía (0 filas) pese a tener columnas `exitoso`/`motivo_fallo`/`ip_origen` completas | MEDIA |
| Sin métricas de efectividad de marketing (clics/impresiones/conversión) | Marketing | `campana`/`banner`/`promocion` no tienen ninguna columna de performance; ese dato, si existe, viviría en ClickHouse, fuera de este inventario de PostgreSQL | MEDIA |
| Precio de lista antes de descuento no disponible | Ventas/Inventario | `producto_variante.precio_comparacion` 100% NULL (0/1221) | BAJA |
| Sin trazabilidad por lote/vencimiento (FEFO) | Inventario | `lote` vacía, `movimiento_inventario.lote_id`/`recepcion_detalle.lote_id` 100% NULL — deuda deliberada y documentada, no accidental | BAJA |
| Sin cuarentena física de devolución a proveedor | Compras | `item_defectuoso`/`devolucion_proveedor` no registran ubicación de retención — deuda ya documentada en `DEUDA_TECNICA.md` Fase 7 | BAJA |
| Función de comparación de productos sin uso | Ventas | `comparacion`/`comparacion_item` en 0 filas — no se puede saber si la funcionalidad se usa en producción | BAJA |
| Catálogo de tienda con ficha de producto incompleta | Inventario | `producto_imagen`, `producto_especificacion`, `producto_etiqueta`, `producto_impuesto`, `producto_relacionado` todas en 0 filas | BAJA |
| Sin fecha de cierre explícita en devolución de cliente | Logística/RMA | `devolucion` no tiene `fecha_cierre`; hay que inferirla de `historial_estado_devolucion` filtrando estado='cerrada' o usar `fecha_actualizacion` genérica | BAJA |
| Configuración de tienda no auditable desde BD | Gerencia | `configuracion_tienda` vacía (0 filas) | BAJA |
| Cobertura geográfica mínima | Referencia | `pais`=1, `ciudad`=2, `provincia`=25 filas cargadas; sin granularidad real para análisis regional fino (aunque el modelo normalizado sí lo soporta) | BAJA |
| Sin uso de internacionalización | Referencia | `traduccion` vacía (0 filas) pese a 2 idiomas configurados en `idioma` | BAJA |
| Sin histórico de tipo de cambio | Referencia | `tipo_cambio` vacía (0 filas); sistema opera en una sola moneda (`moneda`=1 fila) | BAJA |

## 8. Notas de verificación

- **Documento táctico T11**: el PDF `docs/RetailMind_T11_Analisis_Tactico.pdf` no pudo renderizarse
  directamente con la herramienta de lectura (falta `pdftoppm`/poppler-utils en el entorno). En su
  lugar se leyó íntegramente el script generador `docs/build_t11.py`, que contiene el texto fuente
  completo y verbatim usado para producir tanto el PDF como el DOCX (incluye la tabla de 25
  objetivos tácticos, los 7 departamentos y la tabla de "soporte de datos verificado"). Se considera
  fuente equivalente y confiable; no se usó el DOCX binario directamente.
- **Discrepancia de conteo de tablas**: `CLAUDE.md` documenta "~103 tablas"; el conteo real vía
  `information_schema.tables` en el momento de este diagnóstico (2026-07-21) es de **109 tablas**.
  Es una aproximación desactualizada de la documentación por el crecimiento de los scripts 40-45
  (descuentos, segregación financiera, trazabilidad de autor, novedades de envío, devolución a
  proveedor), no un error de este inventario.
- **Metodología de subdivisión**: la inspección se dividió en 4 bloques ejecutados en paralelo
  (Ventas/CRM/Marketing; Compras/Inventario; Logística/Soporte; Seguridad/Sistema/Referencia) para
  cubrir las 109 tablas con evidencia real de cada una (conteos, rangos de fecha, muestras y
  chequeos de nulidad/valor único), y luego se consolidaron en este documento único. Toda cifra
  proviene de una consulta SQL real ejecutada contra `retailmind`, ninguna fue inferida o asumida.
- **Naturaleza del dato observado**: nombres de cupones de prueba (`VENCIDO10`, `FUTURO10`,
  `LIMITE1`, `DEMO10`), solo 2 clientes (coinciden con las cuentas demo `maria.lopez@demo.com` y
  `carlos.vera@demo.com` documentadas en `CLAUDE.md`) y fechas de creación concentradas en
  2026-07-04 a 2026-07-21 confirman que la base es un **entorno de desarrollo/demo**, no producción
  real. Cualquier informe táctico construido sobre estos datos hoy debe interpretarse como prueba de
  concepto del pipeline de reporting, no como reflejo de operación real de negocio a escala.
- **No se pudo verificar en profundidad**: el uso real en código de `configuracion_tienda` (si sus
  valores se leen de otra fuente/env vars en vez de la BD) no se confirmó porque queda fuera del
  alcance de "solo BD" de esta tarea (no se inspeccionó código de aplicación más allá de lo
  estrictamente necesario para interpretar columnas, según instrucción de la tarea).
- Todas las restricciones READ-ONLY se respetaron: únicamente se ejecutaron `SELECT` y consultas de
  catálogo (`information_schema`, `pg_catalog`); no se modificó ningún archivo de código del
  repositorio ni se realizaron operaciones de escritura en la base de datos.
