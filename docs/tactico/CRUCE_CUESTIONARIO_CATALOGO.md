# Cruce del cuestionario de negocio contra el catálogo de objetivos tácticos

Fase 2 de la auditoría (2026-07-21). Insumos: `CUESTIONARIO_NEGOCIO.md` (78 preguntas redactadas
a ciegas, sin conocer el catálogo), `CATALOGO_OBJETIVOS_TACTICOS.md` (57 objetivos OTD-) y
`INVENTARIO_DATOS_TACTICO.md` (diagnóstico de la base real del mismo día). Los veredictos de
factibilidad de los puntos ciegos se apoyaron además en consultas READ-ONLY directas a la base
`retailmind` vía MCP (verificado 2026-07-21: `historial_estado_pedido` con 177 filas cubriendo los
34 pedidos; ninguna tabla de historial de ticket ni de metas de venta; `ticket_soporte` sin
referencia a producto pero con `cliente_id`; `pedido.fecha_pedido` con hora (timestamptz); 6
líneas de orden de compra con cantidad recibida menor a la pedida; 11 precios de compra distintos
en las líneas de orden).

**Criterio de cobertura**: CUBIERTA = existe al menos un objetivo OTD- (o una combinación
explícita de ellos) que responde la pregunta completa, aunque hoy esté en estado «REQUIERE CAMBIO»
o «REQUIERE VOLUMEN» — la cobertura mide si el catálogo *reclamó* la necesidad, no si el dato ya
fluye. PARCIALMENTE CUBIERTA = hay objetivo cercano pero una parte sustantiva de la pregunta queda
sin responder. NO CUBIERTA = ningún objetivo la reclama.

---

## 1. Resumen ejecutivo

| Indicador | Valor |
|---|---|
| Preguntas totales del cuestionario | **78** |
| Por prioridad | CRÍTICA 34 · ÚTIL 29 · DESEABLE 15 |
| CUBIERTAS | **50** (64 %) |
| PARCIALMENTE CUBIERTAS | **11** (14 %) |
| NO CUBIERTAS | **17** (22 %) |
| Puntos ciegos reales (preguntas) | **21** (agrupados en **20 objetivos propuestos**) |
| Exclusiones justificadas | **7** (4 ya documentadas en el catálogo, 3 sin documentar) |
| Preguntas CRÍTICAS plenamente cubiertas | **24 de 34 (71 %)**; sumando parciales, 31 de 34 (91 %) |

Lectura honesta: el catálogo cubre bien el *estado* de cada área (colas, carteras, existencias,
bandejas — ahí la cobertura de críticas es casi total) y los grandes agregados por período. Donde
más se le escapan preguntas de jefe es en (1) **tiempos de ciclo internos** (cuánto tarda un
pedido por etapa, la primera respuesta de soporte), (2) **la dimensión producto en la posventa**
(reclamos y devoluciones por producto), (3) **metas y patrones de calendario** (venta contra meta,
venta por día/hora) y (4) **vistas en dinero de fenómenos que ya se miden en conteos**
(devoluciones como % de la venta, descuento total entregado). Ninguno de esos huecos es
estructuralmente grave: 14 de los 20 objetivos propuestos son factibles hoy sin tocar el sistema.

---

## 2. Tabla de cruce completa

Prioridades: C = CRÍTICA, U = ÚTIL, D = DESEABLE. Veredicto solo aplica a PARCIAL / NO CUBIERTA:
**PC** = punto ciego real (ver §3), **EX** = exclusión justificada (ver §4).

### Ventas

