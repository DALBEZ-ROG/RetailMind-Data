# RetailMind — contexto para Claude Code

Tienda PyME con back-office completo. **PostgreSQL (BD `retailmind`, ~103 tablas) es la ÚNICA
base transaccional** — incluida la TIENDA DEL CLIENTE (catálogo `/api/catalogo` con ~1.214
productos reales cargados del dataset original vía ETL puntual, carrito, wishlist,
perfil/direcciones, checkout y mis pedidos, migrados 2026-07-11). **Desde el 2026-08-03 PostgreSQL
corre EN UN CONTENEDOR** (puerto 5432), así que la vieja frase «con Docker apagado todo funciona»
YA NO VALE: sin Docker no hay base. Lo que SÍ sigue en pie —y es el invariante de diseño que hay
que respetar— es que **ClickHouse es solo analítica** (paquete `analytics/` + señal de eventos para
recomendaciones): con ClickHouse apagado TODO el sistema funciona y solo analytics/recomendaciones
se degradan con aviso (probado: `status: UP` / `analytics: DEGRADED` en ~5 s acotados, informes
simples intactos, compuestos y tableros con `analiticaDisponible: false`, y recuperación **sin
reiniciar el backend**). Por eso el compose declara `clickhouse: service_started` y NUNCA
`service_healthy`. Si algún documento viejo dice "PostgreSQL eliminado", describe la tienda sobre
ClickHouse o afirma que PostgreSQL corre local, está desactualizado: ignóralo.

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
  el rol del usuario **por transacción** con **`set_config('role', ?, true)`** (aspecto
  `security/PgSessionRoleAspect`, excluye `analytics/`). **Desde el 2026-08-06 ya NO es
  `SET LOCAL ROLE ` + nombre**: es equivalente (mismo GUC, mismo alcance de transacción) pero el
  nombre del rol viaja como **PARÁMETRO LIGADO**, así que no se concatena un identificador en
  ningún punto. Ese cambio es lo que permite que el rol venga de la BD —los roles personalizados
  del script 87— sin debilitar nada: la garantía de no-inyección ya no depende de que el nombre
  salga del enum `DbGroupRole`, sino de que NUNCA se concatena. Probado:
  `set_config('role','grp_x; DROP TABLE marca',true)` → «role does not exist». `app.cliente_id`
  viaja por el mismo camino (verificado: el cliente sigue viendo sus 21 pedidos, no 4.083).
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

**TODO está contenerizado desde el 2026-08-03** (ver `docs/DESPLIEGUE_EJECUTADO.md`). El compose
raíz declara **6 servicios** y `pocketbase` se ELIMINÓ: `postgres` y `clickhouse` (sin perfil,
siempre arrancan), `backend` y `frontend` (perfil `demo`), `etl` y `pgadmin` (perfil `tools`, a
demanda). Como `.env` fija `COMPOSE_PROFILES=demo`, un `up -d` a secas levanta **los cuatro
primeros**:

```bash
# TODO el sistema (los 4 servicios del perfil demo, listo en ~28 s)
docker compose up -d

# Tras cambiar código Java o Angular: SIN --build el contenedor sigue con la imagen vieja
docker compose up -d --build

# MODO DESARROLLO: solo la base y la analítica en Docker; backend y frontend a mano
docker compose up -d postgres clickhouse
docker compose stop backend frontend      # libera 8080 y 4200
cd retailmind-backend  && mvn spring-boot:run     # necesita application-local.properties
cd retailmind-frontend && npm start

# Verificación mínima antes de dar por bueno un cambio
mvn compile   &&   ng build
```

**Puertos**: el **5432 es el CONTENEDOR** (PostgreSQL 18.4, la base VIVA). El PostgreSQL **local**
(18.3 Windows, servicio `postgresql-x64-18`) se movió al **5433** y ahí viven las 12 bases de otras
materias más una copia congelada de `retailmind` — sirve de marcha atrás, no se desinstala. Para
inspeccionar el esquema usa el MCP `retailmind` (solo lectura), que ya apunta al contenedor.

**Secretos**: NO hay contraseñas en el código ni en este archivo. `application.properties` dejó de
tener valores por defecto para `postgres.datasource.password` y `jwt.secret` — la app **falla al
arrancar** si faltan, a propósito. Fuera de Docker los toma de
`retailmind-backend/application-local.properties` (gitignored, vía `spring.config.import` con
`optional:`); dentro, del entorno del compose. Las credenciales de motor viven en `.env`,
`retailmind/.env` y `deploy/secrets/pg_superuser.txt` — esos tres más
`application-local.properties` son los **cuatro archivos con secretos, todos fuera del índice de
git** (verificado). `.env.example` es la plantilla versionada: lleva las CLAVES sin los VALORES.

**Trampas del despliegue** (detalle en `docs/DESPLIEGUE_EJECUTADO.md` §8):
- Un cambio de **Java/Angular NO entra solo**: la imagen está horneada, hace falta `--build`. El
  **Python del ETL sí es inmediato** porque `./retailmind` va montado, no copiado.
- Los **datos viven en el volumen, no en la imagen**: reconstruir NO los borra.
- Un **script SQL nuevo NO se aplica solo**: `deploy/postgres/initdb/` corre una única vez, con el
  volumen vacío. Para aplicarlo:
  `docker compose exec -T postgres psql -U postgres -d retailmind < ruta/script.sql`.
- Ningún `down` debe llevar **`-v`**: el volumen de ClickHouse guarda un dato irreproducible
  (`fact_eventos`, 2.823.245 filas) y por eso va declarado `external: true`.

## Credenciales de desarrollo

- Admin: `admin@retailmind.com` / `Admin2026!`
- Resto de roles (`gerente@`, `vendedor@`, `compras@`, `bodega@`, `despacho@`,
  `analista@retailmind.com`): `Retail2026!` (script 27); `soporte@retailmind.com`:
  `Retail2026!` (script 37)
- Clientes demo (`maria.lopez@demo.com`, `carlos.vera@demo.com`): `Cliente2026!` (script 26)

> **Estas credenciales de LOGIN siguen intactas** tras la rotación del 2026-08-03 (verificado:
> los 10 usuarios entran). Lo que se rotó fueron **cuatro secretos internos que nadie teclea** —
> el superusuario `postgres` del contenedor, los roles `retailmind_app` y `retailmind_etl`, y el
> `jwt.secret`—, porque estaban en claro y versionados. **Ya no aparecen en ningún archivo
> rastreado por git**, así que NO los escribas aquí: viven en `.env` (`PG_APP_PASSWORD`,
> `PG_ETL_PASSWORD`, `JWT_SECRET`), `retailmind/.env` (`ETL_PG_PASSWORD`) y
> `deploy/secrets/pg_superuser.txt`. El **superusuario del PostgreSQL local (5433) NO se rotó**:
> esa contraseña la comparten los MCP de otras materias.

- **Rol de motor del ETL** (no es un usuario de la app, no tiene login web): `retailmind_etl`
  (script 85), LOGIN + BYPASSRLS + solo lectura por cuatro capas. Contraseña en `retailmind/.env`
  como `ETL_PG_*` / `ETL_CH_*`.

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

**REBALANCEO DEL ABASTECIMIENTO (2026-07-25, scripts 74-78)**: cierra A1/B2 de
`docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` (§11 lleva el estado). El 78,5 % de las unidades
que entraban al almacén lo hacían como una apertura ficticia de un solo día (2025-01-01,
1.216 movs, 120.160 uds); ahora la apertura baja a **34.210 uds (22,4 %)** y las compras a
proveedores suben a **118.473 uds (77,4 %)** repartidas en los 19 meses. **74** respalda
(`seed_backup.reb74_*`, filas completas de `movimiento_inventario` + 16 huellas md5; reversión
`99_revert_abastecimiento.sql`, probada aplicar→revertir→bit-idéntico 3 veces), **75** calcula
el plan y demuestra su factibilidad temporal SIN escribir en public, **76** crea 529 OC +
recepciones (+566 `producto_proveedor`), **77** sus facturas/CxP/pagos y **78** recompone el
kardex. Si vas a tocar esto: (1) el principio es que **`inventario.stock_actual` NO se escribe** —
solo cambia el ORIGEN de las entradas (menos `inventario_inicial`, más `entrada_compra`), así que
por cada unidad retirada de la apertura hay una `entrada_compra` anterior a la primera salida de
esa variante; (2) la factibilidad se prueba por tramos, no por balance final: un segundo lote más
tarde solo puede llevarse el **mínimo saldo del tramo** `[T1,T2)` (columna `max_seguro` de
`reb75_factibilidad`); (3) el kardex se encadena por **`(fecha_creacion, id)`** y toda cadena
arranca en 0 — insertar en el pasado obliga a reencadenar `stock_anterior/stock_nuevo` de la
cadena completa (2.234 filas reescritas, 1.006 de ellas salidas de venta: cambia su saldo
corrido, NUNCA su cantidad ni su fecha); (4) las variantes con primera salida antes del
2025-03-01 (343) CONSERVAN su apertura a propósito — no hay espacio para una compra previa
creíble — y esa apertura se reparte del 2 al 11 de enero de 2025. Consecuencia declarada:
facturas de compra $3,82 M → **$22,47 M**, pagos → **$16,08 M**, saldo CxP → **$6,38 M**, cuadre
exacto ($0,00 de descuadre); la compra supera con mucho la venta porque el stock sembrado ya era
de ~6,8 años de rotación (M2, fuera de alcance). Ventas intactas al centavo (huellas md5 de
`pedido`/`pedido_detalle`/`factura_venta`/`pago` idénticas).

