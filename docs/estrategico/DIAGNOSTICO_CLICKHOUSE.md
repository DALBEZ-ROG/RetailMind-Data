# Diagnóstico de la instancia ClickHouse de RetailMind

**Universidad Técnica Estatal de Quevedo (UTEQ)** · Facultad de Ciencias de la Ingeniería
**Asignatura**: Construcción de Software — 6.º semestre
**Proyecto**: RetailMind — comercio minorista multicanal de ticket alto (Quevedo, Los Ríos, Ecuador)
**Documento**: diagnóstico de la capa analítica · **Fecha: 2026-07-30** · **Modo: SOLO LECTURA**

> **Alcance y garantía de no escritura.** Este documento inventaría qué contiene hoy ClickHouse y
> qué relación tiene con el PostgreSQL operativo vigente, para poder planificar un ETL. **No se
> escribió, alteró ni borró nada** en ClickHouse ni en PostgreSQL. Todas las consultas fueron
> `SELECT`/`SHOW`/`DESCRIBE`. El volumen de datos original se montó **read-only** y se interrogó
> sobre una **copia desechable** (§2.3), de modo que ni siquiera los logs internos del servidor
> tocaron el dato original. Tampoco se modificó ningún archivo de código. Este documento **no
> decide qué hacer** con lo encontrado: eso se decide después, con el humano.

---

## 1. Resumen ejecutivo

| Pregunta | Respuesta |
|---|---|
| ¿ClickHouse responde? | **Sí**, pero no estaba corriendo: no existe ningún contenedor de ClickHouse; el dato sobrevive en el volumen Docker `1m6datoscs_clickhouse_data`. Levantado sobre una copia, responde y monta las 14 tablas sin error. |
| Bases de datos | **2 con contenido**: `retailmind` (dato del proyecto) y `system` (logs internos del motor). `default` está vacía. |
| Tablas | **14** en `retailmind` (11 más en `system`, todas de telemetría del motor). |
| Filas totales | **2.832.605** en `retailmind`, de las cuales **2.823.245** son `fact_eventos`. (`system` acumula 101,3 M de filas de logs.) |
| Tamaño | **115,59 MiB** el dato del proyecto; **440,98 MiB** los logs del motor; ~830 MB el volumen completo en disco. |
| Última escritura | **2026-06-30 17:58** — hace un mes. Nada entró después de la migración de la tienda a PostgreSQL (2026-07-11). |

**Veredicto en dos líneas.** Lo que hay es **legado obsoleto casi en su totalidad**: el 96,2 % de
los eventos es relleno sintético que apunta a un catálogo fantasma de 500 productos inexistentes, y
el 3,8 % restante es un dataset de e-commerce **global** (regiones US/JP/UK/DE…, categorías en
inglés, 6.806 usuarios ficticios) de un negocio que ya no es el de RetailMind. Lo único vigente es
un **puente de identidad de producto** —los 1.200 ids `P####` de `dim_producto` corresponden 1:1 a
los `slug` del catálogo de PostgreSQL, con precios idénticos—, que es un *mapeo* aprovechable, no un
*dato* aprovechable.

---

## 2. Conexión: qué se encontró y cómo se accedió

### 2.1 Datos de conexión hallados en el repositorio

| Fuente | Contenido relevante |
|---|---|
| `.env` (raíz) | `CH_HOST=clickhouse`, `CH_PORT=8123`, `CH_DATABASE=retailmind`, `CH_USER`, `CH_PASSWORD` (**valores no reproducidos**; `.env` está gitignored) |
| `docker-compose.yml` | Servicio `clickhouse` (imagen `clickhouse/clickhouse-server:latest`), puertos `8123`/`9000`, volumen nombrado `clickhouse_data`, healthcheck a `/ping` |
| `retailmind-backend/src/main/resources/application.properties` | `clickhouse.datasource.url` con **default hardcodeado** `jdbc:ch://172.29.94.38:8123/retailmind?compress=0` (una IP de WSL de otra máquina/sesión) y `CH_HOST` por defecto en la misma IP |
| `retailmind/config/clickhouse_connection.py` | Cliente `clickhouse_connect`, mismos defaults (`172.29.94.38`, usuario `default`) |