| # | Pregunta (resumen) | Prio | Cobertura | ID objetivo | Veredicto |
|---|---|---|---|---|---|
| 1 | Venta del mes vs mes anterior y mismo mes del año pasado | C | CUBIERTA | OTD-VEN-06 | — |
| 2 | Productos más vendidos y los que no se venden | C | CUBIERTA | OTD-VEN-03, VEN-04 | — |
| 3 | Por qué canal me compran más y cuánto deja cada canal | C | PARCIAL | OTD-VEN-07 | PC-01 — VEN-07 da el valor promedio por canal, pero no el total vendido ni la participación de cada canal en la venta |
| 4 | Mejores clientes por monto y frecuencia | C | CUBIERTA | OTD-VEN-05 | — |
| 5 | Pedidos atorados a medio camino y desde cuándo | C | CUBIERTA | OTD-VEN-01 | — |
| 6 | Compra promedio por pedido por canal y su tendencia | U | CUBIERTA | OTD-VEN-07 | — |
| 7 | Venta por vendedor del mostrador/teléfono | U | CUBIERTA | OTD-VEN-02 | — |
| 8 | Días de la semana y horas de más venta | U | NO CUBIERTA | — | PC-02 |
| 9 | Clientes nuevos vs recurrentes por mes | U | NO CUBIERTA | — | PC-03 |
| 10 | Dinero devuelto al mes y % de la venta | C | PARCIAL | OTD-LOG-09, LOG-10 | PC-04 — LOG-09 mide la tasa en conteo de envíos y LOG-10 el reembolso pagado; falta el valor devuelto como porcentaje de la venta del período |
| 11 | Productos que se compran juntos | D | NO CUBIERTA | — | EX-1 |
| 12 | Venta perdida por productos agotados | D | NO CUBIERTA | — | PC-05 |
| 13 | Margen real por producto y por categoría | C | PARCIAL | OTD-GER-03 | PC-06 — GER-03 llega solo a categoría; el nivel producto/variante queda fuera |
| 14 | Carritos abandonados | U | CUBIERTA | OTD-VEN-08 | — |
| 15 | Venta acumulada contra la meta del mes | C | NO CUBIERTA | — | PC-07 |

### Compras

| # | Pregunta (resumen) | Prio | Cobertura | ID objetivo | Veredicto |
|---|---|---|---|---|---|
| 16 | Deuda por proveedor y cuotas por vencer | C | CUBIERTA | OTD-COM-02 | — |
| 17 | Compras por proveedor en el año | C | CUBIERTA | OTD-COM-04 | — |
| 18 | Proveedores que entregan tarde o incompleto | C | PARCIAL | OTD-COM-05, COM-07 | PC-08 — «tarde» lo cubre COM-05 y el rechazo en puerta COM-07, pero «incompleto» (cantidad recibida menor a la pedida; hay 6 líneas reales así) no lo mide nadie |
| 19 | Días de ciclo de compra por proveedor | U | CUBIERTA | OTD-COM-06 | — |
| 20 | Órdenes aprobadas sin llegar y su antigüedad | C | CUBIERTA | OTD-COM-01 | — |
| 21 | ¿Me está subiendo el costo de lo que compro? | U | NO CUBIERTA | — | PC-09 |
| 22 | Mercancía rechazada/devuelta a proveedores | U | CUBIERTA | OTD-COM-07, COM-08, COM-09 | — |
| 23 | Mes que más pagué a proveedores vs venta de ese mes | U | CUBIERTA | OTD-GER-02 | — |
| 24 | Qué reponer ya considerando la demora del proveedor | C | CUBIERTA | OTD-INV-01 + COM-10 | — (COM-10 está en cierre de brechas, pero el catálogo ya reclama la necesidad) |
| 25 | Dinero comprometido en órdenes aún no facturadas | U | CUBIERTA | OTD-COM-01 | — |
| 26 | Notas de crédito/reposiciones pendientes de proveedores | D | CUBIERTA | OTD-COM-08, COM-09 | — |
| 27 | Órdenes esperando aprobación y desde cuándo | U | CUBIERTA | OTD-COM-01 | — |

### Inventario / Bodega

| # | Pregunta (resumen) | Prio | Cobertura | ID objetivo | Veredicto |
|---|---|---|---|---|---|
| 28 | Stock por producto y bodega | C | CUBIERTA | OTD-INV-02 | — |
| 29 | Productos bajo el mínimo | C | CUBIERTA | OTD-INV-01 | — |
| 30 | Valor total del inventario por bodega/categoría | C | CUBIERTA | OTD-INV-07 | — |
| 31 | Productos sin movimiento — dinero dormido | C | CUBIERTA | OTD-VEN-04 + INV-04 + INV-07 | — |
| 32 | Sobrantes/faltantes de ajustes, bodega y autor | C | CUBIERTA | OTD-INV-05, INV-10 (+GER-08 autor) | — |
| 33 | Historia completa de movimientos de un producto | C | CUBIERTA | OTD-INV-03 | — |
| 34 | Rotación / días para vender lo que tengo | U | CUBIERTA | OTD-INV-04 | — |
| 35 | Transferencias entre bodegas: cuántas al mes y cuánto tardan | U | PARCIAL | OTD-INV-06 | PC-10 — INV-06 es la foto por estado con sus fechas; la vista agregada (volumen y duración por mes) no existe |
| 36 | Mercancía defectuosa acumulada pendiente con proveedor | U | CUBIERTA | OTD-COM-08 | — |
| 37 | Qué bodega despacha más / reparto de carga | D | NO CUBIERTA | — | PC-11 |
| 38 | Productos por vencer / lotes a sacar primero | D | NO CUBIERTA | — | EX-2 (documentada) |
| 39 | Cuánto de lo devuelto reingresa y cuánto es merma | U | CUBIERTA | OTD-LOG-08 (+INV-03 kardex) | — |

