# RetailMind

RetailMind es una **tienda PyME con back-office completo y analítica integrada**. Combina un núcleo
transaccional de retail (catálogo, compras, inventario, ventas, marketing) con un motor de
analítica sobre eventos (~2.3M) y una tienda online. Es un sistema web con arquitectura **híbrida
de dos bases de datos**:

- **PostgreSQL** (BD `retailmind`, ~102 tablas): **base de datos operativa principal**. Todo el
  núcleo transaccional vive aquí: usuarios/roles, clientes, catálogo, compras, inventario, ventas,
  facturación, marketing, horarios de acceso.
- **ClickHouse**: **solo analítica** (esquema estrella `fact_eventos` + dimensiones `dim_*`),
  alimentado por el pipeline ETL de Python desde PocketBase.

El sistema se organiza en tres niveles empresariales:

- **Operativo (genera ventas)**: catálogo maestro (productos/variantes/atributos), ciclo de compra
  (orden → aprobación → recepción → factura → pago), inventario (transferencias, ajustes, kardex),
  ciclo de venta (pedido → factura → despacho → devolución) con PDF imprimible, marketing
  (cupones, promociones, campañas, banners, newsletter), tienda online (carrito, wishlist,
  checkout, perfil, recomendaciones) y horarios de acceso por rol.
- **Táctico (toma de decisiones)**: sesiones, conversiones, funnel, analytics por
  región/dispositivo/tráfico y reportes (Excel/PDF). Corre sobre ClickHouse.
- **Estratégico**: dashboard ejecutivo con KPIs.

## Módulos operativos construidos

| Módulo | Alcance |
|---|---|
| Catálogo | CRUD de productos, variantes (SKU), marcas, categorías, atributos |
| Compras | Orden de compra → aprobación (gerencia) → recepción → factura → cuentas por pagar/pagos |
| Inventario | Transferencias entre bodegas, ajustes, kardex de movimientos |
| Ventas | Pedido → factura (PDF) → despacho/seguimiento → devoluciones; vista "mis pedidos" para clientes |
| Marketing | Cupones (con historial de uso), promociones + productos (N:M), campañas, banners, newsletter |
| Seguridad | Horarios de acceso por rol de grupo, gestión de usuarios |

**Pendientes**: módulo de soporte, reseñas, aplicación real de descuentos (cupones/promociones a
pedidos), orquestación del ETL con Airflow.

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