Nota de higiene (no es parte del encargo, pero se declara): la contraseña de ClickHouse y las
credenciales de PocketBase están en claro en `.env`, y la IP `172.29.94.38` es un default frágil
—si Docker levanta el compose, el host correcto es `clickhouse`; si se corre local, `localhost`—.

### 2.2 Estado real del servicio

- `docker ps -a` → **cero contenedores**. El stack fue desmontado, no solo apagado.
- `curl http://localhost:8123/ping` y `http://172.29.94.38:8123/ping` → sin respuesta (conexión rechazada).
- El dato **sí existe**: volumen `1m6datoscs_clickhouse_data`, creado el **2026-05-15T02:45Z**, ~830 MB.
- Existe además una carpeta `clickhouse-data/` en la raíz del repo: es un **bind-mount huérfano y
  vacío de datos** de una configuración anterior (14-may, mismas tablas del star-schema pero con
  UUIDs distintos y partes de 1 KB, es decir sin filas). No es la instancia viva; se ignora.

### 2.3 Cómo se interrogó sin escribir

1. Se listó el contenido del volumen montándolo **`:ro`** en un contenedor efímero, y se leyó el
   DDL directamente de `metadata/retailmind/*.sql` (sentencias `ATTACH TABLE`).
2. Para poder ejecutar SQL se **clonó** el volumen a uno desechable
   (`ch_diag_readonly_copy`) copiando desde el original montado **`:ro`**.
3. Se levantó `clickhouse-server` (v26.4.2.10) **sobre la copia**, en el puerto 18123, y se
   consultó con `clickhouse-client` dentro del contenedor.
4. PostgreSQL se consultó por el MCP `retailmind` (solo lectura), únicamente con `SELECT`.

El original nunca fue montado en modo escritura, de modo que el arranque del servidor —que
inevitablemente escribe logs y tablas de `system`— ocurrió sobre la copia. Los artefactos
temporales (`ch_diag`, `ch_diag_readonly_copy`) se eliminaron al terminar; **el volumen
`1m6datoscs_clickhouse_data` queda exactamente como estaba**.

---

## 3. Inventario de tablas

Base `retailmind` — todas `MergeTree`. «Última parte» = fecha real de la última escritura.

