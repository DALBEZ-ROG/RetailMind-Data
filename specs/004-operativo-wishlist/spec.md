# Feature Specification: Operativo - Wishlist

**Feature Branch**: `004-operativo-wishlist`

**Created**: 2026-06-18

**Status**: Draft

**Input**: Cuarto módulo del nivel operativo de RetailMind. Especificación derivada del código real
implementado (Angular 17 + Spring Boot 3.5 + ClickHouse). Cubre la lista de deseos (agregar, listar,
eliminar) con toggle visual de corazón. El catálogo, el detalle de producto y el carrito son módulos
aparte (fuera de alcance).

> **Nota metodológica**: por instrucción explícita, esta especificación documenta el
> comportamiento **ya implementado** e incluye detalles técnicos verificables (endpoints, tabla
> ClickHouse, eventos). Lo no implementado se marca como **PENDIENTE** y no se inventa.

---

## 1. Objetivo

Permitir que un cliente autenticado guarde productos de interés en una lista de deseos (wishlist),
la consulte y elimine productos de ella, con un control visual de corazón (toggle) en el catálogo y
el detalle. Cada alta registra un evento `wishlist` en `fact_eventos` para alimentar la analítica de
engagement. El módulo busca incrementar la retención y el retorno del cliente a la tienda.

---

## 2. Usuarios / Actores

- **CLIENTE autenticado**: agrega productos a su wishlist, la consulta, elimina productos y puede
  mover un producto de la wishlist al carrito. Es el actor principal.
- **ADMIN autenticado**: puede operar la wishlist como cualquier usuario autenticado (sin
  restricción de rol específica).
- **Sistema (backend)**: persiste la wishlist en `wishlist_items`, evita duplicados y registra el
  evento `wishlist` al agregar.

> El módulo **requiere autenticación**: las rutas de wishlist caen bajo
> `anyRequest().authenticated()` en `SecurityConfig` (requieren JWT) y las rutas de tienda que la
> consumen (`/wishlist`, `/shop`) están protegidas por `authGuard`. Todas las operaciones se
> realizan sobre el `username` del usuario en sesión.

---

## 3. Contexto del problema

La conversión inmediata no siempre ocurre: el cliente descubre productos que le interesan pero que
no compra en el momento. Sin una lista de deseos, ese interés se pierde y el cliente no tiene un
motivo claro para volver. RetailMind necesita capturar ese interés (engagement), facilitar el
retorno del cliente y dejar traza analítica de qué productos despiertan deseo. La wishlist debe ser
personal (por usuario autenticado), evitar duplicados y permitir mover fácilmente un producto
deseado al carrito.

---

## 4. Requisitos Funcionales

| ID | Requisito |
|----|-----------|
| **RF-WIS-001** | El sistema MUST permitir agregar un producto a la wishlist mediante `POST /api/wishlist/agregar` (requiere autenticación). |
| **RF-WIS-002** | El sistema MUST rechazar agregar un producto que ya está en la wishlist del usuario, devolviendo HTTP 400 con "El producto ya esta en tu wishlist". |
| **RF-WIS-003** | El sistema MUST persistir el ítem en `retailmind.wishlist_items` con `wishlist_id` (UUID), `user_id`, `producto_id` y `fecha_agregado`. |
| **RF-WIS-004** | Al agregar, el sistema MUST registrar un evento `wishlist` en `fact_eventos`. |
| **RF-WIS-005** | El sistema MUST listar la wishlist del usuario mediante `GET /api/wishlist/{userId}`, incluyendo datos del producto (JOIN con `productos_catalogo`), ordenada por `fecha_agregado` descendente. |
| **RF-WIS-006** | El sistema MUST eliminar un producto de la wishlist mediante `DELETE /api/wishlist/{userId}/{productoId}` (borrado físico de la fila). |
| **RF-WIS-007** | El cliente MUST ofrecer un control de corazón (toggle) en el grid del catálogo y en el detalle: agrega si el producto no está en la wishlist y lo elimina si ya está. |
| **RF-WIS-008** | La página de wishlist MUST permitir eliminar un producto, moverlo al carrito y navegar a su detalle. |
| **RF-WIS-009** | Todas las operaciones del módulo MUST requerir un usuario autenticado (token JWT válido). |