### Logística / Despacho

| # | Pregunta (resumen) | Prio | Cobertura | ID objetivo | Veredicto |
|---|---|---|---|---|---|
| 40 | Cola de pedidos por despachar y su antigüedad | C | CUBIERTA | OTD-LOG-01 | — |
| 41 | Tiempo de pago a entrega, por etapa (preparar/despachar/entregar) | C | NO CUBIERTA | — | PC-12 |
| 42 | Qué transportista falla más y cuál cumple | C | CUBIERTA | OTD-LOG-03, LOG-05 | — |
| 43 | Entregas fallidas del mes y por qué motivo | C | CUBIERTA | OTD-LOG-05 | — |
| 44 | Cobro de envío al cliente vs costo real | D | CUBIERTA | OTD-LOG-11 | — (en cierre de brechas, pero reclamada) |
| 45 | A qué ciudades y zonas mando más | U | NO CUBIERTA | — | PC-13 |
| 46 | Entregas dentro del tiempo prometido | U | CUBIERTA | OTD-LOG-03 | — |
| 47 | Pedidos devueltos al almacén: qué pasó después con mercancía y cliente | U | PARCIAL | OTD-LOG-05 | PC-14 — la novedad registra el desenlace «devuelto al almacén», pero el después (reembolso, destino de la mercancía) no lo mide nadie — y el propio flujo es deuda declarada (Fase 6) |
| 48 | Intentos promedio por entrega | D | CUBIERTA | OTD-LOG-05 | — |
| 49 | Devoluciones de clientes en camino a bodega | U | CUBIERTA | OTD-LOG-06 | — |
| 50 | Despachos por día de la semana | D | NO CUBIERTA | — | PC-02 (mismo objetivo propuesto que la pregunta 8) |

### Soporte

| # | Pregunta (resumen) | Prio | Cobertura | ID objetivo | Veredicto |
|---|---|---|---|---|---|
| 51 | Reclamos abiertos y sin asignar | C | CUBIERTA | OTD-SOP-01 | — |
| 52 | De qué se quejan más los clientes | C | CUBIERTA | OTD-SOP-04 | — |
| 53 | Tiempo de primera respuesta y de resolución | C | PARCIAL | OTD-SOP-03 | PC-15 — la resolución está; el tiempo hasta la PRIMERA respuesta al cliente no lo mide ningún objetivo |
| 54 | Reclamos pasados del plazo prometido | C | CUBIERTA | OTD-SOP-02 | — (en cierre de brechas, pero reclamada) |
| 55 | Reclamos resueltos que el cliente volvió a abrir | U | NO CUBIERTA | — | PC-16 |
| 56 | Casos y tiempos de resolución por persona del equipo | U | PARCIAL | OTD-SOP-05 | PC-17 — SOP-05 cubre carga y cierres por agente; el TIEMPO de resolución por agente queda fuera (SOP-03 solo lo abre por categoría) |
| 57 | Qué productos generan más reclamos y devoluciones | C | NO CUBIERTA | — | PC-18 |
| 58 | Motivos de devolución y % aprobada/rechazada/reembolsada | U | CUBIERTA | OTD-LOG-08 (+LOG-06 estados) | — |
| 59 | Dinero reembolsado al mes | U | CUBIERTA | OTD-LOG-10 | — |
| 60 | Clientes que reclaman una y otra vez | D | NO CUBIERTA | — | PC-19 |
| 61 | Preguntas de producto sin responder | D | CUBIERTA | OTD-VEN-10 | — |

### Gerencia / Dirección (incluye Marketing)

