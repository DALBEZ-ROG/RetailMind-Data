# Informe de diagnóstico — Productos del dataset original → catálogo PostgreSQL

> **Tipo:** SOLO DIAGNÓSTICO. No se modificó ni construyó nada.
> **Objetivo futuro:** cargar los PRODUCTOS del dataset original en las tablas de catálogo de
> PostgreSQL (`marca`, `categoria`, `producto`, `producto_categoria`, `producto_variante`,
> `inventario`) para llenar el catálogo que hoy tiene solo ~13 productos de demo.
> **Alcance:** solo productos. Los eventos/sesiones se quedan en ClickHouse.
> **Fecha:** 2026-07-10

---

## 1. Flujo de datos original (archivos concretos)

El pipeline es **PocketBase → Parquet → ClickHouse**, todo bajo `retailmind/`:

| Paso | Archivo | Qué hace |
|---|---|---|
| Extracción | `retailmind/etl/extraccion/08_extract_pocketbase.py` | Se autentica en PocketBase (`PB_URL`, colección **`dataset_retail`**), pagina de 500 en 500, elimina columnas internas de PocketBase (`id`, `collection_id`, `created`, `updated`, `expand`) y escribe **`retailmind/data/stage/datos.parquet`**. |
| Carga | `retailmind/etl/carga/09_load_clickhouse.py` | Lee el Parquet, crea BD/tablas en ClickHouse y carga dimensiones + `fact_eventos` en lotes de 50.000. |
| Config CH | `retailmind/config/clickhouse_connection.py` | Conexión ClickHouse (`CH_HOST=172.29.94.38:8123`, BD `retailmind`, user `default`). |
| Verificación / reset | `retailmind/etl/carga/10_verify_clickhouse.py`, `11_reset_clickhouse.py` | Verifican y resetean ClickHouse. |

- **Fuente real:** colección **`dataset_retail`** de PocketBase.
- El Parquet ya generado existe en disco: **`retailmind/data/stage/datos.parquet`** (108.584 filas). Es **autosuficiente**: para el ETL de productos **no se necesita** ClickHouse ni PocketBase levantados.
- No se pudo conectar a ClickHouse (host `172.29.94.38`, probablemente WSL/Docker apagado), pero no es necesario: `dim_producto` se describe a partir del DDL del script de carga (autoritativo).

---

## 2. Estructura del dataset (Parquet)

Un **único dataset plano**: **108.584 filas × 18 columnas**, todas almacenadas como **string** (texto), **sin nulos**. Es un log de **eventos de sesión**, no un catálogo.

| Columna | Tipo real (almacenado) | Ejemplo | Rol |
|---|---|---|---|
| `session_id` | string | `S0000001` | evento |
| `user_id` | string | `U000372` | evento |
| `timestamp_utc` | string | `2026-01-08T02:34:40Z` | evento |
| `event_index` | string (int) | `1` | evento |
| `user_action` | string | `view` | evento |
| `time_spent_sec` | string (num) | `25` | evento |
| `session_length` | string (num) | `4` | evento |
| `interaction_count` | string (int) | `1` | evento |
| `is_conversion` | string (0/1) | `0` | evento |
| `drop_off_flag` | string (0/1) | `0` | evento |
| `channel` | string | `mobile` | evento |
| `device_type` | string | `desktop` | usuario |
| `region` | string | `JP` | usuario |
| `traffic_source` | string | `organic` | usuario |
| **`product_id`** | string | `P1481` | **producto** |
| **`category`** | string | `Electronics` | **producto** |
| **`brand`** | string | `Samsung` | **producto** |
| **`price`** | string (num) | `316.55` | **producto** |

Solo **4 columnas** describen al producto: `product_id`, `category`, `brand`, `price`.

---

## 3. Productos en el dataset

- **1.200 productos únicos** (`product_id`, formato `P####`).
- **Consistencia perfecta:** cada `product_id` tiene exactamente **1 categoría, 1 marca y 1 precio** (0 conflictos). Se puede deduplicar con seguridad por `product_id`.
- **8 categorías:** `Electronics`, `Groceries`, `Sports`, `Accessories`, `Beauty`, `Home`, `Shoes`, `Apparel`.
- **33 marcas:** Samsung, OrganicCo, FreshFarm, Adidas, Coach, Puma, Olay, Mainstays, New Balance, Dove, Decathlon, Loreal, H&M, Wilson, Nike, Ray-Ban, Fitbit, Ikea, Under Armour, Bose, Maybelline, Target, Asics, DailyFresh, NatureBest, GreatValue, Wayfair, Spalding, Sony, Neutrogena, Fossil, HomeGoods, Apple.
- **Precio:** rango **7,29 – 499,86**, media ≈ **249,5**.

### Marcas por categoría (5 por categoría)

