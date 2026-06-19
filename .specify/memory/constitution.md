<!--
SYNC IMPACT REPORT
===================
Version change: [TEMPLATE / sin versión] → 1.0.0
Bump rationale: Ratificación inicial. Se reemplaza la plantilla con placeholders por la
  constitución concreta del proyecto RetailMind. Al establecer por primera vez todos los
  principios y secciones de gobernanza, corresponde la línea base MAJOR 1.0.0.

Principios definidos (7):
  - I. Plataforma Web Escalable y Distribuible (antes [PRINCIPLE_1_NAME])
  - II. El Negocio Vende: Capa Operativa Primero (antes [PRINCIPLE_2_NAME])
  - III. Arquitectura por Niveles Empresariales (antes [PRINCIPLE_3_NAME])
  - IV. ClickHouse como Única Fuente Operativa y Pipeline por Niveles (antes [PRINCIPLE_4_NAME])
  - V. Seguridad por Defecto: JWT + RBAC (NO NEGOCIABLE) (antes [PRINCIPLE_5_NAME])
  - VI. Calidad de Código y Convenciones del Repositorio (nuevo)
  - VII. Verificación y Pruebas (nuevo)

Secciones añadidas:
  - Restricciones Tecnológicas y Arquitectura de Datos (antes [SECTION_2_NAME])
  - Flujo de Desarrollo y Estándares de Entrega (antes [SECTION_3_NAME])
  - Governance (rellenada)

Plantillas dependientes revisadas:
  - .specify/templates/plan-template.md ........ ✅ compatible (sección "Constitution Check"
      genérica; las puertas de calidad se evalúan contra estos principios sin cambios de formato)
  - .specify/templates/spec-template.md ........ ✅ compatible (sin acoplamiento a principios)
  - .specify/templates/tasks-template.md ....... ✅ compatible (categorías de tareas admiten
      seguridad/pruebas exigidas por los principios V y VII)
  - .claude/skills/speckit-*/SKILL.md .......... ✅ sin referencias obsoletas a agente único

Pendientes / deferidos (marcados como PENDIENTE en el cuerpo):
  - Orquestación con Apache Airflow (ejecución cada 2 h): NO implementada en el código actual.
  - Capa de datos procesados data/agg/: directorio existe pero está vacío (.gitkeep).
  - Aspiración multi-región / multi-idioma: no implementada (visión).
  - Divergencia conocida: EtlService apunta a scripts ETL legacy inexistentes.
-->

# RetailMind Constitution

RetailMind es una plataforma web de retail analytics con tienda online integrada. Esta
constitución define los principios no negociables, las restricciones tecnológicas y las reglas
de gobernanza que rigen su construcción y evolución.

## Core Principles

### I. Plataforma Web Escalable y Distribuible

RetailMind ES un sistema **web**, nunca una aplicación de escritorio. Toda funcionalidad se
expone a través de la SPA (Angular) y la API REST (Spring Boot), y se empaqueta en contenedores
orquestados por Docker Compose.

- El backend MUST ser **stateless** (sesiones JWT sin estado de servidor) para permitir escalado
  horizontal y despliegue en servidores distribuidos.
- Cada componente (frontend, backend, ClickHouse, PocketBase, ETL) MUST poder construirse y
  ejecutarse de forma independiente vía su propio `Dockerfile`.
- La aspiración internacional (multi-región, multi-idioma) es parte de la visión. Las decisiones
  de diseño MUST NOT bloquear esa evolución (p. ej., no hardcodear zona horaria/locale únicos en
  lógica de negocio). *PENDIENTE: la internacionalización efectiva aún no está implementada y se
  trata como objetivo, no como requisito vigente.*

**Razón**: el sistema es el esqueleto y el músculo del negocio; debe poder crecer sin reescritura.

### II. El Negocio Vende: Capa Operativa Primero

Si el sistema no genera órdenes ni facturas, el negocio no vende. La **capa operativa** es la
prioridad máxima del producto.