**CIERRE DE LOS OBJETIVOS SIN DATOS (2026-07-25, scripts 79-84)**: cierra 6 de los 7 objetivos
que la §8 de `docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` dejaba vacíos (§12 lleva el estado).
**79** respalda (`seed_backup.op79_*` con filas completas de `movimiento_inventario`/`inventario`
+ 11 huellas md5; reversión `99_revert_objetivos_pendientes.sql`, probada
aplicar→revertir→bit-idéntico→re-aplicar con conteos idénticos). **80** es el ÚNICO que mueve
stock: 61 transferencias (`recibida`/`en_transito`/`pendiente`/`cancelada`) + 50 ajustes
(7 motivos, `aplicado`/`anulado` con contramovimiento) repartidos en 19 meses. **81** siembra
49 preguntas de producto con 29 respuestas (+ `log_auditoria` de la moderación, que es lo ÚNICO
que el sistema audita de este bloque). **82** 1.400 accesos históricos con los 4 motivos de
`LoginFallidoException`. **83** el marketing VIGENTE de hoy (5 promos + 4 campañas + 6 banners +
4 cupones, con % por debajo del margen real de cada categoría). **84** las metas de los 5
departamentos faltantes × 19 meses. Si vas a tocar esto: (1) `transferencia_bodega` y
`ajuste_inventario` son **solo cabecera** — variante y cantidad viven en el kardex y en el texto
`[SKU xN] …`; (2) para insertar una SALIDA en el pasado hay que verificar el **suffix-min** de la
cadena pristina de esa `(variante, bodega)` (el balance final NO basta) y usar **una sola salida
por par**, o el `max_seguro` deja de ser válido; (3) el kardex se reencadena por
`(fecha_creacion, id)` — 150 cadenas reescritas; (4) aquí `inventario.stock_actual` SÍ cambia
(−159 uds: merma −48 + 111 en tránsito), a diferencia del 78. Ventas/compras/dinero intactos
(9 huellas md5 idénticas). `en_transito` = solo la salida en origen; `pendiente`/`cancelada` sin
kardex: son dato histórico, no un flujo vivo (el sistema real crea la transferencia ya
`recibida`). **OTD-GER-07** (efecto de promociones, 123 líneas) queda documentado como
limitación aceptada: densificarlo exigiría reasignar ventas.

**INFORMES TÁCTICOS — VENTAS + PATRÓN REUTILIZABLE (2026-07-25, solo código, sin script)**:
primer módulo del nivel táctico. Se implementaron los CINCO objetivos SIMPLES de Ventas del
`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` — OTD-VEN-01 (cartera de pedidos por estado),
02 (ventas por vendedor), 08 (carritos abandonados), 10 (cola de moderación) y 15 (venta contra
la meta del mes) — bajo la convención `/api/informes/{departamento}/{informe}`, todos GET de
solo lectura y todos devolviendo el MISMO sobre `{items, total, page, size, resumen[]}`.
Backend: `informes/InformeServiceBase` (molde: lista blanca de filtros → 400, paginación
server-side, KPIs, `rolActual()/usuarioActualId()`) + `InformesVentasService/Controller`.
Frontend: UNA pantalla genérica `features/operativo/informes/informes-departamento.component`
parametrizada por `data.departamento` de la ruta, que se pinta desde el archivo declarativo
`definiciones/ventas.informes.ts`; un departamento nuevo = 2 clases Java + 1 archivo TS + 5
líneas de enganche, sin componentes ni servicios Angular nuevos. Si vas a tocar esto: (1) TODO
método de informe va en `@Transactional(readOnly = true)` o corre sin `SET LOCAL ROLE`; (2) los
filtros usan guarda NULL por parámetro `(?::tipo IS NULL OR col = ?::tipo)` con el valor pasado
DOS veces — jamás se concatena texto del usuario; (3) la antigüedad del carrito se mide con
`COALESCE(fecha_actualizacion, fecha_creacion)` porque el trigger touch no dispara en los
abandonados; (4) VEN-02 recorta al VENDEDOR a lo suyo desde el JWT y devuelve
`alcance: "propio"`; (5) NADA de PDF: el nivel táctico se consulta por pantalla. Segregación
financiera verificada: BODEGA y DESPACHO reciben 403 en los cinco informes. El patrón está
documentado en `docs/tactico/PATRON_INFORMES.md`.

**INFORMES TÁCTICOS — COMPRAS Y LOGÍSTICA (2026-07-26, solo código, sin script)**: cuarto y
quinto módulo del nivel táctico, con el mismo coste que el patrón promete (2 clases Java +
1 archivo TS + enganche por departamento; cero componentes, servicios o estilos nuevos).
**Compras**: OTD-COM-01 (`ordenes`, 865), 02 (`cuentas-por-pagar`, 839), 08 (`defectuosos`, 38)
y 10 (`catalogo-proveedor`, 1.106). **Logística**: OTD-LOG-01 (`cola-despacho`, 48),
02 (`envios`, 2.872), 06 (`devoluciones`, 196) y 11 (`costo-envio`, 9 filas agregadas).
Si vas a tocar esto: (1) en COM-01 el estado sintético `pendiente_aprobacion` agrupa
'borrador'+'enviada' — NO existe un estado 'aprobada', aprobar deja la orden en 'confirmada';
(2) en COM-02 conviven DOS clasificaciones: `estado` (columna real) y `situacion` (recalculada
hoy contra `fecha_vencimiento`), y lo pagado es `monto_original − saldo_pendiente`;
(3) **COM-08 estrena el tercer lugar donde vive el corte financiero: la CONSULTA** — BODEGA
entra al informe y el motor NO lo impide (script 45 le dio SELECT sobre
`item_defectuoso.costo_unitario` y `devolucion_proveedor.monto_credito`), así que la barrera es
que el SQL no selecciona monto alguno; (4) la ZONA de LOG-11 no es una columna del envío: se
resuelve desde la dirección del pedido por ciudad > provincia > país, la MISMA cadena de
`VentasService.asignarEnvioPorZona` — agrupar por país miente; (5) LOG-11 lo cierra la RUTA y no
el motor (grp_despacho lee `envio.costo` porque lo escribe al despachar), igual que INV-07;
(6) los informes con agregado por fila usan el triple `tabla + lateral + filtro` — el LATERAL va
ANTES del WHERE y el conteo reutiliza solo `tabla + filtro`. Matriz verificada por API
(8 endpoints × 8 roles): DESPACHO 200 en LOG-01/02/06 y 403 en `/costo-envio`; BODEGA solo en
`/defectuosos` y `/devoluciones`; SOPORTE solo en `/devoluciones`; VENDEDOR y ANALISTA 403 en
los ocho. Detalle en `docs/tactico/PATRON_INFORMES.md` §10.

**INFORMES TÁCTICOS — SOPORTE Y GERENCIA: NIVEL TÁCTICO COMPLETO (2026-07-26, solo código,
sin script)**: sexto y último módulo, mismo coste del patrón (2 clases Java + 1 archivo TS +
enganche por departamento). **Soporte**: OTD-SOP-01 (`bandeja`, 248 tickets / 128 vivos),
04 (`por-categoria`, 8 filas) y 05 (`por-agente`, 7 filas). **Gerencia**: OTD-GER-01
(`foto-dia`, 20 filas agregadas), 04 (`cupones`, 33 / 7 vigentes), 06 (`marketing`, 65 / 20
vigentes), 08 (`auditoria`, 7.073) y 09 (`accesos`, ~1.500). Con esto los SEIS departamentos
tienen sus informes SIMPLES. Si vas a tocar esto: (1) **GER-08 y GER-09 son DATOS SENSIBLES DE
SEGURIDAD** (solo ADMIN/GERENTE, el corte más estricto) y cada uno se apoya en una capa
distinta — en `/accesos` motor + ruta coinciden (solo grp_administrador y grp_gerente leen
`log_acceso`), pero en `/auditoria` **grp_analista SÍ lee `log_auditoria`** (script 19), así que
ahí el corte lo hace la RUTA, como en INV-07 y LOG-11; por eso van en su propia línea de
`SecurityConfig`; (2) el valor por DEFECTO de un filtro es diseño: SOP-01 arranca en el estado
sintético `pendientes` (= `estado NOT IN ('resuelto','cerrado')`, traducido en el servicio, nunca
concatenado), GER-04 en `situacion=vigente` y GER-06 en `vigencia=vigente`; (3) la `situacion`
del cupón replica las TRES condiciones de canje de `DescuentosService` (activo + ventana +
`usos_maximos`), no `activo`; (4) GER-01 emite una fila explícita «Día sin movimiento» y un KPI
«Último día con pedidos» porque el seed llega al 2026-07-22 (pedidos) / 07-23 (cobros) y
consultar hoy sale vacío — limitación temporal declarada, no un fallo; su bloque de
**pendientes es AL MOMENTO**, no del día consultado; (5) un `date` puro se serializa
«AAAA-MM-DD» y el formateador de la pantalla lo lee como UTC restando un día: las fechas-día del
resumen viajan ya formateadas con `to_char` y tipo `texto` (las columnas de los demás informes
son timestamptz y no sufren esto). Detalle en `docs/tactico/PATRON_INFORMES.md` §11. De paso se
corrigió en la pantalla genérica la barra «avance sobre la meta», que aparecía en CUALQUIER
informe con un KPI de porcentaje (afectaba ya a INV-08): ahora es opt-in con `barraAvance: true`
y solo OTD-VEN-15 la declara.

