# Project Structure

Monorepo con tres sub-proyectos independientes que comparten **ClickHouse** como base de datos
operativa, orquestados con Docker Compose.

```
1M6DatosCS/
├── retailmind/                       # Pipeline ETL en Python 3.12
│   ├── config/
│   │   └── clickhouse_connection.py  # Conexion ClickHouse + logging
│   ├── etl/
│   │   ├── extraccion/
│   │   │   ├── 08_extract_pocketbase.py   # PocketBase -> data/stage/datos.parquet
│   │   │   └── 13_create_shop_tables.py   # Tablas de tienda en ClickHouse
│   │   ├── carga/
│   │   │   ├── 09_load_clickhouse.py      # Parquet -> ClickHouse
│   │   │   ├── 10_verify_clickhouse.py    # Verificacion de carga
│   │   │   └── 11_reset_clickhouse.py     # Reset de datos
│   │   ├── sinteticos/
│   │   │   └── 12_generate_synthetic.py   # Generador de datos sinteticos
│   │   ├── analytics/                # Scripts/notas de analitica
│   │   └── reportes/                 # Scripts/notas de reportes
│   ├── data/
│   │   ├── stage/                    # Capa cruda en Parquet (datos.parquet)
│   │   └── agg/                      # Capa procesada/agregada (PENDIENTE: vacia)
│   ├── sql/                          # DDL legacy de PostgreSQL (no usar)
│   ├── utils/
│   ├── logs/
│   ├── requirements.txt
│   ├── Dockerfile
│   └── .env                          # Credenciales (no versionado)
│
├── retailmind-backend/               # API REST Spring Boot 3.5
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/retailmind/
│       ├── RetailmindApplication.java
│       ├── auth/                     # Login JWT, ClickHouseUserRepository, DataInitializer
│       ├── security/                 # SecurityConfig, JwtAuthenticationFilter
│       ├── config/                   # ClickHouseConfig, CorsConfig, Health
│       ├── dto/                      # DTOs + repos JdbcTemplate (FactEvento, Dim*, ...)
│       ├── exception/                # GlobalExceptionHandler
│       │
│       │   # --- Nivel Operativo (genera ventas) ---
│       ├── catalogo/                 # Catalogo publico
│       ├── carrito/                  # Carrito de compras
│       ├── wishlist/                 # Lista de deseos
│       ├── pedidos/                  # Ordenes y checkout
│       ├── perfil/                   # Perfil de usuario
│       ├── recomendaciones/          # Motor de recomendaciones
│       │
│       │   # --- Nivel Tactico + Estrategico ---
│       ├── analytics/
│       │   ├── dashboard/            # KPIs ejecutivos (estrategico)
│       │   ├── sesiones/             # Explorer de sesiones
│       │   ├── conversiones/         # Analisis de conversion
│       │   ├── funnel/               # Embudo (solo ADMIN)
│       │   ├── region/               # Analytics por region (solo ADMIN)
│       │   ├── dispositivo/          # Analytics por dispositivo (solo ADMIN)
│       │   └── trafico/              # Analytics por fuente (solo ADMIN)
│       └── admin/
│           ├── etl/                  # Disparo de ETL (EtlController/EtlService)
│           ├── gestion/              # CRUD de dimensiones
│           ├── usuarios/             # Admin de usuarios
│           └── reportes/             # Excel + PDF
│
├── retailmind-frontend/              # SPA Angular 17 standalone
│   ├── package.json
│   ├── angular.json
│   ├── tsconfig.json
│   ├── Dockerfile
│   ├── nginx.conf
│   └── src/app/
│       ├── core/                     # Infraestructura transversal
│       │   ├── services/             # Servicios API (HttpClient)
│       │   ├── guards/               # Auth guards
│       │   ├── interceptors/         # Interceptor JWT
│       │   └── models/               # Interfaces TypeScript
│       └── features/                 # Modulos por feature (lazy-loaded)
│           ├── login/                # Autenticacion
│           ├── shop/                 # Tienda, carrito, detalle
│           ├── wishlist/             # Lista de deseos
│           ├── recomendaciones/      # Productos recomendados
│           ├── pedidos/              # Mis pedidos
│           ├── perfil/               # Perfil
│           ├── analytics/            # Dashboard, funnel, region, etc.
│           └── admin/                # ETL, gestion, reportes
│
├── docker-compose.yml                # 5 servicios: pocketbase, clickhouse, backend, frontend, etl
├── clickhouse-data/                  # Volumen persistente ClickHouse
├── pocketbase-data/                  # Volumen persistente PocketBase
├── .env                              # Variables de entorno
└── .kiro/                            # Configuracion Kiro
    ├── specs/                        # Especificaciones de features
    └── steering/                     # Reglas de steering (este archivo)
```

## Architecture Patterns

- **Niveles empresariales**: el dominio se separa en operativo (ventas), táctico (decisión) y
  estratégico (KPIs). Cada módulo nuevo debe declararse dentro de un nivel.
- **ETL**: scripts numerados organizados por fase (`extraccion`, `carga`, `sinteticos`). El flujo
  canónico es PocketBase → `data/stage/*.parquet` → (`data/agg/`) → ClickHouse. La capa `data/agg/`
  está prevista pero aún vacía.
- **Backend**: arquitectura por capas Controller → Service → acceso a datos con `JdbcTemplate`
  (sin JPA). El esquema es propiedad del ETL; el backend no genera DDL vía ORM. Resiliencia del ETL
  con Spring Retry.
- **Frontend**: componentes standalone Angular con estructura por features lazy-loaded. `core/`
  agrupa lo transversal (guards, interceptors, services, models).
- **Seguridad**: JWT STATELESS + RBAC (ADMIN/CLIENTE). Catálogo público; analítica avanzada, ETL,
  gestión, reportes y admin solo ADMIN; tienda y datos de usuario requieren autenticación.
- **Flujo de datos**: PocketBase → Python ETL (Parquet) → ClickHouse ← Spring Boot API ← Angular.
