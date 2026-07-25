# RetailMind — contexto para Claude Code

Tienda PyME con back-office completo. **PostgreSQL (BD `retailmind`, ~103 tablas) es la ÚNICA
base transaccional** — incluida la TIENDA DEL CLIENTE (catálogo `/api/catalogo` con ~1.214
productos reales cargados del dataset original vía ETL puntual, carrito, wishlist,
perfil/direcciones, checkout y mis pedidos, migrados 2026-07-11). Con Docker apagado TODO el
sistema funciona. ClickHouse es **solo analítica** (paquete `analytics/` + señal de eventos para recomendaciones): con ClickHouse
apagado TODO el sistema funciona; solo analytics/recomendaciones se degradan con aviso. Si algún
documento viejo dice "PostgreSQL eliminado" o describe la tienda sobre ClickHouse, está
desactualizado: ignóralo.

## Stack

- **Backend** `retailmind-backend/`: Spring Boot 3.5.0, Java 17, Maven, `JdbcTemplate` (sin JPA).
  Dual datasource: `pgJdbcTemplate` (cualificado, `DataSourceTransactionManager` **@Primary**) +
  ClickHouse para analytics. Spring Security JWT (login = email en el campo `username`).
  iText 5 (PDF de facturas) + Apache POI (Excel).
- **Frontend** `retailmind-frontend/`: Angular 17 standalone, diseño "Dubai", Angular Material.
  Pantallas operativas en `features/operativo/` (incluye `marketing/`); estilos compartidos
  `operativo-shared.scss`; errores con `core/services/api-error.util.ts`.
- **ETL** `retailmind/`: Python 3.12, PocketBase → Parquet → ClickHouse. El DDL operativo vigente
  de PostgreSQL está en `retailmind/sql/postgres/` (scripts numerados 01-45 + 99).
- **Tienda del cliente** (solo rol CLIENTE, guard + SecurityConfig): backend en paquetes
  `catalogo/`, `carrito/`, `wishlist/`, `perfil/`, `recomendaciones/` contra `pgJdbcTemplate`;
  el id público de producto es el de la VARIANTE. El checkout llama a `VentasService.crearPedido`
  (mismo pedido que el back-office, stock vía `StockService`). El script
  `34_grants_tienda_cliente.sql` da a `grp_cliente` lo que el checkout necesita (inventario,
  movimiento_inventario, tipo_movimiento, bodega, historial_estado_pedido + políticas RLS de
  horario). Eventos a ClickHouse solo best-effort (`EventoTiendaService`).

## Seguridad a nivel de BD (lo más importante)

- 9 roles de grupo en PostgreSQL: `grp_administrador, grp_gerente, grp_vendedor, grp_compras,
  grp_bodega, grp_despacho, grp_cliente, grp_analista, grp_soporte` — con matriz de privilegios
  GRANT, **RLS** (cliente aislado vía `app.cliente_id`) y **restricción por horario**
  (`grupo_horario` + `esta_en_horario()` + triggers). Admin exento de horario; soporte 24/7.
  OJO al crear un rol nuevo: además de los GRANTs necesita `GRANT USAGE ON SCHEMA public`
  (el script 19 lo revocó a PUBLIC) y política RLS propia en cada tabla con RLS (las
  pol_horario enumeran los grupos). Patrón completo en `37_rol_soporte.sql`.
- La app conecta como `retailmind_app` (LOGIN **NOINHERIT**, sin privilegios de negocio) y asume
  el rol del usuario **por transacción** con `SET LOCAL ROLE` (aspecto
  `security/PgSessionRoleAspect`, excluye `analytics/`).
- Consecuencia: **TODO acceso a Postgres debe ir dentro de `@Transactional`** — si no, corre sin
  privilegios y falla o (peor) se salta la seguridad de motor.
- La BD devuelve SQLState 42501 por privilegio/horario; `GlobalExceptionHandler` lo traduce a 403.

## Reglas de oro

1. **Nunca** escribir columnas GENERATED ni totales de cabecera (los ponen triggers). Tampoco
   `fecha_actualizacion` (trigger touch) ni contadores como `usos_actuales`.
