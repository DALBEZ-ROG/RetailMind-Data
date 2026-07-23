# Auditoría de densidad de datos — objetivos FACTIBLE HOY

Fecha: 2026-07-22. Toda cifra de este documento fue medida vía MCP sobre la base real
`retailmind` (PostgreSQL, `localhost:5432`), en modo estrictamente lectura. Esta auditoría
responde una pregunta que la verificación de factibilidad original NO respondía: el estado
FACTIBLE HOY se asignó comprobando que la columna existiera y tuviera *algún* dato; aquí se mide
si tiene datos **suficientes y variados** para que el informe en pantalla diga algo.

**La clasificación de densidad NO cambia el estado de factibilidad de ningún objetivo.** Es
información nueva y adicional; qué hacer con los DELGADOS se decidirá aparte.

Definiciones:

- **SÓLIDO**: las columnas que sustentan el informe están pobladas y con variedad real; el
  informe devuelve resultados con poder discriminante.
- **DELGADO**: técnicamente devuelve filas, pero tan pocas, tan poco variadas o de un período
  tan corto que en pantalla parecería vacío o inútil. Se indica el conteo exacto que lo limita.
- **HUECO**: las columnas existen pero están vacías, en su default o con un único valor; el
  informe saldría en blanco.

Contexto transversal que pesa sobre casi todos los compuestos: **toda la operación transaccional
vive en un único mes** (pedidos del 2026-07-04 al 2026-07-18; kardex, pagos, facturas de venta y
compra, despachos y devoluciones igual). Cualquier informe "mes a mes" dibuja hoy **un solo
punto**. Además la operación está detenida desde el 2026-07-18: los informes de "hoy" (foto del
día, colas de trabajo) devuelven 0 filas en este momento.

## 1. Resumen ejecutivo

Se auditaron los **45 objetivos FACTIBLE HOY** del catálogo sincronizado (44 originales +
OTD-LOG-10, que pasó a FACTIBLE HOY en la sincronización del 2026-07-22).

| Densidad | Cantidad |
|---|---|
| SÓLIDO | 15 |
| DELGADO | 29 |
| HUECO | 1 |

Lectura honesta: **dos de cada tres informes "factibles hoy" se verían pobres o vacíos en
pantalla**. No es un problema de esquema ni de flujos (eso ya se corrigió); es que el entorno es
demo, con ~2 semanas de operación, 2 proveedores, 2 clientes, 2 bodegas y 17 productos vendidos
de 1.214. La mayoría se cura con volumen de operación (sección 6); una minoría exige cargar
datos maestros deliberadamente (sección 5).

## 2. Tabla completa

Los porcentajes son de filas con valor no nulo (o distinto del default cuando se indica) sobre el
total de la tabla. "Filas hoy" = filas que el informe devolvería si se ejecutara hoy.

### Ventas

| ID | Objetivo | Columnas que lo sustentan | % poblado | Valores distintos | Filas hoy | Densidad |
|---|---|---|---|---|---|---|
| OTD-VEN-01 | Cartera de pedidos por estado | `pedido.estado_pedido_id/numero/fecha_pedido/canal/total` | 100 % (34/34) | 7 estados, 3 canales | 34 | SÓLIDO |
| OTD-VEN-02 | Pedidos y monto por vendedor | `pedido.vendedor_id` (NULL en canal web por diseño), `pedido.total` | 50 % (17/34; el resto es online) | 4 vendedores | 4 | SÓLIDO |
| OTD-VEN-03 | Top 10 productos más vendidos | `pedido_detalle.producto_variante_id/cantidad/precio_unitario` | 100 % (43 líneas) | 17 productos vendidos; unidades 24→3, ranking con variancia real | 10 | SÓLIDO |
| OTD-VEN-04 | Producto hueso (sin venta) | `producto_variante` × `pedido_detalle` + kardex `salida_venta` | 100 % | 1.197 de 1.214 productos jamás vendidos, **todos empatados** en antigüedad (catálogo cargado el 2026-07-10) | ~1.197 | DELGADO — devuelve muchísimas filas pero sin poder discriminante: el 98,6 % empata en "nunca vendido desde la carga" |
| OTD-VEN-06 | Evolución mensual por categoría | `pedido.fecha_pedido` + `pedido_detalle.*` + `producto_categoria` | 100 % | **1 solo mes** con ventas; 7 de 11 categorías con venta | 1 punto temporal | DELGADO — serie de un punto |
| OTD-VEN-07 | Ticket promedio por período y canal | `pedido.total/fecha_pedido/canal` | 100 % (34/34) | 3 canales, **1 mes** | 3 (un punto por canal) | DELGADO — sin evolución posible |
| OTD-VEN-09 | Mezcla de formas de pago en el tiempo | `pago.metodo_pago_id/monto/fecha_pago` | 100 % (26/26) | 3 métodos, **1 mes** | 3 | DELGADO — la mezcla actual sale; el "cómo cambia" es un punto |
| OTD-VEN-10 | Cola de moderación (reseñas/preguntas) | `resena.estado`, `pregunta_producto.estado` + `respuesta_pregunta` | 100 % | 2 reseñas pendientes; 0 preguntas sin responder (1/1 respondida) | 2 | DELGADO |
| OTD-VEN-13 | Venta por canal, por período | `pedido.canal/total/fecha_pedido` | 100 % (34/34) | 3 canales, **1 mes** | 3 | DELGADO — participación actual sí; serie temporal de un punto |
| OTD-VEN-14 | Dinero devuelto vs venta, mensual | `devolucion.monto_total/fecha_creacion` vs `pedido.total` | 100 % (7/7 montos > 0) | 7 devoluciones, **1 mes** | 1 punto mensual | DELGADO |