---

## 5. Requisitos No Funcionales (cuantificados)

| ID | Requisito |
|----|-----------|
| **RNF-WIS-001** | Los endpoints `/api/wishlist/**` MUST requerir token JWT válido (autorización `authenticated`). |
| **RNF-WIS-002** | La wishlist MUST garantizar unicidad por par (`user_id`, `producto_id`): no se admite el mismo producto dos veces. |
| **RNF-WIS-003** | La eliminación MUST ejecutarse de forma síncrona (`SETTINGS mutations_sync = 1`) mediante borrado físico. |
| **RNF-WIS-004** | El listado MUST ordenarse por `fecha_agregado` descendente (lo más reciente primero). |
| **RNF-WIS-005** | El acceso a datos MUST realizarse con `JdbcTemplate` sobre ClickHouse (sin JPA). |
| **RNF-WIS-006** | **PENDIENTE (objetivo de rendimiento)**: no existe un umbral de latencia medido para agregar, listar o eliminar. Debe definirse y medirse antes de tratarlo como requisito vigente. |

---

## 6. Reglas de Negocio

| ID | Regla |
|----|-------|
| **RN-WIS-001** | Un producto no puede estar más de una vez en la wishlist de un mismo usuario. |
| **RN-WIS-002** | Solo la acción de **agregar** registra un evento `wishlist` en `fact_eventos`; la eliminación **no** registra evento. |
| **RN-WIS-003** | El módulo opera siempre sobre el usuario en sesión (su `username` actúa como `user_id`). |
| **RN-WIS-004** | La wishlist no afecta stock ni precio: es una lista de interés sin impacto en inventario. |
| **RN-WIS-005** | Al agregar, el sistema **no** valida que el `producto_id` exista en el catálogo (a diferencia del carrito). |
| **RN-WIS-006** | El control de corazón refleja el estado de pertenencia: lleno si el producto está en la wishlist, vacío si no. |

---

## 7. Entradas

- **Agregar** (`POST /api/wishlist/agregar`), JSON: `{ "user_id": string, "producto_id": string }`.
- **Listar** (`GET /api/wishlist/{userId}`): `userId` en la ruta.
- **Eliminar** (`DELETE /api/wishlist/{userId}/{productoId}`): `userId` y `productoId` en la ruta.
- **Autenticación**: cabecera `Authorization: Bearer <token>` en todas las operaciones.

---

## 8. Salidas (incluye mensajes de error)

**Éxito**

- **Agregar 200**: `{ "success": true, "mensaje": "Agregado a wishlist" }`.
- **Listar 200**: `[ { productoId, fechaAgregado, nombre, brand, price, categoriaId, imagenUrl } ]`.
- **Eliminar 200**: `{ "success": true, "mensaje": "Eliminado de wishlist" }`.

**Error**

- **Agregar 400** (duplicado): `{ "error": "El producto ya esta en tu wishlist" }`.
- **Agregar 500**: `{ "error": "<mensaje>" }`.
- **Listar 500**: `{ "error": "<mensaje>" }`.
- **Eliminar 500**: `{ "error": "<mensaje>" }`.

**Mensajes en cliente (UI)**

- Grid/detalle al agregar: "Agregado a wishlist ❤️"; al eliminar: "Eliminado de wishlist".
- Grid al fallar agregar: mensaje del backend o "Ya esta en wishlist".
- Página de wishlist: "Eliminado de wishlist" / "Error al eliminar"; "Agregado al carrito ✓" /
  "Error al agregar" al mover al carrito.

---

## 9. Escenarios (Gherkin)

### Escenario 1 — Agregar a wishlist (CU-10)

```gherkin
Dado un cliente autenticado viendo un producto
Cuando pulsa el corazón (o envía POST /api/wishlist/agregar con user_id y producto_id)
  Y el producto no está aún en su wishlist
Entonces el sistema inserta el ítem en wishlist_items
  Y registra un evento wishlist en fact_eventos
  Y responde { success: true, mensaje: "Agregado a wishlist" }
  Y el corazón pasa a estado "lleno"
```