2. Validación por **lista blanca** + parámetros JdbcTemplate; nunca concatenar SQL.
3. Guardias de estado/idempotencia con mensaje claro: `IllegalArgumentException` → 400,
   `IllegalStateException` → 409, `NoSuchElementException` → 404 (vía `GlobalExceptionHandler`).
4. Errores al usuario siempre con `mensajeError()` de `api-error.util.ts`.
5. Pantalla CRUD nueva = imitar `features/operativo/` (tabla Material + formulario colapsable +
   toggle activo, ej. `productos-admin` o `marketing/cupones`). Servicio Angular de ~15 líneas
   con `environment.apiUrl`.
6. Ruta nueva = `SecurityConfig` (backend) + `roleGuard([...])` en `app.routes.ts` + entrada en
   sidebar (`app.component.html`, getters `canX`) + `routeMap` de breadcrumbs (`app.component.ts`).
7. No modificar tablas existentes ni sus triggers; no tocar `analytics/` ni ClickHouse desde lo
   operativo.
8. Parámetros null hacia PG en contexto no tipado: castear en SQL (`?::bigint`, `NULLIF(?,'')::timestamptz`).

## Cómo correr

```bash
# Backend (puerto 8080; si está ocupado por otra app usar 8081 y detenerlo al terminar)
cd retailmind-backend && mvn spring-boot:run

# Frontend (puerto 4200)
cd retailmind-frontend && npm start

# Verificación mínima antes de dar por bueno un cambio
mvn compile   &&   ng build
```

PostgreSQL corre **local** (`localhost:5432/retailmind`); ClickHouse/PocketBase van por
docker-compose (5 servicios: pocketbase, clickhouse, backend, frontend, etl). Para inspeccionar el
esquema usa el MCP `retailmind` (solo lectura) o psycopg2 (`postgres/1250143656@localhost:5432`).

## Credenciales de desarrollo

- Admin: `admin@retailmind.com` / `Admin2026!`
- Resto de roles (`gerente@`, `vendedor@`, `compras@`, `bodega@`, `despacho@`,
  `analista@retailmind.com`): `Retail2026!` (script 27); `soporte@retailmind.com`:
  `Retail2026!` (script 37)
- Clientes demo (`maria.lopez@demo.com`, `carlos.vera@demo.com`): `Cliente2026!` (script 26)

## Qué está hecho / qué falta