| Categoría | Marcas |
|---|---|
| Accessories | Coach, Puma, Ray-Ban, Nike, Fossil |
| Apparel | H&M, Nike, Under Armour, Puma, Adidas |
| Beauty | Olay, Dove, Loreal, Maybelline, Neutrogena |
| Electronics | Samsung, Fitbit, Bose, Sony, Apple |
| Groceries | OrganicCo, FreshFarm, DailyFresh, NatureBest, GreatValue |
| Home | Mainstays, Ikea, Target, Wayfair, HomeGoods |
| Shoes | Puma, New Balance, Nike, Asics, Adidas |
| Sports | Adidas, Decathlon, Wilson, Nike, Spalding |

### Campos que TIENE vs. que FALTAN

- **Tiene:** `product_id`, `category`, `brand`, `price`.
- **NO tiene (gaps):** nombre de producto, descripción, **SKU**, código de barras, **stock**, **costo**, **variantes**, proveedor, dimensiones/peso, imágenes.

---

## 4. Dónde viven los productos hoy en ClickHouse

Tabla **`retailmind.dim_producto`** (definida en `09_load_clickhouse.py`), cargada con
`df[["product_id","category","brand","price"]].drop_duplicates("product_id")` → **1.200 filas**.

| Columna | Tipo CH | Origen |
|---|---|---|
| `producto_id` | String | `product_id` |
| `categoria_id` | UInt32 | FK a `dim_categoria` (id sintético 1..8) |
| `brand` | String | `brand` |
| `price` | Float32 | `price` |

- La categoría se normaliza en `dim_categoria` (`categoria_id`, `categoria_nombre`).
- La marca queda como **texto plano** dentro de `dim_producto` (no hay `dim_marca`).
- ClickHouse **tampoco** tiene nombre, SKU, stock ni costo → mismos gaps que el Parquet.

---

## 5. Estructura destino en PostgreSQL

> Todas las PK `id` son **`bigint GENERATED ALWAYS AS IDENTITY`** → **NUNCA escribirlas**
> (deja que la BD las asigne). No hay columnas GENERATED de datos ni triggers de totales en el
> módulo de catálogo (`04_m03_catalogo.sql` limpio).

**Conteos actuales (demo):** `producto`=13, `producto_variante`=20, `categoria`=4, `marca`=3,
`inventario`=26, `producto_categoria`=13, `bodega`=2.

### `marca`
- Obligatorios: `nombre` (**UNIQUE**), `slug` (**UNIQUE**).
- `activo` default `true`. `logo_url`, `descripcion` nullable.

### `categoria`
- Obligatorios: `nombre`, `slug` (**UNIQUE**), `orden` (def 0), `activo` (def true).
- `categoria_padre_id` nullable (jerarquía por lista de adyacencia, FK a sí misma).

### `producto`
- Obligatorios: `nombre`, `slug` (**UNIQUE**).
- `marca_id` **nullable** (FK→`marca`, ON DELETE SET NULL).
- `publicado` def `false`, `destacado` def `false`, `activo` def `true`.
- **No tiene `categoria_id` directo** (se relaciona vía `producto_categoria`).

### `producto_categoria` (junction N:M)
- Obligatorios: `producto_id` (FK), `categoria_id` (FK).
- **UNIQUE compuesto** `(producto_id, categoria_id)` → un producto puede tener varias categorías. Sin problema para 1.200 productos.
- `es_principal` def `false`.

### `producto_variante`
- Obligatorios: `producto_id` (FK), `sku` (**UNIQUE**), `precio` (NOT NULL, CHECK ≥ 0).
- `codigo_barras` UNIQUE nullable.
- `costo` NOT NULL **DEFAULT 0** (CHECK ≥ 0).
- `es_predeterminada` def `false`, `activo` def `true`.
- **Aquí vive el precio.**

### `inventario`
- Obligatorios: `producto_variante_id` (FK, **UNIQUE**), `bodega_id` (FK, **UNIQUE**), `stock_actual` (def 0), `stock_reservado` (def 0), `stock_minimo` (def 0).
- `stock_maximo` nullable.
- ⚠️ **`producto_variante_id` y `bodega_id` son UNIQUE por separado, NO compuesto.** `bodega_id` UNIQUE ⇒ **solo puede existir UNA fila de inventario por bodega**. Ver §7.

### FKs a resolver
- `producto.marca_id` → `marca.id`
- `producto_categoria.categoria_id` → `categoria.id`
- `producto_categoria.producto_id` → `producto.id`
- `producto_variante.producto_id` → `producto.id`
- `inventario.producto_variante_id` → `producto_variante.id`
- `inventario.bodega_id` → `bodega.id` (existen 2: "Bodega Central Quevedo", "Bodega Norte")

---

## 6. Mapeo propuesto dataset → PostgreSQL + gaps

**Orden de carga:** `marca` → `categoria` → `producto` → `producto_categoria` → `producto_variante` → (`inventario`).

