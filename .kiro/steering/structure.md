# Project Structure

Monorepo con tres sub-proyectos. Arquitectura híbrida: **PostgreSQL** (BD `retailmind`, local) es
la base operativa principal; **ClickHouse** (Docker) sirve solo la analítica.

```
1M6DatosCS/
├── retailmind/                       # Pipeline ETL Python 3.12 + DDL de PostgreSQL
│   ├── config/
│   │   └── clickhouse_connection.py  # Conexión ClickHouse + logging
│   ├── etl/
│   │   ├── extraccion/               # PocketBase -> data/stage/datos.parquet
│   │   ├── carga/                    # Parquet -> ClickHouse (+ verify/reset)
│   │   ├── sinteticos/               # Generador de datos sintéticos
│   │   ├── analytics/ y reportes/    # Scripts/notas
│   ├── data/stage/                   # Capa cruda Parquet
│   ├── sql/postgres/                 # ★ DDL OPERATIVO VIGENTE: 28 scripts numerados
│   │                                 #   01-13 módulos (seguridad, clientes, catálogo, ventas,
│   │                                 #   compras, inventario, marketing, soporte...),
│   │                                 #   2x seguridad de motor (grp_*, RLS, horarios),
│   │                                 #   2x seeds demo, 27 usuarios de prueba
│   ├── requirements.txt / Dockerfile / .env
│
├── retailmind-backend/               # API REST Spring Boot 3.5 / Java 17
│   ├── pom.xml
│   └── src/main/java/com/retailmind/
│       ├── RetailmindApplication.java
│       ├── config/                   # PostgresConfig (@Primary tx), ClickHouseConfig, Cors, Health
│       ├── security/                 # SecurityConfig, JwtAuthenticationFilter,
│       │                             # PgSessionRoleAspect (SET LOCAL ROLE), DbGroupRole (lista blanca)
│       ├── auth/                     # AuthService (login vs PostgreSQL), PostgresUserRepository,
│       │                             # JwtUtil, AppUserPrincipal, DataInitializer
│       ├── exception/                # GlobalExceptionHandler -> ApiErrorDTO
│       ├── dto/                      # DTOs compartidos + repos ClickHouse legacy
│       ├── pdf/                      # Base de documentos PDF (iText 5)
│       ├── referencias/              # Selects de referencia (proveedores, bodegas...)
│       │
│       │   # --- Núcleo operativo (PostgreSQL, @Transactional + SET LOCAL ROLE) ---
│       ├── admin/catalogo/           # CRUD catálogo maestro (ADMIN)
│       ├── admin/horarios/           # Ventanas horarias por rol (ADMIN)
│       ├── admin/usuarios/           # Gestión de usuarios
│       ├── compras/                  # Orden -> aprobar -> recepción -> factura -> pago (+PDF)
│       ├── inventario/               # Transferencias, ajustes, kardex, StockService
│       ├── ventas/                   # Pedido -> factura -> despacho -> devolución (+PDF)
│       ├── marketing/                # Cupones, promociones(+productos), campañas, banners, newsletter
│       │
│       │   # --- Tienda online (PostgreSQL) ---
│       ├── catalogo/                 # Catálogo público
│       ├── carrito/  wishlist/  pedidos/  perfil/  recomendaciones/
│       │
│       │   # --- Analítica (ClickHouse, EXCLUIDA del SET LOCAL ROLE) ---
│       ├── analytics/                # dashboard, sesiones, conversiones, funnel,
│       │                             # region, dispositivo, trafico
│       └── admin/                    # etl/ (disparo ETL), gestion/ (dims CH), reportes/ (Excel/PDF)
│
├── retailmind-frontend/              # SPA Angular 17 standalone (diseño "Dubai")
│   └── src/app/
│       ├── app.routes.ts             # Rutas lazy con authGuard/adminGuard/roleGuard([...])
│       ├── app.component.*           # Sidebar por rol + breadcrumbs (routeMap)
│       ├── core/
│       │   ├── services/             # Servicios API (~15 líneas, environment.apiUrl),
│       │   │                         # api-error.util.ts (mensajeError)
│       │   ├── guards/               # auth.guard, role.guard (roleGuard/adminGuard)
│       │   ├── interceptors/         # JWT
│       │   └── models/               # operativo.model.ts (snake_case del backend), etc.
│       └── features/
│           ├── login/  shop/  wishlist/  pedidos/  perfil/  recomendaciones/
│           ├── analytics/            # dashboard, funnel, sesiones, region... (ADMIN)
│           ├── admin/                # etl, gestión de datos, usuarios, reportes
│           └── operativo/            # ★ pantallas del back-office (patrón a imitar:
│               │                     #   tabla + formulario + toggle activo, operativo-shared.scss)
│               ├── catalogo/         # productos-admin
│               ├── compras/          # órdenes, recepciones, facturas
│               ├── inventario/       # transferencias, ajustes, kardex
│               ├── ventas/           # pedidos, facturas, despachos, devoluciones, mis-pedidos
│               ├── marketing/        # cupones, promociones, campañas, banners, newsletter
│               └── horarios/
│
├── docker-compose.yml                # 5 servicios: pocketbase, clickhouse, backend, frontend, etl
│                                     # (PostgreSQL corre LOCAL, fuera de compose)
├── .kiro/steering/                   # Este steering
└── .specify/ / openspec/             # Spec Kit
```

## Architecture Patterns

- **Backend por capas**: Controller → Service (`@Transactional`, guardias de estado) →
  `pgJdbcTemplate` con SQL parametrizado. DTOs de entrada como `record` anidados en el controller;
  respuestas como `List<Map<String,Object>>` (snake_case) que el frontend tipa en
  `operativo.model.ts`.
- **Seguridad en dos capas**: `SecurityConfig` autoriza por ruta/rol; dentro de la transacción,
  `PgSessionRoleAspect` ejecuta `SET LOCAL ROLE grp_*` y la BD aplica privilegios + RLS + horario.
  El paquete `analytics/` queda excluido del aspecto (es ClickHouse).
- **La BD es dueña de la integridad**: totales y columnas GENERATED los calculan triggers; el
  código nunca los escribe. Los CHECKs se espejan con listas blancas en los services para dar
  mensajes claros antes del 400 genérico.
- **Pantalla operativa canónica** (imitar siempre): tabla Material + formulario colapsable +
  toggle activo + snackbar con `mensajeError`; estilos de `operativo-shared.scss`.
- **Ruta nueva** = SecurityConfig + `roleGuard` + sidebar (`app.component.html`, visibilidad por
  getters `canX`) + `routeMap` de breadcrumbs.
- **Flujo analítico**: PocketBase → Python ETL (Parquet) → ClickHouse ← `analytics/` ← Angular.
- **Flujo operativo**: Angular ← API Spring ← PostgreSQL (retailmind_app + SET LOCAL ROLE).