| Tabla | Filas | Tamaño | Rango de fechas del dato | Última parte | Qué representa | Clasificación |
|---|---:|---:|---|---|---|---|
| `fact_eventos` | 2.823.245 | 115,47 MiB | `timestamp_utc` 2026-01-01 → 2026-07-01 | 2026-06-30 | Hecho de comportamiento (view/click/add_to_cart/wishlist/drop/purchase) | **Mezcla**: 3,8 % dataset original, 96,2 % relleno sintético, 64 filas de la app vieja (§4) |
| `dim_usuario` | 6.806 | 60,43 KiB | sin columna temporal | 2026-05-15 | Usuario → región → dispositivo | Legado obsoleto |
| `dim_producto` | 1.200 | 15,59 KiB | sin columna temporal | 2026-05-15 | Producto `P1001`–`P2200` → categoría, marca, precio | **Legado con valor de mapeo** (§4.4) |
| `dim_categoria` | 9 | 1007 B | — | 2026-05-24 | 8 categorías en inglés + una fila espuria `IA` | Legado (mapeable) |
| `dim_region` | 9 | 493 B | — | 2026-05-15 | Países: JP, UK, AU, IN, CA, BR, US, DE, FR | Legado obsoleto |
| `dim_dispositivo` | 4 | 496 B | — | 2026-05-15 | desktop, tablet, android, ios | Legado (mapeable) |
| `dim_canal` | 3 | 462 B | — | 2026-05-15 | mobile, web, app | Legado (mapeable) |
| `dim_fuente_trafico` | 7 | 539 B | — | 2026-05-15 | organic, direct, affiliate, paid_search, social, email, referral | **Huérfana**: ninguna tabla la referencia (§5.3) |
| `productos_catalogo` | 1.200 | 29,99 KiB | `fecha_creacion` constante 2026-01-01 | 2026-05-20 | Catálogo de la tienda cuando la tienda vivía en ClickHouse | Legado muerto |
| `usuarios_sistema` | 6 | 2,50 KiB | 2026-05-20 → 2026-06-24 | 2026-06-24 | `admin` + `cliente1..5`, **con contraseña en columna** | Legado muerto |
| `ordenes` | 18 | 3,25 KiB | 2026-05-21 → 2026-06-30 | 2026-06-30 | Pedidos de prueba de la tienda vieja | Legado muerto |
| `orden_items` | 32 | 1,75 KiB | (sin fecha) | 2026-06-30 | Líneas de esos 18 pedidos | Legado muerto |
| `carrito_items` | 40 | 4,74 KiB | 2026-05-20 → 2026-06-30 | 2026-06-30 | Carrito de la tienda vieja (6 usuarios) | Legado muerto |
| `wishlist_items` | 26 | 2,89 KiB | 2026-05-21 → 2026-06-30 | 2026-06-30 | Wishlist de la tienda vieja (4 usuarios) | Legado muerto |

Base `system` — 11 tablas, 101,3 M filas, 440,98 MiB. Es **telemetría del propio motor**
(`trace_log` 183 MiB, `text_log` 165 MiB, `asynchronous_metric_log`, `metric_log`, `part_log`,
`query_log`…). No contiene dato de negocio; pesa casi cuatro veces más que el dato del proyecto.

Base `default`: **0 tablas**.

Observaciones estructurales del esquema:

- Todo el modelo usa `String` para las fechas (`timestamp_utc`, `fecha_orden`, `fecha_agregado`,
  `fecha_creacion`). Eso impide particionar por tiempo, y de hecho **ninguna tabla tiene
  `PARTITION BY`**: todas son `MergeTree` con `ORDER BY` por clave de negocio y nada más. Con
  formatos mezclados en la misma columna (`2026-01-08T02:34:40Z`, `2026-01-11 01:39:44`,
  `2026-06-24T02:33:35.763535838`) el orden lexicográfico ni siquiera es fiable como orden temporal.
- `fact_eventos.event_pk` está declarada `DEFAULT rowNumberInAllBlocks()`, que se evalúa **por
  lote de inserción**: el valor real es 0 en las primeras filas de cada lote y **se repite** entre
  lotes. No es una clave única, aunque `GestionDatosService` la usa como identificador de fila.
- El hecho no tiene FK a `dim_fuente_trafico`, `dim_region` ni `dim_dispositivo`: región y
  dispositivo se alcanzan solo vía `dim_usuario`; la fuente de tráfico no se alcanza en absoluto.

---

## 4. Análisis de origen y vigencia

Esta es la parte sustantiva del diagnóstico. `fact_eventos` **no es un único conjunto de datos**:
al cruzar el patrón de los identificadores contra la columna `semana` aparecen tres poblaciones
perfectamente separadas.

| Población | Filas | % | Ids de producto | Ids de sesión | Origen |
|---|---:|---:|---|---|---|
| A · Dataset original | 108.581 | 3,8 % | `P1001`–`P2200` (1.200) | `S########` (18.000) | Carga real desde PocketBase (`semana = 1`) |
| B · Relleno sintético | 2.714.600 | 96,2 % | `prod_0001`–`prod_0500` (500) | `sess_NN_######` (437.550) | Generador Python, `semana = 2..26` |
| C · Trazas de la app vieja | 64 | 0,002 % | `P####` reales | `sess_shop_########` | La tienda cuando corría sobre ClickHouse |

