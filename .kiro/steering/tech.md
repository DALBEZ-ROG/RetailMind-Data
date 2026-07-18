# Tech Stack

> Stack verificado contra el código (pom.xml, package.json, application.properties, esquema real).
> Arquitectura **híbrida**: PostgreSQL es la BD operativa principal (incluida la tienda del
> cliente, migrada 2026-07-11); ClickHouse es **solo analítica**. Con ClickHouse apagado TODO
> funciona; solo analytics/recomendaciones se degradan con aviso. Si algún documento viejo dice
> "PostgreSQL eliminado" o describe la tienda sobre ClickHouse, está desactualizado: ignorarlo.

## Bases de datos (arquitectura híbrida — dual datasource)

### PostgreSQL (operativa principal)
- **BD**: `retailmind` (~103 tablas transaccionales, catálogo con ~1.214 productos reales),
  local en `localhost:5432` (no está en docker-compose; se administra con los scripts de
  `retailmind/sql/postgres/`, scripts numerados 01–45 + 99).
- **Conexión de la app**: usuario `retailmind_app` (LOGIN **NOINHERIT**, sin privilegios de
  negocio directos). Nunca conectar como `postgres` desde la app.
- **Seguridad de motor**: 9 roles de grupo `grp_administrador`, `grp_gerente`, `grp_vendedor`,
  `grp_compras`, `grp_bodega`, `grp_despacho`, `grp_cliente`, `grp_analista`, `grp_soporte`;
  matriz de privilegios GRANT por tabla (y POR COLUMNA para segregación financiera: BODEGA y
  DESPACHO no leen montos, script 41); **RLS** (aislamiento de cliente vía `app.cliente_id`,
  incluidos `pago`/`transaccion_pago`/`cupon`/`uso_cupon` desde el script 43);
  restricción por **horario** (`grupo_horario` + `esta_en_horario()` + triggers de bloqueo;
  admin exento, soporte 24/7). OJO al crear un rol nuevo: además de los GRANTs necesita
  `GRANT USAGE ON SCHEMA public` (el script 19 lo revocó a PUBLIC) y política RLS propia en
  cada tabla con RLS — patrón completo en `37_rol_soporte.sql`.
- **Integridad en la BD**: columnas GENERATED, totales de cabecera calculados por triggers,
  triggers `touch` para `fecha_actualizacion`, contadores como `usos_actuales` (NO escribirlos),
  CHECKs de estado/vigencia. Numeración de documentos por secuencia global
  `seq_numero_documento` y tickets por `fn_siguiente_numero_ticket()` (script 43) — nunca por
  azar ni por count(+1).
- **Trazabilidad de autor (script 42)**: columnas directas FK a usuario en `pedido.vendedor_id`
  (NULL en canal 'web'), `envio.despachado_por`, `factura_compra.registrado_por`,
  `resena`/`pregunta_producto`.`moderado_por`+`fecha_moderacion`; `log_auditoria` append-only
  por grants (INSERT para admin/gerente/vendedor/despacho/compras y bodega — script 45;
  grp_cliente sin INSERT a propósito), CHECK de acciones, sin RLS.

### ClickHouse (solo analítica)
- Esquema estrella: `fact_eventos` (~2.3M) + dimensiones `dim_*` + tablas legacy de tienda.
- Alimentado por el ETL Python desde PocketBase. **No usar para el núcleo operativo.**