**Hecho**: catálogo maestro; ciclo de compra con compuertas ENFORZADAS en backend
(orden→aprobación de GERENTE/ADMIN→recepción completa→factura→pago; sin aprobar no se recibe ni
factura, sin recibir completo no se factura, sin factura no hay CxP ni pago); inventario
(transferencias, ajustes, kardex); ciclo de venta completo con compuertas (pedido confirmado→
PAGO del cliente [tabla pago+transaccion_pago, abonos parciales]→pagado→facturado→
en_preparacion→preparado→despachado→entregado→devolución solo tras entrega) con PDF, listado
de facturas de venta con búsqueda/paginación, timeline (historial_estado_pedido) y acciones
encadenadas en el detalle del pedido; **TRAMO DE SALIDA robustecido (2026-07-16, script 39)**:
estados nuevos `facturado` y `preparado`; la factura del pedido ONLINE se emite AUTOMÁTICAMENTE
al pagar el checkout (misma transacción, bajo grp_cliente: INSERT + pol_cliente_emision +
`fn_recalcular_total_factura_venta` SECURITY DEFINER) y el cliente la ve/descarga en la
confirmación y en Mis Pedidos; la factura de pedidos INTERNOS sigue MANUAL
(POST /pedidos/{id}/factura = ADMIN/VENDEDOR; en ambos casos exige estado 'pagado' exacto y
deja el pedido 'facturado'); transportista ASIGNADO AUTOMÁTICAMENTE por zona al crear el pedido
(`pedido.transportista_id` + metodo_envio_id; dirección→zona_envio por ciudad>provincia>país→
tarifa activa más barata→metodo_envio.transportista_id; seeds Quevedo local/Los Ríos/Ecuador,
el cliente solo lo VE con su tiempo estimado); BODEGA prepara en
`/operativo/ventas/preparacion` (cola GET /api/ventas/preparacion = pedidos
facturado/en_preparacion con detalle de picking dedicado — NO usa obtenerPedido porque bodega
no lee pago — y transiciones POST /pedidos/{id}/preparacion y /preparado con guardias);
DESPACHO solo despacha pedidos 'preparado' (detalle GET /api/ventas/despacho/{id} con ítems,
cliente, dirección y transportista asignado; en el POST transportista/método son OPCIONALES:
por defecto van los asignados y mandarlos distintos es un override que queda registrado en
historial y seguimiento, actualizando el pedido);
el checkout del cliente entra al MISMO flujo y el cliente ve estado/guía/seguimiento/factura PDF
en Mis Pedidos (RLS, script 35); **checkout ONLINE completo tipo Amazon (2026-07-15, script 36)**:
`/shop/checkout` con dirección de envío (o alta inline), campo de cupón (solo UI; la validación
llega con la fase de descuentos — enganche en `CarritoService.checkout`), método de pago
tarjeta/transferencia SIMULADO (validación de formato + Luhn + MM/AA + CVV; se persiste SOLO
marca + últimos 4 en `pago.referencia_externa` y `transaccion_pago.respuesta_pasarela`, NUNCA
PAN/CVV) — el pedido online nace **PAGADO** en una sola transacción (`pagarCheckoutOnline`).
`pedido.canal` discrimina origen: 'web' = online (el back-office NO muestra "registrar pago";
`registrarPago` lo rechaza con 409) vs 'tienda'/'telefono' = interno (cobro manual intacto;
POST /api/ventas/pedidos rechaza canal 'web');
horarios de acceso; marketing (cupones, promociones+productos, campañas, banners, newsletter —
solo gestión); tienda online 100% PostgreSQL (catálogo real con búsqueda con debounce, filtro por
marca/categoría y paginación server-side, carrito,
wishlist, checkout → pedido del ciclo de venta, perfil + CRUD de direcciones, un solo
"Mis Pedidos" en `/operativo/ventas/mis-pedidos`; recomendaciones con señal ClickHouse y
productos PG, degradan a destacados); analítica
ClickHouse (dashboard, funnel, sesiones, región/dispositivo/tráfico, reportes); **soporte
profesional con rol SOPORTE (2026-07-15, script 37)**: usuario `soporte@retailmind.com` /
`Retail2026!`, bandeja con filtros (sin asignar/míos, estado, categoría, prioridad), 7 categorías
reales con `prioridad_defecto` → prioridad AUTOMÁTICA al crear (el cliente no la elige;
solo SOPORTE/ADMIN la cambia con PATCH /prioridad), número `TICK-AAAA-NNNN`, SLA calculado
(urgente 2h/alta 4h/media 24h/baja 72h, indicador vence en/VENCIDO), tomar ticket
(auto-asignación, abierto→en_proceso), transiciones con guardias, reapertura si el cliente
responde un 'resuelto' (grant de columna UPDATE(estado) a grp_cliente), RLS de cliente y
"Equipo de soporte" como autor anónimo; notas de pedido (`nota_pedido`, bitácora con nota
interna vs. visible al cliente); anulación de ajuste de inventario por contramovimiento de
kardex; **RMA / logística inversa (2026-07-16, script 38, paquete `devoluciones/`)**: la
devolución NACE DEL CLIENTE en Mis Pedidos (pedido entregado/devuelto con plazo de 30 días
desde la entrega — constante `PLAZO_DIAS_DEVOLUCION` — o despachado = rechazo en puerta) y
crea/engancha un ticket categoría "Devolución" (`devolucion.ticket_soporte_id`); ciclo
solicitada→en_revision→aprobada|rechazada(terminal)→en_transito→recibida→inspeccionada→
reembolsada→cerrada con UN rol por transición (SOPORTE valida y genera guía de retorno
RET-… + transportista + bodega, PDF vía DocumentoPdfService; DESPACHO tránsito/recepción;
BODEGA inspección POR ÍTEM: solo `apto_reventa` reingresa stock vía StockService con kardex
`entrada_devolucion_cliente` y el pedido pasa a 'devuelto' — `defectuoso` = merma pendiente
proveedor, `rechazado` = sin reembolso; GERENTE/ADMIN reembolso SIMULADO apto+defectuoso;
SOPORTE cierra y el ticket queda resuelto), historial en `historial_estado_devolucion`
(autor usuario O cliente), RLS pol_cliente_propio/pol_soporte/pol_horario en las 3 tablas,
`devolucion.monto_total` lo mantiene el trigger `fn_recalcular_total_devolucion`
(SECURITY DEFINER, NUNCA escribirlo) y el endpoint viejo de devolución en un paso se
eliminó (`/api/devoluciones`, tablero multi-rol en `/operativo/ventas/devoluciones`).
**DESCUENTOS REALES (2026-07-17, script 40, `marketing/DescuentosService`)**: promociones
vigentes se aplican AUTOMÁTICAMENTE por línea en `crearPedido`
(`pedido_detalle.monto_descuento`, IVA sobre la base rebajada; gana la de mayor prioridad y
solo las `acumulable` se suman; historial las registra) y el CUPÓN del checkout online se
valida/recalcula SIEMPRE en backend (existe+activo, vigencia, `usos_maximos` vía
`usos_actuales`, `usos_por_cliente` contra `uso_cupon`, monto mínimo sobre el subtotal neto
de promos sin IVA; el front solo envía el CÓDIGO) y se aplica en `pedido.monto_descuento`
— los triggers de total ya restaban ambas capas, CERO columnas nuevas — registrando
`uso_cupon` en la misma transacción; trigger `fn_registrar_uso_cupon` SECURITY DEFINER
(lock FOR UPDATE del cupón) mantiene `usos_actuales` y es el backstop de concurrencia de
límites; un solo cupón por pedido (UNIQUE `uso_cupon.pedido_id`); promoción + cupón SÍ
combinan (promo a la línea, cupón sobre el subtotal ya rebajado); la factura PRORRATEA el
cupón entre líneas (`factura_venta_detalle.monto_descuento`, ajuste de redondeo en la
última) porque su trigger recalcula solo desde el detalle, y el PDF muestra
Subtotal/Descuento/IVA/Total + meta "Cupón"; endpoint POST `/api/carrito/cupon/validar`
(motivos claros), carrito/checkout muestran precio promocional tachado y desglose, y el
back-office/Mis Pedidos muestran cupón y descuentos.
**RESEÑAS con compra verificada (script 32 + fase de pulido 2026-07-17)**: módulo completo
en `resenas/` (reseñas, votos de utilidad, reportes de abuso, preguntas/respuestas;
moderación ADMIN/GERENTE en `/operativo/resenas`); crear reseña EXIGE compra
(pedido pagado→entregado, incluye 'devuelto', del propio cliente con ese producto;
si no → 409 "Solo puedes reseñar productos que has comprado"); el selector del
formulario ofrece SOLO productos comprados (GET `/api/resenas/productos-comprados`,
CLIENTE) y Mis Pedidos tiene botón "Reseñar" por ítem que navega con
`?productoId=` preseleccionado.
**SEGREGACIÓN FINANCIERA (2026-07-17, script 41)**: BODEGA y DESPACHO no leen montos —
grants POR COLUMNA sin dinero sobre pedido/pedido_detalle/factura_venta/factura_compra/
orden_compra/orden_compra_detalle/devolucion y `pago` revocado a despacho; consultas
role-aware en `VentasService` (colaPreparacion/detalleLogistico/listarPedidos/entregar),
`ComprasService` (listarOrdenes/obtenerOrden) y `DevolucionService` (listar/obtener);
UI sin columnas de monto para esos roles y BODEGA fuera de Facturas de Compra
(ruta + nav `facturasCompra`). EXCEPCIÓN documentada: grp_bodega conserva SELECT de
`precio_unitario` en los DETALLES porque valoriza el kardex bajo su rol (recepción y
reingreso RMA); la UI no lo muestra. OJO: `entregar` devuelve respuesta ligera para
DESPACHO (sin pagos) y el pedido completo para el resto.
**TRAZABILIDAD DE AUTOR (2026-07-17, script 42, `auditoria/AuditoriaService`)**: columnas
directas de autor (FK a usuario, del JWT SIEMPRE) — `pedido.vendedor_id` (NULL si canal
'web': el autor del checkout es el CLIENTE, trazado por cliente_id+historial),
`envio.despachado_por`, `factura_compra.registrado_por`, `resena.moderado_por` +
`fecha_moderacion` y `pregunta_producto.moderado_por`+`fecha_moderacion`
(`respuesta_pregunta` ya tenía `usuario_id`); `AuditoriaService.registrar()` generaliza el
log de la aprobación de OC y escribe `log_auditoria` (jsonb antes/después, CHECK de
acciones) en: crear pedido interno, despachar, registrar factura de compra y moderar
reseña/pregunta; INSERT de log_auditoria otorgado a grp_vendedor/despacho/compras (script
42; el checkout online NO loguea: grp_cliente sin INSERT a propósito). Trazabilidad futura
(producto, marketing, historial de ticket) documentada en deuda.
**SANEAMIENTO TIPO 1 (2026-07-18, script 43)**: los 10 bugs del inventario consolidado
quedaron cerrados por causa raíz — cupón ahora SÍ recalcula el IVA (prorrateo por línea en
`aplicarCupon`, reescala `pedido_detalle.monto_impuesto` antes del pago; `envio_gratis` no
toca base); RLS habilitado en `pago`/`transaccion_pago`/`cupon`/`uso_cupon` (cliente aislado
a lo suyo, cupones solo activos o usados por él; helper `fn_pago_del_cliente` SECURITY
DEFINER); numeración de documentos por secuencia global `seq_numero_documento` (desde
100000; los 3 `siguienteNumero`) y tickets por `fn_siguiente_numero_ticket()` (correlativo
por año bajo lock); `resena` con grants de cliente POR COLUMNA (sin UPDATE); soporte sin
escritura en `categoria_ticket`; RMA 'despachado' con plazo de 30 días desde
`fecha_despacho`; pedidos legacy saneados (factura del 24662 anulada — `emitirFactura`
ignora 'anulada' —, el 87538 facturado). La deuda técnica acumulada vive en
`DEUDA_TECNICA.md` (raíz).
**NOVEDADES DE ENVÍO (2026-07-18, script 44)**: incidencias sobre el envío en tránsito
(tabla `novedad_envio`: cliente_ausente/direccion_incorrecta/cliente_rechazo/
zona_dificil_acceso/dano_en_transito, autor del JWT, RLS pol_horario+pol_cliente_propio).
DESPACHO registra la novedad (POST `/api/ventas/envios/{id}/novedades`, el envío pasa a
'fallido' — CHECK existente) y la resuelve: REPROGRAMAR (`/novedades/{id}/reprogramar`,
máx. 3 intentos, envío vuelve a 'en_transito' + nueva fecha estimada) o DEVOLVER AL ALMACÉN
(`/novedades/{id}/devolver-almacen`: envío 'devuelto', pedido → estado NUEVO
'no_entregado' terminal, SIN reingreso de stock — el kardex solo se mueve tras inspección
de bodega, criterio RMA; reembolso/reingreso por soporte, deuda Fase 6). Guardias: solo
envíos en tránsito, UNA novedad abierta a la vez, `entregar` bloqueado con novedad abierta,
intentos = 1 + reprogramaciones (calculado, sin columna). Consulta compartida GET
`/pedidos/{id}/novedades` (role-aware: el cliente no une usuario). Rastro triple:
seguimiento_envio + historial_estado_pedido + log_auditoria. UI: tarjeta "Novedades de
entrega" en `/operativo/ventas/despachos` y mensaje amable + timeline en Mis Pedidos.