**ETL AL DWH — FASE 0, PRERREQUISITOS (2026-07-30, script 85 + paquete `etl/dwh/`)**: arranca
el pipeline PostgreSQL → ClickHouse que alimentará los 39 objetivos tácticos COMPUESTOS, según
`docs/estrategico/DISENO_ETL_CLICKHOUSE.md` (§9.1). **No se carga ni una fila de datos**:
`registro.TAREAS` está vacío a propósito y las 19 tablas de destino entran por fases. Tres piezas:
(1) **script 85** = rol `retailmind_etl` (LOGIN + **BYPASSRLS** + SELECT sobre las 54 tablas de
origen, `usuario` POR COLUMNA para no exponer `password_hash`). BYPASSRLS es el punto entero del
script: `pol_horario` está declarada con `cmd = ALL`, y **ALL incluye SELECT**, así que un ETL
nocturno con cualquier rol `grp_*` no recibe un 403 — RLS filtra **en silencio** y devuelve CERO
FILAS, publicando 19 tablas vacías sin un error en ningún log (verificado: bajo `grp_cliente` el
mismo `SELECT count(*) FROM pedido` da **0**; bajo `retailmind_etl`, **4.083**). (2) base
**`retailmind_dwh`** (ENGINE `Atomic`, obligatorio para `EXCHANGE TABLES`) + bitácora
`etl_ejecucion`; la base `retailmind` **legada de ClickHouse no se toca** y quedó verificada
bit-idéntica (14 tablas, `fact_eventos` 2.823.245). (3) esqueleto `retailmind/etl/dwh/` con el
patrón de carga atómica del §6.2 (staging `_new` → validar contra el origen → `EXCHANGE TABLES`;
si la validación falla se ABORTA y la tabla publicada no se toca). Si vas a tocar esto: (1) el rol
es de **SOLO LECTURA en cuatro capas** — sin atributos de escritura, solo GRANT SELECT, REVOKE
explícito de escritura, y `default_transaction_read_only = on` a nivel de ROL; las capas 2 y 4 se
probaron **por separado** (con la sesión en READ WRITE el motor sigue negando por privilegio);
(2) el `default_transaction_read_only` implica que **una sesión de este rol no puede escribir ni
temporales** — si alguna fase necesitara una tabla temporal, va en ClickHouse, no en PostgreSQL;
(3) el CONNECT a otras bases del clúster lo concede el `PUBLIC` por defecto de PostgreSQL
(`pg_database.datacl IS NULL` en las 14 bases): cerrarlo exigiría `REVOKE ... FROM PUBLIC`, que es
modificar privilegios existentes y afectaría a las otras apps del clúster — queda declarado como
limitación, y el riesgo residual es nulo porque el rol no tiene privilegio alguno sobre sus
objetos; (4) `EXCHANGE TABLES` exige que AMBAS tablas existan: la primera carga de cada tabla usa
`RENAME TABLE` (ya contemplado en `carga_atomica`); (5) `conexiones._validar_destino` lanza
`BaseProhibida` si alguien apunta el pipeline a la base legada. Punto de entrada:
`python -m etl.dwh.cargar --tabla X` (además de `--init`, `--verificar`, `--listar`, `--bitacora`),
ejecutado desde `retailmind/`. Cada tabla es un comando autónomo: el DAG de Airflow de §7.1 sería
un `BashOperator` de una línea por tarea, sin lógica de negocio dentro.

**ETL AL DWH — FASE 1, EL PILOTO DE PUNTA A PUNTA (2026-07-30, solo código, sin script)**:
`dim_fecha` (730 días, GENERADA con `numbers()` dentro de ClickHouse, no consulta PostgreSQL),
`dim_producto` (1.221 variantes) y `fact_venta_linea` (10.384 líneas) cargadas y validadas contra
PostgreSQL **al centavo**, más el primer informe COMPUESTO **OTD-VEN-06**
(`GET /api/informes/ventas/evolucion-mensual`, ADMIN/GERENTE/ANALISTA). Las 7 cifras de control de
§9.2 cuadran exactas (20.687 uds · venta neta de línea $4.991.078,85 · costo $3.844.509,33 · 0
líneas sin costo · 19 meses) y también el control mes a mes en los 19 meses (3.924 pedidos no
cancelados / $5.498.570,35). Entregable extra: `python -m etl.dwh.validar_dwh` (4 controles,
`--fase`, `--control`, `--detalle`; exit 1 si algo difiere). Si vas a tocar esto:
(1) **`factura_venta` NO es 1:1 con el pedido** — el pedido 2 tiene DOS facturas 'emitida'
(duplicado legacy), así que filtrar solo por `estado <> 'anulada'` da 10.386 filas donde hay
10.384; hay que tomar UNA **factura canónica** por pedido (`DISTINCT ON`, la no anulada más
reciente por `fecha_emision, id`); (2) las **6 excepciones de descuento NO son las que el diseño
suponía** (no son los pedidos 20/21/24662): son los pedidos **40, 4031, 4078, 4106, 4161 y 4176**,
todos con descuento y en estado 'pagado' sin llegar a 'facturado', o sea SIN factura de la que
prorratear el cupón — se cargan con `descuento_cupon_prorrateado = 0`, se MARCAN con la columna
`excepcion_descuento` (consultables en el almacén, no solo contadas) y se registran en
`etl_ejecucion.excepciones`; (3) el `mes` se calcula **en PostgreSQL**
(`date_trunc('month', fecha_pedido AT TIME ZONE 'America/Guayaquil')`) y viaja resuelto: no se
deriva en ClickHouse; (4) `fact_venta_linea.pedido_total` es un **atributo degenerado de cabecera**
— solo se lee tras `GROUP BY pedido_id`; sumarlo a grano de línea lo contaría 2,5 veces; (5) OJO
con ClickHouse: ningún alias de agregado puede llamarse como una columna (`ILLEGAL_AGGREGATION`),
dividir dos `Decimal` trunca a la escala del operando izquierdo (los porcentajes van en
`toFloat64`, el dinero NO), y el minuto de `formatDateTime` es `%i` — `%M` es el nombre del mes.
La degradación se afinó: **solo un fallo de CONEXIÓN degrada**; una consulta mal formada se propaga
como 500, porque capturar todo `DataAccessException` disfrazaba bugs de SQL de «analítica no
disponible» y dejaba la prueba por API en verde. Detalle en `docs/tactico/PATRON_INFORMES.md` §13.

**ETL AL DWH — FASE 2, EL NÚCLEO DE LA VENTA Y EL DINERO (2026-07-30, solo código, sin script)**:
`dim_cliente` (72), `fact_pedido` (4.083) y `fact_flujo_caja` (4.981 = 4.079 cobros + 902 pagos)
cargadas y validadas al centavo, más OCHO informes compuestos nuevos — OTD-VEN-05
(`/clientes`), VEN-07 (`/ticket-promedio`), VEN-09 (`/formas-cobro`), VEN-12 (`/cobros-fallidos`),
VEN-13 (`/evolucion-canal`), LOG-12 (`/logistica/tiempos-ciclo`), GER-02 (`/gerencia/balanza`) y
GER-05 (`/gerencia/descuento-cupones`). Los 10 controles de `validar_dwh.py` (4 de Fase 1 + 6
nuevos) cuadran EXACTOS: 3.924 pedidos no cancelados / $5.498.570,35, canal web 2.132 / tienda 990
/ teléfono 802, $5.467.791,59 cobrados y $16.084.462,74 pagados a proveedor, y el control mes a mes
en los 19 meses. `pedido_total` se RETIRÓ de `fact_venta_linea` (era un atributo degenerado de
cabecera que solo existía para poder validar la Fase 1 sin `fact_pedido`); ningún informe lo leía y
los controles de la Fase 1 siguen cuadrando. Si vas a tocar esto: (1) los **176 cobros fallidos no
tienen `pedido_id` NI `fecha_pago`** —el intento se registra antes de que exista pedido y un cobro
rechazado nunca se liquida—, así que la fecha es `COALESCE(fecha_pago, fecha_creacion)` marcada con
`fecha_es_intento = 1`; tomar `fecha_pago` a secas habría dejado a OTD-VEN-12 sin período y el
informe habría salido vacío sin un solo error; (2) `movimiento_id` **no es único**: `pago.id` y
`pago_proveedor.id` son secuencias independientes que se solapan, la clave es el par
`(sentido, movimiento_id)`; (3) `factura_venta` sigue sin ser 1:1 con el pedido (3.886 no anuladas
→ **3.885** pedidos), así que la factura canónica se aplica también aquí o `fact_pedido` sale con
4.084 filas; (4) `uso_cupon.monto_descontado` ($50.727,89 en 564 canjes) y `pedido.monto_descuento`
($50.590,25 en 562) NO son la misma cifra —los pedidos legacy 20 y 21— y viajan en columnas
separadas a propósito; (5) el pivote de hitos usa `min` y no `max` porque hay hitos repetidos (19
'confirmado', 8 'pagado', 5 'despachado'), y las cuatro etapas de LOG-12 se miden sobre
poblaciones DISTINTAS (2.868 / 2.856 / 2.727 / 3.696: hay 828 entregados sin registro de despacho),
por lo que cada fila declara su `pedidos_medidos`; (6) los tramos van en `Decimal(12,2)` y no en el
`Float32` del diseño, porque `validar_dwh.py` rechaza floats por construcción; (7) `motivo_fallo` se
normaliza en `transformar()` (Python, no SQL) para poder aplicar la regla de escape: 6 valores
crudos → **5** motivos, y cualquier valor no previsto cae en `'otro'` y se registra en la bitácora.
La matriz rol × endpoint (8 × 8) está verificada por API: BODEGA fuera de los ocho y DESPACHO solo
en LOG-12, cuyo corte financiero lo hace **la CONSULTA** (la tabla sí tiene `total`; ClickHouse no
tiene GRANT por columna). Detalle en `docs/tactico/PATRON_INFORMES.md` §14.