### 4.1 Población B — relleno sintético (96,2 % del volumen)

Es el hallazgo principal. El cruce `semana × patrón de id` es tajante: la semana 1 tiene 108.581
filas, **todas** con producto `P####`; las semanas 2 a 26 tienen **exactamente 108.581 filas cada
una**, **todas** con producto `prod_####`. El código lo confirma:
`retailmind/etl/sinteticos/12_generate_synthetic.py` genera `TOTAL_REGISTROS = 108_584` por semana
con `product_ids = [f"prod_{i:04d}" for i in rng.integers(1, 501, ...)]`.

Consecuencias medidas:

- **2.714.600 eventos (96,2 %) son huérfanos**: su `product_id` no existe en `dim_producto`
  (comprobado con `LEFT JOIN`) ni en PostgreSQL (`SELECT count(*) FROM producto WHERE slug ~
  '^prod_[0-9]{4}$'` → **0**). Referencian un catálogo de 500 productos que nunca existió.
- El `price` del evento es un `uniform(5.99, 999.99)` **independiente del producto**: 99.401
  valores distintos para 500 productos. Cualquier «revenue» calculado sobre esta población —y
  `TraficoService`, `RegionService` y `DispositivoService` calculan `sum(price * is_conversion)`—
  es ruido puro.
- El `timestamp_utc` está anclado artificialmente en `2026-01-01 + (semana-1) semanas`, lo que
  produce una serie perfectamente uniforme de 108.581 eventos semanales: sin estacionalidad, sin
  fines de semana, sin campañas. No sirve como serie temporal.
- Lo único «real» que conserva es el `user_id`, muestreado de `dim_usuario` (el script lo hace a
  propósito para que los JOIN de región y dispositivo no queden vacíos). Es decir: los cortes por
  región y dispositivo **devuelven números**, pero son números de eventos inventados.

### 4.2 Población A — dataset original (3,8 %)

Son los 108.581 eventos cargados desde PocketBase (`08_extract_pocketbase.py` →
`09_load_clickhouse.py`). Es dato coherente internamente: 18.000 sesiones, 6.806 usuarios, 1.200
productos que sí existen en `dim_producto`, tasa de conversión 3,87 %, mezcla de acciones
plausible. **Pero no es RetailMind**:

- **Geografía**: las 9 regiones son **países** (US 781 usuarios, JP 777, CA 767, IN 762, DE 755,
  AU 748, UK 745, BR 743, FR 728). No hay Ecuador, ni Quevedo, ni Los Ríos. El PostgreSQL vigente
  resuelve zona de envío por ciudad > provincia > país sobre direcciones ecuatorianas.
- **Catálogo**: 8 categorías en inglés con exactamente 150 productos cada una
  (Apparel, Shoes, Home, Beauty, Sports, Electronics, Groceries, Accessories) — un dataset de
  demostración, perfectamente equilibrado. El PostgreSQL vigente tiene 11 categorías en español,
  desbalanceadas a propósito (Abarrotes lidera con 3,69× Accesorios tras el Bloque D).
- **Modelo de negocio**: es solo comportamiento web de una tienda al consumidor. No hay proveedor,
  orden de compra, bodega, kardex, factura, devolución ni ticket. RetailMind hoy es un **comercio
  minorista multicanal de ticket alto** con todo el back-office (ver
  `DIAGNOSTICO_SEGMENTO_CLIENTE.md`, 2026-07-30).
- **Ventana temporal**: los eventos de la semana 1 se reparten entre **2026-01 y 2026-05**
  (27.321 / 25.204 / 27.999 / 27.703 / 354). El PostgreSQL vigente cubre **2025-01-13 → 2026-07-22**.
  ClickHouse **no tiene ni un evento de 2025**: le falta el 63 % de la historia del negocio.
