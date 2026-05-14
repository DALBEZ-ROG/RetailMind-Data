# Tech Stack

## ETL Pipeline (`retailmind/`)
- **Language**: Python 3
- **Database driver**: psycopg2-binary 2.9.9
- **Data processing**: pandas 2.2.2
- **ORM (optional)**: SQLAlchemy 2.0.30
- **Config**: python-dotenv 1.0.1 (loads `.env` for DB credentials)
- **Logging**: stdlib `logging` with RotatingFileHandler

## Backend (`retailmind-backend/`)
- **Language**: Java 17
- **Framework**: Spring Boot 3.2.5
- **Build tool**: Maven (pom.xml)
- **Database**: PostgreSQL via Spring Data JPA + JdbcTemplate
- **Auth**: Spring Security + JWT (jjwt 0.12.3)
- **Resilience**: Spring Retry
- **ORM mode**: `ddl-auto=none` (schema managed by ETL DDL scripts)

## Frontend (`retailmind-frontend/`)
- **Framework**: Angular 17 (standalone components, no NgModules)
- **UI library**: Angular Material 17 + Angular CDK
- **Charts**: Chart.js 4.5
- **Styling**: SCSS
- **Build**: Angular CLI / Webpack (via @angular-devkit/build-angular)
- **TypeScript**: 5.4

## Database
- **Engine**: PostgreSQL
- **Database name**: CDRetail_Intelligence
- **Schema**: 10 normalized tables + materialized views for dashboard

## Common Commands

### ETL Pipeline
```bash
cd retailmind
pip install -r requirements.txt
python main.py                    # Run full ETL pipeline (steps 1-4)
python etl/05_load_incremental.py # Incremental load from weekly CSVs
python etl/06_optimize_database.py
python etl/07_monitor_performance.py
python generate_synthetic.py      # Generate test CSV data
```

### Backend
```bash
cd retailmind-backend
mvn clean install                 # Build + run tests
mvn spring-boot:run               # Start dev server on port 8080
mvn package -DskipTests           # Package JAR without tests
```

### Frontend
```bash
cd retailmind-frontend
npm install
npm start                         # Dev server (ng serve)
npm run build                     # Production build to dist/
```
