# Feature Specification: Operativo - Catálogo de productos

**Feature Branch**: `002-operativo-catalogo`

**Created**: 2026-06-18

**Status**: Draft

**Input**: Segundo módulo del nivel operativo de RetailMind. Especificación derivada del código
real implementado (Angular 17 + Spring Boot 3.5 + ClickHouse). Cubre la visualización del catálogo,
el filtrado, el detalle de producto y el registro del evento de interacción "view". El carrito y la
wishlist son módulos aparte (fuera de alcance).

> **Nota metodológica**: por instrucción explícita, esta especificación documenta el
> comportamiento **ya implementado** e incluye detalles técnicos verificables (endpoints, tablas
> ClickHouse, parámetros). Lo no implementado se marca como **PENDIENTE** y no se inventa.

---

## 1. Objetivo

Permitir que cualquier visitante (autenticado o no) explore el catálogo de productos de la tienda
RetailMind, lo filtre por categoría, marca y rango de precio, y consulte el detalle de un producto.
Cada visualización de detalle registra un evento `view` en `fact_eventos` para alimentar la
analítica de comportamiento. El módulo es la puerta de entrada a la venta: maximiza la exposición
de productos para favorecer la conversión.

---

## 2. Usuarios / Actores

- **Visitante no autenticado**: puede listar el catálogo, filtrar y ver el detalle de un producto.
  El catálogo es de **acceso público** (no requiere login).
- **CLIENTE autenticado**: además de navegar el catálogo, puede agregar productos al carrito o a la
  wishlist (acciones de **otros módulos**) y recibe el `user_id` real en los eventos registrados.
- **Sistema (backend)**: sirve los productos, categorías y marcas desde ClickHouse y persiste los
  eventos de interacción en `fact_eventos`.

---

## 3. Contexto del problema

RetailMind necesita exponer su inventario (~1.200 productos según los datos cargados en
`productos_catalogo`) de forma navegable y filtrable para que el cliente encuentre rápidamente lo
que busca. Una navegación pobre reduce la conversión. Además, el negocio necesita capturar la
interacción del usuario con los productos (vistas) para alimentar la analítica táctica y
estratégica. Por ello, el catálogo debe ser público (para no poner barreras a la exploración) y, al
mismo tiempo, registrar eventos de comportamiento sin interrumpir la experiencia de navegación.

---

## 4. Requisitos Funcionales

| ID | Requisito |
|----|-----------|
| **RF-CAT-001** | El sistema MUST listar productos paginados mediante `GET /api/catalogo/productos` (endpoint público). |
| **RF-CAT-002** | El listado MUST incluir únicamente productos activos (`activo = 1`). |
| **RF-CAT-003** | El sistema MUST permitir filtrar por categoría mediante el parámetro `categoria_id`. |
| **RF-CAT-004** | El sistema MUST permitir filtrar por marca mediante el parámetro `brand`. |
| **RF-CAT-005** | El sistema MUST permitir filtrar por rango de precio mediante `min_price` y `max_price`. |
| **RF-CAT-006** | El listado MUST soportar paginación con `page` (por defecto 0) y `size` (por defecto 20), devolviendo `content`, `totalElements`, `totalPages`, `number` y `size`. |
| **RF-CAT-007** | Cada producto devuelto MUST incluir: `productoId`, `nombre`, `descripcion`, `categoriaId`, `categoriaNombre` (vía JOIN con `dim_categoria`), `brand`, `price`, `stock`, `imagenUrl`. |
| **RF-CAT-008** | El sistema MUST exponer el detalle de un producto mediante `GET /api/catalogo/productos/{productoId}`, devolviendo HTTP 404 si no existe. |
| **RF-CAT-009** | El sistema MUST listar las categorías con su conteo de productos activos mediante `GET /api/catalogo/categorias`. |
| **RF-CAT-010** | El sistema MUST listar las marcas distintas de productos activos mediante `GET /api/catalogo/marcas`. |
| **RF-CAT-011** | El sistema MUST registrar eventos de interacción mediante `POST /api/catalogo/eventos`, insertando una fila en `retailmind.fact_eventos`. |
| **RF-CAT-012** | Al abrir el detalle de un producto, el cliente MUST registrar automáticamente un evento con `user_action = "view"`. |
| **RF-CAT-013** | Si la petición de evento no trae `session_id`, el sistema MUST generar uno con el formato `sess_shop_<8 hex>`. |
| **RF-CAT-014** | Al registrar un evento, el sistema MUST derivar `is_conversion = 1` cuando `user_action = "purchase"`, `drop_off_flag = 1` cuando `user_action = "drop"`, y calcular la `semana` ISO actual. |
| **RF-CAT-015** | La vista de tienda MUST mostrar un grid de productos con filtro por categoría (selección tipo toggle) y un paginador. |
| **RF-CAT-016** | La vista de tienda MUST permitir navegar al detalle del producto desde su tarjeta (`/shop/producto/{id}`). |
| **RF-CAT-017** | Los endpoints de catálogo (`GET productos`, `GET productos/{id}`, `GET categorias`, `GET marcas`, `POST eventos`) MUST ser accesibles sin autenticación. |