- Los flujos que producen ingresos (autenticación, catálogo, carrito, wishlist, pedidos/checkout,
  perfil, recomendaciones) MUST tener prioridad sobre analítica y reportes ante conflictos de
  alcance, tiempo o recursos.
- Ningún cambio en analítica/estrategia puede degradar o romper la capacidad de completar una
  compra. Una orden no debe poder perderse por un fallo en módulos tácticos o estratégicos.

**Razón**: la analítica describe el negocio; la operación lo sostiene.

### III. Arquitectura por Niveles Empresariales

El dominio se organiza en tres niveles explícitos, reflejados en la estructura de paquetes del
backend y de features del frontend:

- **Operativo** (genera ventas): `auth`, `catalogo`, `carrito`, `wishlist`, `pedidos`, `perfil`,
  `recomendaciones`.
- **Táctico** (toma de decisiones): `analytics/sesiones`, `analytics/conversiones`,
  `analytics/funnel`, `analytics/region`, `analytics/dispositivo`, `analytics/trafico`,
  `admin/reportes`.
- **Estratégico**: `analytics/dashboard` (KPIs ejecutivos).

Cada módulo nuevo MUST declararse dentro de uno de estos niveles. No se permiten módulos sin
nivel asignado ni dependencias que crucen niveles en sentido inverso (estratégico → operativo) que
acoplen la venta a la analítica.

**Razón**: separar operación, decisión y estrategia mantiene el sistema comprensible y evolutivo.

### IV. ClickHouse como Única Fuente Operativa y Pipeline por Niveles

ClickHouse (esquema estrella, ~2.3M eventos en `fact_eventos`) es la **única base de datos
operativa** del sistema. PocketBase actúa como fuente del dataset crudo de retail
(colección `dataset_retail`).

- El flujo de datos canónico es: **datos crudos (PocketBase) → `data/stage/*.parquet` →
  datos procesados (`data/agg/`) → carga a ClickHouse**. El uso de archivos Parquet como capa
  intermedia es obligatorio.
- PostgreSQL fue **eliminado**. El DDL en `retailmind/sql/` y las dependencias `psycopg2-binary` /
  `sqlalchemy` son **legacy** y MUST NOT usarse en código nuevo; su reintroducción está prohibida.
- El acceso a datos desde el backend MUST hacerse con `JdbcTemplate` y consultas parametrizadas
  (sin JPA/Hibernate y sin concatenación de SQL).
- *PENDIENTE*: la capa `data/agg/` existe pero aún está vacía (solo `.gitkeep`); la orquestación
  con **Apache Airflow cada 2 horas** es parte de la visión y **no está implementada** (no hay
  servicio Airflow en `docker-compose.yml` ni job programado). Hoy el ETL se dispara manualmente
  por un ADMIN vía la API (`/api/etl/**`).

**Razón**: una única fuente operativa columnar y un pipeline por niveles garantizan rendimiento
analítico y trazabilidad del dato.

### V. Seguridad por Defecto: JWT + RBAC (NO NEGOCIABLE)

La autenticación y autorización son obligatorias y se aplican por defecto.

- Autenticación vía **JWT** con Spring Security en modo **STATELESS**; contraseñas con **BCrypt**.
- Control de acceso basado en roles (**RBAC**) con dos roles: **ADMIN** y **CLIENTE**.
- Reglas de acceso vigentes (deben respetarse y no relajarse sin enmienda):
  - **Catálogo público**: `GET /api/catalogo/**` y registro de eventos de catálogo son abiertos.
  - **Analítica avanzada, ETL, gestión, reportes y administración** (`region`, `dispositivo`,
    `trafico`, `funnel`, `/api/etl/**`, `/api/gestion/**`, `/api/admin/**`, `/api/reportes/**`):
    **solo ADMIN**.
  - **Tienda y datos de usuario** (`perfil`, `recomendaciones`, carrito, wishlist, pedidos):
    requieren **usuario autenticado**.
