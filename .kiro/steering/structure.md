# Project Structure

Monorepo with three independent sub-projects sharing a PostgreSQL database.

```
1M6DatosCS/
├── retailmind/                  # Python ETL pipeline
│   ├── main.py                  # Orchestrator: runs steps 1-4 sequentially
│   ├── config/
│   │   └── db_connection.py     # PostgreSQL connection + logging setup
│   ├── etl/
│   │   ├── 01_create_tables.py  # DDL execution
│   │   ├── 02_load_lookup_tables.py  # Load dimension/lookup tables
│   │   ├── 03_load_main_tables.py    # Load fact tables from CSV
│   │   ├── 04_verify_load.py         # Post-load validation
│   │   ├── 05_load_incremental.py    # Weekly incremental loads
│   │   ├── 06_optimize_database.py   # Index + vacuum
│   │   ├── 07_monitor_performance.py # Query stats
│   │   ├── 08_apply_advanced_optimize.py
│   │   ├── 09_create_refresh_function.py
│   │   └── load_csv_staging.py       # CSV → staging helper
│   ├── sql/
│   │   ├── create_tables.sql    # Full DDL (10 tables)
│   │   ├── optimize.sql         # Index definitions
│   │   └── advanced_optimize.sql
│   ├── utils/
│   │   ├── error_reporter.py
│   │   └── load_tracker.py
│   ├── logs/                    # ETL log output
│   ├── generate_synthetic.py    # Test data generator
│   ├── requirements.txt
│   └── .env                     # DB credentials (not committed)
│
├── retailmind-backend/          # Java Spring Boot REST API
│   ├── pom.xml
│   └── src/main/java/com/retailmind/
│       ├── RetailmindApplication.java
│       ├── config/              # CORS, DB init
│       ├── controller/          # REST endpoints (Auth, Dashboard, ETL, etc.)
│       ├── dto/                 # Data Transfer Objects
│       ├── entity/              # JPA entities (maps to ETL tables)
│       ├── exception/           # Global error handler
│       ├── migration/           # DataInitializer (seed data)
│       ├── repository/          # Spring Data JPA repositories
│       ├── security/            # JWT filter, config
│       └── service/             # Business logic layer
│
├── retailmind-frontend/         # Angular 17 SPA
│   ├── package.json
│   ├── angular.json
│   ├── tsconfig.json
│   └── src/app/
│       ├── app.routes.ts        # Route definitions
│       ├── app.config.ts        # Standalone app config
│       ├── core/                # Shared infrastructure
│       │   ├── components/      # Layout components (navbar, sidebar)
│       │   ├── guards/          # Auth guards
│       │   ├── interceptors/    # HTTP interceptors (JWT attach)
│       │   ├── models/          # TypeScript interfaces
│       │   └── services/        # API services (HttpClient wrappers)
│       └── features/            # Feature modules (lazy-loaded routes)
│           ├── dashboard/       # Main analytics dashboard
│           ├── sesiones/        # Session explorer
│           ├── conversiones/    # Conversion analytics
│           ├── admin-etl/       # ETL management UI
│           └── login/           # Authentication page
│
└── .kiro/                       # Kiro configuration
    ├── specs/                   # Feature specifications
    └── steering/                # Steering rules (this file)
```

## Architecture Patterns

- **ETL**: Numbered scripts (01-09) run in sequence. Each exposes a main function callable from `main.py` or standalone.
- **Backend**: Standard Spring Boot layered architecture (Controller → Service → Repository). JPA entities map directly to the ETL-created tables. No Hibernate DDL — schema is owned by the Python ETL.
- **Frontend**: Angular standalone components with feature-based folder structure. Core folder holds cross-cutting concerns (guards, interceptors, services). Features are self-contained route modules.
- **Data flow**: CSV → Python ETL → PostgreSQL ← Spring Boot API ← Angular Frontend