### Compras

| ID | Objetivo | Columnas que lo sustentan | % poblado | Valores distintos | Filas hoy | Densidad |
|---|---|---|---|---|---|---|
| OTD-COM-01 | Órdenes de compra por estado | `orden_compra.estado/numero/fecha_emision/total` + `proveedor.razon_social` | 100 % (17/17) | 4 estados, 2 proveedores | 17 (0 esperando aprobación en este momento) | SÓLIDO |
| OTD-COM-02 | Deuda y vencimientos por proveedor | `cuenta_por_pagar.saldo_pendiente/fecha_vencimiento/estado` | 100 % (14/14) | 3 estados, 9 fechas de vencimiento; 7 con saldo, 1 vencida | 14 | SÓLIDO |
| OTD-COM-03 | Puntualidad de pago a proveedor | `pago_proveedor.fecha_pago` vs `cuenta_por_pagar.fecha_vencimiento` | 100 % (10/10) | **Resultado único: 10/10 a tiempo, 0 tarde**; 1 mes | 10, todas en la misma categoría | DELGADO — sin un solo pago tardío no hay contraste que mostrar |
| OTD-COM-04 | Gasto de compras por proveedor y mes | `factura_compra.total/fecha_emision/proveedor_id` | 100 % (14/14) | **2 proveedores, 1 mes** | 2 barras, 1 punto temporal | DELGADO |
| OTD-COM-06 | Días reales del ciclo de compra | `orden_compra.fecha_emision` + `recepcion_mercancia.fecha_recepcion` | 100 % (14 recepciones) | Ciclo observado: **solo 2 valores, 0 y 1 día** (tiempos comprimidos de demo) | 14 | DELGADO — un histograma con dos barras pegadas al cero |
| OTD-COM-08 | Pool de defectuosos y devoluciones en curso | `item_defectuoso.estado/origen/cantidad`, `devolucion_proveedor.estado` | 100 % | `item_defectuoso.estado`: **único valor 'resuelto' (3/3)**; `devolucion_proveedor.estado`: **único valor 'cerrada' (2/2)** | **0 pendientes, 0 en curso** | **HUECO** — el tablero de trabajo saldría en blanco: no hay ni un ítem pendiente ni una devolución en curso; solo historia cerrada |
| OTD-COM-11 | Proveedores que entregan incompleto | `orden_compra_detalle.cantidad/cantidad_recibida` | 100 % (25 líneas) | 7 líneas incompletas, 2 proveedores | 7 | DELGADO |
| OTD-COM-12 | Evolución del costo de compra por producto | `orden_compra_detalle.precio_unitario/producto_variante_id` + `orden_compra.fecha_emision` | 100 % (25 líneas, 11 precios distintos) | Solo 5 variantes con recompra y **solo 1 con cambio de precio entre compras** | 1 serie con evolución real | DELGADO — el informe existe para exactamente un producto |

### Inventario / Bodega

