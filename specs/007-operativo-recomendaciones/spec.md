# Feature Specification: Operativo - Recomendaciones

**Feature Branch**: `007-operativo-recomendaciones`

**Created**: 2026-06-18

**Status**: Draft

**Input**: Séptimo y último módulo del nivel operativo de RetailMind. Especificación derivada del
código real implementado (Angular 17 + Spring Boot 3.5 + ClickHouse). Cubre el motor de
recomendaciones: personalizadas por scoring ponderado de categorías, similares por categoría y
precio, y fallback a populares. El catálogo, el carrito y la wishlist son fuentes/relaciones (fuera
de alcance).

> **Nota metodológica**: por instrucción explícita, esta especificación documenta el
> comportamiento **ya implementado** e incluye detalles técnicos verificables (endpoints, pesos,
> umbrales, tablas). Lo no implementado se marca como **PENDIENTE** y no se inventa.
>
> **Nota de trazabilidad**: el documento **EVF04 NO define un OO ni un CU propios** para
> recomendaciones. Esta spec usa el caso de uso **CU-O09 "Recibir recomendaciones personalizadas"**
> (TA06) y lo relaciona con **OE-01**, **OE-04** y **OT-02**, dejando registrada la **deuda de
> trazabilidad** en lugar de forzar un mapeo a un OO inexistente.

---

## 1. Objetivo

Aumentar la conversión y el engagement ofreciendo al cliente autenticado productos relevantes: un
motor calcula sus categorías de mayor interés mediante un scoring ponderado por tipo de evento y le
recomienda productos de esas categorías con los que aún no ha interactuado; ofrece además productos
similares a uno en pantalla (misma categoría, precio en rango ±30%); y, cuando el usuario tiene poca
actividad, recurre a productos populares globales como fallback.

---

## 2. Usuarios / Actores

- **CLIENTE autenticado**: recibe recomendaciones personalizadas, ve similares en el detalle de un
  producto y, si tiene poca actividad, ve productos populares.
- **Motor de recomendaciones** (componente del sistema): calcula el scoring ponderado de categorías,
  selecciona productos no interactuados, computa similares y aplica el fallback. Es un actor lógico
  (no humano) que ejecuta las reglas del módulo.
- **ADMIN autenticado**: puede consumir el módulo como cualquier usuario autenticado.

> El módulo **requiere autenticación**: las rutas `/api/recomendaciones/**` caen bajo
> `.authenticated()` en `SecurityConfig` (requieren JWT) y las vistas que lo consumen (`/recomendaciones`,
> `/shop/producto/:id`) están protegidas por `authGuard`. En la interfaz, todas las llamadas usan el
> `username` del usuario en sesión.

---

## 3. Contexto del problema

Un catálogo de ~1.200 productos abruma al cliente y dificulta que descubra lo que le interesa, lo que
reduce la conversión. RetailMind necesita guiar al cliente hacia productos relevantes a partir de su
comportamiento (compras, carrito, wishlist, clics, vistas) y del contexto del producto que mira,
manteniendo la frescura (no recomendar lo ya interactuado) y cubriendo el arranque en frío (usuarios
nuevos con poca actividad) mediante populares. El objetivo es maximizar conversiones (OE-01) y
reforzar el engagement y la retención (OT-02) con personalización (OE-04).

---

## 4. Requisitos Funcionales

