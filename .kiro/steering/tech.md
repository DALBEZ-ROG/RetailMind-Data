# Tech Stack

> Stack verificado contra el código (pom.xml, package.json, application.properties, esquema real).
> Arquitectura **híbrida**: PostgreSQL es la BD operativa principal (incluida la tienda del
> cliente, migrada 2026-07-11); ClickHouse es **solo analítica**. Si algún documento viejo dice
> "PostgreSQL eliminado" o describe la tienda sobre ClickHouse, está desactualizado: ignorarlo.
>
> **Desde el 2026-08-03 PostgreSQL corre EN UN CONTENEDOR** (bitácora:
> `docs/DESPLIEGUE_EJECUTADO.md`), así que la vieja frase «con Docker apagado todo funciona» YA NO
> VALE: sin Docker no hay base. Lo que SÍ sigue en pie es que **ClickHouse es solo analítica**: con
> ClickHouse apagado TODO el sistema funciona y solo analytics/recomendaciones se degradan con
> aviso. Por eso el compose declara `clickhouse: service_started` y NUNCA `service_healthy`.

## Bases de datos (arquitectura híbrida — dual datasource)

### PostgreSQL (operativa principal)
- **BD**: `retailmind` (~103 tablas transaccionales, catálogo con ~1.214 productos reales),
  **en el CONTENEDOR Docker** (`postgres`, PostgreSQL 18.4 Debian) publicado en el
  **5432 del anfitrión**. El DDL vigente son los scripts numerados de
  `retailmind/sql/postgres/` (01–85 + 99).
- **El PostgreSQL local de Windows** (18.3, servicio `postgresql-x64-18`) **se movió al 5433**:
  ahí viven 12 bases de otras materias más una copia congelada de `retailmind` que sirve de
  marcha atrás. **No se desinstala.**
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
- **Dos bases**: `retailmind` (LEGADA — `fact_eventos` ~2,8M + `dim_*`, la lee `analytics/`) y
  **`retailmind_dwh`** (el almacén VIVO: 21 tablas, 64.664 filas, alimentado desde PostgreSQL por
  `retailmind/etl/dwh/`). Los informes compuestos y los tableros cualifican `retailmind_dwh`
  explícitamente.
- Versión fijada en el compose: **`26.4.2.10`** — nunca `:latest` sobre un motor con formato en
  disco propio.
- Su volumen es **`external: true`**: guarda un dato irreproducible y debe fallar si falta.
- **No usar para el núcleo operativo.**

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
- Python 3.12, clickhouse-connect, psycopg2, pyarrow, pandas.
- **Flujo VIGENTE**: PostgreSQL → `etl/dwh/` → ClickHouse `retailmind_dwh` (21 tablas,
  carga atómica staging `_new` → validar → `EXCHANGE TABLES`). Corre con el rol de motor
  `retailmind_etl` (solo lectura, BYPASSRLS).
- El flujo viejo desde **PocketBase** es histórico: el servicio se **eliminó** del compose.
- Dentro de Docker el ETL es el servicio `etl` (perfil `tools`) y `./retailmind` va **montado**,
  así que un cambio de Python entra sin reconstruir la imagen.

## Infraestructura

**TODO está contenerizado desde el 2026-08-03** (bitácora: `docs/DESPLIEGUE_EJECUTADO.md`). El
compose raíz declara **6 servicios** y `pocketbase` se **ELIMINÓ**:

| Servicio | Perfil | Arranca con `up -d` |
|---|---|---|
| `postgres` | — | sí (la base VIVA) |
| `clickhouse` | — | sí (analítica) |
| `backend` | `demo` | sí, porque `.env` fija `COMPOSE_PROFILES=demo` |
| `frontend` | `demo` | sí, ídem |
| `etl` | `tools` | no: se invoca a demanda |
| `pgadmin` | `tools` | no: se invoca a demanda |

**Puertos**: **5432 = el CONTENEDOR** (base viva) · **5433 = el PostgreSQL local** (plan B + bases
de otras materias) · backend 8080 · frontend 4200 · ClickHouse 8123/9000 · pgAdmin 5050.

### Trampas del despliegue (§8 de `docs/DESPLIEGUE_EJECUTADO.md`)
- Un cambio de **Java o Angular NO entra solo**: la imagen está horneada, hace falta
  `docker compose up -d --build`. El **Python del ETL sí es inmediato**, porque `./retailmind` va
  **montado**, no copiado.
- Los **datos viven en el volumen, no en la imagen**: reconstruir **NO** los borra.
- Un **script SQL nuevo NO se aplica solo**: `deploy/postgres/initdb/` corre **una única vez**, con
  el volumen vacío. Para aplicar uno nuevo:
  `docker compose exec -T postgres psql -U postgres -d retailmind < ruta/al/script.sql`.
- **Ningún `down` debe llevar `-v`**: el volumen de ClickHouse guarda un dato irreproducible
  (`fact_eventos`, 2.823.245 filas) y por eso va declarado `external: true`.