| ID | Objetivo | Columnas que lo sustentan | % poblado | Valores distintos | Filas hoy | Densidad |
|---|---|---|---|---|---|---|
| OTD-INV-01 | Productos bajo stock mínimo | `inventario.stock_actual` vs `stock_minimo` | `stock_minimo` fuera del default 0: **1,3 % (16/1.227)** | 2 valores: {0, 2} | **1** | DELGADO — el informe de reposición completo cabe en una fila; 1.211 filas siguen en el default |
| OTD-INV-02 | Existencias por bodega | `inventario.stock_actual/stock_reservado/bodega_id` | 100 % (1.227) | 2 bodegas; nota: `stock_reservado` = 0 en 1.227/1.227 (la parte "apartado" no discrimina hoy) | 1.227 | SÓLIDO |
| OTD-INV-03 | Kardex por producto | `movimiento_inventario.cantidad/stock_anterior/stock_nuevo/fecha_creacion` + `tipo_movimiento` | 100 % (98/98) | 9 tipos de movimiento en uso | 98 | SÓLIDO |
| OTD-INV-04 | Rotación por categoría y período | kardex + `producto_categoria` | 100 % | 7 de 11 categorías con movimiento; **1 mes** | 7 | DELGADO — ranking actual sí; "por período" es un punto |
| OTD-INV-05 | Ajustes de inventario y motivos | `ajuste_inventario.tipo/motivo/estado/fecha_aplicacion` | 100 % (3/3) | 3 motivos distintos | 3 | DELGADO |
| OTD-INV-06 | Transferencias entre bodegas | `transferencia_bodega.estado/fecha_envio/fecha_recepcion` | 100 % (10/10) | **Estado único: 'recibida' (10/10)** | 10 listadas, **0 en camino** | DELGADO — la mitad del objetivo ("cuáles van en camino") devuelve 0 |
| OTD-INV-07 | Valor del inventario por categoría/bodega | `inventario.stock_actual` × `producto_variante.costo` | costo 100 % (1.221/1.221) | 2 bodegas, 10 categorías asignadas | 1.227 valorizadas | SÓLIDO |
| OTD-INV-09 | Evolución mensual del capital almacenado | stock actual − kardex hacia atrás, valorizado con costo | 100 % (98 movimientos con stock_anterior/nuevo) | Kardex de **1 solo mes** (04→18 jul) | 1 punto mensual | DELGADO — la reconstrucción funciona pero solo hay un mes que reconstruir |

### Logística / Despacho

| ID | Objetivo | Columnas que lo sustentan | % poblado | Valores distintos | Filas hoy | Densidad |
|---|---|---|---|---|---|---|
| OTD-LOG-01 | Cola de pedidos por despachar | `pedido.estado_pedido_id` = 'preparado' | 100 % (columna poblada, 7 estados) | — | **0** (ningún pedido está hoy en 'preparado' ni 'en_preparacion') | DELGADO — la cola funciona pero está vacía en este momento operativo; con pedidos en curso puebla sola |
| OTD-LOG-02 | Tablero de envíos por estado/transportista | `envio.estado/numero_guia/fecha_despacho` + `transportista.nombre` | 100 % (24/24) | 3 estados (11 en tránsito, 11 entregados, 2 devueltos), 2 transportistas | 24 | SÓLIDO |
| OTD-LOG-03 | Cumplimiento de fecha prometida | `envio.fecha_entrega_estimada` vs `fecha_entrega_real` | estimada: **33 % (8/24)**; pares con ambas fechas: **7** | **Resultado único: 7/7 a tiempo, 0 tarde** | 7 | DELGADO — dos tercios de los envíos ni siquiera tienen fecha prometida, y en los 7 medibles no hay un solo retraso que contrastar |
| OTD-LOG-04 | Días de tránsito por transportista | `envio.fecha_despacho` vs `fecha_entrega_real` | 100 % en los 11 entregados | **Solo 2 valores de tránsito** (rango 0–3 días) | 11 | DELGADO — comparar 2 transportistas con 2 valores posibles no discrimina |
| OTD-LOG-06 | Devoluciones de cliente en curso | `devolucion.numero/estado/guia_retorno/fecha_creacion` | 100 % (7/7) | 2 estados: 6 'cerrada', 1 'reembolsada' | 7 listadas, **1 en curso** | DELGADO — el tablero del ciclo vivo tiene una sola tarjeta |
| OTD-LOG-09 | % de envíos que terminan en devolución, mensual | `envio.fecha_despacho` vs `devolucion.pedido_id/fecha_creacion` | 100 % | 24 envíos, 7 devoluciones, **1 mes** | 1 punto mensual | DELGADO |
| OTD-LOG-10 | Reembolsos pagados por período y vía | `reembolso.monto/estado/fecha_procesado/devolucion_id` | tabla con **1 fila** (el e2e verificado del 2026-07-21) | 1 vía, 1 estado | 1 | DELGADO — el flujo ya escribe; el histórico es una fila |
| OTD-LOG-12 | Duración por etapa del ciclo del pedido | `historial_estado_pedido` (177 filas) + `estado_pedido.codigo` | 100 % (34/34 pedidos cubiertos) | 9 estados, 136 timestamps distintos en 11 días; tramo pagado→despachado con 9 duraciones distintas (prom. 15,9 h) | 34 pedidos × etapas | SÓLIDO — granularidad temporal real; única salvedad: todo en un solo período |