| ID | Requisito |
|----|-----------|
| **RF-REC-001** | El sistema MUST exponer recomendaciones para un usuario mediante `GET /api/recomendaciones/{username}` (requiere autenticación). |
| **RF-REC-002** | El sistema MUST contar los eventos del usuario en `fact_eventos` y decidir entre modo **personalizado** (≥ 10 eventos) y **fallback** (< 10 eventos). |
| **RF-REC-003** | En modo personalizado, el sistema MUST calcular las **top 3 categorías** del usuario mediante un scoring ponderado por tipo de evento (ver RN-REC-001). |
| **RF-REC-004** | En modo personalizado, el sistema MUST recomendar productos **activos** de las top categorías **excluyendo** los productos con los que el usuario ya interactuó, ordenados aleatoriamente, hasta **12** resultados. |
| **RF-REC-005** | Si tras el filtrado quedan **menos de 4** productos recomendados, el sistema MUST completar con productos **populares**. |
| **RF-REC-006** | En modo fallback (< 10 eventos), el sistema MUST devolver **productos populares globales** (los más comprados), hasta **12**, con `esPersonalizado = false`, `tipo = "populares"` y un mensaje de fallback. |
| **RF-REC-007** | El sistema MUST exponer productos similares a uno dado mediante `GET /api/recomendaciones/{username}/similares/{productoId}`. |
| **RF-REC-008** | Los similares MUST ser de la **misma categoría**, con **precio en rango ±30%** del producto base, **activos**, excluyendo el propio producto y los ya interactuados, ordenados aleatoriamente, hasta **6** resultados. |
| **RF-REC-009** | La respuesta de recomendaciones MUST incluir: `username`, `totalEventos`, `recomendaciones`, `esPersonalizado`, `tipo`, `mensajeFallback`, `categoriaFavorita` y `queryMs`. |
| **RF-REC-010** | El sistema MUST degradar con seguridad: si no hay categorías o falla una consulta, recurre a populares; si `fact_eventos` está vacía, recurre al catálogo por stock; ante error general devuelve HTTP 500. |
| **RF-REC-011** | Todas las operaciones del módulo MUST requerir un usuario autenticado (token JWT válido). |

---

## 5. Requisitos No Funcionales (cuantificados)

| ID | Requisito |
|----|-----------|
| **RNF-REC-001** | Las rutas `/api/recomendaciones/**` MUST requerir token JWT válido (autorización `authenticated`). |
| **RNF-REC-002** | El umbral de personalización MUST ser **10 eventos** (`< 10` → fallback; `≥ 10` → personalizado). |
| **RNF-REC-003** | El scoring ponderado MUST usar los pesos: `purchase = 5`, `add_to_cart = 3`, `wishlist = 2`, `click = 1`, y **0.5 por defecto** para cualquier otra acción (incluida `view`). |
| **RNF-REC-004** | El rango de precio de similares MUST ser **±30%** (`min = price × 0.7`, `max = price × 1.3`). |
| **RNF-REC-005** | Los límites de resultados MUST ser: **12** (personalizado/populares), **6** (similares), **3** (top categorías); umbral de relleno con populares: **< 4**. |
| **RNF-REC-006** | El sistema MUST incluir el tiempo de cómputo (`queryMs`) en la respuesta de recomendaciones. |
| **RNF-REC-007** | El cálculo MUST ser tolerante a fallos en cada paso (categorías, recomendados, populares) sin romper la respuesta. |
| **RNF-REC-008** | El acceso a datos MUST realizarse con `JdbcTemplate` sobre ClickHouse (sin JPA). |
| **RNF-REC-009** | **PENDIENTE (objetivo de rendimiento)**: aunque se reporta `queryMs`, no existe un umbral de latencia definido. Debe establecerse y medirse antes de tratarlo como requisito vigente. |

---

## 6. Reglas de Negocio

| ID | Regla |
|----|-------|
| **RN-REC-001** | El score de cada categoría se calcula sumando, por cada evento del usuario en esa categoría: `purchase = 5`, `add_to_cart = 3`, `wishlist = 2`, `click = 1`, **cualquier otra acción = 0.5** (incluye `view`). Las 3 categorías con mayor score son las "top categorías". |
| **RN-REC-002** | Un usuario con **menos de 10 eventos** recibe el fallback de productos populares (no personalizado). |
| **RN-REC-003** | Los productos recomendados **excluyen** aquellos con los que el usuario ya interactuó (cualquier evento, no solo vistas). |
| **RN-REC-004** | Los productos similares son de la **misma categoría** y con **precio entre el 70% y el 130%** del precio del producto base. |
| **RN-REC-005** | Solo se recomiendan productos **activos** (`activo = 1`). |
| **RN-REC-006** | Las recomendaciones personalizadas y los similares se ordenan **aleatoriamente** (`rand()`): el resultado no es determinista entre llamadas. |
| **RN-REC-007** | Los "populares" se determinan por el **número de compras** (`user_action = 'purchase'`) a nivel global. |
| **RN-REC-008** | En la interfaz, el módulo opera sobre el usuario en sesión (su `username`). |

