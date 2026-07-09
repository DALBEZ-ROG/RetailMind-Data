# Tech Stack

> Stack verificado contra el código (pom.xml, package.json, application.properties, esquema real).
> Arquitectura **híbrida**: PostgreSQL es la BD operativa principal; ClickHouse es solo analítica.

## Bases de datos (arquitectura híbrida — dual datasource)

### PostgreSQL (operativa principal)
- **BD**: `retailmind` (~102 tablas transaccionales), local en `localhost:5432` (no está en
  docker-compose; se administra con los scripts de `retailmind/sql/postgres/`, 28 scripts
  numerados 01–27 + 99).
- **Conexión de la app**: usuario `retailmind_app` (LOGIN **NOINHERIT**, sin privilegios de
  negocio directos). Nunca conectar como `postgres` desde la app.
- **Seguridad de motor**: 8 roles de grupo `grp_administrador`, `grp_gerente`, `grp_vendedor`,
  `grp_compras`, `grp_bodega`, `grp_despacho`, `grp_cliente`, `grp_analista`; matriz de
  privilegios GRANT por tabla; **RLS** (aislamiento de cliente vía `app.cliente_id`); restricción
  por **horario** (`grupo_horario` + `esta_en_horario()` + triggers de bloqueo; admin exento).
- **Integridad en la BD**: columnas GENERATED, totales de cabecera calculados por triggers,
  triggers `touch` para `fecha_actualizacion`, CHECKs de estado/vigencia.

### ClickHouse (solo analítica)
- Esquema estrella: `fact_eventos` (~2.3M) + dimensiones `dim_*` + tablas legacy de tienda.
- Alimentado por el ETL Python desde PocketBase. **No usar para el núcleo operativo.**

## Backend (`retailmind-backend/`)
- **Language**: Java 17 · **Framework**: Spring Boot 3.5.0 · **Build**: Maven
- **Data access**: Spring JDBC (`JdbcTemplate`) — **sin JPA/Hibernate**, consultas parametrizadas
- **Dual DataSource** (declarados a mano; `DataSourceAutoConfiguration` excluida):
  - `PostgresConfig`: `pgDataSource` + `pgJdbcTemplate` (cualificado) +
    `DataSourceTransactionManager` **@Primary** → todo `@Transactional` va contra Postgres.
  - `ClickHouseConfig`: datasource/JdbcTemplate de analytics (driver clickhouse-jdbc 0.6.5).
- **SET LOCAL ROLE por transacción**: aspecto `PgSessionRoleAspect` (spring-boot-starter-aop) —
  dentro de cada `@Transactional` (excluye `analytics/`) asume el `grp_*` del usuario autenticado
  y fija `app.cliente_id` si es CLIENTE. Por eso **todo acceso a Postgres debe ir dentro de
  `@Transactional`**.
- **Auth**: Spring Security + JWT (jjwt 0.12.3), STATELESS, BCrypt. Login contra la tabla
  `usuario` de **PostgreSQL** (`PostgresUserRepository` / `AuthService`); el campo del login es
  `username` (contiene el email). `ClickHouseUserRepository` es el camino viejo.
- **Errores**: `GlobalExceptionHandler` → `ApiErrorDTO` con mensajes de negocio claros
  (404/400/409; SQLState 42501 → 403 "fuera de horario / sin privilegios").
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
- **PENDIENTE (visión)**: Apache Airflow. No implementado.

## Reglas de oro (obligatorias en código nuevo)
1. **Nunca** escribir columnas GENERATED ni totales de cabecera (los ponen triggers de la BD).
2. **Todo** acceso a Postgres dentro de `@Transactional` (si no, `SET LOCAL ROLE` no aplica y la
   operación corre sin privilegios de negocio).
3. Validación por **lista blanca** + parámetros de JdbcTemplate; **nunca** concatenar SQL.
4. Guardias de estado/idempotencia con mensaje claro (`IllegalStateException` → 409,
   `IllegalArgumentException` → 400) vía `GlobalExceptionHandler`.
5. Errores al frontend siempre con `api-error.util.ts`; pantallas nuevas imitan el patrón de
   `features/operativo/` (tabla + formulario + toggle activo, diseño Dubai).
6. Ruta nueva = entrada en `SecurityConfig` + `roleGuard` en `app.routes.ts` + entrada en el
   sidebar (`app.component.html`) + `routeMap` de breadcrumbs (`app.component.ts`).

## Credenciales de desarrollo
- Admin: `admin@retailmind.com` / `Admin2026!`
- Usuarios de prueba por rol (gerente@, vendedor@, compras@, bodega@, despacho@, analista@
  `retailmind.com`; clientes `maria.lopez@demo.com`, `carlos.vera@demo.com`): `Retail2026!`
  (seed `27_seed_usuarios_prueba_roles.sql`)

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