## Backend (`retailmind-backend/`)
- **Language**: Java 17 · **Framework**: Spring Boot 3.5.0 · **Build**: Maven
- **Data access**: Spring JDBC (`JdbcTemplate`) — **sin JPA/Hibernate**, consultas parametrizadas
- **Dual DataSource** (declarados a mano; `DataSourceAutoConfiguration` excluida):
  - `PostgresConfig`: `pgDataSource` + `pgJdbcTemplate` (cualificado) +
    `DataSourceTransactionManager` **@Primary** → todo `@Transactional` va contra Postgres.
  - `ClickHouseConfig`: datasource/JdbcTemplate de analytics (driver clickhouse-jdbc 0.6.5)
    con **fail-fast** (2026-07-18): pool Hikari `connectionTimeout=3s` + driver
    `connect_timeout=2.5s`/`socket_timeout=30s` — con ClickHouse apagado `/api/health`
    responde acotado (~3s, `status: UP, analytics: DEGRADED`) y sirve como healthcheck de
    contenedores; NO quitar esos timeouts.
- **SET LOCAL ROLE por transacción**: aspecto `PgSessionRoleAspect` (spring-boot-starter-aop) —
  dentro de cada `@Transactional` (excluye `analytics/`) asume el `grp_*` del usuario autenticado
  y fija `app.cliente_id` si es CLIENTE. Por eso **todo acceso a Postgres debe ir dentro de
  `@Transactional`**.
- **Auth**: Spring Security + JWT (jjwt 0.12.3), STATELESS, BCrypt. Login contra la tabla
  `usuario` de **PostgreSQL** (`PostgresUserRepository` / `AuthService`); el campo del login es
  `username` (contiene el email). `ClickHouseUserRepository` es el camino viejo.
- **Errores**: `GlobalExceptionHandler` → `ApiErrorDTO` con mensajes de negocio claros
  (404/400/409; SQLState 42501 → 403 "fuera de horario / sin privilegios").
- **Auditoría**: `auditoria/AuditoriaService.registrar()` — una fila en `log_auditoria`
  (jsonb antes/después, lista blanca de acciones espejo del CHECK) con el usuario del JWT,
  NUNCA del body; DEBE invocarse dentro de la `@Transactional` del caso de uso para correr
  bajo `SET LOCAL ROLE` y confirmarse/revertirse junto con la acción que documenta.
- **Reportes/PDF**: Apache POI 5.2.5 (Excel), iText 5.5.13.3 (PDF de facturas)
- **Resilience**: Spring Retry (ETL)

## Frontend (`retailmind-frontend/`)
- **Framework**: Angular 17.3 (standalone components, lazy-loaded, sin NgModules)
- **UI**: Angular Material 17 + CDK; diseño **"Dubai"** (`styles.scss` con variables, `.glass-card`,
  sidebar premium); pantallas operativas comparten `features/operativo/operativo-shared.scss`
- **Auth**: `auth.service` + `auth.interceptor` (JWT) + `roleGuard(...)`/`adminGuard` funcionales
- **Errores**: `core/services/api-error.util.ts` (`mensajeError`) extrae el mensaje de negocio del
  `ApiErrorDTO`; nunca mostrar el texto técnico del status
- **Charts**: Chart.js 4.5 · **Reportes cliente**: xlsx, jspdf · **TypeScript**: 5.4

## Tienda del cliente (solo rol CLIENTE)
- Backend en paquetes `catalogo/`, `carrito/`, `wishlist/`, `perfil/`, `recomendaciones/` contra
  `pgJdbcTemplate`. El id público de producto es el de la **VARIANTE**.
- Checkout llama a `VentasService.crearPedido` (mismo pedido que el back-office, stock vía
  `StockService`).
- Script `34_grants_tienda_cliente.sql` da a `grp_cliente` lo necesario (inventario,
  movimiento_inventario, tipo_movimiento, bodega, historial_estado_pedido + políticas RLS
  de horario).
- Eventos a ClickHouse solo best-effort (`EventoTiendaService`).
- Recomendaciones con señal ClickHouse + productos PG; degradan a destacados si CH no disponible.

## ETL Pipeline (`retailmind/`)
- Python 3.12, clickhouse-connect, pocketbase, pyarrow, pandas
- Flujo: PocketBase → `data/stage/*.parquet` → ClickHouse (solo analítica)
- psycopg2 disponible para inspección/administración de PostgreSQL en desarrollo