- Todo endpoint nuevo MUST declarar explícitamente su nivel de acceso; el comportamiento por
  defecto es `authenticated()` (denegar lo no declarado).
- Secretos (JWT, credenciales de BD) MUST provenir de variables de entorno y NUNCA versionarse.

**Razón**: la plataforma maneja ventas y datos de negocio; la seguridad no es opcional.

### VI. Calidad de Código y Convenciones del Repositorio

- **Nomenclatura bilingüe coherente**: términos de dominio en **español** (`sesiones`,
  `conversiones`, `pedidos`, `carrito`); convenciones de framework en **inglés**
  (`Controller`, `Service`, `Repository`, `Component`). Java en `PascalCase`/`camelCase`,
  Python en `snake_case`, identificadores Angular según guía oficial.
- **Estructura del repositorio** (monorepo de 3 sub-proyectos sobre ClickHouse):
  - `retailmind/` — pipeline ETL en Python (subcarpetas `etl/extraccion`, `etl/carga`,
    `etl/analytics`, `etl/sinteticos`, `etl/reportes`; datos en `data/stage` y `data/agg`).
  - `retailmind-backend/` — API REST Spring Boot, paquetes por nivel/módulo bajo
    `com.retailmind` (Controller → Service → acceso a datos).
  - `retailmind-frontend/` — SPA Angular 17 standalone, con `core/` (servicios, guards,
    interceptors, models) y `features/` por módulo lazy-loaded.
- Frontend con **componentes standalone** y rutas **lazy-loaded**; sin NgModules nuevos.
- El backend separa **Controller → Service → acceso a datos**; los controladores no contienen
  lógica de negocio ni SQL.
- Código y comentarios de dominio en español; mensajes de error orientados al usuario.

**Razón**: convenciones explícitas reducen fricción en un equipo y un dominio bilingües.

### VII. Verificación y Pruebas

- Todo cambio MUST compilar/construir antes de considerarse terminado: `mvn clean install`
  (backend), `npm run build` (frontend), ejecución de scripts ETL sin errores.
- Las **operaciones de venta** (carrito, checkout, pedidos) y la **seguridad** (login, RBAC)
  son rutas críticas y MUST cubrirse con pruebas antes de fusionarse.
- El backend usa **Spring Boot Test** como framework base; el ETL valida cargas con sus pasos de
  verificación (p. ej. `verify_clickhouse`). Las pruebas nuevas MUST usar el framework existente
  del sub-proyecto correspondiente.
- La resiliencia del ETL (reintentos vía Spring Retry) MUST conservarse: un fallo transitorio no
  debe corromper datos ni dejar cargas a medias sin reportar el error.

**Razón**: lo que sostiene la venta y el dato debe demostrarse, no suponerse.

## Restricciones Tecnológicas y Arquitectura de Datos

El stack está fijado y verificado contra el código. Cambiarlo requiere enmienda formal.

- **Frontend**: Angular 17.3 (standalone, lazy-loaded), Angular Material + CDK 17.3,
  Chart.js 4.5, TypeScript 5.4; exportación de reportes con `xlsx` y `jspdf`.
- **Backend**: Java 17, Spring Boot 3.5.0, Spring Web + Spring JDBC (`JdbcTemplate`, **sin JPA**),
  Spring Security + JWT (`jjwt` 0.12.3), Spring Retry; driver `clickhouse-jdbc` 0.6.5; reportes
  con Apache POI 5.2.5 (Excel) e iText 5.5.13 (PDF).
- **BD analítica**: ClickHouse (columnar, esquema estrella) — única BD operativa.
- **Fuente de datos / tienda**: PocketBase (dataset crudo `dataset_retail`). *Nota de
  implementación*: la autenticación de usuarios del sistema se resuelve hoy contra ClickHouse
  (`usuarios_sistema` vía `ClickHouseUserRepository`), no contra PocketBase.