---

## 5. Requisitos No Funcionales (cuantificados)

| ID | Requisito |
|----|-----------|
| **RNF-CAT-001** | El tamaño de página por defecto MUST ser **20** en el backend; la vista de tienda usa **12** por página. |
| **RNF-CAT-002** | La paginación MUST usar índice **base 0** (`page = 0` es la primera página). |
| **RNF-CAT-003** | Los endpoints de catálogo MUST responder **sin requerir token JWT** (acceso público). |
| **RNF-CAT-004** | El listado MUST ordenarse de forma estable por `producto_id`. |
| **RNF-CAT-005** | El registro de un evento NO debe interrumpir la navegación: un fallo al insertar en `fact_eventos` se registra en log y no propaga error al usuario. |
| **RNF-CAT-006** | El acceso a datos MUST realizarse con `JdbcTemplate` sobre ClickHouse (sin JPA). |
| **RNF-CAT-007** | Ante un error en la consulta de productos, el sistema MUST degradar a un conjunto vacío (`content: []`, `totalElements: 0`) en lugar de exponer el error al cliente. |
| **RNF-CAT-008** | **PENDIENTE (objetivo de rendimiento)**: no existe en el código un umbral de latencia medido para el listado o el detalle. Debe definirse y medirse (p. ej., p95 < N ms) antes de tratarlo como requisito vigente. |

---

## 6. Reglas de Negocio

| ID | Regla |
|----|-------|
| **RN-CAT-001** | Solo se muestran productos con `activo = 1`. |
| **RN-CAT-002** | Las categorías y las marcas se derivan exclusivamente de productos activos. |
| **RN-CAT-003** | El evento `view` se registra al visualizar el **detalle** de un producto (no en la vista de grid). |
| **RN-CAT-004** | Si no hay usuario autenticado, el evento se registra con `user_id = "anonymous"`; el canal por defecto es `"web"`. |
| **RN-CAT-005** | `is_conversion = 1` únicamente cuando `user_action = "purchase"`; `drop_off_flag = 1` únicamente cuando `user_action = "drop"`. |
| **RN-CAT-006** | El catálogo es de acceso público: no requiere sesión para listar, filtrar ni ver detalle. |
| **RN-CAT-007** | La selección de categoría en la tienda es excluyente y tipo toggle: volver a pulsar la categoría activa la deselecciona y vuelve a mostrar todo. |
| **RN-CAT-008** | Las 8 categorías del dominio son: Electronics, Groceries, Sports, Accessories, Beauty, Home, Shoes, Apparel (los nombres provienen de `dim_categoria`; el frontend asocia un ícono/color a los `categoria_id` 1–8). |

---

## 7. Entradas

- **Listar productos** (`GET /api/catalogo/productos`), query params (todos opcionales):
  `categoria_id` (int), `brand` (string), `min_price` (float), `max_price` (float),
  `page` (int, def 0), `size` (int, def 20).
