<!--
SYNC IMPACT REPORT
===================
Version change: 1.0.0 → 2.0.0
Bump rationale: MAJOR. El Principio IV ("ClickHouse como Única Fuente Operativa…
  PostgreSQL fue eliminado… su reintroducción está prohibida") se INVIERTE: el sistema real
  opera sobre PostgreSQL como única base transaccional (BD `retailmind`, 109 tablas verificadas
  el 2026-07-21) y ClickHouse quedó dedicado exclusivamente a analítica. Redefinición
  incompatible de un principio fundacional → cambio mayor según la regla de versionado de la
  sección Governance. La enmienda alinea la constitución con el sistema real documentado en
  `CLAUDE.md` y `.kiro/steering/` (product/tech/structure), según el diagnóstico de
  `docs/tactico/SINCRONIZACION_OBJETIVOS.md` (§7 Contradicciones).

Principios modificados en 2.0.0:
  - I. Plataforma Web Escalable y Distribuible ......... AJUSTE: la contenerización de
      PostgreSQL pasa a fase PENDIENTE declarada (corre local, fuera de docker-compose).
  - III. Arquitectura por Niveles Empresariales ........ REESCRITO: la enumeración del nivel
      Operativo incorpora el back-office real (compras, inventario, ventas, devoluciones,
      marketing, soporte, resenas, auditoria, admin/*) según `.kiro/steering/structure.md`.
  - IV. ClickHouse como Única Fuente Operativa ......... REEMPLAZADO por "Arquitectura Híbrida
      de Datos: PostgreSQL Transaccional, ClickHouse Analítico" (inversión del principio).
  - V. Seguridad por Defecto: JWT + RBAC ............... REESCRITO: de 2 roles de aplicación a
      9 roles de grupo con enforcement a nivel de motor (SET LOCAL ROLE, RLS, horario,
      segregación financiera por grants de columna).
  - VI. Calidad de Código y Convenciones ............... AJUSTE menor: se añade
      `sql/postgres/` (DDL operativo vigente, scripts 01-45 + 99) a la estructura del repo.
  - VII. Verificación y Pruebas ........................ AJUSTE menor: comando canónico de
      verificación mínima actualizado a `mvn compile` + `ng build`.

Principios intactos:
  - II. El Negocio Vende: Capa Operativa Primero (es el principio que mejor describe el
      estado actual del sistema; se conserva íntegro).

Secciones añadidas:
  - Historia Arquitectónica (tres etapas: PostgreSQL inicial → ClickHouse único → híbrido).
  - Registro de enmiendas (dentro de Governance).

Secciones actualizadas:
  - Restricciones Tecnológicas y Arquitectura de Datos (dual datasource, stack verificado).
  - Flujo de Desarrollo y Estándares de Entrega (comandos y puertas de calidad).

Plantillas dependientes revisadas (2026-07-21):
  - .specify/templates/plan-template.md ........ ✅ compatible (la sección "Constitution Check"
      es genérica; las puertas se evalúan contra los principios enmendados sin cambio de formato)
  - .specify/templates/spec-template.md ........ ✅ compatible (sin acoplamiento a principios)
  - .specify/templates/tasks-template.md ....... ✅ compatible (categorías de tareas admiten
      seguridad/pruebas exigidas por los principios V y VII)
  - .specify/templates/checklist-template.md ... ✅ compatible (genérica)
  - .specify/templates/constitution-template.md  ✅ sin cambios (plantilla base)

Pendientes / deferidos (marcados como PENDIENTE en el cuerpo):
  - Contenerización de PostgreSQL (hoy corre local, fuera de docker-compose).
  - Orquestación con Apache Airflow para los informes tácticos compuestos: NO implementada.
  - Aspiración multi-región / multi-idioma: no implementada (visión).
-->

# RetailMind Constitution

RetailMind es una plataforma web de retail para PyME con back-office operativo completo, tienda
online integrada y analítica. Esta constitución define los principios no negociables, las
restricciones tecnológicas y las reglas de gobernanza que rigen su construcción y evolución.

## Core Principles

### I. Plataforma Web Escalable y Distribuible

RetailMind ES un sistema **web**, nunca una aplicación de escritorio. Toda funcionalidad se
expone a través de la SPA (Angular) y la API REST (Spring Boot), y se empaqueta en contenedores
orquestados por Docker Compose.

- El backend MUST ser **stateless** (sesiones JWT sin estado de servidor) para permitir escalado
  horizontal y despliegue en servidores distribuidos.
- Los cinco servicios de `docker-compose.yml` (`frontend`, `backend`, `clickhouse`, `pocketbase`,
  `etl`) MUST poder construirse y ejecutarse de forma independiente vía su propio `Dockerfile`.
- **PostgreSQL corre hoy local (`localhost:5432`), fuera de docker-compose.** Su contenerización
  completa es una fase **PENDIENTE declarada** del proyecto (así consta en `CLAUDE.md` y en el
  steering); los planes que la aborden MUST tratarla como trabajo nuevo, y ningún documento
  MUST NOT presentarla como ya cumplida.
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
backend (`com.retailmind`) y de features del frontend:

- **Operativo** (sostiene y genera la venta). Dos bloques sobre PostgreSQL:
  - *Back-office* (`@Transactional` + `SET LOCAL ROLE`): `admin/catalogo`, `admin/horarios`,
    `admin/usuarios`, `compras` (incluida la devolución a proveedor), `inventario`, `ventas`
    (pedidos, facturación, preparación, despacho, novedades de envío), `devoluciones` (RMA),
    `marketing`, `soporte`, `resenas`, `auditoria`.
  - *Tienda del cliente* (rol CLIENTE): `auth`, `catalogo`, `carrito`, `wishlist`, `pedidos`,
    `perfil`, `recomendaciones`.
- **Táctico** (toma de decisiones): `analytics/sesiones`, `analytics/conversiones`,
  `analytics/funnel`, `analytics/region`, `analytics/dispositivo`, `analytics/trafico`,
  `admin/reportes`; más los informes tácticos departamentales en análisis (documento TA11),
  cuyos informes compuestos se servirán desde ClickHouse vía el pipeline ETL.
- **Estratégico**: `analytics/dashboard` (KPIs ejecutivos).

Cada módulo nuevo MUST declararse dentro de uno de estos niveles. No se permiten módulos sin
nivel asignado ni dependencias que crucen niveles en sentido inverso (estratégico → operativo) que
acoplen la venta a la analítica.

**Razón**: separar operación, decisión y estrategia mantiene el sistema comprensible y evolutivo.

### IV. Arquitectura Híbrida de Datos: PostgreSQL Transaccional, ClickHouse Analítico

El sistema opera con **dos motores, cada uno en el trabajo que hace mejor**. Este principio
reemplaza — por enmienda formal — al antiguo "ClickHouse como única fuente operativa", que
quedó invertido por la evolución del producto (ver Historia Arquitectónica).

- **PostgreSQL es la ÚNICA base transaccional operativa**: BD `retailmind` (109 tablas en el
  esquema `public`, conteo verificado el 2026-07-21), incluida la tienda del cliente (migrada
  el 2026-07-11). Su DDL vigente vive en `retailmind/sql/postgres/` (scripts numerados
  01-45 + 99), que es la fuente de verdad del esquema.
- **ClickHouse es EXCLUSIVAMENTE analítico**: esquema estrella (`fact_eventos` ~2.3M eventos +
  dimensiones), alimentado por el pipeline ETL. MUST NOT usarse para el núcleo operativo.
- **Ningún flujo operativo puede depender de ClickHouse.** Con ClickHouse apagado, TODO el
  sistema operativo MUST seguir funcionando; solo analítica y recomendaciones se degradan con
  aviso al usuario. Los eventos de tienda hacia ClickHouse son best-effort y MUST NOT bloquear
  ni revertir una transacción operativa.
- El flujo de datos analítico canónico es: **datos crudos (PocketBase) →
  `data/stage/*.parquet` → carga a ClickHouse**. El uso de Parquet como capa intermedia es
  obligatorio para ese pipeline.
- El acceso a datos desde el backend MUST hacerse con `JdbcTemplate` y consultas parametrizadas
  (sin JPA/Hibernate y sin concatenación de SQL), sobre el dual datasource: `pgJdbcTemplate`
  (cualificado, con `DataSourceTransactionManager` **@Primary**) para lo operativo y el
  datasource de ClickHouse solo para `analytics/`.
- **Todo acceso a PostgreSQL MUST ir dentro de `@Transactional`**: es el mecanismo que activa
  `SET LOCAL ROLE` (Principio V); fuera de transacción la operación corre sin privilegios de
  negocio.
- La integridad vive en la BD: columnas GENERATED, totales de cabecera, `fecha_actualizacion` y
  contadores como `usos_actuales` los mantienen triggers y MUST NOT escribirse desde código.
- *PENDIENTE*: la orquestación del pipeline ETL con **Apache Airflow** (para alimentar los
  informes tácticos compuestos) NO está implementada; hoy el ETL se dispara manualmente por un
  ADMIN vía la API (`/api/etl/**`).

**Razón**: la operación exige integridad ACID, triggers y seguridad de motor (relacional); la
analítica exige lectura columnar masiva. Ninguno de los dos motores hace bien el trabajo del
otro, y acoplar la venta a la analítica pondría en riesgo el ingreso del negocio.

### V. Seguridad por Defecto: JWT + Seguridad a Nivel de Motor (NO NEGOCIABLE)

La autenticación y autorización son obligatorias, se aplican por defecto y su enforcement
principal vive **en el motor de la base de datos**, no solo en la aplicación.

- Autenticación vía **JWT** con Spring Security en modo **STATELESS**; contraseñas con
  **BCrypt**; el login se resuelve contra la tabla `usuario` de PostgreSQL (el campo `username`
  del login contiene el email).
- Control de acceso con **9 roles de grupo nativos de PostgreSQL**: `grp_administrador`,
  `grp_gerente`, `grp_vendedor`, `grp_compras`, `grp_bodega`, `grp_despacho`, `grp_cliente`,
  `grp_analista`, `grp_soporte`, con matriz de privilegios GRANT por tabla.
- La aplicación conecta como `retailmind_app` (LOGIN **NOINHERIT**, sin privilegios de negocio)
  y asume el rol del usuario **por transacción** con `SET LOCAL ROLE` (aspecto
  `PgSessionRoleAspect`, que excluye `analytics/`). MUST NOT conectarse como superusuario desde
  la aplicación.
- **RLS (Row-Level Security)**: el cliente queda aislado a sus propias filas vía
  `app.cliente_id` (incluye pagos, cupones y sus usos). **Restricción horaria** por rol
  (`grupo_horario` + `esta_en_horario()` + triggers; admin exento, soporte 24/7).
  **Segregación financiera por grants de columna**: Bodega y Despacho MUST NOT poder leer
  montos de dinero — solo cantidades, estados y fechas.
- Crear un rol nuevo MUST incluir: GRANTs propios, `GRANT USAGE ON SCHEMA public` y política
  RLS propia en cada tabla con RLS (patrón de referencia: `37_rol_soporte.sql`).
- Todo endpoint nuevo MUST declarar explícitamente su nivel de acceso en `SecurityConfig` (y su
  `roleGuard` en el frontend); el comportamiento por defecto es `authenticated()` (denegar lo
  no declarado). La BD devuelve SQLState 42501 ante privilegio/horario insuficiente y la API lo
  traduce a 403.
- Secretos (JWT, credenciales de BD) MUST provenir de variables de entorno y NUNCA versionarse.

**Razón**: la plataforma maneja ventas y datos de negocio; una seguridad que solo vive en la
aplicación se puede saltar — la que vive en el motor, no.

### VI. Calidad de Código y Convenciones del Repositorio

- **Nomenclatura bilingüe coherente**: términos de dominio en **español** (`pedidos`,
  `compras`, `devoluciones`, `carrito`); convenciones de framework en **inglés**
  (`Controller`, `Service`, `Repository`, `Component`). Java en `PascalCase`/`camelCase`,
  Python en `snake_case`, identificadores Angular según guía oficial.
- **Estructura del repositorio** (monorepo de 3 sub-proyectos sobre la arquitectura híbrida):
  - `retailmind/` — pipeline ETL en Python (subcarpetas `etl/extraccion`, `etl/carga`,
    `etl/analytics`, `etl/sinteticos`, `etl/reportes`; datos crudos en `data/stage`) **y el DDL
    operativo vigente de PostgreSQL en `sql/postgres/` (scripts numerados 01-45 + 99)**.
  - `retailmind-backend/` — API REST Spring Boot, paquetes por nivel/módulo bajo
    `com.retailmind` (Controller → Service → acceso a datos).
  - `retailmind-frontend/` — SPA Angular 17 standalone, con `core/` (servicios, guards,
    interceptors, models) y `features/` por módulo lazy-loaded; las pantallas operativas
    siguen el patrón canónico de `features/operativo/` (tabla + formulario + toggle activo,
    estilos compartidos en `operativo-shared.scss`).
- Frontend con **componentes standalone** y rutas **lazy-loaded**; sin NgModules nuevos.
- El backend separa **Controller → Service → acceso a datos**; los controladores no contienen
  lógica de negocio ni SQL. Las guardias de estado usan excepciones semánticas
  (`IllegalArgumentException` → 400, `IllegalStateException` → 409,
  `NoSuchElementException` → 404) traducidas por el manejador global.
- Código y comentarios de dominio en español; mensajes de error orientados al usuario (en el
  frontend, siempre vía `api-error.util.ts`).

**Razón**: convenciones explícitas reducen fricción en un equipo y un dominio bilingües.

### VII. Verificación y Pruebas

- Todo cambio MUST compilar/construir antes de considerarse terminado. Verificación mínima
  canónica: `mvn compile` (backend) y `ng build` (frontend); los scripts ETL deben ejecutarse
  sin errores.
- Las **operaciones de venta** (carrito, checkout, pedidos) y la **seguridad** (login, roles,
  RLS) son rutas críticas y MUST cubrirse con pruebas antes de fusionarse.
- El backend usa **Spring Boot Test** como framework base; el ETL valida cargas con sus pasos de
  verificación (p. ej. `verify_clickhouse`). Las pruebas nuevas MUST usar el framework existente
  del sub-proyecto correspondiente.
- La resiliencia del ETL (reintentos vía Spring Retry) MUST conservarse: un fallo transitorio no
  debe corromper datos ni dejar cargas a medias sin reportar el error.

**Razón**: lo que sostiene la venta y el dato debe demostrarse, no suponerse.

## Historia Arquitectónica

La arquitectura de datos de RetailMind pasó por **tres etapas deliberadas**. Esta sección existe
para que el registro histórico no se lea como contradicción sino como evolución con criterio, y
para que ninguna enmienda futura repita el error de describir una etapa anterior como si fuera
la vigente.

1. **Etapa 1 — PostgreSQL inicial.** El proyecto arrancó sobre PostgreSQL como base relacional
   del dominio retail (el DDL original en `retailmind/sql/` y las dependencias `psycopg2`/
   `sqlalchemy` del ETL datan de esta etapa). Razón de negocio: modelar el dominio con un motor
   relacional convencional.
2. **Etapa 2 — ClickHouse como base única.** Cuando el foco del producto era la **analítica**
   (esquema estrella, ~2.3M eventos en `fact_eventos`, dashboards, funnel, reportes), el
   proyecto migró todo a ClickHouse y la constitución v1.0.0 (2026-06-18) ratificó ese estado —
   incluida la declaración, correcta *entonces*, de que "PostgreSQL fue eliminado". Razón de
   negocio: un producto cuyo valor central era leer y agregar millones de eventos necesitaba un
   motor columnar como pieza principal.
3. **Etapa 3 — Arquitectura híbrida actual.** Al construir el **nivel operativo completo** —
   back-office con ciclo de compra, inventario con kardex, ciclo de venta con pagos y
   facturación, logística con despacho y devoluciones, soporte y marketing — las necesidades
   pasaron a ser transaccionales: integridad ACID, triggers de totales, RLS, restricción
   horaria y segregación financiera por columna. Un motor columnar no es el instrumento para
   eso. El proyecto **volvió a PostgreSQL como única base transaccional** (la tienda del
   cliente migró el 2026-07-11; el back-office se construyó directamente sobre él, scripts
   01-45 + 99) y ClickHouse quedó **dedicado exclusivamente a analítica**. Razón de negocio:
   cada motor en el trabajo que hace mejor — la operación no puede depender de la analítica, y
   la analítica no debe competir con la operación por recursos.

La enmienda 2.0.0 formaliza la Etapa 3, que la v1.0.0 (ratificada en plena Etapa 2) nunca llegó
a reflejar.

## Restricciones Tecnológicas y Arquitectura de Datos

El stack está fijado y verificado contra el código (`pom.xml`, `package.json`, esquema real).
Cambiarlo requiere enmienda formal.

- **Frontend**: Angular 17.3 (standalone, lazy-loaded), Angular Material + CDK 17.3,
  Chart.js 4.5, TypeScript 5.4; exportación de reportes con `xlsx` y `jspdf`.
- **Backend**: Java 17, Spring Boot 3.5.0, Spring Web + Spring JDBC (`JdbcTemplate`, **sin
  JPA**), Spring Security + JWT (`jjwt` 0.12.3), Spring Retry, AOP (aspecto de rol por
  transacción); reportes con Apache POI 5.2.5 (Excel) e iText 5.5.13 (PDF de facturas y guías).
- **BD transaccional (operativa)**: PostgreSQL, BD `retailmind` local (`localhost:5432`),
  109 tablas; dual datasource con `DataSourceTransactionManager` **@Primary** sobre Postgres.
- **BD analítica**: ClickHouse (columnar, esquema estrella), driver `clickhouse-jdbc` 0.6.5,
  con configuración **fail-fast** (timeouts de pool y driver) para que `/api/health` responda
  acotado con ClickHouse apagado (`status: UP, analytics: DEGRADED`); esos timeouts MUST NOT
  retirarse.
- **Fuente del dataset analítico**: PocketBase (dataset crudo `dataset_retail`). La
  autenticación de usuarios del sistema se resuelve contra PostgreSQL (tabla `usuario`);
  `ClickHouseUserRepository` es el camino legacy.
- **ETL**: Python 3.12 (imagen `python:3.12-slim`), con `clickhouse-connect`, `pyarrow`,
  `pandas` y cliente `pocketbase`; `psycopg2` es legítimo para inspección/administración de
  PostgreSQL en desarrollo (ya no es dependencia legacy prohibida).
- **Contenedores**: Docker Compose con **5 servicios**: `pocketbase`, `clickhouse`, `backend`,
  `frontend`, `etl`. **PostgreSQL corre local, fuera de compose** (contenerización PENDIENTE,
  ver Principio I).
- **Apache Airflow (PENDIENTE)**: si se incorpora para orquestar el pipeline táctico, MUST
  fijarse a Python 3.12 (las versiones vigentes de Airflow no soportan Python 3.14).

## Flujo de Desarrollo y Estándares de Entrega

- **Comandos canónicos**: backend `mvn compile` / `mvn spring-boot:run` (puerto 8080; si está
  ocupado, 8081 y detenerlo al terminar); frontend `npm start` / `ng build`; analítica y
  servicios contenerizados `docker-compose up -d`. Verificación mínima antes de dar por bueno
  un cambio: `mvn compile` && `ng build`.
- Todo trabajo se realiza por feature; las especificaciones se gestionan con Spec Kit en `specs/`
  (constitución en `.specify/memory/constitution.md`).
- Las puertas de calidad (Constitution Check de los planes) MUST verificar: nivel empresarial
  del módulo (Principio III), acceso/seguridad declarados en las dos capas — ruta/rol en la
  aplicación y GRANT/RLS/horario en el motor (Principio V), respeto de la frontera híbrida —
  operativo en PostgreSQL dentro de `@Transactional`, analítica en ClickHouse, sin dependencias
  operativas de ClickHouse (Principio IV) — y cobertura de pruebas en rutas críticas
  (Principio VII).
- Cualquier desviación de un principio MUST justificarse en la sección de complejidad del plan
  o, si es permanente, mediante enmienda a esta constitución.
- Los elementos marcados **PENDIENTE** (contenerización de PostgreSQL, Airflow, i18n/multi-región)
  NO deben documentarse como ya implementados; los planes que los aborden deben tratarlos como
  trabajo nuevo.

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
  *Nota de la enmienda 2.0.0*: cuando la constitución quede desfasada del sistema real (como
  ocurrió entre la migración a PostgreSQL de 2026-07-11 y esta enmienda), la respuesta correcta
  es enmendar la constitución con prontitud, no ignorarla — el desfase de v1.0.0 se arrastró
  cinco semanas y generó contradicciones documentales en cascada.

### Registro de enmiendas

| Versión | Fecha | Tipo | Motivo |
|---|---|---|---|
| 1.0.0 | 2026-06-18 | Ratificación inicial (MAJOR) | Constitución concreta del proyecto sobre la plantilla Spec Kit, ratificada durante la Etapa 2 (ClickHouse como base única). |
| 2.0.0 | 2026-07-21 | Enmienda MAYOR | Inversión del Principio IV: PostgreSQL es la única base transaccional operativa y ClickHouse queda exclusivamente analítico (arquitectura híbrida, Etapa 3). Se reescriben los principios III (nivel Operativo con el back-office real) y V (9 roles con seguridad a nivel de motor), se ajustan I (contenerización de PostgreSQL como PENDIENTE), VI (`sql/postgres/` como DDL vigente) y VII (verificación mínima `mvn compile` + `ng build`), y se añade la sección Historia Arquitectónica. Base: diagnóstico de `docs/tactico/SINCRONIZACION_OBJETIVOS.md` contra `CLAUDE.md` y `.kiro/steering/`. |

**Version**: 2.0.0 | **Ratified**: 2026-06-18 | **Last Amended**: 2026-07-21