| Destino (columna) | Origen dataset | Transformación |
|---|---|---|
| `marca.nombre` | `brand` (distinct, 33) | tal cual; `slug` = slugify(brand); **ON CONFLICT (nombre) DO NOTHING** |
| `categoria.nombre` | `category` (distinct, 8) | traducir a ES (Electronics→Electrónica…) o dejar EN; `slug` = slugify |
| `producto.nombre` | `brand` + `category`/`product_id` | **derivado** (ej. `"Samsung P1481"`) |
| `producto.slug` | `product_id` | slugify(product_id) → único garantizado |
| `producto.marca_id` | `brand` | lookup a `marca` cargada |
| `producto_categoria.categoria_id` | `category` | lookup; `es_principal = true` |
| `producto_variante.precio` | `price` | cast a `numeric(12,2)` |
| `producto_variante.sku` | `product_id` | ej. `SKU-P1481` → único |
| `producto_variante.costo` | — | **gap** → `round(price * 0.6, 2)` |
| `producto_variante.es_predeterminada` | — | `true` (variante única) |
| `inventario.stock_actual` | — | **gap** → `100` |
| `inventario.bodega_id` | — | **gap** → bodega genérica |

### Gaps a rellenar con defaults
- **Nombre de producto:** derivar (ej. `brand + product_id`).
- **Descripción:** opcional / `null`.
- **SKU:** `SKU-{product_id}`.
- **Variante única:** `es_predeterminada = true`.
- **Costo:** `round(price * 0.6, 2)`.
- **Stock:** `100`.
- **Proveedor genérico:** solo si además se cargan cuentas por pagar; para catálogo + inventario **no es obligatorio** (`inventario` no referencia proveedor).

---

## 7. Advertencias / a confirmar antes del ETL

1. **`inventario.bodega_id` es UNIQUE (no compuesto).** Con este esquema no se pueden crear 1.200 filas de inventario (solo 1 por bodega). Opciones a confirmar:
   - (a) **Cambiar** la constraint a UNIQUE compuesto `(producto_variante_id, bodega_id)` — es casi seguro un bug del DDL y el patrón correcto.
   - (b) Omitir `inventario` en esta carga.
   - Decisión de diseño: el steering dice "la BD es dueña de la integridad", así que **confírmalo antes** de tocar el DDL.
2. **Overlap de marcas.** El dataset trae **Nike, Adidas, Puma**, que **ya existen** en `marca` (nombre UNIQUE). Usar `INSERT ... ON CONFLICT (nombre) DO NOTHING` y resolver `marca_id` por lookup.
3. **Overlap/idioma de categorías.** Las 4 categorías actuales están en español (Ropa Hombre, Ropa Mujer, Calzado, Accesorios) y no coinciden 1:1 con las 8 en inglés del dataset (Accessories≈Accesorios, Shoes≈Calzado, Apparel≈Ropa). Decidir si **traducir y fusionar** o crear nuevas. `slug` es UNIQUE: cuidar colisiones.
4. **Todo dentro de `@Transactional` / SET LOCAL ROLE.** Si el ETL corre por el backend, debe ir en transacción con rol `grp_administrador`. Si se hace con script Python (psycopg2) conectando como `postgres`, se salta RLS/privilegios **y** el modelo de seguridad — elegir una vía y ser consistente.
5. **No escribir IDs identity.** Dejar que PostgreSQL asigne `id`; mapear `product_id`→`producto` por `slug`/lookup, no por id.
6. **Tipos:** todo el Parquet es string; castear `price` a numérico (hay 1.189 precios distintos) antes de insertar.
7. **Nombre de producto artificial.** El dataset no tiene nombres reales; los productos serán sintéticos (marca + código). Confirmar si es aceptable para el catálogo público.
8. **Config `.env` desalineada.** `retailmind/.env` apunta `DB_NAME=CDRetail_IntelligenceViejo2` (nombre antiguo), mientras la BD operativa real es **`retailmind`**. Si el ETL usa ese `.env`, ajustarlo a `retailmind`; las credenciales viven en `retailmind/.env` (gitignored) y **nunca se escriben aquí**.

> **Nota de actualización (2026-08-05).** Este informe es un diagnóstico **histórico** del 2026-07-10 y su contexto de despliegue ya no vale: desde el 2026-08-03 PostgreSQL corre **en un contenedor** publicado en el **5432**, el PostgreSQL local pasó al **5433**, y las credenciales de motor (`postgres` del contenedor, `retailmind_app`, `retailmind_etl`, `jwt.secret`) fueron **rotadas**. La contraseña que este punto 8 traía en claro se retiró por eso. Estado real: `docs/DESPLIEGUE_EJECUTADO.md`.

---

## Resumen ejecutivo

- **Fuente:** `retailmind/data/stage/datos.parquet` (108.584 eventos), extraído de la colección
  PocketBase `dataset_retail`. Autosuficiente; no requiere ClickHouse.
- **Productos:** 1.200 únicos, consistentes; 8 categorías, 33 marcas. Solo hay `product_id`,
  `category`, `brand`, `price`.
- **Faltan** nombre, SKU, stock, costo, variantes → rellenar con defaults (variante única,
  stock 100, costo 60% del precio).
- **Bloqueadores a confirmar:** UNIQUE de `inventario.bodega_id` (bug probable), overlap de
  marcas/categorías, y vía de ejecución (backend transaccional vs. script directo).