- **Usuarios**: 6.806 ids sintéticos `U000001`–`U006806`, sin correspondencia con los **72 clientes**
  de PostgreSQL. **Cero** `user_id` de ClickHouse contiene un `@` (comprobado), mientras que el
  backend actual identifica al usuario por email.

### 4.3 Población C — trazas de la tienda anterior (64 filas)

Son los eventos que la aplicación escribió en vivo, y datan la migración con precisión: 61 de
ellos usan `user_id` = `admin`, `cliente1`…`cliente5`, que son los **usuarios de `usuarios_sistema`
en ClickHouse**, no los de PostgreSQL. Todos son `view`, todos con `price = 968.9` y
`channel = mobile` constantes (valores por defecto del emisor). La última es del **2026-06-30**.

Corolario: **desde que la tienda se migró a PostgreSQL (2026-07-11) no ha entrado ni un evento**.
La señal de comportamiento está muerta hace un mes.

### 4.4 Lo único que sí sigue vigente: el puente de identidad de producto

Contraste hecho contra PostgreSQL:

- Los **1.200** ids de `dim_producto` (`P1001`–`P2200`) existen **todos** en PostgreSQL como
  `producto.slug` en minúsculas: `1200 presentes / 0 ausentes`.
- Los precios coinciden **al centavo**: `P1001` = 190,40 en ambas; `P1002` = 475,60; `P1003` =
  367,34; `P1005` = 82,23; `P1008` = 433,76; `P2200` = 69,39.
- La razón está documentada: `retailmind/etl/carga/14_carga_productos_catalogo.py` cargó esos 1.200
  productos del dataset original al catálogo operativo con `slug = slugify(product_id)` y
  `nombre = "{marca} {product_id}"` (de ahí los «Nike P1001» del catálogo actual).
- PostgreSQL tiene 1.214 productos: los 1.200 del dataset + 14 hechos a mano.

O sea: `fact_eventos.product_id ↔ producto.slug` es una junta válida **para la población A**. Es la
única pieza reutilizable, y es un mapeo de 1.200 filas, no dos millones de eventos.

### 4.5 Cuadro de correspondencia con el PostgreSQL vigente

| Entidad | ClickHouse | PostgreSQL hoy | ¿Corresponde? |
|---|---|---|---|
| Producto | 1.200 `P####` (+500 `prod_####` fantasma) | 1.214 productos / 1.221 variantes | **Sí para los 1.200** (vía `slug`); no para los 500 |
| Cliente / usuario | 6.806 `U######` + 6 de PocketBase | 72 clientes (email) | **No** |
| Categoría | 8 en inglés + 1 espuria | 11 en español | Mapeable, no idéntica |
| Geografía | 9 países | Ecuador (ciudad/provincia/país) | **No** |
| Pedido | 18 `ORD-…` de prueba, `total` sin impuestos ni descuentos | 4.083 pedidos (web 2.213, tienda 1.030, teléfono 840), $5,72 M | **No** |
| Ventana temporal | 2026-01 → 2026-07 | 2025-01-13 → 2026-07-22 | **Parcial**: falta todo 2025 |
| Compras / inventario / posventa | inexistente | 13.287 movs de kardex, 3.887 facturas, 2.872 envíos, 248 tickets, 344 reseñas | **No** |

---

## 5. Estado del código de la capa analítica

No existe un directorio `analytics/` en la raíz. La capa vive en tres sitios:

### 5.1 `retailmind/` — el ETL Python

