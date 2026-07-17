# RetailMind — contexto para Claude Code

Tienda PyME con back-office completo. **PostgreSQL (BD `retailmind`, ~102 tablas) es la ÚNICA
base transaccional** — incluida la TIENDA DEL CLIENTE (catálogo `/api/catalogo`, carrito,
wishlist, perfil/direcciones, checkout y mis pedidos, migrados 2026-07-11). ClickHouse es **solo
analítica** (paquete `analytics/` + señal de eventos para recomendaciones): con ClickHouse
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
  de PostgreSQL está en `retailmind/sql/postgres/` (scripts numerados 01-35 + 99).
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
  `analista@retailmind.com`): `Retail2026!` (script 27)
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
La deuda técnica acumulada vive en `DEUDA_TECNICA.md` (raíz).

**Pendiente**: aplicación real de descuentos (cupones/promociones a pedidos, alimenta `uso_cupon`);
módulo de reseñas; orquestación ETL con Airflow.

**Deuda técnica conocida** (tablas huérfanas, requieren bloque dedicado):

- `lote` (0 filas): trazabilidad por lote/vencimiento. La FK ya existe desde
  `movimiento_inventario.lote_id` y `recepcion_detalle.lote_id`, así que darle uso obliga a tocar
  recepción de compra (capturar lote), kardex (arrastrar `lote_id`) y salida FEFO en el despacho —
  no es un CRUD suelto.
- `ajuste_inventario.estado = 'borrador'`: el CHECK lo admite y `'anulado'` ya tiene flujo, pero un
  borrador aplicable exigiría una tabla de detalle de líneas del ajuste, que hoy no existe (el
  ajuste escribe el movimiento de kardex directo al aplicarse).
- `devolucion_proveedor` **no existe** en la BD: la única devolución modelada es al cliente
  (`devolucion` / `devolucion_detalle`), ya implementada como RMA completo. Los ítems
  `defectuoso` de la inspección quedan como merma documental esperando ese proceso (el
  kardex ya tiene `salida_devolucion_proveedor` sin uso).

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