**DEVOLUCIÓN A PROVEEDOR (2026-07-18, script 45, `compras/DevolucionProveedorService`)**:
espejo del RMA hacia el proveedor. Pool `item_defectuoso` (pendiente→en_devolucion→resuelto)
con DOS orígenes: (1) inspección RMA 'defectuoso' lo crea AUTOMÁTICO (proveedor rastreado por
última OC de la variante o NULL → COMPRAS lo asigna con PATCH; SIN stock: nunca reingresó) y
(2) recepción de compra — rechazo EN PUERTA (`cantidad_rechazada`, automático, jamás entró a
stock; UI en Recepciones) o marcado POSTERIOR de BODEGA (POST
`/api/compras/recepciones/detalles/{id}/defectuoso`: ahí SÍ sale del stock vendible con kardex
`salida_devolucion_proveedor`, tope recibidas−ya marcadas). COMPRAS agrupa pendientes de UN
proveedor en `devolucion_proveedor` (numero DP-… por seq global; registrada→enviada [sin
movimiento de stock]→resuelta→cerrada, guardias 409) y registra la resolución: `nota_credito`
(crédito SIMULADO = Σ cantidad·costo, sin stock) o `reposicion` (reingreso APTO vía
StockService, kardex NUEVO `entrada_reposicion_proveedor`, en la bodega de origen de cada
ítem, bajo grp_compras — grants de stock dados en el 45). Historial propio
(`historial_devolucion_proveedor`) + AuditoriaService en cada acción (grp_bodega ganó INSERT
de log_auditoria); RLS pol_horario en las 4 tablas nuevas. Pantalla multi-rol
`/operativo/compras/devoluciones-proveedor` (BODEGA marca/ve, COMPRAS gestiona con timeline;
GERENTE lee). Deuda de la fase en `DEUDA_TECNICA.md` (Fase 7): sin cuarentena física, nota de
crédito sin asiento en CxP, reposición en un paso, rechazo total en puerta imposible
(CHECK recibida>0).