| Script | Qué hace | Estado |
|---|---|---|
| `etl/extraccion/08_extract_pocketbase.py` | Extrae la colección `dataset_retail` de PocketBase → Parquet | **Obsoleto**: la fuente ya no es PocketBase sino PostgreSQL |
| `etl/carga/09_load_clickhouse.py` | Parquet → DDL del star-schema + carga de dims y hecho | Reutilizable **como molde**; el esquema que crea es el legado |
| `etl/carga/10_verify_clickhouse.py` | Verificación de la carga | Reutilizable como molde |
| `etl/carga/11_reset_clickhouse.py` | `DROP TABLE` de las 8 tablas del star-schema | **Peligroso**: destructivo, sin confirmación |
| `etl/sinteticos/12_generate_synthetic.py` | Genera 108.584 eventos falsos por semana | **A retirar**: es el origen del 96,2 % de basura (§4.1) |
| `etl/extraccion/13_create_shop_tables.py` | Crea la tienda (catálogo/carrito/wishlist/órdenes) **en ClickHouse** | **Obsoleto**: la tienda vive en PostgreSQL desde 2026-07-11 |
| `etl/carga/14_carga_productos_catalogo.py` | Carga puntual del catálogo al PostgreSQL operativo | **Ya ejecutado**, histórico; es el que creó el puente del §4.4 |
| `sql/create_tables.sql` | DDL **PostgreSQL** de un modelo `regiones/dispositivos/canales/eventos/conversiones` en inglés | **Fósil**: ese esquema no existe en ninguna base viva |
| `etl/analytics/README.md` | Contiene una sola línea: «Scripts de análisis - Pendiente Semana 5» | Vacío |
| `etl/reportes/` | Solo `__init__.py` y README | Vacío |

Resumen: **no hay ETL PostgreSQL → ClickHouse**. Todo el pipeline escrito va de PocketBase a
ClickHouse, y PocketBase ya no es fuente de nada. Tampoco hay orquestación (ni Airflow ni cron);
`docker-compose` deja el contenedor `etl` con `tail -f /dev/null`, o sea a la espera de invocación
manual. Lo aprovechable son los patrones (`config/clickhouse_connection.py`, inserción por lotes de
50.000, `utils/load_tracker.py`, `utils/error_reporter.py`), no la lógica.

### 5.2 `retailmind-backend/.../analytics/` — los 7 servicios de lectura

`dashboard`, `funnel`, `sesiones`, `conversiones`, `region`, `dispositivo`, `trafico`. Todos
consultan `retailmind.fact_eventos` y sus dimensiones, y están **técnicamente vivos**: `ClickHouseConfig`
quedó blindado el 2026-07-18 (Hikari `connectionTimeout` 3 s, `initializationFailTimeout=-1`), de
modo que con ClickHouse apagado la app arranca y `/api/health` responde `status: UP, analytics:
DEGRADED`. Pero **lo que muestran es la basura del §4.1**: sus KPIs de conversión, revenue y tiempo
promedio se calculan sobre los 2,71 M de eventos sintéticos.

Fuera del paquete, dos dependencias más:

- `admin/reportes/ReportesService` lee `retailmind.ordenes` — los 18 pedidos de prueba de la tienda
  muerta. Sus reportes de «clientes top» y «ventas» no tienen relación con los 4.083 pedidos reales.
- `admin/gestion/GestionDatosService` expone un CRUD sobre `fact_eventos` y las dimensiones,
  concatenando `event_pk`, nombres de tabla y columnas directamente en el SQL.

### 5.3 Desalineaciones concretas entre el código y el dato (para tenerlas a la vista al planificar)

1. **La señal de recomendaciones está rota en origen.** `RecomendacionesService` busca por
   `user_id = username` (email) y hace `lower(product_id)` para juntar con `producto.slug` — es
   decir, espera `p####`. Pero `CarritoService` y `WishlistService` llaman a
   `EventoTiendaService.registrar(..., String.valueOf(varianteId), ...)`: escriben el **id numérico
   de la variante**, que nunca juntará con un slug. Solo el evento de navegación
   (`POST /api/catalogo/eventos`, que manda el front) escribe el slug correcto. Y como no hay ni un
   `user_id` con `@` en la tabla, en la práctica el motor **siempre** cae en su degradación a
   destacados.
2. **La columna `semana` colisiona.** `EventoTiendaService` la calcula como semana ISO del año en
   curso, y el generador sintético la usó como 1..26. Por eso los eventos de la app aparecen
   mezclados dentro de las semanas 23, 25, 26 y 27 del relleno. Como discriminador de lote es
   inservible.
