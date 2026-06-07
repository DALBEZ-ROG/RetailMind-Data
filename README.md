# 🛍️ RetailMind Shop

## Sistema de Retail Analytics con Tienda Online

![Angular](https://img.shields.io/badge/Angular-17-red)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-green)
![ClickHouse](https://img.shields.io/badge/ClickHouse-24.4-yellow)
![Docker](https://img.shields.io/badge/Docker-Compose-blue)
![License](https://img.shields.io/badge/License-Academic-purple)

---

## 📋 Descripción

**RetailMind Shop** es una plataforma integral de retail analytics con tienda online que combina un pipeline ETL de datos sintéticos, un motor de analítica en tiempo real y una experiencia de e-commerce completa.

El sistema procesa **108,584 registros por semana** (más de 2.3 millones de eventos en 22 semanas) para alimentar dashboards analíticos, funnel de conversión, análisis por región/dispositivo/tráfico, y un motor de recomendaciones personalizado.

### Tecnologías principales:
- **Frontend**: Angular 17 con Angular Material y diseño glassmorphism premium
- **Backend**: Spring Boot 3 con Spring Security + JWT
- **Base de datos**: ClickHouse (columnar, alta velocidad analítica)
- **ETL**: Python con generación de datos sintéticos
- **Infraestructura**: Docker Compose para orquestación completa

### Tipos de usuario:
| Rol | Acceso |
|-----|--------|
| **ADMIN** | Dashboard, Analytics, ETL, Gestión de datos, CRUD de usuarios, Tienda |
| **CLIENTE** | Tienda, Carrito, Wishlist, Recomendaciones, Pedidos, Perfil |

---

## 📸 Screenshots

| Vista | Descripción |
|-------|-------------|
| Login Premium | Pantalla de acceso con glassmorphism y gradientes |
| Dashboard Analytics | 8 KPIs en tiempo real con gráficos Chart.js |
| Tienda Online | Catálogo con filtros por categoría y wishlist |
| Funnel de Conversión | Análisis completo del embudo de ventas |
| Análisis por Región | Mapa de calor y métricas por zona geográfica |
| Gestión de Datos | CRUD completo sobre ClickHouse |
| Recomendaciones | Motor personalizado basado en historial |

> 💡 *Insertar capturas de pantalla en la carpeta `/docs/screenshots/` y referenciarlas aquí*

---

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────────────┐
│                Docker Compose                    │
│                                                  │
│  ┌──────────────┐      ┌──────────────────────┐ │
│  │   Angular    │─────▶│   Spring Boot 3      │ │
│  │    :4200     │      │       :8080          │ │
│  └──────────────┘      └──────────┬───────────┘ │
│                                    │             │
│  ┌──────────────┐      ┌──────────▼───────────┐ │
│  │  PocketBase  │      │     ClickHouse       │ │
│  │    :8091     │      │       :8123          │ │
│  └──────────────┘      └──────────────────────┘ │
│                                    ▲             │
│                         ┌──────────┴───────────┐ │
│                         │     Python ETL       │ │
│                         │     (scripts)        │ │
│                         └──────────────────────┘ │
└─────────────────────────────────────────────────┘
```

**Flujo de datos:**
```
CSV sintéticos → Python ETL → ClickHouse ← Spring Boot API ← Angular Frontend
```

---

## 🚀 Arranque Rápido

```bash
# 1. Clonar el repositorio
git clone https://github.com/DALBEZ-ROG/RetailMind-Data.git
cd RetailMind-Data

# 2. Levantar todo el sistema
docker-compose up -d

# 3. Abrir en el navegador
http://localhost:4200

# Credenciales por defecto
# Usuario: admin
# Contraseña: admin123
```

### Requisitos previos:
- Docker Desktop 4.x+
- Docker Compose 2.x+
- Node.js 18+ (solo para desarrollo local del frontend)
- Java 17+ (solo para desarrollo local del backend)

---

## 📦 Módulos Implementados

| # | Paquete | Módulo | Estado | Semana |
|---|---------|--------|--------|--------|
| 1 | Autenticación | Login JWT + Registro | ✅ Implementado | S1 |
| 2 | Dashboard | 8 KPIs + Gráficos Chart.js | ✅ Implementado | S1 |
| 3 | Sesiones | Explorer con búsqueda | ✅ Implementado | S1 |
| 4 | Conversiones | Filtros Todos/Conv/Abandonos | ✅ Implementado | S1 |
| 5 | Admin ETL | Generación de semanas | ✅ Implementado | S2 |
| 6 | Gestión de Datos | CRUD ClickHouse (8 tabs) | ✅ Implementado | S2 |
| 7 | Funnel | Embudo de conversión | ✅ Implementado | S3 |
| 8 | Región | Analytics por zona geográfica | ✅ Implementado | S3 |
| 9 | Dispositivo | Analytics por device + tendencias | ✅ Implementado | S3 |
| 10 | Tráfico | Analytics por fuente + embudo/canal | ✅ Implementado | S3 |
| 11 | Tienda Online | Catálogo, filtros, categorías | ✅ Implementado | S4 |
| 12 | Carrito | Agregar, eliminar, checkout | ✅ Implementado | S4 |
| 13 | Wishlist | Lista de deseos + al carrito | ✅ Implementado | S4 |
| 14 | Pedidos | Historial de órdenes | ✅ Implementado | S4 |
| 15 | Recomendaciones | Motor personalizado + populares | ✅ Implementado | S5 |
| 16 | Perfil | Stats, editar email, cambiar password | ✅ Implementado | S5 |
| 17 | Reportes | Excel + PDF descargables | ✅ Implementado | S5 |

---

## 📁 Estructura del Proyecto

```
RetailMind-Data/
├── retailmind/                     # Python ETL Pipeline
│   ├── main.py                     # Orquestador principal
│   ├── config/
│   │   └── db_connection.py        # Conexión ClickHouse + logging
│   ├── etl/
│   │   ├── 01_create_tables.py     # DDL de tablas
│   │   ├── 02_load_lookup_tables.py
│   │   ├── 03_load_main_tables.py
│   │   ├── 04_verify_load.py
│   │   ├── 05_load_incremental.py  # Carga semanal
│   │   ├── 06_optimize_database.py
│   │   ├── 07_monitor_performance.py
│   │   ├── 08_apply_advanced_optimize.py
│   │   └── 09_create_refresh_function.py
│   ├── generate_synthetic.py       # Generador de datos sintéticos
│   └── requirements.txt
│
├── retailmind-backend/             # Spring Boot 3 REST API
│   ├── pom.xml
│   └── src/main/java/com/retailmind/
│       ├── auth/                   # JWT + ClickHouseUserRepository
│       ├── security/               # SecurityConfig, JwtFilter
│       ├── catalogo/               # Productos del catálogo
│       ├── carrito/                # Carrito de compras
│       ├── wishlist/               # Lista de deseos
│       ├── pedidos/                # Órdenes y checkout
│       ├── perfil/                 # Perfil de usuario
│       ├── recomendaciones/        # Motor de recomendaciones
│       ├── analytics/
│       │   ├── dashboard/          # KPIs y métricas globales
│       │   ├── sesiones/           # Explorer de sesiones
│       │   ├── conversiones/       # Análisis de conversión
│       │   ├── funnel/             # Embudo de conversión
│       │   ├── region/             # Analytics por región
│       │   ├── dispositivo/        # Analytics por dispositivo
│       │   └── trafico/            # Analytics por fuente
│       ├── admin/
│       │   ├── etl/                # Generación de datos
│       │   ├── gestion/            # CRUD de dimensiones
│       │   ├── pedidos/            # Admin de pedidos
│       │   ├── usuarios/           # Admin de usuarios
│       │   └── reportes/           # Excel + PDF
│       └── config/                 # CORS, ClickHouse, Health
│
├── retailmind-frontend/            # Angular 17 SPA
│   ├── angular.json
│   ├── package.json
│   └── src/app/
│       ├── app.component.*         # Layout: navbar + sidebar
│       ├── core/
│       │   ├── services/           # API services (HttpClient)
│       │   ├── guards/             # Auth guards
│       │   ├── interceptors/       # JWT interceptor
│       │   └── models/             # Interfaces TypeScript
│       └── features/
│           ├── login/              # Login premium glassmorphism
│           ├── shop/               # Tienda, carrito, detalle
│           ├── wishlist/           # Lista de deseos
│           ├── recomendaciones/    # Productos recomendados
│           ├── pedidos/            # Mis pedidos
│           ├── perfil/             # Perfil del usuario
│           ├── analytics/          # Dashboard, Funnel, etc.
│           └── admin/              # ETL, Gestión, Reportes
│
├── docker-compose.yml              # Orquestación completa
├── clickhouse-data/                # Volumen persistente ClickHouse
└── .env                            # Variables de entorno
```

---

## 🔐 Variables de Entorno

| Variable | Descripción | Ejemplo |
|----------|-------------|---------|
| `CLICKHOUSE_HOST` | Host de ClickHouse | `localhost` |
| `CLICKHOUSE_PORT` | Puerto HTTP | `8123` |
| `CLICKHOUSE_USER` | Usuario | `default` |
| `CLICKHOUSE_PASSWORD` | Contraseña | `YOUR_PASSWORD` |
| `CLICKHOUSE_DB` | Base de datos | `retailmind` |
| `JWT_SECRET` | Clave secreta para JWT | `YOUR_SECRET_KEY` |
| `JWT_EXPIRATION` | Expiración del token (ms) | `86400000` |
| `SPRING_PORT` | Puerto del backend | `8080` |
| `ANGULAR_PORT` | Puerto del frontend | `4200` |

---

## 🛠️ Comandos Útiles

```bash
# Reconstruir un servicio específico
docker-compose up -d --build backend
docker-compose up -d --build frontend

# Ver logs en tiempo real
docker-compose logs -f backend
docker-compose logs -f clickhouse

# Detener todo
docker-compose down

# Detener y eliminar volúmenes (CUIDADO: borra datos)
docker-compose down -v

# Generar datos sintéticos para semana N
# → Usar el módulo "Administración ETL" en la web

# Resetear datos del sistema
# → Usar el módulo "Inicialización" en la web

# Desarrollo local del frontend
cd retailmind-frontend
npm install
npm start

# Desarrollo local del backend
cd retailmind-backend
mvn clean install
mvn spring-boot:run
```

---

## 🗄️ Tablas de la Base de Datos (ClickHouse)

| Tabla | Tipo | Registros | Descripción |
|-------|------|-----------|-------------|
| `fact_eventos` | Fact | ~2,388,848 | Eventos de usuario (clicks, compras, abandonos) |
| `dim_canal` | Dimensión | 3 | Mobile, Web, App |
| `dim_region` | Dimensión | 5 | Regiones geográficas |
| `dim_dispositivo` | Dimensión | 4 | Smartphone, Desktop, Tablet, App |
| `dim_categoria` | Dimensión | 8 | Categorías de producto |
| `dim_fuente_trafico` | Dimensión | 4 | Fuentes de tráfico |
| `dim_producto` | Dimensión | ~1,000 | Productos del catálogo analítico |
| `dim_usuario` | Dimensión | ~5,000 | Usuarios del sistema analítico |
| `productos_catalogo` | Tienda | ~200 | Catálogo activo de la tienda |
| `carrito_items` | Tienda | Variable | Items en carritos activos |
| `wishlist_items` | Tienda | Variable | Productos en listas de deseos |
| `ordenes` | Tienda | Variable | Órdenes de compra |
| `orden_items` | Tienda | Variable | Detalle de items por orden |
| `usuarios_sistema` | Sistema | Variable | Usuarios con login (JWT) |

---

## 🧰 Tecnologías

| Tecnología | Versión | Propósito |
|------------|---------|-----------|
| Angular | 17+ | Framework frontend (standalone components) |
| Angular Material | 17 | Componentes UI + CDK |
| Chart.js | 4.5 | Gráficos en dashboard |
| TypeScript | 5.4 | Lenguaje del frontend |
| SCSS | - | Estilos con variables CSS premium |
| Spring Boot | 3.4 | Framework backend REST API |
| Spring Security | 6.x | Autenticación y autorización |
| JWT (jjwt) | 0.12.3 | Tokens de acceso |
| ClickHouse | 24.4 | Base de datos columnar analítica |
| ClickHouse JDBC | 0.6.5 | Driver de conexión Java |
| Python | 3.x | Pipeline ETL y generación de datos |
| Pandas | 2.2.2 | Procesamiento de datos |
| Docker | 24+ | Contenedores |
| Docker Compose | 2.x | Orquestación multi-contenedor |
| Apache POI | 5.2.5 | Generación de reportes Excel |
| iText | 5.5.13 | Generación de reportes PDF |
| Inter Font | - | Tipografía premium del sistema |

---

## 👨‍💻 Autor

| | |
|---|---|
| **Estudiante** | Darien Benites Pérez |
| **Universidad** | Universidad Técnica Estatal de Quevedo (UTEQ) |
| **Materia** | Construcción de Software — 6to Semestre |
| **Docente** | Ing. Ariosto |
| **Año** | 2026 |

---

<p align="center">
  <strong>RetailMind Shop</strong> — Retail Analytics Platform<br>
  <em>Hecho con ❤️ en Ecuador 🇪🇨</em>
</p>
