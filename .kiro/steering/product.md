# RetailMind

RetailMind es una **tienda PyME con back-office completo y analítica integrada**. Combina un núcleo
transaccional de retail (catálogo, compras, inventario, ventas, marketing) con un motor de
analítica sobre eventos (~2.3M) y una tienda online. Es un sistema web con arquitectura **híbrida
de dos bases de datos**:

- **PostgreSQL** (BD `retailmind`, ~102 tablas): **base de datos operativa principal**. Todo el
  núcleo transaccional vive aquí, **incluida la tienda del cliente** (catálogo `/api/catalogo`,
  carrito, wishlist, perfil/direcciones, checkout y mis pedidos — migrados 2026-07-11).
- **ClickHouse**: **solo analítica** (esquema estrella `fact_eventos` + dimensiones `dim_*`),
  alimentado por el pipeline ETL de Python desde PocketBase. Con ClickHouse apagado TODO funciona;
  solo analytics/recomendaciones se degradan con aviso.

> Si algún documento viejo dice "PostgreSQL eliminado" o describe la tienda sobre ClickHouse,
> está desactualizado: ignorarlo.

El sistema se organiza en tres niveles empresariales:

- **Operativo (genera ventas)**: catálogo maestro (productos/variantes/atributos), ciclo de compra
  (orden → aprobación → recepción → factura → pago), inventario (transferencias, ajustes, kardex),
  ciclo de venta (pedido → pago del cliente → factura → despacho → devolución) con PDF imprimible,
  marketing (cupones, promociones, campañas, banners, newsletter), tienda online (carrito,
  wishlist, checkout → pedido del ciclo de venta, perfil + direcciones, recomendaciones) y
  horarios de acceso por rol.
- **Táctico (toma de decisiones)**: sesiones, conversiones, funnel, analytics por
  región/dispositivo/tráfico y reportes (Excel/PDF). Corre sobre ClickHouse.
- **Estratégico**: dashboard ejecutivo con KPIs.

## Módulos operativos construidos

| Módulo | Alcance |
|---|---|
| Catálogo | CRUD de productos, variantes (SKU), marcas, categorías, atributos |
| Compras | Orden → aprobación (GERENTE/ADMIN, compuerta enforced) → recepción completa → factura → CxP/pagos |
| Inventario | Transferencias entre bodegas, ajustes (con anulación por contramovimiento de kardex), kardex |
| Ventas | Pedido confirmado → pago del cliente (tabla `pago`+`transaccion_pago`, abonos parciales) → pagado → factura (PDF) → despacho con guía → entregado → devolución (solo tras entrega); listado facturas con búsqueda/paginación; timeline (`historial_estado_pedido`); acciones encadenadas en detalle |
| Marketing | Cupones (con historial de uso), promociones + productos (N:M), campañas, banners, newsletter (solo gestión) |
| Tienda online | Catálogo real (búsqueda/paginación), carrito, wishlist, checkout → pedido del ciclo de venta (el back-office cobra/factura/despacha); perfil + CRUD direcciones; "Mis Pedidos" con estado/guía/seguimiento/factura PDF (RLS, script 35); recomendaciones (señal CH + productos PG, degradan a destacados) |
| Soporte | Tickets, categorías, FAQ con RLS de cliente |
| Seguridad | Horarios de acceso por rol de grupo, gestión de usuarios |
| Notas de pedido | Bitácora `nota_pedido`: nota interna vs. visible al cliente |

## Pendientes
- Aplicación real de descuentos (cupones/promociones a pedidos, alimenta `uso_cupon`).
- Módulo de reseñas.
- Orquestación ETL con Airflow.

## Deuda técnica conocida (tablas huérfanas, requieren bloque dedicado)
- `lote` (0 filas): trazabilidad por lote/vencimiento. FK ya en `movimiento_inventario.lote_id`
  y `recepcion_detalle.lote_id`. Darle uso obliga a tocar recepción (capturar lote), kardex
  (arrastrar `lote_id`) y salida FEFO en despacho — no es un CRUD suelto.
- `ajuste_inventario.estado = 'borrador'`: CHECK lo admite y `'anulado'` ya tiene flujo, pero un
  borrador aplicable exigiría tabla de detalle de líneas (hoy el ajuste escribe el movimiento
  directo al aplicarse).
- `devolucion_proveedor` **no existe** en la BD: la única devolución modelada es al cliente
  (`devolucion` / `devolucion_detalle`), ya implementada.

## Roles y seguridad

**8 roles de aplicación** espejados en **roles de grupo de PostgreSQL** (`grp_*`): ADMIN
(administrador), GERENTE, VENDEDOR, COMPRAS, BODEGA, DESPACHO, CLIENTE, ANALISTA.

La seguridad se aplica en **dos capas**:
1. **Aplicación**: Spring Security JWT (`SecurityConfig` por ruta) + `roleGuard` en Angular.
2. **Motor de BD**: matriz de privilegios por grupo, RLS (p. ej. cliente solo ve sus pedidos) y
   restricción por horario. La app asume el rol del usuario en cada transacción con
   `SET LOCAL ROLE` (aspecto `PgSessionRoleAspect`). El admin está exento de horario.

El catálogo de tienda es público; todo lo demás requiere autenticación.

## Idioma

El proyecto usa **español** para términos de dominio, comentarios y etiquetas de UI. Los
identificadores mezclan inglés (convenciones de framework) con español (entidades de dominio como
`compras`, `ventas`, `inventario`, `cupon`, `promocion`).