## Infraestructura
- **Docker Compose (5 servicios)**: `pocketbase`, `clickhouse`, `backend`, `frontend`, `etl`.
  **PostgreSQL corre local** (fuera de compose) por ahora.
- **Puertos**: backend 8080 (si está ocupado por otra app, levantar en 8081 con
  `--server.port=8081` y detenerlo al terminar), frontend 4200, ClickHouse 8123, Postgres 5432.

## Orquestación del pipeline
- El ETL se dispara manualmente por un ADMIN vía `/api/etl/**` (`ProcessBuilder`).
- **PENDIENTE (siguiente fase)**: Apache Airflow. No implementado. El análisis del nivel
  táctico (2026-07-17, `docs/RetailMind_T11_Analisis_Tactico.pdf`) definió 25 informes por
  departamento: 12 simples directo de PostgreSQL y 13 compuestos que justifican este pipeline
  hacia ClickHouse.

## Reglas de oro (obligatorias en código nuevo)
1. **Nunca** escribir columnas GENERATED ni totales de cabecera (los ponen triggers de la BD).
   Tampoco `fecha_actualizacion` (trigger touch) ni contadores como `usos_actuales`.
2. **Todo** acceso a Postgres dentro de `@Transactional` (si no, `SET LOCAL ROLE` no aplica y la
   operación corre sin privilegios de negocio).
3. Validación por **lista blanca** + parámetros de JdbcTemplate; **nunca** concatenar SQL.
4. Guardias de estado/idempotencia con mensaje claro: `IllegalArgumentException` → 400,
   `IllegalStateException` → 409, `NoSuchElementException` → 404 (vía `GlobalExceptionHandler`).
5. Errores al frontend siempre con `api-error.util.ts`; pantallas nuevas imitan el patrón de
   `features/operativo/` (tabla + formulario + toggle activo, diseño Dubai).
6. Ruta nueva = entrada en `SecurityConfig` + `roleGuard` en `app.routes.ts` + entrada en el
   sidebar (`app.component.html`, getters `canX`) + `routeMap` de breadcrumbs (`app.component.ts`).
7. No modificar tablas existentes ni sus triggers; no tocar `analytics/` ni ClickHouse desde lo
   operativo.
8. Parámetros null hacia PG en contexto no tipado: castear en SQL (`?::bigint`,
   `NULLIF(?,'')::timestamptz`).

## Credenciales de desarrollo
- Admin: `admin@retailmind.com` / `Admin2026!`
- Usuarios de prueba por rol (gerente@, vendedor@, compras@, bodega@, despacho@, analista@
  `retailmind.com`): `Retail2026!` (seed `27_seed_usuarios_prueba_roles.sql`);
  `soporte@retailmind.com`: `Retail2026!` (script 37)
- Clientes demo (`maria.lopez@demo.com`, `carlos.vera@demo.com`): `Cliente2026!` (script 26)

## Common Commands

### Backend
```bash
cd retailmind-backend
mvn compile                        # Compilar
mvn spring-boot:run                # Dev server en 8080
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081   # Si 8080 ocupado
```

### Frontend
```bash
cd retailmind-frontend
npm start                          # ng serve, puerto 4200
npm run build                      # Build a dist/
```

### Verificación mínima antes de dar por bueno un cambio
```bash
cd retailmind-backend && mvn compile
cd retailmind-frontend && ng build
```

### ETL (solo analítica)
```bash
cd retailmind
python etl/extraccion/08_extract_pocketbase.py
python etl/carga/09_load_clickhouse.py
```

### Docker (analítica + tienda legacy)
```bash
docker-compose up -d               # pocketbase, clickhouse, backend, frontend, etl
```

### Inspección de esquema PostgreSQL
```
MCP retailmind (solo lectura) o psycopg2 (postgres/1250143656@localhost:5432/retailmind)
```