| # | Pregunta (resumen) | Prio | Cobertura | ID objetivo | Veredicto |
|---|---|---|---|---|---|
| 62 | Foto del día: vendí, cobré, debo, valor de inventario | C | CUBIERTA | OTD-GER-01 + COM-02 + INV-07 | — |
| 63 | ¿Gano o pierdo?: venta menos costo de mercancía, mes a mes | C | CUBIERTA | OTD-GER-03 | — |
| 64 | Tendencia de venta 12 meses, meses fuertes y flojos | C | CUBIERTA | OTD-VEN-06 | — |
| 65 | Equilibrio compro vs vendo vs lo que queda en bodega | U | CUBIERTA | OTD-GER-02 + INV-09 | — |
| 66 | Dinero que entra vs dinero que sale, por mes | C | CUBIERTA | OTD-GER-02 | — |
| 67 | ¿Los cupones traen venta nueva o regalan margen? | C | PARCIAL | OTD-GER-05 | EX-3 — GER-05 mide qué cupones se usan y cuánto cuestan; saber si la venta era «incremental» exige comparar contra quien no recibió el cupón (experimentación), fuera del reporting táctico |
| 68 | Descuento total entregado al mes (promos + cupones) y sobre qué productos | C | PARCIAL | OTD-GER-05, GER-07 | PC-20 — el costo de cupones está (GER-05) y el efecto de promos en venta también (GER-07), pero la vista consolidada del descuento total entregado por período y producto no existe |
| 69 | Qué campañas trajeron ventas y cuáles fueron dinero tirado | U | NO CUBIERTA | — | EX-4 (documentada) |
| 70 | ¿Los anuncios de la tienda generan clics y compras? | D | NO CUBIERTA | — | EX-5 (documentada) |
| 71 | Suscriptores del boletín y su crecimiento | D | PARCIAL | OTD-GER-06 | EX-6 (documentada) — GER-06 solo muestra la vigencia de las acciones; la evolución de la lista quedó excluida con razón (1 suscriptor) |
| 72 | Clientes que compraron una vez y no volvieron | U | CUBIERTA | OTD-VEN-05 | — (filtro directo sobre su tabla de frecuencia y última compra) |
| 73 | Cuánto deja un cliente en toda su relación con la tienda | D | CUBIERTA | OTD-VEN-05 | — |
| 74 | Productos mal calificados en reseñas | U | CUBIERTA | OTD-VEN-11 | — |
| 75 | De las visitas a la tienda, cuántas terminan en compra y dónde se caen | U | NO CUBIERTA | — | EX-7 |
| 76 | Personal entrando fuera de horario o actividad inusual | D | CUBIERTA | OTD-GER-08, GER-09 | — |
| 77 | Categorías que crecen y categorías que caen | U | CUBIERTA | OTD-VEN-06 | — |
| 78 | Números al día para un crédito bancario | D | CUBIERTA | OTD-GER-02 + COM-02 + INV-07 + VEN-06 | — |

---

## 3. PUNTOS CIEGOS REALES

21 preguntas legítimas sin objetivo que las responda por completo, agrupadas en **20 objetivos
propuestos** (las preguntas 8 y 50 comparten uno). Catorce son factibles hoy con los datos que ya
existen; seis exigen tocar el sistema. Clasificación S/C con la misma regla del catálogo (SIMPLE =
foto actual; COMPUESTO = histórico/comparación de períodos).