### Soporte

| ID | Objetivo | Columnas que lo sustentan | % poblado | Valores distintos | Filas hoy | Densidad |
|---|---|---|---|---|---|---|
| OTD-SOP-01 | Bandeja de tickets | `ticket_soporte.numero/estado/prioridad/categoria_ticket_id/asignado_usuario_id` | 100 %; asignado 67 % (8/12) | 5 estados, 4 prioridades, 4 categorías | 12 (9 abiertos) | SÓLIDO |
| OTD-SOP-04 | Tickets por categoría | `ticket_soporte.categoria_ticket_id` → `categoria_ticket.nombre` | 100 % (12/12) | 4 de 8 categorías con tickets, distribución 5/3/2/1 | 4 | SÓLIDO — universo pequeño (12 tickets) pero con distribución discriminante |
| OTD-SOP-05 | Carga y cierre por agente | `ticket_soporte.asignado_usuario_id/estado` | 67 % asignados (8/12) | 3 agentes (equipo completo) | 3 | SÓLIDO — cubre todo el equipo real |
| OTD-SOP-06 | Horas hasta la primera respuesta | `ticket_soporte.fecha_creacion` vs primer `mensaje_ticket` del equipo visible | 67 % (8/12 tickets con respuesta del equipo) | **1 mes**, 8 casos | 8 | DELGADO |

### Gerencia / Dirección

| ID | Objetivo | Columnas que lo sustentan | % poblado | Valores distintos | Filas hoy | Densidad |
|---|---|---|---|---|---|---|
| OTD-GER-01 | Foto del día (pedidos y cobros de hoy) | `pedido.fecha_pedido/total` + `pago.fecha_pago/monto` | 100 % histórico | — | **0 pedidos y 0 pagos con fecha de HOY** (última operación: 2026-07-18) | DELGADO — el informe funciona pero cualquier día sin operación muestra tarjetas en cero |
| OTD-GER-02 | Entradas vs salidas de dinero, mensual | `factura_venta.total/fecha_emision` (30) vs `factura_compra` (14); `pago` (26) vs `pago_proveedor` (10) | 100 % | **1 mes en los cuatro flujos** | 1 par de barras | DELGADO |
| OTD-GER-03 | Ganancia por categoría | `pedido_detalle.*` + `producto_variante.costo` + `producto_categoria` | costo 100 % | 43 líneas, 7 categorías con venta, **1 mes** | 7 | DELGADO |
| OTD-GER-04 | Cupones activos, usos y vigencia | `cupon.codigo/activo/usos_actuales/usos_maximos/fecha_fin` | 100 % (6/6) | 3 fechas de fin, 3 cupones ya usados | 6 | SÓLIDO — universo maestro completo |
| OTD-GER-06 | Acciones de marketing vigentes | `promocion` + `promocion_producto` + `campana` + `banner` | 100 % | 1 promoción (2 productos), 1 campaña, 1 banner | 3 | DELGADO — el panel completo son tres filas |
| OTD-GER-08 | Registro de auditoría (quién hizo qué) | `log_auditoria.usuario_id/tabla/accion/datos_anteriores/datos_nuevos` | 100 % (39/39) | 6 usuarios, 8 tablas, 2 acciones | 39 | SÓLIDO |
| OTD-GER-10 | Margen por producto | `producto_variante.precio/costo` + `pedido_detalle.*` | precio/costo 100 % (1.221) | **17 productos con venta** de 1.214 | 17 | DELGADO — el margen teórico existe para todo el catálogo, pero el margen realizado solo para 17 productos |

## 3. OBJETIVOS HUECOS (saldrían en blanco)