**RE-AUDITORÍA DE DEUDA + HEALTH FAIL-FAST (2026-07-18, solo backend, sin script)**: auditoría
completa de deuda post scripts 43-45 verificada contra el sistema real; el único Tipo 1
detectado quedó CERRADO el mismo día — `/api/health` se colgaba con ClickHouse apagado
(Hikari con `connectionTimeout` por defecto de 30s y driver sin timeout). `ClickHouseConfig`
ahora arma el pool con `connectionTimeout=3s`, `validationTimeout=1.5s`,
`initializationFailTimeout=-1` y propiedades del driver `connect_timeout=2.5s` /
`socket_timeout=30s`; `checkPythonRuntime` con `waitFor(3s)`. Verificado en vivo:
`/api/health` responde en ~3.1s acotados con `status: UP, analytics: DEGRADED` — YA SIRVE
como healthcheck de contenedores. **Tipo 1 vigentes = 0**; la foto completa de deuda vive en
`DEUDA_TECNICA.md` (raíz) + `docs/INVENTARIO_DEUDA_CONSOLIDADO.md` (re-verificado).

**CONTRASTE DE CATÁLOGO Y DEMANDA (2026-07-24, scripts 67 y 68-70)**: corrige los hallazgos
A2/A3/A5/A6/B4 de `docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` (§11 lleva el estado).
El **67** reasigna solo `producto_variante.costo` con banda por categoría (mayorista:
Electrónica ~9 % … Belleza ~36 %): NO hay COGS almacenado, el margen se computa en vivo contra
`producto_variante.costo`, así que no altera ninguna transacción. El **Bloque D (68 respaldo →
69 curva/mapeo → 70 aplicación**, reversión `99_revert_bloque_d_demanda.sql`) **redistribuye**
la demanda ya sembrada sin crear ni borrar ventas: reasigna *qué variante* se vendió en 9.167 de
10.384 líneas dejando `cantidad` y `precio_unitario` INTACTOS, por lo que el impacto monetario
es **$0,00 exacto** (16 agregados y 19 meses sin mover un centavo). top 20 % de variantes
45,9 % → 62,2 %; Abarrotes líder con 3,69× Accesorios en venta. Si vas a tocar esto:
(1) el destino de una línea debe cumplir `precio_unitario/precio ∈ [0,90;1,00]`, invariante del
seed; (2) `factura_venta_detalle` tiene su PROPIO `producto_variante_id` y hay que moverlo en
paralelo o la factura queda apuntando a otro producto; (3) `uq_pedido_detalle` NO es deferrable
⇒ UPDATE en pasadas iterativas; (4) para el kardex el balance final NO basta: hay que respetar
la cronología (el 70 lo deja encadenado por fecha, no por id). Respaldos en el esquema
`seed_backup`, fuera de `public`.