- Al interpretar un **403 de bodega/despacho/compras, mirar el reloj antes que la migración**:
  `fuera_horario` bloquea el LOGIN entero, así que el rol no llega ni a pedir el endpoint.

### Secretos
**NO hay contraseñas en el código ni en los documentos de contexto.** `application.properties`
dejó de tener valores por defecto para `postgres.datasource.password` y `jwt.secret`: la app
**falla al arrancar** si faltan, a propósito. Dónde vive cada secreto (los cuatro archivos están
**fuera del índice de git**, verificado):

| Secreto | Dónde vive |
|---|---|
| Superusuario `postgres` del contenedor | `deploy/secrets/pg_superuser.txt` (secreto de Docker) |
| Rol `retailmind_app` | `.env` → `PG_APP_PASSWORD` |
| Rol `retailmind_etl` | `.env` → `PG_ETL_PASSWORD` · `retailmind/.env` → `ETL_PG_PASSWORD` |
| `jwt.secret` | `.env` → `JWT_SECRET` |
| Modo desarrollo (fuera de Docker) | `retailmind-backend/application-local.properties` |

`.env.example` es la plantilla versionada: lleva las **claves sin los valores**.

## Orquestación del pipeline
- El ETL se dispara manualmente por un ADMIN vía `/api/etl/**` (`ProcessBuilder`), y
  automáticamente por el `@Scheduled` del backend según `DWH_CRON` del `.env` (02:00 por defecto).
  `run_etl.py` ya orquesta las 21 tablas en orden topológico.
- **PENDIENTE**: Apache Airflow (§7 del diseño de despliegue). Contenerizar PostgreSQL eliminó la
  fricción que lo bloqueaba; la red `retailmind_net` tiene nombre FIJO justamente para que el
  compose de Airflow se enganche como red externa. **El día que Airflow tome el relevo hay que
  poner `DWH_CRON=-` en el `.env`**, o a las 02:00 disparan los dos y dos cargas concurrentes
  compiten por el `EXCHANGE TABLES` del mismo destino.
- Estado del catálogo: **30 informes simples + 43 rutas de informe compuesto**, 7 tableros
  estratégicos y 2 modelos. Los **39 objetivos compuestos** del catálogo están COMPLETOS desde
  el 2026-08-07 (entraron OTD-VEN-03 y OTD-VEN-04). Cuidado con el conteo: **43 rutas ≠ 43
  objetivos** — las 4 de más son los 2 modelos, `costo-envio-mensual` y `prevision-demanda`,
  que se sirve en dos departamentos. Detalle en `CLAUDE.md`,
  `docs/tactico/PATRON_INFORMES.md` y la ficha **C-14** de `DEUDA_TECNICA.md`.

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

> Estas credenciales de **LOGIN** siguen intactas tras la rotación del 2026-08-03 (verificado: los
> 10 usuarios entran). Lo que se rotó fueron **cuatro secretos internos que nadie teclea** — el
> superusuario `postgres` del contenedor, `retailmind_app`, `retailmind_etl` y el `jwt.secret` —,
> porque estaban en claro y versionados. **No los escribas aquí**: ver la tabla de §Secretos.
> El superusuario del PostgreSQL **local (5433) NO se rotó**: esa contraseña la comparten los MCP
> de otras materias.

## Common Commands

### Docker (el sistema entero)
```bash
docker compose up -d               # postgres + clickhouse + backend + frontend (~28 s)
docker compose up -d --build       # OBLIGATORIO tras cambiar codigo Java o Angular
docker compose down                # NUNCA con -v
```

### Modo desarrollo (backend y frontend a mano)
```bash
docker compose up -d postgres clickhouse   # solo la base y la analitica
docker compose stop backend frontend       # libera 8080 y 4200
```

### Backend
```bash
cd retailmind-backend
mvn compile                        # Compilar
mvn spring-boot:run                # Dev server en 8080 — EXIGE application-local.properties
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081   # Si 8080 ocupado
```
Sin `application-local.properties` el backend **se niega a arrancar**
(`Could not resolve placeholder 'JWT_SECRET'`). Es el comportamiento buscado.

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

### ETL al almacén (solo analítica)
```bash
cd retailmind
python -m etl.dwh.cargar --listar          # tablas del DWH
python -m etl.dwh.cargar --tabla fact_pedido
python -m etl.dwh.validar_dwh              # 49 controles contra PostgreSQL
```
Dentro de Docker: `docker compose run --rm etl python -m etl.dwh.cargar --tabla X`.

#### Inspección de esquema PostgreSQL
```
MCP retailmind (solo lectura) — ya apunta al CONTENEDOR (localhost:5432/retailmind).
```
Para entrar por psql sin salir de Docker (no hace falta contraseña, el secreto lo
resuelve el contenedor):
```bash
docker compose exec -it postgres psql -U postgres -d retailmind
```
**Ninguna contraseña se escribe en este archivo ni en ningún otro versionado.** Ver
§"Secretos" arriba para saber dónde vive cada una.