3. **«Tráfico» no mide tráfico.** `TraficoService` agrupa por `fe.channel` (mobile/web/app), que es
   el **dispositivo/medio**, no la fuente. `dim_fuente_trafico` existe con sus 7 filas pero
   **ninguna tabla la referencia**: el hecho no tiene columna de fuente. La pantalla rotula
   «fuente» lo que en realidad es canal — el mismo tipo de confusión ya documentado para
   `pedido.canal` en la memoria de OTD-VEN-16.

---

## 6. Veredicto de planificación del ETL

Sin ejecutar nada. Cuatro conclusiones y su fundamento.

### 6.1 Qué es legado obsoleto

**Las 14 tablas, en bloque.** Con matices de grado:

- `fact_eventos` semanas 2-26 (2.714.600 filas, 96,2 %): **basura verificable**, no dato débil.
  Apunta a 500 productos que no existen en ninguna parte y su `price` es ruido aleatorio.
- `fact_eventos` semana 1 (108.581 filas): dato **coherente pero ajeno** — negocio B2C global, sin
  Ecuador, sin 2025, con 6.806 usuarios que no son clientes de RetailMind.
- `productos_catalogo`, `usuarios_sistema`, `ordenes`, `orden_items`, `carrito_items`,
  `wishlist_items` (1.322 filas entre las seis): la tienda **cuando vivía en ClickHouse**. Fueron
  reemplazadas por PostgreSQL el 2026-07-11. `usuarios_sistema` además guarda contraseñas en una
  columna, lo que es razón suficiente para no arrastrarla a ningún esquema nuevo.
- `dim_region` (países), `dim_usuario` (6.806 ficticios), `dim_fuente_trafico` (huérfana): sin uso
  posible en el modelo actual.
- Base `system`: 441 MiB de telemetría del motor, el 79 % del peso del volumen. No es dato; se
  regenera sola.

### 6.2 Qué sigue siendo válido y reutilizable

Poco, y conviene decirlo sin adornos:

1. **El puente `P#### ↔ producto.slug`** (§4.4): 1.200 correspondencias verificadas con precios
   idénticos. Es lo que permitiría, si se quisiera, reinterpretar los 108.581 eventos de la
   semana 1 como comportamiento sobre productos que hoy existen.
2. **Los catálogos pequeños mapeables**: `dim_canal` (mobile/web/app) y `dim_dispositivo`
   (desktop/tablet/android/ios) siguen siendo vocabularios válidos para un modelo nuevo, aunque
   caben en ocho líneas de un `INSERT`.
3. **El código como molde**, no como lógica: conexión, inserción por lotes, tracker de carga y
   reporte de errores del ETL Python; y el patrón de degradación del backend
   (`ClickHouseConfig` con timeouts acotados + `analytics: DEGRADED`), que ya está probado y debe
   conservarse tal cual.

Lo que **no** es reutilizable, aunque lo parezca: la población A como «histórico de comportamiento».
Mezclarla con dato reconstruido desde PostgreSQL produciría una serie con 6.806 usuarios fantasma
conviviendo con 72 clientes reales, y con 4 meses de 2026 sin contrapartida en 2025.

### 6.3 ¿Tablas y base nuevas, o reutilizar lo existente?

**Base nueva y limpia**, dejando `retailmind` intacta y aislada. Razones, por orden de peso:

1. **No se puede separar lo bueno de lo malo dentro de `fact_eventos`.** Las tres poblaciones
   comparten tabla y esquema; el único discriminador fiable es el *patrón del identificador*, y
   `semana` —que sería el discriminador natural— está colisionada (§5.3). Limpiar in situ exigiría
   borrar, que es justo lo que esta tarea no decide.