### OTD-COM-08 — Pool de defectuosos y devoluciones a proveedor en curso

- `item_defectuoso.estado`: **único valor 'resuelto' en 3/3 filas**. Cero ítems 'pendiente' o
  'en_devolucion'.
- `devolucion_proveedor.estado`: **único valor 'cerrada' en 2/2 filas**. Cero devoluciones en
  curso.
- El objetivo pide "ver los artículos defectuosos **pendientes de devolver** y en qué paso va
  cada devolución": ambas consultas devuelven **0 filas hoy**. Lo único mostrable es historia
  cerrada.
- Importante: el flujo está completo y operativo (módulo del script 45); es un HUECO de datos
  vivos, no de sistema. El siguiente ítem defectuoso que se marque re-puebla el tablero solo.

Casos frontera que se quedaron en DELGADO y no aquí, para dejar el criterio explícito:
OTD-LOG-01 y OTD-GER-01 también devuelven 0 filas *hoy*, pero sus columnas están pobladas y
variadas (7 estados de pedido; un mes de fechas); su vacío es del momento operativo — la
operación está parada desde el 2026-07-18 — no de las columnas. En OTD-COM-08 el vacío está en
la columna misma (estado con un único valor en las dos tablas).

## 4. OBJETIVOS DELGADOS (devuelven muy poco) — con el conteo exacto que los limita

**Limitados por "todo vive en un solo mes"** (la serie temporal que piden dibuja un punto):

| ID | Conteo limitante |
|---|---|
| OTD-VEN-06 | 1 mes de ventas (2026-07-04 → 07-18) |
| OTD-VEN-07 | 1 mes; 3 canales |
| OTD-VEN-09 | 1 mes; 26 pagos, 3 métodos |
| OTD-VEN-13 | 1 mes; 3 canales |
| OTD-VEN-14 | 1 mes; 7 devoluciones |
| OTD-COM-04 | 1 mes; 2 proveedores |
| OTD-INV-04 | 1 mes de kardex; 7 categorías con movimiento |
| OTD-INV-09 | 1 mes de kardex que reconstruir |
| OTD-LOG-09 | 1 mes; 24 envíos vs 7 devoluciones |
| OTD-GER-02 | 1 mes en los cuatro flujos de dinero |
| OTD-GER-03 | 1 mes; 43 líneas, 7 categorías |

**Limitados por conteo absoluto de filas:**

| ID | Conteo limitante |
|---|---|
| OTD-VEN-10 | Cola de moderación = 2 reseñas pendientes + 0 preguntas sin responder |
| OTD-COM-11 | 7 líneas incompletas, 2 proveedores |
| OTD-INV-01 | **1 fila bajo mínimo**; solo 16/1.227 filas con mínimo real (valor único 2) |
| OTD-INV-05 | 3 ajustes |
| OTD-LOG-06 | 1 devolución en curso (6 de 7 cerradas) |
| OTD-LOG-10 | **1 reembolso** en la tabla (el e2e de verificación) |
| OTD-SOP-06 | 8 tickets con primera respuesta |
| OTD-GER-06 | 3 filas en total: 1 promoción + 1 campaña + 1 banner |
| OTD-GER-10 | 17 productos con venta de 1.214 |

**Limitados por falta de variedad (las filas existen pero empatan):**

| ID | Conteo limitante |
|---|---|
| OTD-VEN-04 | 1.197 productos sin venta, todos empatados en antigüedad (catálogo cargado 2026-07-10) |
| OTD-COM-03 | 10/10 pagos a tiempo — cero contraste |
| OTD-COM-06 | Ciclo de compra con solo 2 valores: 0 y 1 día |
| OTD-COM-12 | **1 sola variante** con cambio de precio entre compras (5 con recompra) |
| OTD-INV-06 | Estado único 'recibida' en 10/10 transferencias; 0 en camino |
| OTD-LOG-03 | Estimada solo en 8/24; de los 7 pares medibles, 7/7 a tiempo |
| OTD-LOG-04 | Solo 2 valores de días de tránsito (0–3) para comparar 2 transportistas |

**Limitados por el momento operativo (0 filas hoy, columnas sanas):**

| ID | Conteo limitante |
|---|---|
| OTD-LOG-01 | 0 pedidos en 'preparado' en este momento |
| OTD-GER-01 | 0 pedidos y 0 pagos con fecha de hoy (operación detenida desde 07-18) |

