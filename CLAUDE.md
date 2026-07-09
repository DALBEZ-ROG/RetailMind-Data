# RetailMind — contexto para Claude Code

Tienda PyME con back-office completo. **Arquitectura híbrida**: PostgreSQL (BD `retailmind`,
~102 tablas) es la **base operativa principal**; ClickHouse es **solo analítica** (paquete
`analytics/`, no tocar desde lo operativo). Si algún documento viejo dice "PostgreSQL eliminado",
está desactualizado: ignóralo.

## Stack

- **Backend** `retailmind-backend/`: Spring Boot 3.5.0, Java 17, Maven, `JdbcTemplate` (sin JPA).
  Dual datasource: `pgJdbcTemplate` (cualificado, `DataSourceTransactionManager` **@Primary**) +
  ClickHouse para analytics. Spring Security JWT (login = email en el campo `username`).
  iText 5 (PDF de facturas) + Apache POI (Excel).
- **Frontend** `retailmind-frontend/`: Angular 17 standalone, diseño "Dubai", Angular Material.
  Pantallas operativas en `features/operativo/` (incluye `marketing/`); estilos compartidos
  `operativo-shared.scss`; errores con `core/services/api-error.util.ts`.
- **ETL** `retailmind/`: Python 3.12, PocketBase → Parquet → ClickHouse. El DDL operativo vigente
  de PostgreSQL está en `retailmind/sql/postgres/` (28 scripts numerados).

## Seguridad a nivel de BD (lo más importante)

- 8 roles de grupo en PostgreSQL: `grp_administrador, grp_gerente, grp_vendedor, grp_compras,
  grp_bodega, grp_despacho, grp_cliente, grp_analista` — con matriz de privilegios GRANT, **RLS**
  (cliente aislado vía `app.cliente_id`) y **restricción por horario** (`grupo_horario` +
  `esta_en_horario()` + triggers). Admin exento de horario.
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
  `analista@retailmind.com`) y clientes demo (`maria.lopez@demo.com`, `carlos.vera@demo.com`):
  `Retail2026!`

## Qué está hecho / qué falta

**Hecho**: catálogo maestro; ciclo de compra (orden→aprobar→recepción→factura→pago); inventario
(transferencias, ajustes, kardex); ciclo de venta (pedido→factura→despacho→devolución) con PDF;
horarios de acceso; marketing (cupones, promociones+productos, campañas, banners, newsletter —
solo gestión); tienda online (carrito, wishlist, checkout, perfil, recomendaciones); analítica
ClickHouse (dashboard, funnel, sesiones, región/dispositivo/tráfico, reportes).

**Pendiente**: aplicación real de descuentos (cupones/promociones a pedidos, alimenta `uso_cupon`);
módulos de soporte y reseñas; orquestación ETL con Airflow.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