2. **El esquema legado no sirve para el modelo actual.** Fechas en `String`, sin `PARTITION BY`,
   `event_pk` no único, dimensiones geográficas por país, y ninguna noción de proveedor, bodega,
   kardex, factura, devolución ni ticket — que es donde está el 80 % del negocio que hoy sí existe
   en PostgreSQL.
3. **Aísla el riesgo.** Con base nueva, el ETL nuevo no puede dañar lo viejo, y lo viejo se puede
   archivar o descartar más tarde sin tocar el pipeline. Los 7 servicios de `analytics/` seguirían
   apuntando a las tablas viejas hasta que se decida migrarlos, sin romperse.

Sugerencia de nombre para la decisión posterior (no ejecutada): una base tipo `retailmind_dwh` o
`retailmind_analytics`, con `PARTITION BY toYYYYMM(...)` sobre columnas `DateTime` reales, y
`ORDER BY` por la clave de consulta. La base `retailmind` quedaría congelada como archivo.

### 6.4 Qué habría que reconstruir desde PostgreSQL

Todo el contenido analítico, porque el PostgreSQL vigente es la única fuente que refleja el negocio
actual. Material disponible hoy, medido:

| Dominio | Fuente en PostgreSQL | Volumen |
|---|---|---|
| Venta | `pedido` / `pedido_detalle` / `factura_venta` / `pago` | 4.083 pedidos, 10.384 líneas, 3.887 facturas · 2025-01-13 → 2026-07-22 |
| Canal | `pedido.canal` | web 2.213 · tienda 1.030 · teléfono 840 |
| Compra y abastecimiento | `orden_compra` / recepciones / `factura_compra` / CxP | 865 órdenes, facturas $22,47 M, CxP $6,38 M |
| Inventario | `movimiento_inventario` / `inventario` | 13.287 movimientos encadenados por `(fecha_creacion, id)` |
| Logística | `envio` / `novedad_envio` / `seguimiento_envio` | 2.872 envíos |
| Posventa | `devolucion` / `ticket_soporte` / `resena` | 196 RMA · 248 tickets · 344 reseñas |
| Marketing | `cupon` / `uso_cupon` / `promocion` / `campana` | 33 cupones, 561 usos |
| Trazabilidad | `log_auditoria` / `log_acceso` | 7.073 · 1.551 |
| Catálogo | `producto` / `producto_variante` / `categoria` / `marca` | 1.214 / 1.221 / 11 |

Y una brecha que ningún ETL resuelve, conviene declararla ahora: **el comportamiento previo a la
compra (vistas, clics, abandono de navegación) no existe en PostgreSQL** y ya no se está capturando
en ClickHouse. PostgreSQL tiene el resultado (290 carritos, de los cuales los abandonados alimentan
OTD-VEN-08), no el recorrido. Reconstruir el embudo completo requeriría **reactivar la captura de
eventos** —y arreglar antes las tres desalineaciones del §5.3—, no solo mover datos. Es una decisión
de alcance, no una tarea de ETL.

---

## 7. Limitaciones de este diagnóstico

- Se interrogó una **copia** del volumen, no la instancia productiva (que no existe como
  contenedor). El contenido es idéntico byte a byte: la copia se hizo con `cp -a` desde el original
  montado `:ro`.
- El servidor que leyó el dato es **26.4.2.10** (`clickhouse-server:latest` de hoy), más nuevo que
  el que lo escribió en mayo. Las 14 tablas montaron sin error ni conversión, así que la lectura es
  fiel; aun así, el número de versión original no quedó registrado en ningún archivo del repo.
- Los rangos de fechas se derivan de columnas `String`, con formatos mezclados dentro de la misma
  columna. Los mínimos y máximos son lexicográficos; se contrastaron contra la distribución mensual
  para descartar errores de orden, pero no son comparaciones de tipo temporal.
- No se verificó el contenido de `access/` (usuarios y roles del motor); es irrelevante para el
  ETL y habría exigido interpretar ficheros internos del servidor.