| ID | Pregunta(s) | Objetivo táctico propuesto | S/C | Qué haría falta en el sistema |
|---|---|---|---|---|
| PC-01 | 3 | Venta total y participación de cada canal (mostrador, teléfono, línea) por período | C | Nada — `pedido.canal` y `pedido.total` ya están poblados; es una extensión natural de OTD-VEN-07 |
| PC-02 | 8, 50 | Patrón de ventas y despachos por día de la semana y franja horaria | C | Nada — `pedido.fecha_pedido` y `envio.fecha_despacho` guardan la hora (timestamptz, verificado) |
| PC-03 | 9 | Clientes nuevos vs recurrentes por mes | C | Nada de sistema — cruce de primera compra por cliente; hoy limita el volumen (2 clientes demo) |
| PC-04 | 10 | Valor devuelto por clientes como porcentaje de la venta del período | C | Nada — `devolucion.monto_total` (trigger) contra la venta mensual; respetar que Bodega/Despacho no ven montos |
| PC-05 | 12 | Venta perdida por falta de existencias | C | CAMBIO en tres capas: hoy nada persiste el intento de compra sin stock en PostgreSQL (`reserva_stock` 0 filas); habría que registrar el evento (BD), escribirlo desde el catálogo/carrito al chocar con stock 0 (backend) y no exige formulario (registro automático) |
| PC-06 | 13 | Margen (precio − costo) a nivel producto/variante, además de categoría | C | Nada — `producto_variante.precio` y `.costo` poblados en las 1221 variantes; heredaría la salvedad documentada del costo vigente sin histórico |
| PC-07 | 15 | Cumplimiento de la meta de venta del período | C | CAMBIO en tres capas: no existe ninguna tabla de metas/presupuesto (verificado); crear tabla de metas por período (BD), su CRUD (backend) y un formulario de captura para Gerencia (UI) |
| PC-08 | 18 | Entregas incompletas por proveedor: cantidad pedida vs recibida por línea | C | Nada — `orden_compra_detalle.cantidad`/`cantidad_recibida` ya registran el fenómeno (6 líneas reales con recepción parcial, verificado) |
| PC-09 | 21 | Evolución del costo de compra por producto (¿me venden más caro que antes?) | C | Nada — cada línea de orden/factura de compra guarda su precio a esa fecha (11 precios distintos verificados); distinto de la exclusión «costo histórico de producto_variante», que es de valoración |
| PC-10 | 35 | Volumen y duración de transferencias entre bodegas por mes | C | Nada — `transferencia_bodega.fecha_envio/fecha_recepcion` pobladas; hoy limita el volumen (10 transferencias) |
| PC-11 | 37 | Carga de despacho por bodega | C | Nada — `envio.bodega_id` existe; con 2 bodegas hoy es poco discriminante (requiere volumen) |
| PC-12 | 41 | Tiempo del ciclo del pedido por etapa: pago → preparación → despacho → entrega | C | Nada — `historial_estado_pedido` (177 filas, los 34 pedidos cubiertos, verificado) permite medir cada tramo; es el punto ciego de mayor valor: el inventario de datos ya lo listaba como métrica temporal verificada y ningún objetivo lo reclamó |
| PC-13 | 45 | Pedidos y envíos por ciudad/zona de destino | C | Nada de esquema — dirección→zona ya existe para asignar transportista; hoy limita la cobertura geográfica real (2 ciudades, 4 direcciones) |
| PC-14 | 47 | Desenlace de los pedidos no entregados: reembolso al cliente y destino de la mercancía | S | CAMBIO: el flujo mismo es deuda declarada (Fase 6 en `DEUDA_TECNICA.md` — reembolso/reingreso por soporte); el objetivo sería el tablero de pedidos 'no_entregado' pendientes de resolución, que hoy quedan en estado terminal sin rastro de cierre |
| PC-15 | 53 | Tiempo hasta la primera respuesta de soporte al cliente | C | Nada — `mensaje_ticket` tiene autor y fecha (verificado); primer mensaje de staff vs creación del ticket; hoy limita el volumen |
| PC-16 | 55 | Reclamos reabiertos tras darse por resueltos | C | CAMBIO: no existe historial de estados del ticket (verificado: ninguna tabla, y la reapertura sobreescribe el estado sin rastro); crear historial de ticket (BD+backend, ya anotado como «trazabilidad futura» en la deuda del proyecto), sin formulario (registro automático) |
| PC-17 | 56 | Tiempo de resolución por persona del equipo de soporte | C | Nada — extensión de OTD-SOP-03 agregando la dimensión `asignado_usuario_id` |
| PC-18 | 57 | Reclamos y devoluciones por producto | C | Mitad y mitad: devoluciones por producto son factibles hoy (`devolucion_detalle`→variante); ligar tickets a un producto exige CAMBIO (verificado: `ticket_soporte` no referencia producto) — columna opcional producto en el ticket (BD), escribirla al crear (backend) y selector opcional en el formulario del ticket (UI) |
| PC-19 | 60 | Clientes con reclamos repetidos | S | Nada — `ticket_soporte.cliente_id` existe (verificado); conteo sobre la bandeja actual; hoy limita el volumen (2 clientes) |
| PC-20 | 68 | Descuento total entregado por período y producto (promociones + cupones consolidados) | C | Nada — `pedido_detalle.monto_descuento` (promos), `pedido.monto_descuento`/`uso_cupon.monto_descontado` (cupones) y el prorrateo en factura ya existen; falta solo el objetivo que los consolide |