---

## 7. Entradas

- **Recomendaciones personalizadas** (`GET /api/recomendaciones/{username}`): `username` en la ruta.
- **Similares** (`GET /api/recomendaciones/{username}/similares/{productoId}`): `username` y
  `productoId` en la ruta.
- **Autenticación**: cabecera `Authorization: Bearer <token>`.

---

## 8. Salidas (incluye mensajes de error)

**Éxito — recomendaciones**

- **200 (personalizado)**:
  `{ username, totalEventos, recomendaciones: [ producto... ], esPersonalizado: true, tipo: "personalizado", mensajeFallback: null, categoriaFavorita: "<nombre>", queryMs }`.
- **200 (fallback)**:
  `{ username, totalEventos, recomendaciones: [ populares... ], esPersonalizado: false, tipo: "populares", mensajeFallback: "Navega más productos para recibir recomendaciones personalizadas", categoriaFavorita: null, queryMs }`.
- Cada producto incluye: `productoId, nombre, brand, price, categoriaId, imagenUrl, descripcion, stock, categoriaNombre, totalCompras`.

**Éxito — similares**

- **200**: arreglo de hasta 6 productos `{ productoId, nombre, brand, price, categoriaId, imagenUrl }`.
- **200 (vacío)**: lista vacía si el producto base no existe/está inactivo o no hay similares.

**Error**

- **Recomendaciones 500**: `{ "error": "Error al obtener recomendaciones: <mensaje>" }`.
- **Similares 500**: `{ "error": "Error al obtener similares: <mensaje>" }` (el servicio captura y
  suele devolver lista vacía antes de propagar).

**Cliente (UI)**

- Vista de recomendaciones: título dinámico "Recomendado para ti" (personalizado) o "Productos
  Populares" (fallback); muestra `categoriaFavorita`, `totalEventos` y `queryMs`. Snackbar "Error al
  cargar recomendaciones" si falla.
- Detalle de producto: el bloque de similares muestra la latencia (`⚡ N ms`).

---

## 9. Escenarios (Gherkin)

### Escenario 1 — Recomendaciones personalizadas por top categorías (CU-O09)

```gherkin
Dado un cliente autenticado con 10 o más eventos en fact_eventos
Cuando solicita GET /api/recomendaciones/{username}
Entonces el motor calcula sus top 3 categorías con scoring ponderado
  (purchase=5, add_to_cart=3, wishlist=2, click=1, otras=0.5)
  Y devuelve hasta 12 productos activos de esas categorías que el usuario no ha interactuado
  Y esPersonalizado = true y tipo = "personalizado"
  Y si tras filtrar quedan menos de 4 productos, completa con populares
```

### Escenario 2 — Similares por categoría y precio ±30% (CU-O09)

```gherkin
Dado un cliente autenticado viendo un producto activo
Cuando solicita GET /api/recomendaciones/{username}/similares/{productoId}
Entonces el motor toma la categoría y el precio del producto base
  Y devuelve hasta 6 productos activos de la misma categoría
  Y con precio entre el 70% y el 130% del precio base
  Y excluyendo el propio producto y los ya interactuados por el usuario
```

### Escenario 3 — Fallback por usuario con pocos eventos (CU-O09)

```gherkin
Dado un cliente autenticado con menos de 10 eventos
Cuando solicita GET /api/recomendaciones/{username}
Entonces el motor devuelve productos populares globales (más comprados)
  Y esPersonalizado = false y tipo = "populares"
  Y un mensajeFallback invitando a navegar más
```

### Escenario 4 — Usuario sin historial (CU-O09)

```gherkin
Dado un cliente autenticado sin eventos registrados
Cuando solicita GET /api/recomendaciones/{username}
Entonces el motor aplica el fallback de populares
  Y si fact_eventos no tiene compras, recurre al catálogo ordenado por stock
  Y devuelve una lista no personalizada (o vacía si no hay catálogo activo)
```

---

## 10. Criterios de Aceptación