## 5. DATOS MAESTROS POR CARGAR

Campos que **ninguna operación llena por sí sola**: hay que cargarlos deliberadamente
(pantallas/endpoints de captura ya existen en todos los casos). Un seed transaccional NO los
resuelve.

| Tabla.columna | Estado actual | Filas afectadas | Objetivos que desbloquea | Prioridad |
|---|---|---|---|---|
| `inventario.stock_minimo` | 1.211/1.227 en el default 0; los 16 reales con valor único 2 | ~1.211 | OTD-INV-01 pasa de 1 fila a un informe de reposición real | ALTA |
| `inventario.stock_maximo` | 100 % NULL (0/1.227) pese al endpoint PUT `/api/inventario/niveles` ya operativo | 1.227 | OTD-INV-08 (hoy devuelve 0 filas; única razón por la que sigue sin ser respondible) | ALTA |
| `producto_variante.peso_kg` | 100 % NULL (0/1.221) | 1.221 | Componente por peso de OTD-LOG-11 (`envio.peso_total_kg` se persiste solo si hay peso); confirma el error conocido del inventario de datos | ALTA |
| `tarifa_envio.costo_por_kg` | 0.00 en 3/3 tarifas | 3 | OTD-LOG-11 completo (sin esto el costo calculado es solo el `costo_base` plano) | MEDIA |
| `promocion` / `promocion_producto` / `campana` / `banner` | 1 / 2 / 1 / 1 filas | crear registros nuevos (gestión ya existe en marketing) | OTD-GER-06 denso; habilita evaluar OTD-GER-07 (hoy REQUIERE VOLUMEN) | MEDIA |

Nota: `producto_proveedor` (0 filas) también es un maestro vacío, pero NO va en esta lista de
carga: no existe CRUD ni pantalla de captura, por eso OTD-COM-10 sigue correctamente en
REQUIERE CAMBIO EN EL SISTEMA.

## 6. DATOS TRANSACCIONALES QUE RESUELVE EL SEED

Columnas que se llenan solas conforme el sistema opera, ahora que los flujos están corregidos.
Un seed de volumen (u operación real sostenida varios meses) las puebla sin tocar nada más:

- **Series multi-mes**: `pedido.fecha_pedido`, `pago.fecha_pago`, `factura_venta/factura_compra.fecha_emision`,
  `pago_proveedor.fecha_pago`, `movimiento_inventario.fecha_creacion`, `envio.fecha_despacho`,
  `devolucion.fecha_creacion` — cura los 11 objetivos "serie de un punto" (VEN-06/07/09/13/14,
  COM-04, INV-04/09, LOG-09, GER-02/03).
- **`orden_compra.fecha_entrega_esperada`**: cada OC nueva la captura desde el formulario
  (1/17 ya la tiene); cuando esas órdenes se reciban habrá pares promesa/llegada → OTD-COM-05.
- **`envio.costo`** (y `peso_total_kg` si el maestro de pesos se carga): cada despacho nuevo lo
  calcula desde la tarifa → OTD-LOG-11. Ojo: los 24 envíos históricos quedarán en 0.00 — el
  arreglo no re-escribe historia.
- **`envio.fecha_entrega_estimada`**: los envíos nuevos la traen (hoy 8/24) → OTD-LOG-03, y con
  entregas reales variadas aparecerán retrasos que contrastar (LOG-03/LOG-04).
- **`reembolso`**: cada transición a 'reembolsada' inserta su fila → OTD-LOG-10.
- **`item_defectuoso` / `devolucion_proveedor` con estados vivos**: nuevas inspecciones RMA y
  rechazos de recepción re-pueblan el pool → cura el único HUECO (OTD-COM-08).
- **Estados intermedios en colas**: pedidos en 'preparado' (LOG-01), transferencias 'en_transito'
  (INV-06), devoluciones en tramos intermedios (LOG-06), operación del día corriente (GER-01).
- **Variedad por repetición**: recompras con precio distinto (COM-12), pagos a proveedor
  tardíos (COM-03), ciclos de compra de más de 1 día (COM-06), tránsitos variados (LOG-04),
  ventas de más productos (VEN-03/04, GER-10), ajustes (INV-05), tickets respondidos y cerrados
  (SOP-06), reseñas/preguntas nuevas (VEN-10), usos de cupón y líneas con promoción
  (GER-04 usos), `inventario.stock_reservado` > 0 (nota de INV-02).