## 4. EXCLUSIONES JUSTIFICADAS

| ID | Pregunta | Motivo de la exclusión | ¿El catálogo la documenta? |
|---|---|---|---|
| EX-1 | 11 — productos que se compran juntos | La señal de afinidad/canasta es terreno del módulo de recomendaciones (analítica ClickHouse), fuera del alcance del catálogo departamental; además, con 43 líneas de pedido el análisis no sería representativo | **Parcialmente** — la sección de alcance excluye wishlist/comparador y remite la señal de demanda a recomendaciones, pero no menciona explícitamente el análisis de canasta. Conviene añadir una línea |
| EX-2 | 38 — vencimientos y lotes | Trazabilidad por lote/vencimiento pospuesta deliberadamente (decisión de `ROADMAP.md`, `lote` en 0 filas, FK listas para esa fase) | **Sí** — primera viñeta de Decisiones de alcance |
| EX-3 | 67 — ¿el cupón trae venta nueva o regala margen? | Medir incrementalidad exige grupo de control o experimentación (comparar contra clientes sin cupón), más allá del reporting táctico; GER-05 entrega el insumo honesto disponible: uso y costo del cupón | **No** — la sección de alcance no dice nada de incrementalidad. Conviene documentarlo para que la limitación quede explícita ante Gerencia |
| EX-4 | 69 — efectividad de campañas | Ni `campana` ni `banner` ni `promocion` tienen columnas de desempeño en PostgreSQL; ese dato vive (si existe) en ClickHouse/analytics, prohibido para el catálogo | **Sí** — viñeta «Efectividad de campañas y banners» de Decisiones de alcance |
| EX-5 | 70 — clics/compras de banners | Mismo motivo que EX-4 | **Sí** — misma viñeta |
| EX-6 | 71 — crecimiento del boletín | 1 suscriptor; la gestión existe pero no hay lista que dirigir; la vigencia queda representada en OTD-GER-06 | **Sí** — viñeta «Boletín de correos» |
| EX-7 | 75 — embudo de visitas a compra | El embudo se calcula sobre eventos de navegación que viven en ClickHouse (el dashboard analítico del sistema ya tiene un funnel); es analítica, no reporting táctico departamental sobre la base transaccional | **Parcialmente** — el catálogo excluye la analítica de campañas/banners pero no menciona el embudo de conversión como caso; conviene una línea que remita al dashboard analítico existente |

## 5. COBERTURA POR PRIORIDAD

Porcentaje de preguntas **CRÍTICAS** de cada departamento plenamente cubiertas (PARCIAL no cuenta
como cubierta; se muestra aparte):

| Departamento | Críticas | Cubiertas | Parciales | No cubiertas | % pleno | % con parciales |
|---|---|---|---|---|---|---|
| Ventas | 8 | 4 | 3 (#3, #10, #13) | 1 (#15 meta de venta) | 50 % | 88 % |
| Compras | 5 | 4 | 1 (#18 incompletos) | 0 | 80 % | 100 % |
| Inventario / Bodega | 6 | 6 | 0 | 0 | **100 %** | 100 % |
| Logística / Despacho | 4 | 3 | 0 | 1 (#41 ciclo por etapa) | 75 % | 75 % |
| Soporte | 5 | 3 | 1 (#53 primera respuesta) | 1 (#57 por producto) | 60 % | 80 % |
| Gerencia / Dirección | 6 | 4 | 2 (#67, #68) | 0 | 67 % | 100 % |
| **Total** | **34** | **24** | **7** | **3** | **71 %** | **91 %** |

Las tres críticas totalmente descubiertas — venta contra meta (15), tiempo de ciclo del pedido por
etapa (41) y reclamos/devoluciones por producto (57) — son los candidatos más fuertes a entrar al
catálogo: dos de ellas (41 y la mitad de la 57) son factibles hoy sin tocar el sistema.

Por prioridad global: CRÍTICAS 24/34 cubiertas (71 %) · ÚTILES 19/29 (66 %) · DESEABLES 7/15
(47 %). Que la cobertura caiga con la prioridad es la forma correcta del resultado: el catálogo
concentró su esfuerzo donde los jefes no pueden dirigir sin respuesta.