### Escenario 2 — Quitar de wishlist con toggle (CU-12)

```gherkin
Dado un cliente autenticado con un producto ya en su wishlist (corazón lleno)
Cuando pulsa nuevamente el corazón (o envía DELETE /api/wishlist/{userId}/{productoId})
Entonces el sistema elimina físicamente la fila de wishlist_items
  Y responde { success: true, mensaje: "Eliminado de wishlist" }
  Y el corazón vuelve al estado "vacío"
Nota: la eliminación no registra evento en fact_eventos (PENDIENTE).
```

### Escenario 3 — Listar wishlist (CU-11)

```gherkin
Dado un cliente autenticado con productos guardados
Cuando solicita GET /api/wishlist/{userId}
Entonces el sistema devuelve los productos de su wishlist con nombre, marca, precio,
  categoría e imagen (vía JOIN con productos_catalogo)
  Y ordenados por fecha de agregado descendente
```

### Escenario 4 — Agregar un producto ya existente (CU-10)

```gherkin
Dado un cliente autenticado con un producto ya en su wishlist
Cuando intenta agregar el mismo producto otra vez
Entonces el sistema responde HTTP 400 con "El producto ya esta en tu wishlist"
  Y no se crea una fila duplicada
```

### Escenario 5 — Wishlist vacía (CU-11)

```gherkin
Dado un cliente autenticado que no ha guardado productos
Cuando solicita GET /api/wishlist/{userId}
Entonces el sistema responde HTTP 200 con una lista vacía
  Y la página de wishlist muestra el estado sin productos
```

---

## 10. Criterios de Aceptación

| ID | Criterio |
|----|----------|
| **CA-WIS-001** | Agregar un producto que no está en la wishlist crea una fila en `wishlist_items` y genera un evento `wishlist`. |
| **CA-WIS-002** | Intentar agregar un producto ya presente devuelve HTTP 400 "El producto ya esta en tu wishlist" y no duplica la fila. |
| **CA-WIS-003** | `GET /api/wishlist/{userId}` devuelve los productos del usuario con datos del catálogo, ordenados por fecha descendente. |
| **CA-WIS-004** | Eliminar un producto borra físicamente su fila y deja de aparecer en la wishlist. |
| **CA-WIS-005** | El control de corazón refleja correctamente el estado: agregar lo llena, eliminar lo vacía. |
| **CA-WIS-006** | Una wishlist sin productos devuelve HTTP 200 con lista vacía. |
| **CA-WIS-007** | Todas las operaciones rechazan peticiones sin token JWT válido. |

---

## 11. Restricciones

- **Base de datos única**: la wishlist reside en `retailmind.wishlist_items`; los eventos en
  `retailmind.fact_eventos`; los datos de producto se resuelven contra `productos_catalogo`.
- **Acceso a datos**: `JdbcTemplate` (sin JPA). Todas las consultas (count, insert, select, delete)
  se construyen por concatenación de cadenas (ver PENDIENTE de seguridad).
- **Autenticación obligatoria**: el módulo no opera de forma anónima.
- **Borrado físico**: a diferencia del carrito (borrado lógico), la wishlist elimina la fila.
- **Stack fijado**: Spring Boot 3.5 (backend) y Angular 17 standalone con Angular Material
  (frontend); su cambio requiere enmienda a la constitución.

---

## 12. Dependencias

- **Módulo de Catálogo (002)**: la wishlist resuelve los datos del producto vía JOIN con
  `productos_catalogo` y reutiliza `registrarEvento` para el evento `wishlist`. El toggle de corazón
  se muestra en el grid y el detalle del catálogo (módulo 002).
- **Módulo de Carrito (003)**: desde la página de wishlist se puede mover un producto al carrito
  (`POST /api/carrito/agregar`), operación que pertenece a ese módulo.
- **Módulo de Autenticación (001)**: provee el usuario en sesión (`username`) usado como `user_id`.
- **ClickHouse** con las tablas `wishlist_items`, `productos_catalogo` y `fact_eventos` (las tablas
  de tienda se crean con `etl/extraccion/13_create_shop_tables.py`).