- **Detalle** (`GET /api/catalogo/productos/{productoId}`): `productoId` en la ruta.
- **Categorías** (`GET /api/catalogo/categorias`): sin parámetros.
- **Marcas** (`GET /api/catalogo/marcas`): sin parámetros.
- **Registrar evento** (`POST /api/catalogo/eventos`), JSON:
  `{ "user_id"?: string, "product_id"?: string, "user_action": string, "channel"?: string,
  "price"?: number, "session_id"?: string }`.

---

## 8. Salidas (incluye mensajes de error)

**Éxito**

- **Listar 200** (estructura paginada):
  `{ "content": [ { productoId, nombre, descripcion, categoriaId, categoriaNombre, brand, price, stock, imagenUrl } ], "totalElements": n, "totalPages": n, "number": page, "size": size }`.
- **Detalle 200**: objeto producto con los mismos campos.
- **Categorías 200**: `[ { "categoriaId": n, "nombre": "...", "total": n } ]`.
- **Marcas 200**: `[ "Marca1", "Marca2", ... ]`.
- **Evento 200**: `{ "success": true }`.

**Error / casos especiales**

- **Detalle 404**: producto inexistente (cuerpo vacío, `notFound`).
- **Listar (fallo interno)**: devuelve estructura vacía con HTTP 200
  (`content: []`, `totalElements: 0`, `totalPages: 0`).
- **Listar/Detalle/Categorías/Marcas 500**: `{ "error": "<mensaje>" }` si la excepción escapa del
  servicio (poco frecuente: el servicio captura y degrada).
- **Evento 500**: `{ "error": "<mensaje>" }` (en la práctica el servicio captura el error, lo
  registra en log y no propaga).
- **Cliente (tienda)**: si falla la carga de productos, el grid queda vacío sin mensaje explícito al
  usuario (degradación silenciosa).

---

## 9. Escenarios (Gherkin)

### Escenario 1 — Ver catálogo (CU-04)

```gherkin
Dado un visitante en la tienda (sin necesidad de iniciar sesión)
Cuando solicita GET /api/catalogo/productos sin filtros
Entonces el sistema responde HTTP 200 con una página de productos activos
  Y la respuesta incluye content, totalElements, totalPages, number y size
  Y cada producto trae productoId, nombre, precio, marca, categoría, stock e imagen
```

### Escenario 2 — Filtrar por categoría (CU-05)

```gherkin
Dado un visitante viendo el catálogo
Cuando selecciona una categoría (categoria_id)
Entonces el sistema responde solo con productos activos de esa categoría
  Y el paginador se reinicia a la primera página (page = 0)
Cuando vuelve a pulsar la misma categoría
Entonces se deselecciona el filtro y se muestran todas las categorías
```

### Escenario 3 — Filtrar por marca y por precio (CU-05)

```gherkin
Dado un visitante viendo el catálogo
Cuando solicita GET /api/catalogo/productos con brand="<marca>"
Entonces el sistema devuelve solo productos activos de esa marca

Cuando solicita GET /api/catalogo/productos con min_price y/o max_price
Entonces el sistema devuelve solo productos activos cuyo price esté dentro del rango
```

### Escenario 4 — Ver detalle con registro de evento "view" (CU-05)

```gherkin
Dado un visitante que abre el detalle de un producto existente
Cuando el cliente carga GET /api/catalogo/productos/{id}
Entonces el sistema responde HTTP 200 con el detalle del producto
  Y el cliente registra automáticamente POST /api/catalogo/eventos con user_action="view"
  Y el evento se inserta en fact_eventos con channel="web" y user_id real o "anonymous"
  Y si no se envía session_id, el sistema genera uno con formato sess_shop_<8 hex>
```

### Escenario 5 — Detalle inexistente

```gherkin
Dado un visitante que abre el detalle de un producto que no existe
Cuando el cliente solicita GET /api/catalogo/productos/{id-inexistente}
Entonces el sistema responde HTTP 404
```

### Escenario 6 — Catálogo vacío / sin resultados