**DESCUENTOS SEMBRADOS QUE SÍ MUEVEN EL DINERO (2026-07-25, scripts 71-73)**: cierra A8/A9 de
`docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md`. Los cupones y promociones del Bloque C ahora
descuentan de verdad: **71** respalda en `seed_backup.dsc71_*` con huella md5 por tabla
(reversión `99_revert_descuentos.sql`, probada aplicar→revertir→bit-idéntico), **72** aplica el
cupón a 535 pedidos y **73** la promoción a 120 líneas de 119 pedidos (+ recálculo del cupón en
los 24 con solape). Se aplicó por el camino del sistema real: se escriben SOLO
`pedido.monto_descuento`, `pedido_detalle.monto_descuento/monto_impuesto` y
`factura_venta_detalle.monto_descuento/monto_impuesto` — los totales de pedido y factura los
rehacen sus triggers y `pago.monto` es el ÚNICO ajuste manual (no tiene trigger de recálculo).
Si vas a tocar esto: (1) el invariante real es **`factura_venta.total = pedido.total −
pedido.costo_envio`** (la factura no factura el flete), no `= pedido.total`; (2) un cupón
`envio_gratis` NO reescala IVA pero SÍ se prorratea en `factura_venta_detalle.monto_descuento`,
porque `emitirFactura` lee `pedido.monto_descuento` sin mirar el tipo — omitirlo desalinea la
factura; (3) el descuento arrastra su IVA: el total cae **1,15×** el descuento (ingreso
$5.780.474,00 → $5.716.436,55 = −$64.037,45 = cupón $50.537,34 + promo $5.205,94 + IVA liberado
$8.294,17); (4) alcance = solo pedidos con pago `completado`; los 176 pagos fallidos, el kardex
(12.396 movs) y el inventario quedan intactos. Excepción declarada: pedidos **20 y 21** (legacy,
con factura pero sin fila en `pago`) siguen con su cupón sin reflejar.