- **Constitución** (`.specify/memory/constitution.md`), Principios II (operativa primero) y V
  (seguridad: requiere autenticación).

---

## 13. Fuera de Alcance

- **Catálogo y detalle de producto (002)**: la navegación y visualización de productos son otro
  módulo; aquí solo se integra el toggle de corazón y el JOIN para mostrar datos.
- **Carrito y checkout (003)**: mover de wishlist al carrito invoca el módulo de carrito.
- **Compartir la wishlist** o múltiples listas por usuario: no implementado.
- **Notificaciones** de cambio de precio/stock de productos guardados: no implementado.
- **Paginación de la wishlist**: no implementada (se devuelve la lista completa).

---

## 14. KPI y Criterios de Éxito Medibles

- **KPI oficial (wishlist rate)**: *productos agregados a wishlist / total de productos vistos*.
  - **SC-WIS-001 (objetivo)**: incrementar la proporción de productos guardados en wishlist respecto
    a los productos vistos, como señal de engagement.
  - **Medible parcialmente**: `fact_eventos` registra eventos `wishlist` (agregar) y `view` (detalle),
    por lo que el indicador es **derivable** correlacionando ambos tipos de evento.
  - **PENDIENTE de instrumentación**: no existe hoy un endpoint ni cálculo que compute este KPI; debe
    implementarse la consulta de agregación de eventos para reportarlo.

---

## 15. Trazabilidad

| Requisito | OO | CU | OT | OE |
|-----------|----|----|----|----|
| RF-WIS-001 Agregar a wishlist | OO-03 | CU-10 | OT-02 | OE-01 |
| RF-WIS-002 Rechazar duplicado | OO-03 | CU-10 | OT-02 | OE-01 |
| RF-WIS-003 Persistir ítem | OO-03 | CU-10 | OT-02 | OE-01 |
| RF-WIS-004 Evento wishlist | OO-03 | CU-10 | OT-02 | OE-01 |
| RF-WIS-005 Listar wishlist | OO-03 | CU-11 | OT-02 | OE-01 |
| RF-WIS-006 Eliminar de wishlist | OO-03 | CU-12 | OT-02 | OE-01 |
| RF-WIS-007 Toggle de corazón | OO-03 | CU-10/CU-12 | OT-02 | OE-01 |
| RF-WIS-008 Acciones en página wishlist | OO-03 | CU-11/CU-12 | OT-02 | OE-01 |
| RF-WIS-009 Requiere autenticación | OO-03 | CU-10/11/12 | OT-02 | OE-01 |

**Leyenda de objetivos**:

- **OE-01**: Maximizar conversiones y ventas en la tienda online.
- **OT-02**: Incrementar el engagement y la retención del cliente.
- **OO-03**: Permitir al cliente guardar productos de interés en una lista de deseos.
- **CU-10**: Agregar a la wishlist. **CU-11**: Listar la wishlist. **CU-12**: Eliminar de la wishlist.

---

## 16. Pendientes detectados (no inventar; tratar como trabajo futuro)

- **PEND-WIS-01**: La **eliminación no registra evento** en `fact_eventos`; solo el alta registra el
  evento `wishlist`. (El KPI de "quitar" no es medible con los datos actuales.)
- **PEND-WIS-02**: Todas las consultas (count, insert, select, delete) se construyen por
  **concatenación de cadenas** (no parametrizadas) → riesgo de inyección a endurecer (consistente con
  PEND-AUT-04, PEND-CAT-03 y PEND-CAR-03).
- **PEND-WIS-03**: Al agregar **no se valida** que el producto exista en el catálogo.
- **PEND-WIS-04**: La wishlist usa **borrado físico** (no lógico); no conserva historial de
  eliminaciones.
- **PEND-WIS-05**: El **KPI wishlist rate** es derivable de `fact_eventos` pero no está instrumentado
  (sin consulta/endpoint que lo calcule).
- **PEND-WIS-06**: No hay **paginación** en el listado de la wishlist.