```gherkin
Dado un conjunto de filtros que no coincide con ningún producto activo
Cuando el visitante aplica esos filtros
Entonces el sistema responde HTTP 200 con content vacío y totalElements = 0
  Y la tienda muestra un grid sin productos

Dado un fallo interno al consultar los productos
Cuando el visitante solicita el catálogo
Entonces el sistema degrada a content vacío (totalElements = 0) sin exponer el error
```

---

## 10. Criterios de Aceptación

| ID | Criterio |
|----|----------|
| **CA-CAT-001** | `GET /api/catalogo/productos` responde 200 sin token y devuelve solo productos con `activo = 1`. |
| **CA-CAT-002** | Aplicar `categoria_id` reduce el resultado a la categoría indicada; aplicar `brand` lo reduce a la marca; aplicar `min_price`/`max_price` lo acota al rango. |
| **CA-CAT-003** | La respuesta de listado contiene `content`, `totalElements`, `totalPages`, `number` y `size`, con `size` = 20 por defecto (12 en la vista de tienda) y `page` base 0. |
| **CA-CAT-004** | Cada producto incluye `categoriaNombre` resuelto vía JOIN con `dim_categoria`. |
| **CA-CAT-005** | `GET /api/catalogo/productos/{id}` devuelve el detalle si existe y HTTP 404 si no. |
| **CA-CAT-006** | Al abrir el detalle, se registra exactamente un evento `view` en `fact_eventos` con `channel = "web"` y `user_id` real o `"anonymous"`. |
| **CA-CAT-007** | `GET /api/catalogo/categorias` devuelve cada categoría con su `total` de productos activos; `GET /api/catalogo/marcas` devuelve marcas distintas ordenadas. |
| **CA-CAT-008** | Un fallo al registrar el evento no interrumpe la visualización del detalle. |
| **CA-CAT-009** | Filtros sin coincidencias devuelven `content: []` y `totalElements: 0` con HTTP 200. |

---

## 11. Restricciones

- **Base de datos única**: los productos residen en `retailmind.productos_catalogo` (ClickHouse) y
  los eventos en `retailmind.fact_eventos`; las categorías se resuelven contra `dim_categoria`.
- **Acceso a datos**: `JdbcTemplate` (sin JPA).
- **Acceso público**: los endpoints de catálogo no requieren autenticación (definido en
  `SecurityConfig`).
- **Stack fijado**: Spring Boot 3.5 (backend) y Angular 17 standalone con Angular Material
  (frontend); su cambio requiere enmienda a la constitución.
- **Persistencia de eventos best-effort**: el registro de eventos prioriza no romper la navegación
  sobre garantizar la inserción.

---

## 12. Dependencias

- **ClickHouse** con las tablas `productos_catalogo`, `dim_categoria` y `fact_eventos` pobladas (las
  tablas de tienda se crean con `etl/extraccion/13_create_shop_tables.py`).
- **Módulo de Autenticación (001)**: provee el `user_id` real para los eventos cuando hay sesión; el
  catálogo funciona también de forma anónima.
- **Módulo de Carrito** (aparte): `POST /api/carrito/agregar` se invoca desde la tienda/detalle pero
  pertenece a otro módulo → ver Fuera de Alcance.
- **Módulo de Wishlist** (aparte): `POST /api/wishlist/agregar`, `GET/DELETE /api/wishlist/...` →
  ver Fuera de Alcance.
- **Módulo de Recomendaciones** (aparte): el detalle muestra "productos similares" vía
  `GET /api/recomendaciones/.../similares/{id}` → ver Fuera de Alcance.
- **Constitución** (`.specify/memory/constitution.md`), Principios II (operativa primero), III
  (niveles) y IV (ClickHouse + datos).

---

## 13. Fuera de Alcance

- **Agregar al carrito** (`/api/carrito/...`): módulo de Carrito independiente. Aquí solo se
  menciona como relación, ya que la tarjeta y el detalle ofrecen el botón.
- **Wishlist** (`/api/wishlist/...`): módulo independiente; el icono/estado de wishlist en el grid y
  el detalle pertenecen a ese módulo.