**ETL AL DWH — FASE 3A, EL CICLO DE COMPRAS + BITÁCORA DE CORRECCIONES (2026-07-30, solo código,
sin script)**: `dim_proveedor` (11), `fact_orden_compra` (865) y `fact_compra_linea` (2.949)
cargadas y validadas al centavo; **6 controles nuevos en `validar_dwh.py` (16 en total, todos en
verde)**, entre ellos el CUADRE CONTABLE que cruza dos tablas de hechos de fases distintas —
facturas de compra $22.467.387,27 − pagos a proveedor $16.084.462,74 = saldo CxP $6.382.924,53,
**descuadre $0,00**. Entregable de la fase: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`, la
bitácora de los **17 supuestos del diseño que NO se sostuvieron** (2 de la Fase 1, 7 de la 2,
7 de la 3A + apéndice de los que sí), con el formato «qué decía · qué dice la base · cómo se
resolvió · qué informe habría roto». **A partir de aquí, cada supuesto que falle se registra ahí
antes de cerrar la tarea.** Si vas a tocar esto: (1) **la cadena OC→recepción→factura→CxP NO es
1:1 completa** aunque §5.4 lo afirme — 839 OC con recepción y 839 con factura, pero solo **838 con
ambas** (la OC 8 tiene factura sin recepción, la 20 recepción sin factura): dos conteos idénticos
sobre conjuntos distintos, así que se parte SIEMPRE de `orden_compra` con LEFT JOIN encadenado y se
validan los tres por separado; (2) el gasto de compras es **`factura_total` y nunca el total de la
orden** — difieren en 119 órdenes por $226.070,31 porque el proveedor factura lo que entregó
(72 OC en `recibida_parcial`), y sumar la orden inventaría un +2,4 %; (3) `pct_rechazo` se calcula
sobre **lo que LLEGÓ** (`recibida + rechazada`) y no sobre lo pedido, porque el rechazo no siempre
se descuenta de lo recibido (49 líneas descontado, 37 aditivo, 6 mixto): sobre lo pedido, una línea
real da 42,9 % donde la verdad es 30,0 %, y el sesgo cae solo en unos proveedores; (4)
`fecha_recepcion` es `timestamptz` y `fecha_emision` es `date`: la resta exige `AT TIME ZONE`
explícito o 5 de 839 órdenes ganan un día — invisible en el promedio de COM-06, pero COM-05
clasifica con el corte en `desvio <= 0` y ahí un día cambia de lado; (5) `motivo_rechazo` es TEXTO
LIBRE (6 valores crudos, 5 del negocio) y se normaliza en `transformar()` con regla de escape a
`'Otro'`, pero a **frase canónica y no a slug** como en la Fase 2, porque aquí el origen ya guarda
frases legibles; (6) `fact_compra_linea` es la ÚNICA tabla con el `ORDER BY` invertido
(`producto_variante_id, proveedor_id, fecha_emision`) — respétalo: COM-12 es una serie por producto.

**ETL AL DWH — FASE 3B, EL KARDEX Y LA RECONSTRUCCIÓN DEL INVENTARIO MENSUAL (2026-07-31, solo
código, sin script)**: `fact_movimiento_inventario` (13.287) y `fact_stock_mensual` (21.122), la
única `TareaDerivada` del modelo — se calcula DENTRO de ClickHouse y no vuelve a consultar
PostgreSQL. **8 controles nuevos en `validar_dwh.py` (24 en total, todos en verde)**, entre ellos
**LA PRUEBA DEFINITIVA**: el `stock_cierre` del último mes contra `inventario.stock_actual`
**posición por posición — 1.406 de 1.406, 0 diferencias**; corre también DENTRO de la tarea y si una
sola posición difiere la tabla NO se publica. Se conectaron los tres informes compuestos de
Inventario: OTD-INV-04 (`/rotacion`, 10 categorías), OTD-INV-09 (`/capital-inmovilizado`, 19 meses,
$22.024.063,50 al cierre) y OTD-INV-10 (`/mermas`, 11 motivos, 137 uds perdidas / 90 sobrantes).
Si vas a tocar esto: (1) **INV-10 filtra por `es_ajuste_real` y JAMÁS por `naturaleza='ajuste'`** —
la apertura del almacén se registró como `entrada_ajuste` (343 movs / 34.210 uds), así que el filtro
«obvio» multiplica el sobrante real por **380×** sin que falle ninguna suma; el ETL precalcula la
columna para que el error no esté al alcance de un descuido; (2) `ajuste_inventario.motivo` es TEXTO
LIBRE con el SKU incrustado (`[SKU-P1340 x4] Merma…`): en crudo hay **53 valores distintos sobre 53
ajustes** y tras limpiar prefijo y sufijo quedan **11** — aquí se LIMPIA pero NO se remapean
sinónimos, al revés que en C3.3, porque quitar decoración de máquina es una limpieza y fusionar dos
frases que escribió una persona es una opinión; (3) `fact_stock_mensual` son **21.122 filas y no las
~26.700 del diseño**: la malla arranca en el PRIMER movimiento de cada par y no en el primer mes del
período, o se fabrican 5.592 ceros que aplanan la curva de INV-09 al principio; (4) la
reconstrucción LEE `argMax(stock_nuevo, (fecha, movimiento_id))` en vez de recalcular, y eso es
lícito solo porque la cadena está íntegra (verificado: ecuación, enlaces, arranque en 0 y
Σ(cantidad×factor) = stock_actual en los 1.406 pares); (5) **en ClickHouse «no hay dato» y «el dato
es cero» se parecen demasiado** — un LEFT JOIN rellena con el DEFECTO del tipo y no con NULL (por eso
el NULL del arrastre se fabrica desde `movimientos_mes`), y `any(x) OVER (… 1 PRECEDING)` hacía que
el primer mes de INV-09 mostrara su capital entero como «variación»; (6) INV-09 valoriza a **costo
VIGENTE** (no hay histórico) y lo DECLARA en pantalla con el campo nuevo `salvedad` del sobre — es
volumen a moneda constante, no el valor histórico de la bodega; (7) los 56 movimientos de ajuste son
justo los que NO traen `costo_unitario` (177 en total, con las transferencias), así que INV-10
valoriza con `dim_producto.costo` y arrastra la misma salvedad. Matriz 8 roles × 3 endpoints
verificada por API (BODEGA 200 en rotación y mermas, **403 en capital-inmovilizado**; INV-10 es
MIXTO y a BODEGA no le llegan las columnas de valor: no se seleccionan). Detalle en
`docs/tactico/PATRON_INFORMES.md` §15.

**ETL AL DWH — FASE 3C, LA ÚLTIMA MILLA Y LAS INCIDENCIAS DE ENTREGA (2026-07-31, solo código,
sin script)**: `fact_envio` (2.872) y `fact_novedad_envio` (176) cargadas y validadas, **7
controles nuevos en `validar_dwh.py` (31 en total, todos en verde)**, y los CUATRO informes
compuestos de Logística que las tablas dejan servidos: OTD-LOG-03 (`/cumplimiento-promesa`, 5
transportistas), LOG-04 (`/dias-transito`, con filtro `agrupar` ∈ transportista|mes|zona),
LOG-05 (`/novedades`, 14 filas tipo × desenlace) y la **SERIE mensual del costo de envío**
(`/costo-envio-mensual`, 19 meses) — la evolución que OTD-LOG-11 dejó pendiente para ClickHouse
al reclasificarse a SIMPLE; el simple da la FOTO por zona, éste la SERIE. Coste del patrón: **0
clases Java nuevas** (entran en el servicio/controlador de Logística que ya existían por LOG-12)
+ 1 bloque por informe en las definiciones. LOG-09 NO se implementó: necesita `fact_devolucion`
(Fase 4). Si vas a tocar esto: (1) **la zona NO es una columna de `envio`** — se resuelve por
ciudad > provincia > país con precedencia por especificidad (181/596/2.078/17, exactos contra
§5.8), y como las TRES zonas cuelgan del mismo país, agrupar por país manda **2.855 de 2.872 a
UNA fila** sin dar error: por eso la tabla trae `zona_nivel` y los cuatro conteos quedan
auditables con un `GROUP BY`; (2) **C3C.1 — la zona horaria decide el día y con él los tres
plazos**: 569 de 2.727 envíos (20,9 %) cambian de `dias_transito` entre UTC y America/Guayaquil
y el promedio se mueve de 3,98 a 3,77 días — el error es ASIMÉTRICO (el despacho es de tarde, la
entrega de mañana) y acorta el tránsito sistemáticamente; la expresión del día vive en UNA
constante porque aplicarla en dos restas y olvidarla en la tercera da un informe coherente
consigo mismo y equivocado; (3) **C3C.3 — `accion` NO vale lo que dice §5.9**: `reprogramar`/
`devolver_almacen` son los verbos del API y lo guardado es el participio (`reprogramada` 49 /
`devuelto_almacen` 120 / NULL 7), así que el filtro del diseño casa con CERO filas y ocultaría
el 68 % de las novedades — la lista blanca sale de los DATOS, no del documento; (4) **C3C.2 —
los 24 envíos con `costo=0` y peso nulo NO son envíos gratis**, son envíos sin tarifar (ids 1-24,
anteriores al script 54) y caen todos en julio de 2026: promediarlos deja ese mes en $7,59 en vez
de $9,74 (**−22 %**), o sea el último punto de la serie parece una bajada de tarifas; se marcan
con `sin_tarifa`, se excluyen del promedio y el informe DICE cuántos excluyó (campo `salvedad`);
(5) cada informe declara su denominador porque hay TRES distintos (2.872 despachados / 2.727
entregados / 2.723 con promesa medible: los 145 restantes no llegaron tarde, no llegaron) y
`entregado_a_tiempo` viaja NULL —nunca 0— cuando falta una fecha; (6) **es la primera fase donde
dos informes de la MISMA tabla se separan por dinero**: LOG-03/04 y la serie de costo salen los
tres de `fact_envio`, y lo único que deja a DESPACHO fuera del tercero es que su consulta SÍ
selecciona importes (más la línea de `SecurityConfig`) — el motor no los distingue, ClickHouse no
tiene GRANT por columna; las dos rutas de dinero se enumeran POR NOMBRE y no con comodín para que
un endpoint futuro no herede el permiso. Matriz 8 roles × 4 endpoints verificada por API (32
celdas, 0 discrepancias): DESPACHO 200 en LOG-03/04/05 y **403 en `/costo-envio-mensual`**,
SOPORTE solo en `/novedades`, ANALISTA en todos menos `/novedades` y el costo. Degradación
probada con `docker stop`: los 4 informes dan 200 con `analiticaDisponible=false` en ~4,1 s, los
informes SIMPLES de PostgreSQL siguen intactos, y al levantar el contenedor se recuperan sin
reiniciar el backend. Detalle en `docs/tactico/PATRON_INFORMES.md` §16.

**ETL AL DWH — FASE 4, LA POSVENTA Y EL CIERRE DEL MODELO (2026-07-31, solo código, sin
script)**: última fase de carga. `dim_promocion_producto` (232), `fact_devolucion` (196),
`fact_devolucion_linea` (274), `fact_ticket` (248), `fact_resena` (344) y
`fact_devolucion_proveedor` (38) cargadas y validadas al centavo. **13 controles nuevos en
`validar_dwh.py` (44 en total, todos en verde)**, tres de ellos CRUZADOS dentro del almacén
(devoluciones ↔ `fact_pedido`, líneas/tickets/reseñas ↔ `dim_producto`, y la base mensual de
LOG-09 cruzando `fact_envio` de la Fase 3C con `fact_devolucion`): **0 huérfanos en todas las
direcciones**. Con esto el modelo está COMPLETO —19 de 19 tablas— y se conectaron los 16
informes compuestos de POSVENTA: VEN-11, VEN-14, LOG-07/08/09/10, SOP-02/03/06/07/08,
GER-03/07/10/11 y COM-09 — **32 endpoints compuestos en producción de los 39 del catálogo**;
los 7 que faltan son de Compras (COM-03/04/05/06/07/11/12), con sus tablas cargadas desde la
Fase 3A y pendientes solo de conectar. Coste: 2 clases Java nuevas (Soporte y Compras) + 1
bloque por informe en las definiciones; GER-03/10/11 salen de `fact_venta_linea` sin tabla
nueva. Si vas a tocar esto: (1) **el reembolso tiene DOS registros que no coinciden** —
`devolucion.monto_reembolsado` (86 / $44.695,33) y la tabla `reembolso` (85 / $44.525,63), la
devolución 8 no tiene asiento— y solo la cabecera guarda la VÍA, que es media pregunta de
LOG-10: viajan sin reconciliar y el informe declara cuál usa; (2) **el ciclo completo del RMA
solo existe en 35 de 196** (las cerradas), así que se carga además `dias_hasta_desenlace`, que
suma las 18 rechazadas —terminales y las más rápidas— y mide 53; (3) `ticket_soporte`
.`categoria_ticket_id` es NULLABLE y el JOIN interno de §5.12 tira 1 de 248 sin avisar; (4)
**unir `fact_resena` a `dim_producto` por `producto_id` MULTIPLICA** (344 → 347): la dimensión
es por variante y la reseña por producto padre, así que la tabla denormaliza y jamás une; (5)
«resuelto» (44) NO escribe `fecha_cierre` — los tiempos miden sobre los 76 cerrados y no sobre
los 120 «atendidos»; (6) `item_defectuoso.origen` vale `rma`/`recepcion` y NO
`inspeccion_rma`/`recepcion_compra` como dice §5.14: el filtro del diseño vacía COM-09 entero
sin dar error (segunda reincidencia exacta de C3C.3); (7) **19 de 28 ítems defectuosos se
detectaron DESPUÉS de la devolución que los agrupa**, así que `dias_hasta_resolucion` mide el
ciclo de la devolución (registro → resolución) y no la espera del ítem — la resta ingenua sale
negativa en 18 y la carga abortó por ello; (8) los 4 informes con `now()` en su clasificación
(SOP-02 y los vencidos de SOP-07/08) NO precalculan nada: el veredicto de un abierto depende de
la hora en que se mira. **SOP-02 parte la base en cuatro** (12 a tiempo / 64 tarde / 0 abiertos
en plazo / 172 abiertos y vencidos) porque una tasa sobre 248 daría 4,8 % y sería falsa; los
DOS informes de muestra débil la declaran en pantalla (**COM-09**: 6 resoluciones,
$5.220,94 recuperados sobre un pool de $9.349,93; **GER-07**: 184 líneas en ventana y 123 con
descuento frente a 3.217 de base, ordenado por VOLUMEN y nunca por la variación). Matriz 16
endpoints × 8 roles verificada por API (128 celdas, 0 discrepancias): los seis con dinero
—VEN-14, LOG-10, GER-03, GER-10, GER-11, COM-09— dejan fuera a BODEGA y DESPACHO por RUTA;
BODEGA solo entra en LOG-08 y DESPACHO solo en LOG-09. Degradación probada con `docker stop`:
200 con `analiticaDisponible=false` en ~4,1 s y recuperación sin reiniciar. Detalle en
`docs/tactico/PATRON_INFORMES.md` §17.

**LOS SIETE DE COMPRAS — CATÁLOGO TÁCTICO COMPLETO (2026-07-31, solo código, sin script, NO
carga ni una fila)**: se conectan los objetivos que quedaban, todos sobre tablas ya validadas
en las Fases 2 y 3A. **Compuestos** (ClickHouse): OTD-COM-03 (`/puntualidad-pago`, 902 pagos /
$16.084.462,74 / 564 a tiempo), COM-04 (`/gasto-mensual`, 839 facturas / $22.467.387,27),
COM-05 (`/cumplimiento-plazo`, 825 pares / 449 cumplidas), COM-06 (`/ciclo-compra`, 839 órdenes
/ 10,81 días de media) y COM-12 (`/evolucion-costo`, 1.041 pares producto-proveedor; 768 subieron,
150 bajaron). **Mixtos** (BODEGA entra «en cantidades, sin montos»): COM-07 (`/rechazos`, 185 uds
/ 5 motivos / $27.557,63) y **COM-11** (`/entregas-incompletas`, 165 líneas cortas / 1.514 uds),
que es **SIMPLE y va contra PostgreSQL** —era el último objetivo simple del catálogo sin
construir— porque agrega sobre la foto presente sin comparar períodos. Coste: **0 clases Java
nuevas** + 1 bloque de definición por informe + 2 líneas de `SecurityConfig`. Si vas a tocar esto:
(1) **el mes del GASTO es el de la FACTURA y no el de la orden** (corrección C5.1): 360 de las 839
facturas caen en un mes distinto al de su OC, y agrupar por `fact_orden_compra.mes` desplaza
**$4.628.932,62** entre meses —enero 2025 +52,6 %, julio 2026 −46,8 %— **sin que el total deje de
cuadrar al centavo**; la columna `mes` sí es la correcta para COM-05/06, que hablan de la orden;
(2) las «259 líneas incompletas» del catálogo son **tres cosas distintas** (C5.2): solo 165 son
incumplimiento del proveedor, 41 vienen de camino y 53 son de órdenes canceladas — con las 259,
Comercial El Costeno pasa de mejor proveedor (99,71 %) a PEOR (91,77 %) por 4 órdenes que canceló
Compras, así que el filtro `alcance` arranca en `entregadas`; (3) **COM-05 y COM-06 miden sobre
poblaciones distintas a propósito** (825 pares con promesa y llegada vs 839 con llegada) y cada
fila declara su base; (4) en COM-12 **`lagInFrame` rellena la primera fila de cada partición con
el DEFECTO del tipo y no con NULL**, así que la frontera de la serie se marca con
`row_number() > 1` y jamás con `precio_previo != 0` —comparar contra ese 0,00 daría «subida del
100 %» en los 1.041 primeros precios—, y el desempate del mismo día (16 pares) es
`orden_compra_id`; (5) dos trampas NUEVAS y las dos de Java, no de ClickHouse: un bloque de texto
**recorta el espacio final de cada línea** (`"""SELECT """ + col` → `SELECTpr.razon_social`) y
`String.formatted()` **interpreta el bloque entero, comentarios incluidos** (el patrón de fecha
va con el por-ciento duplicado… y el comentario que lo explicaba tumbó la consulta por llevar un
especificador suelto dentro). Verificación: **41 controles contra PostgreSQL tomando la cifra de
la RESPUESTA HTTP**, todos con Δ = 0; matriz 7 endpoints × 8 roles (56 celdas, 0 discrepancias):
ANALISTA entra en COM-03/04/06/12 y queda fuera de COM-05 —que no lleva ni un importe, pero el
catálogo lo reserva a Compras y Gerencia—, BODEGA solo en COM-07 y COM-11. Detalle en
`docs/tactico/PATRON_INFORMES.md` §18.

**TABLEROS DE DIRECCIÓN — FASE E1-A (2026-08-01, solo código, sin script, NO carga ni una
fila)**: arranca el nivel ESTRATÉGICO con los tres primeros tableros de
`docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md` §4 — **T-1 Omnicanal** (OE-06), **T-2 Rentabilidad
y Rotación** (OE-07) y **T-3 Cliente y Posventa** (OE-08) — que cubren 10 de las 19 decisiones de
dashboard. **0 tablas nuevas**: las 19 del almacén bastan. Paquete nuevo `tableros/`
(`TableroServiceBase` + 3 servicios + 1 controlador) y pantalla genérica
`features/operativo/tableros/` parametrizada por `definiciones/tableros.ts`; un tablero nuevo =
1 clase Java + 1 bloque de definición.
**ENFOQUE: una respuesta por tablero, no una por elemento** (`GET /api/tableros/{tablero}` →
`{tablero, kpis[], bloques[], salvedades[], datosAl, analiticaDisponible}`). Los seis elementos
comparten filtros, llevan la MISMA marca de agua y degradan a la vez; con seis peticiones,
ClickHouse cayéndose a mitad de carga dejaría medio tablero pintado. **Excepción declarada**: los
dos elementos que NO salen del almacén —carrito abandonado (T-1) y sobre-stock del presente
(T-2)— los pide la PANTALLA con una segunda llamada a los informes simples OTD-VEN-08 y
OTD-INV-08, y por eso **siguen vivos con ClickHouse apagado**.
Si vas a tocar esto: (1) **el embudo de T-1 cuenta «alcanzó este hito O uno posterior», jamás la
marca a secas**: hay **969 pedidos entregados sin registro de despacho**, y con
`countIf(fecha_despachado IS NOT NULL)` el embudo NO es monótono (2.868 despachados contra 3.696
entregados) y pinta una fuga del 26 % en la etapa que no la tiene — justo la decisión que D-06.2
toma; (2) la **tasa de rechazo del cobro NO es separable por canal**: los 176 intentos fallidos no
tienen `pedido_id` y el canal sale del pedido, así que partirla daría **0 % en los tres canales**
sin un solo error (misma causa que C2.1); (3) **T-3 recorta a SOPORTE por la CONSULTA y no por la
ruta** —entra al endpoint y el servicio NO ejecuta los bloques de valor del cliente ni de
reseñas—, y el sobre declara cuáles omitió en `bloquesOmitidos`; (4) la salvedad de **costo
vigente** (margen) y **moneda constante** (capital) es obligatoria y se pinta ENCIMA de la cifra;
(5) `bloque()` **exige** el campo `denominador` y revienta sin él: en este nivel una cifra sin su
base no produce una pantalla rara, produce una decisión; (6) el alias de agregado con el nombre de
su columna reincidió **seis veces** en una tarde (`sum(monto) AS monto` → `ILLEGAL_AGGREGATION`):
prefija `t_` SIEMPRE y repón el nombre del contrato en el SELECT exterior; (7) la **dispersión se
pinta entera** (834 puntos) aunque su tabla se recorte — la cruz son las MEDIANAS del conjunto y
con las 40 primeras por venta los cuadrantes dejan de corresponder con lo que se ve; (8) los
rótulos del SVG son `<title>` NATIVO y no `matTooltip`: con 1.933 directivas vivas el navegador
dejaba de responder.
Verificación: **71 controles contra PostgreSQL tomando la cifra de la RESPUESTA HTTP**
(`retailmind/validar_tableros.py`, rol `retailmind_etl`), todos con Δ = 0 — venta $5.498.570,35 ·
3.924 pedidos · 64 clientes omnicanales · margen $1.049.320,91 · capital $22.024.063,50 · 387
productos hueso · 834 variantes con venta · 76 tickets cerrados · 344 reseñas sin multiplicar.
Matriz **24 celdas × 0 discrepancias** (`retailmind/matriz_tableros.py`, que ensancha la ventana
horaria y la **restaura verificándola**). Degradación probada con `docker stop`: 200 con
`analiticaDisponible=false` en ~4,1 s, los bloques de PostgreSQL intactos, y recuperación sin
reiniciar. Los 6 supuestos del diseño que no se sostuvieron están en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE1.1 a CE1.6).

**TABLEROS DE DIRECCIÓN — FASE E1-B: NIVEL ESTRATÉGICO DE TABLERO COMPLETO (2026-08-01, solo
código, sin script, NO carga ni una fila)**: los cuatro que faltaban — **T-4 Operación y Última
Milla** (OE-09), **T-5 Costo de la Operación** (OE-09), **T-6 Abastecimiento** (OE-11) y **T-7
Gobierno del Dato** (OE-10) —. Con E1-A son **7 tableros y las 19 decisiones de dashboard, todas
servidas**; **0 tablas nuevas** en el almacén, como el diseño anticipó. Coste: 4 clases Java + 1
bloque de definición por tablero + 4 líneas de `SecurityConfig` (la pantalla genérica no se tocó,
solo ganó dos trazados: caja-y-bigotes y mapa de calor).
Si vas a tocar esto: (1) **T-4 es el ÚNICO tablero SIN dinero y el único que DESPACHO y BODEGA
abren**; lo sostienen DOS cosas a la vez —su línea de `SecurityConfig` y que su consulta no
seleccione un importe—, y como ClickHouse no tiene GRANT por columna la segunda se comprueba
automáticamente: `validar_tableros.py` recorre la respuesta entera buscando nombres con aspecto
monetario y falla si aparece uno, **en los cinco roles**; (2) **`dim_fecha` NO tiene
`fecha_carga`** —es el calendario, generado con `numbers()` dentro de ClickHouse— y pedírsela no
devuelve nulo, revienta con `UNKNOWN_IDENTIFIER`: la frescura se calcula sobre las **18** tablas
con sello y el calendario se publica aparte, marcado; (3) en `etl_ejecucion` **`corrida` y
`validar_dwh` NO son tablas**: la primera escribe DOS filas (`en_curso` al empezar, `exito` al
acabar) y su `filas_escritas` repite el total de todas las tablas, así que sumar sin excluirlas da
**128.214 donde hay 64.085** —el doble exacto— y lista una tarea eternamente «en curso»; se colapsa
con `argMax(…, inicio)` y se excluyen de todo conteo; (4) **«preparación» en `horas_pago_a_
preparacion` es el hito `preparado` (picking TERMINADO), no `en_preparacion`** —2.868 pedidos
frente a 2.883—, y las etiquetas del tablero ya no usan esa palabra; (5) el embudo del retorno al
almacén **termina en CERO y se publica igual**: los 120 pedidos devueltos no tienen ninguna
devolución registrada después, la mercancía volvió y no consta en ningún sitio — es la brecha que
D-09.4 tiene que ver, no un fallo del dato; (6) en `argMax(resultado, inicio)` el segundo argumento
es la **columna cruda**, jamás el alias del `min(inicio)` del mismo nivel: renombrar el alias no
basta, hay que dejar de usarlo ahí dentro; (7) T-6 arranca en `alcance=entregadas` porque con
«todas» el mejor proveedor (99,71 %) pasa a ser el peor (91,77 %), y su GASTO se agrupa por el mes
de la **FACTURA**, no por el de la orden.
Verificación: **132 controles contra PostgreSQL tomando la cifra de la RESPUESTA HTTP**
(`retailmind/validar_tableros.py`, rol `retailmind_etl`), todos con Δ = 0 — 2.872 envíos · 2.723
con promesa medible · 1.704 a tiempo · tramos 2.868/2.856/2.727/3.696 · 176 incidencias con 169
resueltas · merma 137 perdidas/90 sobrantes (con el filtro por naturaleza serían 34.300: **381×**) ·
$32.723,25 de flete con 24 envíos sin tarifar excluidos · 86 reembolsos con 1 sin asiento y $169,70
de diferencia · $22.467.387,27 facturado · $6.382.924,53 de saldo · 902 pagos por $16.084.462,74 ·
38 ítems defectuosos en 9 devoluciones. Matriz **56 celdas × 0 discrepancias**
(`retailmind/matriz_tableros.py`, que ensancha la ventana horaria y la **restaura verificándola**):
ANALISTA entra en SEIS tableros y queda fuera de T-7; COMPRAS solo en T-6; BODEGA y DESPACHO solo
en T-4. Degradación probada con `docker stop`: 200 con `analiticaDisponible=false` en ~4,08 s, los
bloques servidos desde PostgreSQL intactos (auditoría 7.073, accesos 219), y recuperación sin
reiniciar. Los 4 supuestos que no se sostuvieron están en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE2.1 a CE2.4). **Pendiente del nivel estratégico**:
solo el modelo E3 (alerta de abandono); E2 está hecho — ver el bloque siguiente.

**PREVISIÓN DE DEMANDA — FASE E2, EL PRIMER MODELO (2026-08-01, solo código, sin script)**:
`fact_prevision_demanda`, la **tabla 20** del almacén y la primera con filas de **fecha futura**
(510 = 3 meses × [1 total + 10 categorías + 159 variantes]). Descomposición multiplicativa con
factores estacionales ENCOGIDOS, ajustada a total y categoría, desagregada a la variante por
cuota; entra como `TareaModelo` (sabor nuevo de `TareaDerivada`: se calcula desde el DWH pero su
transformación es Python y no un `INSERT … SELECT`). El modelo vive en
`retailmind/etl/dwh/modelos/prevision_demanda.py` y **no abre ninguna conexión**: entran dos
vectores, sale una previsión con banda. **Veredicto: se publica el MODELO** — MAPE total
**8,78 %** contra **12,22 %** del ingenuo estacional y 33,00 % del ingenuo (con el mes truncado
anualizado, 17,39 % contra 20,07 %), cobertura de la banda del 80 % en **87,6 % (772/881 puntos)**,
y 41 de 168 series publican su línea base por no superarla (`es_linea_base = 1`). Dos endpoints
—`/api/informes/{gerencia|compras}/prevision-demanda`— sobre UNA sola clase
(`InformesPrevisionService`) que los dos controladores existentes inyectan. Si vas a tocar esto:
(1) **el `k ≈ 2` del diseño encoge DEMASIADO y hace que el modelo se rechace a sí mismo**: deja
diciembre en 1,075 donde el generador del seed lo escribió en 1,48, infla σ al 0,214 y la banda al
±41 %, con lo que la cobertura sale **100 %** y suspende el criterio de §5.1.6; k se ESTIMA de los
datos (Stein / Bayes empírico, `k = σ²/τ²` → 0,175, recortado al suelo declarado de 0,25);
(2) **el nivel con el que se calculan las razones tiene que ser ESTACIONALMENTE NEUTRO** — el nivel
filtrado por suavizado exponencial persigue la subida de mayo, la varianza dentro del mes sale mayor
que la de entre meses y los doce factores acaban entre 0,98 y 1,02; se usa el nivel GLOBAL de la
serie ya normalizada al año base; (3) **la cobertura NO se juzga sobre los 6 puntos del total** —
una banda perfecta al 80 % da 6/6 el 26 % de las veces—: se mide sobre los 881 puntos agrupados;
(4) **el mes truncado se DETECTA** (día máximo del último mes contra la mediana de los anteriores;
julio 2026 cubre 1/1,227) y su exclusión se publica en `horizonte_efectivo`, porque la tabla sale
IDÉNTICA se haya excluido o no y la pantalla no tendría cómo marcarlo; (5) **la regla «la banda se
ensancha con el horizonte» NO es exigible fila a fila** —en unidades falla en 15 series y en
relativo en 16, todas con razón—: se exige serie a serie sobre `descomposicion` y en media sobre el
resto; (6) `String.formatted()` interpreta el bloque entero y `formatDateTime(mes,'%Y-%m')` lo
revienta con un 400 «Conversion = 'Y'» (reincidencia de §18). Verificación: **46 controles** de
`validar_dwh.py` en verde (44 + universo + ancla, este último comprobando desde PostgreSQL que el
mes truncado se excluyó) y **16 celdas × 0 discrepancias** en `retailmind/matriz_prevision.py`, que
además contrasta contra PostgreSQL **la serie del gráfico mes a mes** y exige las cinco
limitaciones de §5.1.10 en la salvedad. Degradación probada con `docker stop`: 200 con
`analiticaDisponible=false` en ~4,1 s y recuperación sin reiniciar. Detalle en
`docs/tactico/PATRON_INFORMES.md` §20; los 6 supuestos que no se sostuvieron, en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE3.1 a CE3.6).

**ALERTA DE ABANDONO — FASE E3, EL ÚLTIMO MODELO (2026-08-02, solo código, sin script)**:
`fact_alerta_cliente`, la **tabla 21** del almacén, y con ella el nivel estratégico COMPLETO.
Es un modelo del **PROCESO** y no uno aprendido, y entra sabiendo que §5.2.2 lo declaró **NO
VIABLE como modelo entrenado**: no hay etiqueta de abandono, el generador del seed sortea al
cliente con peso constante —**nadie abandona nunca**— y la correlación entre el mejor predictor
y el resultado real es **0,039**. Se implementa supervivencia exponencial con la tasa propia de
cada cliente (λᵢ = pedidos/días observados; P = e^(−λᵢ·t); alerta si P < α = 0,05), sin
etiquetas y con la tasa de falsa alarma conocida de antemano. **Se publica CON SU LIFT A LA
VISTA**: las tres primeras tarjetas de la cabecera son el lift medido (**1,99×**), su muestra
(**14 casos positivos de 167 evaluaciones**) y el dictamen **«¿Supera al azar? NO · p = 0,1019»**
—el valor p es la pieza que el diseño no pidió y sin la cual un 1,99 sobre 14 positivos se lee
como un éxito—. Endpoint `GET /api/informes/ventas/clientes-en-riesgo` (ADMIN/GERENTE/VENDEDOR,
lleva monto: Bodega y Despacho fuera por RUTA), **0 clases Java nuevas** y **0 componentes
Angular nuevos**. Si vas a tocar esto: (1) **la ventana estable NO es una fecha** — son los
últimos `MESES_VENTANA = 7` meses contados desde el ancla, y un mes escrito en el código
funcionaría exactamente una vez; el guardia de **concentración ABORTA la publicación** si algún
mes de la ventana tiene un cliente por encima del 25 % (hoy 10,9 %; con los 19 meses, **100 %**,
y ahí el 2.º cliente de la cartera —$399.425— sale como la alerta más fuerte del sistema con
P = 4·10⁻¹⁷: la inversión exacta de la verdad); (2) **la recencia se ancla a `max(fecha_pedido)`
del almacén y JAMÁS al reloj** —si el ETL se para, los 69 clientes cruzarían el umbral a la vez—
y la fecha ancla va EN EL TÍTULO; (3) **los clientes sin muestra son los candidatos más fuertes
y el modelo los expulsa**: los dos silencios más largos de la cartera (179 y 94 días) tienen por
eso mismo menos de 3 pedidos en la ventana, así que se publican los **69** con nivel
`sin_muestra` y su silencio REAL en vez de los 61 evaluables; (4) el lift se divide por la tasa
base **de su propio origen** (5,8 % · 7,0 % · 12,1 %, no el 9,4 % del diseño) y un origen sin
positivos da lift **inexistente**, no cero; (5) **la ventana de entrenamiento del backtest RUEDA
con el origen**, o se mide un estimador que nunca se publica (1,34 fija vs 1,99 rodante); (6) el
recorte del VENDEDOR **no puede usar `vendedor_id`** —el almacén guarda el NOMBRE—: casa contra
`vendedores Array(String)` y el ETL valida que los nombres sean únicos; medido, deja **50 de 69**
clientes, porque 54 fueron atendidos por 3 o más vendedores. Verificación: **49 controles** de
`validar_dwh.py` en verde (46 + 3 nuevos, donde PostgreSQL **recalcula el modelo entero**,
exponencial incluida, y contrasta λ **cliente por cliente**) y **8 celdas × 0 discrepancias** en
`retailmind/matriz_alerta_cliente.py`, que además verifica el reparto por nivel contra
PostgreSQL (69/3/6/8, Δ = 0), el recorte del vendedor cliente por cliente y que el **lift esté en
las tres primeras tarjetas**. Degradación probada con `docker stop`: 200 con
`analiticaDisponible=false` en ~4,1 s y recuperación sin reiniciar. Detalle en
`docs/tactico/PATRON_INFORMES.md` §21; los 8 supuestos que no se sostuvieron, en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE4.1 a CE4.8).

**CONTENERIZACIÓN COMPLETA (2026-08-03, sin script — bitácora en `docs/DESPLIEGUE_EJECUTADO.md`)**:
PostgreSQL migrado al contenedor y **cortado** (el contenedor sirve en el **5432**, el local pasó al
**5433** y queda como marcha atrás de un minuto). Las **11 verificaciones** de §9.5 del diseño
pasaron —95 políticas RLS, 109 columnas con ACL, 13 funciones SECURITY DEFINER, 1.354 GRANT, las 4
sumas de columnas GENERATED al centavo— con `diff` de salidas contra el local, no leyendo números.
El `docker-compose.yml` objetivo está escrito y probado de punta a punta: los 4 servicios `healthy`
en **28 s**, los 10 usuarios entran, 4 pantallas operativas + informe simple + compuesto + **los 7
tableros** + **los 2 modelos** responden 200, el invariante de ClickHouse apagado se cumple
(`status: UP` / `analytics: DEGRADED` en 5 s acotados, y recuperación **sin reiniciar el backend**)
y el **ETL corrió DENTRO de Docker por primera vez**: 21/21 tablas, 66.079 filas, **49/49
controles**. Credenciales internas rotadas. Si vas a tocar esto: (1) la imagen **`postgres:18` monta
en `/var/lib/postgresql`**, NO en `/var/lib/postgresql/data` —desde la 18 el directorio lleva la
versión (`PGDATA=/var/lib/postgresql/18/docker`)— y con la ruta antigua la imagen **se niega a
arrancar** con un mensaje que habla de `pg_upgrade` y despista; (2) **un ACL que `pg_dump` no emite
NO es un privilegio perdido**: omite el GRANT cuando coincide con el que el objeto tendría por
defecto, así que se compara el privilegio EFECTIVO con `has_function_privilege`, no el texto del
ACL; (3) comparar catálogos entre dos motores exige **`COLLATE "C"` en todo `ORDER BY` de texto**
(origen `Spanish_Ecuador.1252`, destino ICU `es-EC`) o el diff acusa una diferencia inexistente;
(4) un **healthcheck contra `localhost` dentro de un contenedor** resuelve a `::1` primero y nginx
solo escucha IPv4 → `unhealthy` eterno **con la página sirviéndose bien**: siempre `127.0.0.1`;
(5) al interpretar un 403 de bodega/despacho/compras, **mira el reloj antes que la migración**
(`fuera_horario` bloquea el LOGIN entero). **Pendiente**: si se decide, el DAG de Airflow de §7
(`run_etl.py` ya orquesta con orden topológico) — y el día que tome el relevo hay que poner
**`DWH_CRON=-`**, o a las 02:00 disparan los dos y compiten por el `EXCHANGE TABLES`. El MODELO DE
DATOS está **COMPLETO**: las **19 tablas de hechos** del DWH más `fact_prevision_demanda` y
`fact_alerta_cliente` — **21 tablas, 64.664 filas**, cargadas y validadas (**49 controles en
verde**). El **CATÁLOGO TÁCTICO también**: 30 informes simples + **39 compuestos**. Y el **NIVEL
ESTRATÉGICO está CERRADO**: 7 tableros, las 19 decisiones de dashboard y **los 2 modelos**
(previsión de demanda y alerta de abandono). El diseño del pipeline vive en
`docs/estrategico/DISENO_ETL_CLICKHOUSE.md` y el del nivel estratégico en
`docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`; **los dos están corregidos en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` — 57 supuestos que no se sostuvieron; léelo antes
de tocar cualquier tabla**.