- **ETL**: Python 3.12 (imagen `python:3.12-slim`), con `clickhouse-connect` 0.7.16,
  `pyarrow` 16.1.0, `pandas` 2.2.2 y cliente `pocketbase` 0.12.1.
- **Contenedores**: Docker Compose con **5 servicios**: `pocketbase`, `clickhouse`, `backend`,
  `frontend`, `etl`.

**Validación de compatibilidad de versiones** (requisito de gobernanza, reportada al ratificar):

- Python 3.12 + `clickhouse-connect` 0.7.16 + `pyarrow` 16.1.0 + `pandas` 2.2.2 + `pocketbase`
  0.12.1 → **compatibles** (todas soportan 3.12). Se detectaron artefactos locales `cpython-314`;
  el runtime canónico es 3.12 según el `Dockerfile`, que debe prevalecer.
- ClickHouse JDBC 0.6.5 + Spring Boot 3.5.0 + Java 17 → **compatibles**.
- `jjwt` 0.12.3 + Spring Boot 3.5 → **compatibles**.
- **Apache Airflow (visión, PENDIENTE)**: si se incorpora, MUST fijarse a Python 3.12 (Airflow
  2.9/2.10 soporta 3.8–3.12). Airflow **no** soporta Python 3.14; usar 3.14 sería incompatible.
- **Deuda detectada**: `psycopg2-binary` 2.9.9 y `sqlalchemy` 2.0.30 permanecen en
  `requirements.txt` pero son legacy (PostgreSQL eliminado) y deben retirarse. `EtlService`
  invoca scripts inexistentes (`etl/load_csv_staging.py`, `etl/05_load_incremental.py`); debe
  realinearse con la estructura actual (`etl/extraccion`, `etl/carga`).

## Flujo de Desarrollo y Estándares de Entrega

- **Comandos canónicos**: backend `mvn clean install` / `mvn spring-boot:run`; frontend
  `npm install` / `npm start` / `npm run build`; sistema completo `docker-compose up -d`.
- Todo trabajo se realiza por feature; las especificaciones se gestionan con Spec Kit en `specs/`
  (constitución en `.specify/memory/constitution.md`).
- Las puertas de calidad (Constitution Check de los planes) MUST verificar: nivel empresarial del
  módulo (Principio III), acceso/seguridad declarados (Principio V), uso de ClickHouse + Parquet
  (Principio IV) y cobertura de pruebas en rutas críticas (Principio VII).
- Cualquier desviación de un principio MUST justificarse en la sección de complejidad del plan o,
  si es permanente, mediante enmienda a esta constitución.
- Los elementos marcados **PENDIENTE** (Airflow, capa `data/agg`, i18n/multi-región) NO deben
  documentarse como ya implementados; los planes que los aborden deben tratarlos como trabajo
  nuevo.

## Governance

Esta constitución **prevalece** sobre cualquier otra práctica o convención del proyecto.

- **Enmiendas**: requieren (1) descripción del cambio y su motivo, (2) actualización de versión
  según versionado semántico, y (3) revisión de impacto sobre las plantillas dependientes en
  `.specify/templates/`.
- **Versionado**:
  - **MAJOR**: eliminación o redefinición incompatible de principios o reglas de gobernanza.
  - **MINOR**: nuevo principio/sección o ampliación material de una guía.
  - **PATCH**: aclaraciones, redacción o correcciones no semánticas.
- **Cumplimiento**: toda revisión de código y todo plan de feature MUST verificar conformidad con
  estos principios. La complejidad o desviación no justificada se rechaza.
- **Fuente de verdad**: ante conflicto entre esta constitución y los documentos de steering
  (`.kiro/steering/`), prevalece la constitución; el steering debe actualizarse para alinearse.

**Version**: 1.0.0 | **Ratified**: 2026-06-18 | **Last Amended**: 2026-06-18