**Pendiente**: contenerización completa (PostgreSQL sigue local, fuera de compose) y
orquestación ETL con Airflow. El NIVEL TÁCTICO está en análisis (2026-07-17,
`docs/RetailMind_T11_Analisis_Tactico.pdf`): 25 informes tácticos por departamento — 12 simples
que salen directo de la BDR PostgreSQL y 13 compuestos (agregaciones/series temporales) que se
procesarán en ClickHouse vía el ETL orquestado por Airflow; esa es la siguiente fase.

**Deuda técnica conocida** (tablas huérfanas, requieren bloque dedicado):

- `lote` (0 filas): trazabilidad por lote/vencimiento. **DECISIÓN DE ALCANCE (2026-07-18,
  ver `ROADMAP.md`)**: se evaluó FEFO y se pospuso deliberadamente — obliga a tocar
  recepción (capturar lote+vencimiento), inventario (stock por lote), kardex (arrastrar
  `lote_id`) y salida FEFO en despacho, sin aporte al flujo retail general. Las FK
  `movimiento_inventario.lote_id` y `recepcion_detalle.lote_id` quedan listas para esa fase.
- `ajuste_inventario.estado = 'borrador'`: el CHECK lo admite y `'anulado'` ya tiene flujo, pero un
  borrador aplicable exigiría una tabla de detalle de líneas del ajuste, que hoy no existe (el
  ajuste escribe el movimiento de kardex directo al aplicarse).
- `devolucion_proveedor` **ya existe** (script 45): el ítem `defectuoso` de la inspección RMA
  y el de recepción caen al pool `item_defectuoso` y se devuelven al proveedor con resolución
  nota de crédito/reposición (ver bloque DEVOLUCIÓN A PROVEEDOR). `salida_devolucion_proveedor`
  y `entrada_reposicion_proveedor` en uso.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
