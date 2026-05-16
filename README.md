# 🚀 RetailMind Analytics S.A.

Sistema de analítica de datos de retail construido con Python, ClickHouse, Spring Boot 3 y Angular 17+.

**Repositorio:** https://github.com/DALBEZ-ROG/RetailMind-Data.git  
**Dataset:** The Shift in Retail AI: 100,000 User Journeys Decoded (108,584 registros)  
**Base de datos:** ClickHouse (columnar, OLAP)

---

## 🏗️ Arquitectura

```
Pocketbase (fuente) → Python ETL → Parquet → ClickHouse
PostgreSQL          → solo autenticación JWT
Spring Boot 3       → API REST :8080
Angular 17+         → Frontend :4200
Docker              → conteneriza todo
```

---

## ⚡ Arranque rápido

### Prerequisitos
- Docker Desktop instalado y corriendo
- Git

### Un solo comando
```bash
git clone https://github.com/DALBEZ-ROG/RetailMind-Data.git
cd RetailMind-Data
docker-compose up
```

Espera ~2 minutos mientras Docker construye las imágenes. Cuando veas:
```
backend-1 | Started RetailmindApplication
```

Abre el navegador en: **http://localhost:4200**

### Credenciales de acceso
```
Usuario:    admin
Contraseña: admin123
```

---

## 📦 Servicios Docker

|         Servicio         | Puerto |        Descripción        |
|--------------------------|--------|---------------------------|
|     Frontend (Angular)   |  4200  | Interfaz web              |
|    Backend (Spring Boot) |  8080  | API REST                  |
|        ClickHouse        |  8123  | Base de datos analítica   |
|        PostgreSQL        |  5432  | Autenticación JWT         |
|        Pocketbase        |  8091  | Fuente de datos original  |

---

## 🗄️ Modelo de Datos en ClickHouse

### Tabla de Hechos
```sql
retailmind.fact_eventos
- event_pk, session_id, user_id, timestamp_utc
- event_index, user_action, product_id
- time_spent_sec, session_length, interaction_count
- is_conversion, drop_off_flag, price, channel, semana
```

### Tablas de Dimensiones
```sql
retailmind.dim_canal          (3 registros)
retailmind.dim_dispositivo    (4 registros)
retailmind.dim_region         (9 registros)
retailmind.dim_categoria      (8 registros)
retailmind.dim_fuente_trafico (7 registros)
retailmind.dim_producto       (1,200 registros)
retailmind.dim_usuario        (6,806 registros)
```

---

## 📋 Módulos del sistema

|        Módulo      |               Descripción                |
|--------------------|------------------------------------------|
| Dashboard          | 8 KPIs en tiempo real desde ClickHouse   |
| Sesiones           | Tabla paginada de sesiones               |
| Conversiones       | Análisis de conversiones                 |
| Administración ETL | Generar datos semanales sintéticos       |
| Inicialización     | Carga completa desde Pocketbase + Reset  |
| Gestión de Datos   | CRUD para fact_eventos y dimensiones     |

---

## 🔄 Pipeline ETL

### Carga inicial (desde la web)
```
Módulo Inicialización → CARGA COMPLETA DESDE POCKETBASE
→ Extrae 108,584 registros de Pocketbase
→ Convierte a Parquet (data/stage/datos.parquet)
→ Carga a ClickHouse (~27 segundos)
```

### Generación semanal (desde la web)
```
Módulo Administración ETL → GENERAR DATOS SEMANA N
→ Genera 108,584 registros sintéticos con numpy
→ Inserta directo en ClickHouse (~15 segundos)
```

### Scripts ETL (manual)
```bash
# Extraer de Pocketbase → Parquet
docker-compose exec etl python etl/08_extract_pocketbase.py

# Cargar Parquet → ClickHouse
docker-compose exec etl python etl/09_load_clickhouse.py

# Verificar datos
docker-compose exec etl python etl/10_verify_clickhouse.py

# Resetear ClickHouse
docker-compose exec etl python etl/11_reset_clickhouse.py

# Generar datos sintéticos semana N
docker-compose exec etl python etl/12_generate_synthetic.py --semana 2
```

---

## 🛠️ Comandos útiles

```bash
# Levantar todo
docker-compose up

# Levantar en background
docker-compose up -d

# Apagar (conserva datos)
docker-compose down

# Apagar y borrar datos
docker-compose down -v

# Reconstruir un servicio tras cambios de código
docker-compose up -d --build backend
docker-compose up -d --build frontend
docker-compose up -d --build etl

# Ver logs de un servicio
docker-compose logs backend
docker-compose logs clickhouse
```

---

## 📁 Estructura del proyecto

```
RetailMind-Data/
├── docker-compose.yml
├── .env
├── README.md
├── retailmind/              # Python ETL
│   ├── etl/
│   │   ├── 08_extract_pocketbase.py
│   │   ├── 09_load_clickhouse.py
│   │   ├── 10_verify_clickhouse.py
│   │   ├── 11_reset_clickhouse.py
│   │   └── 12_generate_synthetic.py
│   ├── config/
│   │   └── clickhouse_connection.py
│   └── data/
│       ├── stage/           # Archivos Parquet
│       └── agg/             # Agregaciones
├── retailmind-backend/      # Spring Boot 3
│   ├── src/
│   └── Dockerfile
└── retailmind-frontend/     # Angular 17+
    ├── src/
    ├── Dockerfile
    └── nginx.conf
```

---

## 🔐 Variables de entorno (.env)

```env
# ClickHouse
CH_HOST=clickhouse
CH_PORT=8123
CH_DATABASE=retailmind
CH_USER=default
CH_PASSWORD=retailmind2026

# PostgreSQL
POSTGRES_DB=CDRetail_Intelligence
POSTGRES_USER=postgres
POSTGRES_PASSWORD=1250143656

# Pocketbase
PB_EMAIL=benitesperezdariemalberto@gmail.com
PB_PASSWORD=retailmind2026@.
```

---

## 📊 Tecnologías

|            Categoría         |    Tecnología    |    Versión |
|------------------------------|------------------|------------|
| Base de datos analítica      | ClickHouse       | 26.4+      |
| Base de datos operacional    | PostgreSQL       | 15         |
| Fuente de datos              | Pocketbase       | 0.38       |
| Backend                      | Spring Boot      | 3.2.5      |
| Frontend                     | Angular          | 17+        |
| ETL                          | Python           | 3.12       |
| Contenedores                 | Docker + Compose | 29.4+      |
| Formato intermedio           | Apache Parquet   |     -      |

---

## 👨‍💻 Autor

**Darien Benites** — Universidad Técnica Estatal de Quevedo (UTEQ)  
Materia: Construcción de Software — 6to Semestre  
Docente: Ing. Ariosto Vicuña
