# RetailMind Analytics S.A.

RetailMind is a retail analytics platform that tracks user sessions, events, and conversions across e-commerce channels. It provides a dashboard for monitoring conversion rates, session behavior, traffic sources, and regional performance.

The system consists of three components:
- **ETL Pipeline** (Python): Extracts data from CSV files, transforms it into a normalized schema, and loads it into PostgreSQL.
- **Backend API** (Java/Spring Boot): REST API serving analytics data, managing ETL execution, and handling authentication via JWT.
- **Frontend Dashboard** (Angular): SPA displaying charts, KPIs, session tables, and admin tools for triggering ETL loads.

The database is PostgreSQL with a normalized schema of 10 tables (regiones, dispositivos, canales, fuentes_trafico, categorias, usuarios, productos, sesiones, eventos, conversiones) plus materialized views for dashboard aggregations.

The project language is Spanish for domain terms, comments, and UI labels. Code identifiers mix English (framework conventions) with Spanish (domain entities like `sesiones`, `conversiones`, `eventos`).
