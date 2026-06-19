# RetailMind

RetailMind es una **plataforma web de retail analytics con tienda online integrada**. Combina un
pipeline ETL de datos, un motor de analítica sobre eventos (~2.3M de eventos) y una experiencia de
e-commerce completa. Es un sistema web (no de escritorio) con aspiración de escalado internacional.

El sistema se organiza en tres niveles empresariales:

- **Operativo (genera ventas)**: autenticación, catálogo, carrito, wishlist, pedidos/checkout,
  perfil y recomendaciones. Es la prioridad del producto: si no genera órdenes, el negocio no vende.
- **Táctico (toma de decisiones)**: sesiones, conversiones, funnel, analytics por
  región/dispositivo/tráfico y reportes (Excel/PDF).
- **Estratégico**: dashboard ejecutivo con KPIs.

Componentes:

- **ETL Pipeline** (Python): extrae el dataset crudo de PocketBase, lo materializa en archivos
  Parquet y lo carga en ClickHouse.
- **Backend API** (Java/Spring Boot): API REST que sirve analítica y operación de tienda, dispara
  el ETL y gestiona autenticación vía JWT.
- **Frontend** (Angular): SPA con tienda, dashboards, gráficos, tablas y herramientas de admin.

## Datos y roles

- **Base de datos operativa**: ClickHouse (esquema estrella). Tabla de hechos `fact_eventos`
  (~2.3M registros) más dimensiones (`dim_canal`, `dim_region`, `dim_dispositivo`, `dim_categoria`,
  `dim_fuente_trafico`, `dim_producto`, `dim_usuario`) y tablas de tienda (`productos_catalogo`,
  `carrito_items`, `wishlist_items`, `ordenes`, `orden_items`, `usuarios_sistema`).
- **PocketBase**: fuente del dataset crudo de retail (colección `dataset_retail`).
- **Roles (RBAC)**: `ADMIN` (dashboard, analytics avanzado, ETL, gestión de datos, usuarios,
  reportes, tienda) y `CLIENTE` (tienda, carrito, wishlist, recomendaciones, pedidos, perfil). El
  catálogo es público; la analítica avanzada es solo ADMIN; la tienda requiere autenticación.

## Idioma

El proyecto usa **español** para términos de dominio, comentarios y etiquetas de UI. Los
identificadores de código mezclan inglés (convenciones de framework) con español (entidades de
dominio como `sesiones`, `conversiones`, `pedidos`, `carrito`).