| ID | Criterio |
|----|----------|
| **CA-REC-001** | Con ≥ 10 eventos, la respuesta es `esPersonalizado = true`, `tipo = "personalizado"` e incluye productos de las top 3 categorías del usuario. |
| **CA-REC-002** | Los productos recomendados no incluyen productos con los que el usuario ya interactuó y son todos activos. |
| **CA-REC-003** | El scoring de categorías aplica exactamente los pesos purchase=5, add_to_cart=3, wishlist=2, click=1 y 0.5 por defecto. |
| **CA-REC-004** | Con < 10 eventos, la respuesta es `esPersonalizado = false`, `tipo = "populares"` y trae mensaje de fallback. |
| **CA-REC-005** | Si tras filtrar quedan menos de 4 recomendados, la lista se completa con populares. |
| **CA-REC-006** | Los similares son de la misma categoría, con precio entre el 70% y 130% del base, sin incluir el producto base, hasta 6. |
| **CA-REC-007** | Un producto base inexistente o inactivo en similares devuelve lista vacía. |
| **CA-REC-008** | La respuesta de recomendaciones incluye `totalEventos`, `categoriaFavorita`, `tipo`, `esPersonalizado` y `queryMs`. |
| **CA-REC-009** | Todas las operaciones rechazan peticiones sin token JWT válido. |

---

## 11. Restricciones

- **Fuentes de datos**: `fact_eventos` (comportamiento), `dim_producto` (categoría del producto en el
  modelo analítico), `productos_catalogo` (catálogo de tienda) y `dim_categoria` (nombres).
- **Acceso a datos**: `JdbcTemplate` (sin JPA). Las consultas se construyen por concatenación de
  cadenas (ver PENDIENTE de seguridad).
- **No determinismo**: el orden aleatorio (`rand()`) implica resultados distintos entre llamadas.
- **Autenticación obligatoria**: el módulo no opera de forma anónima.
- **Solo lectura**: el motor no escribe en las tablas; solo consulta.
- **Stack fijado**: Spring Boot 3.5 (backend) y Angular 17 standalone con Angular Material
  (frontend); su cambio requiere enmienda a la constitución.

---

## 12. Dependencias

- **Módulo de Catálogo (002)**: `productos_catalogo` provee los productos recomendables; el bloque de
  similares se muestra en el detalle de producto (módulo 002).
- **Módulo de Carrito (003)** y **Wishlist (004)**: sus eventos (`add_to_cart`, `purchase`,
  `wishlist`) alimentan el scoring; desde la vista de recomendaciones se puede agregar al carrito o a
  la wishlist (operaciones de esos módulos).
- **Módulo de Autenticación (001)**: provee el usuario en sesión (`username`).
- **ETL / `fact_eventos`**: el historial de eventos es la materia prima del motor.
- **ClickHouse** con `fact_eventos`, `dim_producto`, `productos_catalogo` y `dim_categoria`.
- **Constitución** (`.specify/memory/constitution.md`), Principios II (operativa primero), IV
  (ClickHouse + datos) y V (autenticación).

---

## 13. Fuera de Alcance

- **Catálogo y detalle de producto (002)**: navegación y visualización; aquí solo se consumen como
  fuente y se inserta el bloque de similares.
- **Carrito (003) y Wishlist (004)**: las acciones de agregar son de esos módulos.
- **Registro de impresiones y clics de recomendaciones** para el CTR: no implementado (ver KPI).
- **Modelos de IA/ML entrenados** (filtrado colaborativo, embeddings): no implementado; el motor es
  un scoring heurístico basado en reglas y conteos.
- **Configuración dinámica de pesos/umbrales** (sin recompilar): no implementado (valores fijos en
  código).
- **Caché de recomendaciones**: no implementado (se calcula en cada petición).

---

## 14. KPI y Criterios de Éxito Medibles