**PERMISOS DEL MOTOR — LA PANTALLA QUE ENSEÑA LA SEGURIDAD (2026-08-05/06, scripts 86 y 87)**:
`/operativo/seguridad/permisos`, **solo ADMIN** (6+3 endpoints ENUMERADOS uno a uno en
`SecurityConfig`, nunca por comodín). Seis pestañas: **Editor de rol** (interruptores),
Roles, Usuarios por rol, Permisos (grilla plana auditable), Políticas RLS y Restricción
horaria. Todo sale de `pg_catalog`: las tablas `permiso`/`rol_permiso` están VACÍAS y son
vestigiales. Cifras contrastadas contra el motor: 9 roles · 95 políticas · 50 tablas con RLS ·
**109 columnas con ACL en 14 tablas** · **1.355 GRANT** (era 1.354; +1 por la tabla nueva
`rol_personalizado`) + 113 MAINTAIN aparte.
Si vas a tocar esto: (1) **un GRANT ejecutado por quien NO es propietario NO FALLA** — emite
`WARNING: no privileges were granted` y no hace nada, así que sin `fn_admin_cambiar_permiso`
(script 86, SECURITY DEFINER) la pantalla respondería 200 a cada clic sin cambiar el motor; la
función **verifica el privilegio efectivo antes y después** y devuelve `aplicado`; (2)
**`information_schema` filtra por `pg_has_role` y miente por debajo EN SILENCIO**:
`role_table_grants` da **1.354** como superusuario y **738** bajo `grp_administrador`, que es el
rol con el que corre la pantalla — todo se lee con `aclexplode()` sobre `pg_catalog`, y los de
COLUMNA desde `pg_attribute.attacl`, nunca desde `column_privileges` (expande los heredados);
(3) **los privilegios de columna solo SUMAN**: revocar una columna a un rol que tiene el
privilegio de TABLA no cambia nada — para restringir hay que revocar la tabla y conceder las
columnas, que es exactamente como está hecha la segregación financiera (bodega: 90 ACL de
columna y CERO SELECT de tabla sobre `pedido`); (4) LECTURA no necesita SECURITY DEFINER
(`grp_administrador` lee `pg_catalog` entero), solo la ESCRITURA. **Cuatro protecciones**: R1
`grp_administrador` no se toca; R2 identidad (`usuario`/`usuario_rol`/`rol`/`permiso`/
`rol_permiso`) cerrada en AMBAS direcciones —ahí lo peligroso es CONCEDER, por `password_hash`—
y rastro/compuerta (`log_auditoria`/`log_acceso`/`grupo_horario`) solo prohíbe REVOKE; R3 solo
los `grp_*` son destinatarios (deja fuera a `retailmind_app`/`retailmind_etl`/PUBLIC **por
construcción**); R4 solo tabla/columna y SELECT/INSERT/UPDATE/DELETE (USAGE ON SCHEMA y las
membresías quedan fuera porque no hay parámetro que las exprese). Todo cambio va a
`log_auditoria` como `pg_privilegio` con el autor del JWT.

