# Tech Stack

> Stack verificado contra el código. Su modificación se rige por la constitución del proyecto
> (`.specify/memory/constitution.md`).

## ETL Pipeline (`retailmind/`)
- **Language**: Python 3.12 (imagen Docker `python:3.12-slim`)
- **ClickHouse driver**: clickhouse-connect 0.7.16
- **PocketBase client**: pocketbase 0.12.1
- **Parquet**: pyarrow 16.1.0
- **Data processing**: pandas 2.2.2
- **Config**: python-dotenv 1.0.1 (carga `.env`)
- **Legacy (no usar)**: psycopg2-binary 2.9.9 y SQLAlchemy 2.0.30 quedan en `requirements.txt`
  pero corresponden a PostgreSQL, que fue **eliminado**. No usar en código nuevo.

## Backend (`retailmind-backend/`)
- **Language**: Java 17
- **Framework**: Spring Boot 3.5.0
- **Build tool**: Maven (pom.xml)
- **Data access**: Spring JDBC (`JdbcTemplate`) — **sin JPA/Hibernate**, con consultas parametrizadas
- **Database driver**: clickhouse-jdbc 0.6.5
- **Auth**: Spring Security + JWT (jjwt 0.12.3), STATELESS, contraseñas con BCrypt
- **Resilience**: Spring Retry
- **Reportes**: Apache POI 5.2.5 (Excel), iText 5.5.13 (PDF)

## Frontend (`retailmind-frontend/`)
- **Framework**: Angular 17.3 (standalone components, lazy-loaded, sin NgModules)
- **UI library**: Angular Material 17 + Angular CDK
- **Charts**: Chart.js 4.5
- **Reportes cliente**: xlsx (SheetJS), jspdf + jspdf-autotable
- **Styling**: SCSS
- **Build**: Angular CLI (@angular-devkit/build-angular)
- **TypeScript**: 5.4

## Database (operativa única)
- **Engine**: ClickHouse (columnar, esquema estrella)
- **Database name**: `retailmind`
- **Tabla de hechos**: `fact_eventos` (~2.3M eventos) + dimensiones `dim_*` + tablas de tienda
- **Nota**: PostgreSQL fue eliminado; el DDL en `retailmind/sql/` es **legacy**.

## Autenticación / fuente de datos
- **PocketBase**: fuente del dataset crudo (colección `dataset_retail`).
- **Usuarios del sistema (login)**: se autentican contra ClickHouse (`usuarios_sistema` vía
  `ClickHouseUserRepository`), no contra PocketBase.

## Infraestructura
- **Docker Compose** con **5 servicios**: `pocketbase`, `clickhouse`, `backend`, `frontend`, `etl`.

## Orquestación del pipeline
- Hoy el ETL se dispara **manualmente** por un ADMIN vía la API (`/api/etl/**`); el backend ejecuta
  los scripts Python mediante `ProcessBuilder` (`EtlService`).
- **PENDIENTE (visión)**: orquestación con Apache Airflow cada 2 horas. **No está implementada** (no
  hay servicio Airflow en `docker-compose.yml` ni job programado). Si se incorpora, fijar a Python
  3.12 (Airflow no soporta 3.14).

## Common Commands

### ETL Pipeline
```bash
cd retailmind
pip install -r requirements.txt
python etl/extraccion/08_extract_pocketbase.py   # PocketBase -> data/stage/datos.parquet
python etl/carga/09_load_clickhouse.py           # Parquet -> ClickHouse
python etl/carga/10_verify_clickhouse.py         # Verificacion de carga
python etl/sinteticos/12_generate_synthetic.py   # Generar datos sinteticos
```

### Backend
```bash
cd retailmind-backend
mvn clean install                 # Build + tests
mvn spring-boot:run               # Dev server en puerto 8080
mvn package -DskipTests           # Empaquetar JAR sin tests
```

### Frontend
```bash
cd retailmind-frontend
npm install
npm start                         # Dev server (ng serve, puerto 4200)
npm run build                     # Build de produccion a dist/
```

### Sistema completo
```bash
docker-compose up -d              # Levantar los 5 servicios
docker-compose logs -f backend    # Ver logs
docker-compose down               # Detener (usar -v borra volumenes y datos)
```