- **Recomendaciones / productos similares**: módulo independiente que se consume desde el detalle.
- **Búsqueda por texto libre**: el componente de tienda tiene un campo `busqueda` pero **no está
  cableado** a ninguna consulta (PENDIENTE); el backend no implementa búsqueda por texto.
- **Filtros de marca y precio en la UI de la tienda**: soportados por la API y el `ShopService`,
  pero la vista de grid solo cablea el filtro por categoría (PENDIENTE).
- **Gestión/CRUD de productos del catálogo** (alta, edición, activar/desactivar): corresponde al
  módulo administrativo de gestión de datos.

---

## 14. KPI y Criterios de Éxito Medibles

- **KPI oficial (CTR de catálogo)**: *tasa de clics en productos / total de vistas del catálogo*.
  - **SC-CAT-001 (objetivo)**: incrementar la proporción de productos vistos en detalle respecto a
    las impresiones del catálogo.
  - **PENDIENTE de instrumentación**: actualmente solo se registra el evento `view` al abrir el
    **detalle**. No existe un evento de **impresión del catálogo** (grid) ni un evento de **clic**
    diferenciado, por lo que el CTR (clics/vistas de catálogo) **no es calculable** con los datos
    actuales. Para medirlo se requiere instrumentar la impresión del listado y/o el clic en tarjeta.

---

## 15. Trazabilidad

| Requisito | OO | CU | OT | OE |
|-----------|----|----|----|----|
| RF-CAT-001 Listar productos | OO-01 | CU-04 | OT-01 | OE-01 |
| RF-CAT-002 Solo activos | OO-01 | CU-04 | OT-01 | OE-01 |
| RF-CAT-003 Filtrar por categoría | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-004 Filtrar por marca | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-005 Filtrar por precio | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-006 Paginación | OO-01 | CU-04 | OT-01 | OE-01 |
| RF-CAT-007 Datos del producto | OO-01 | CU-04 | OT-01 | OE-01 |
| RF-CAT-008 Detalle de producto | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-009 Listar categorías | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-010 Listar marcas | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-011 Registrar evento | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-012 Evento "view" en detalle | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-013 Generar session_id | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-014 Derivar flags y semana | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-015 Grid con filtro y paginador | OO-01 | CU-04 | OT-01 | OE-01 |
| RF-CAT-016 Navegar al detalle | OO-01 | CU-05 | OT-01 | OE-01 |
| RF-CAT-017 Endpoints públicos | OO-01 | CU-04 | OT-01 | OE-01 |

**Leyenda de objetivos**:

- **OE-01**: Maximizar conversiones y ventas en la tienda online.
- **OT-01**: Mejorar la experiencia de navegación del cliente.
- **OO-01**: Facilitar la búsqueda y filtrado de productos por categoría, marca y precio.
- **CU-04**: Ver catálogo. **CU-05**: Filtrar productos (incluye ver detalle y el evento "view").

---

## 16. Pendientes detectados (no inventar; tratar como trabajo futuro)

- **PEND-CAT-01**: Los filtros de **marca** y **precio** existen en la API y en `ShopService`, pero
  la UI del grid solo cablea el filtro por **categoría**.
- **PEND-CAT-02**: El campo de **búsqueda por texto** (`busqueda`) existe en el componente de tienda
  pero no filtra (no se envía al backend; el backend no soporta búsqueda por texto).
- **PEND-CAT-03**: Las consultas SQL del catálogo (listado, detalle e inserción de evento) se
  construyen por **concatenación de cadenas** (no parametrizadas) → riesgo de inyección a endurecer.
- **PEND-CAT-04**: El **KPI CTR** no es medible aún: solo se registra `view` del detalle; falta
  instrumentar impresiones del catálogo y/o clics diferenciados.
- **PEND-CAT-05**: El conteo real de productos depende de los datos cargados en
  `productos_catalogo` (aprox. ~1.200, no verificado contra la BD en esta spec).
- **PEND-CAT-06**: Ante error en el listado, el backend degrada a conjunto vacío con HTTP 200, lo
  que oculta el fallo al cliente (no hay señalización de error).