**ROLES PROPIOS (script 87)**: `fn_admin_crear_rol` / `fn_admin_eliminar_rol` + tabla
`rol_personalizado`. Un `CREATE ROLE` a secas da un rol **INSERVIBLE y falla en silencio**:
hacen falta **SEIS piezas** —NOLOGIN · `GRANT USAGE ON SCHEMA public` (el 19 se lo revocó a
PUBLIC) · `GRANT <rol> TO retailmind_app` (sin ella `SET LOCAL ROLE` falla y la app entera da
403) · 7 ventanas en `grupo_horario` (sin ellas el script 53 BLOQUEA el login) · **una política
RLS por cada una de las 50 tablas con RLS** (el defecto de RLS es DENEGAR: con SELECT y sin
política lee **CERO FILAS sin un solo error**) · fila en `rol` con `es_sistema = false`—. El rol
nace SIN privilegios; se encienden con los interruptores. **`rol_base` decide PANTALLAS, no
datos**: `SecurityConfig` es código compilado y no conoce roles creados en caliente, así que la
authority del JWT es la del rol base mientras que contra el motor se asume el rol PROPIO — dos
usuarios en la misma pantalla viendo datos distintos. Probado de punta a punta: el usuario del
rol nuevo entra a `/api/ventas/pedidos` y recibe **403 del motor** hasta que se le encienden los
privilegios. Solo se elimina lo que tenga marca de catálogo **y** fila propia **y** cero
usuarios. Trampas de PL/pgSQL que costaron tiempo: un alias de tabla `r` choca con la variable
de bucle `r record` («record is not assigned yet»), y una columna de `RETURNS TABLE` con el
mismo nombre que una columna real da «ambiguous».

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