- **KPI oficial (EVF04)**: *CTR de recomendaciones = clics en recomendados / impresiones*, con
  **meta del 15%**.
  - **SC-REC-001 (objetivo)**: alcanzar un CTR de recomendaciones ≥ 15%.
  - **PENDIENTE de instrumentación**: el código **no registra impresiones ni clics** de las
    recomendaciones como eventos diferenciados en `fact_eventos`. El frontend mide la latencia
    (`queryMs` / `⚡ N ms`) pero no emite eventos de impresión/clic. Por tanto, el **CTR no es
    calculable** con los datos actuales; debe instrumentarse el registro de impresiones y clics de
    los bloques de recomendaciones y similares.

---

## 15. Trazabilidad

> **Deuda de trazabilidad**: EVF04 **no asigna** un Objetivo Operativo (OO) ni un Caso de Uso (CU)
> propios a recomendaciones. Se usa **CU-O09** (TA06) y se vincula a **OE-01**, **OE-04** y
> **OT-02**. La columna "OO" queda marcada como **sin asignación en EVF04**.

| Requisito | OO (EVF04) | CU (TA06) | OT | OE |
|-----------|------------|-----------|----|----|
| RF-REC-001 Endpoint de recomendaciones | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-01 / OE-04 |
| RF-REC-002 Decidir personalizado/fallback | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-04 |
| RF-REC-003 Top 3 categorías ponderadas | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-04 |
| RF-REC-004 Recomendados no interactuados | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-01 |
| RF-REC-005 Relleno con populares (<4) | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-01 |
| RF-REC-006 Fallback populares (<10) | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-01 |
| RF-REC-007 Endpoint de similares | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-01 |
| RF-REC-008 Similares categoría + precio ±30% | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-01 |
| RF-REC-009 Metadatos de la respuesta | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-04 |
| RF-REC-010 Degradación segura | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-01 |
| RF-REC-011 Requiere autenticación | — (sin OO en EVF04) | CU-O09 | OT-02 | OE-04 |

**Leyenda de objetivos**:

- **OE-01**: Maximizar conversiones y ventas en la tienda online.
- **OE-04**: Inteligencia de negocio / personalización (y control de acceso).
- **OT-02**: Incrementar el engagement y la retención del cliente.
- **CU-O09** (TA06): Recibir recomendaciones personalizadas (actor: Cliente / Motor IA).
- **OO**: EVF04 no define un objetivo operativo para recomendaciones (deuda de trazabilidad registrada).

---

## 16. Pendientes detectados (no inventar; tratar como trabajo futuro)

- **PEND-REC-01** (KPI): no se registran **impresiones ni clics** de recomendaciones/similares; el
  CTR (meta 15%) **no es medible** con los datos actuales. Debe instrumentarse.
- **PEND-REC-02** (precisión del peso): el peso **0.5 no es exclusivo de `view`**; es el valor por
  defecto del `multiIf` para cualquier acción no enumerada (p. ej. `drop`). Coincide en valores con
  lo esperado, pero la semántica es "resto de acciones", no solo `view`.
- **PEND-REC-03** (modelo de datos): el motor mezcla `dim_producto` (modelo analítico) y
  `productos_catalogo` (tienda) uniendo por `producto_id`/`product_id`; si los identificadores no
  están alineados entre ambas tablas, la calidad de las recomendaciones puede degradarse. Verificar
  consistencia de IDs.
- **PEND-REC-04** (seguridad): los endpoints `/api/recomendaciones/{username}` **no validan la
  propiedad** (el `username` llega por la ruta sin comprobarse contra el token) → IDOR, análogo a
  PEND-PED-01 y PEND-PER-01. Impacto bajo (datos de recomendación), pero expone el historial de otro
  usuario indirectamente.
- **PEND-REC-05** (seguridad): consultas construidas por **concatenación de cadenas** (no
  parametrizadas) → riesgo de inyección (consistente con módulos previos).
- **PEND-REC-06** (semántica): "productos vistos" excluye en realidad **todos** los productos con
  cualquier evento del usuario (no solo `view`); el nombre del helper puede inducir a error.
- **PEND-REC-07** (no determinismo): el orden `rand()` hace que las recomendaciones cambien en cada
  petición; no hay caché ni estabilidad de resultados.
- **PEND-REC-08** (configurabilidad): pesos, umbral (10) y rango (±30%) están **fijos en código**; no
  son configurables sin recompilar.
