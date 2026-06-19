# Feature Specification: Operativo - Carrito de compras y checkout

**Feature Branch**: `003-operativo-carrito`

**Created**: 2026-06-18

**Status**: Draft

**Input**: Tercer módulo del nivel operativo de RetailMind. Especificación derivada del código real
implementado (Angular 17 + Spring Boot 3.5 + ClickHouse). Cubre el carrito de compras (agregar,
listar, eliminar) y el checkout (generación de orden + evento `purchase` + vaciado del carrito). El
historial de pedidos y la wishlist son módulos aparte (fuera de alcance).

> **Nota metodológica**: por instrucción explícita, esta especificación documenta el
> comportamiento **ya implementado** e incluye detalles técnicos verificables (endpoints, tablas
> ClickHouse, eventos). Lo no implementado se marca como **PENDIENTE** y no se inventa.

---

## 1. Objetivo

Permitir que un cliente autenticado agregue productos al carrito, revise su contenido, elimine
ítems y finalice la compra (checkout). El checkout genera una orden en `ordenes`/`orden_items`,
registra un evento `purchase` por cada producto en `fact_eventos` (marcado como conversión) y vacía
el carrito. Es el módulo que materializa la venta: convierte la navegación en órdenes.

---

## 2. Usuarios / Actores

- **CLIENTE autenticado**: agrega productos al carrito, consulta el carrito, elimina ítems y
  finaliza la compra. Es el actor principal del módulo.
- **ADMIN autenticado**: puede operar el carrito como cualquier usuario autenticado (no hay
  restricción de rol específica), aunque su flujo habitual es la administración.
- **Sistema (backend)**: persiste el carrito en `carrito_items`, congela el precio al agregar,
  genera la orden en el checkout y registra los eventos de comportamiento en `fact_eventos`.

> El módulo **requiere autenticación**: las rutas de carrito caen bajo `anyRequest().authenticated()`
> en `SecurityConfig` (requieren JWT) y la ruta de tienda `/shop/carrito` está protegida por
> `authGuard`. Todas las operaciones se realizan sobre el `username` del usuario en sesión.

---

## 3. Contexto del problema

La navegación del catálogo (módulo 002) solo genera valor si el cliente puede concretar la compra.
RetailMind necesita un carrito persistente por usuario y un proceso de checkout que cree la orden y
deje traza analítica del embudo (add_to_cart → purchase). Sin este módulo, el negocio no vende:
es el "músculo" operativo. Además, el sistema debe impedir compras inválidas (carrito vacío) y
capturar los eventos que permiten medir el tiempo y la tasa de conversión.

---

## 4. Requisitos Funcionales

| ID | Requisito |
|----|-----------|
| **RF-CAR-001** | El sistema MUST permitir agregar un producto al carrito mediante `POST /api/carrito/agregar` (requiere autenticación). |
| **RF-CAR-002** | Al agregar, el sistema MUST obtener el precio actual del producto desde el catálogo; si el producto no existe, MUST devolver error (HTTP 400). |
| **RF-CAR-003** | El sistema MUST persistir el ítem en `retailmind.carrito_items` con `carrito_id` (UUID), `user_id`, `producto_id`, `cantidad`, `precio_unitario` (congelado), `fecha_agregado` y `activo = 1`. |
| **RF-CAR-004** | Al agregar, el sistema MUST registrar un evento `add_to_cart` en `fact_eventos`. |
| **RF-CAR-005** | El sistema MUST listar los ítems activos del carrito mediante `GET /api/carrito/{userId}`, incluyendo datos del producto (JOIN con `productos_catalogo`), ordenados por `fecha_agregado` descendente. |
| **RF-CAR-006** | El sistema MUST eliminar un ítem mediante `DELETE /api/carrito/{userId}/{productoId}` aplicando borrado lógico (`activo = 0`). |
| **RF-CAR-007** | Al eliminar, el sistema MUST registrar un evento `drop` en `fact_eventos`. |
| **RF-CAR-008** | El cliente MUST calcular y mostrar el total del carrito como la suma de `precioUnitario × cantidad` de los ítems. |
| **RF-CAR-009** | El sistema MUST permitir finalizar la compra mediante `POST /api/carrito/{userId}/checkout`. |
| **RF-CAR-010** | El checkout MUST rechazarse si el carrito está vacío, devolviendo HTTP 400 con el mensaje "El carrito esta vacio". |
| **RF-CAR-011** | El checkout MUST generar un identificador de orden con formato `ORD-<8 hex en mayúsculas>`. |
| **RF-CAR-012** | El checkout MUST insertar cada ítem del carrito en `retailmind.orden_items` (`orden_id`, `producto_id`, `cantidad`, `precio_unitario`). |
| **RF-CAR-013** | El checkout MUST registrar un evento `purchase` (conversión) en `fact_eventos` por cada producto comprado. |
| **RF-CAR-014** | El checkout MUST crear la orden en `retailmind.ordenes` con `total`, `estado = "COMPLETADA"`, `canal = "web"` y `fecha_orden`. |
| **RF-CAR-015** | Tras el checkout, el sistema MUST vaciar el carrito del usuario (borrado lógico `activo = 0` de todos los ítems activos). |
| **RF-CAR-016** | El checkout MUST devolver `{ ordenId, total, items }` y el cliente MUST mostrar la confirmación con el `ordenId`. |
| **RF-CAR-017** | Todas las operaciones del módulo MUST requerir un usuario autenticado (token JWT válido). |

---

## 5. Requisitos No Funcionales (cuantificados)

| ID | Requisito |
|----|-----------|
| **RNF-CAR-001** | Los endpoints `/api/carrito/**` MUST requerir token JWT válido (autorización `authenticated`). |
| **RNF-CAR-002** | La cantidad por defecto al agregar MUST ser **1** cuando no se especifica. |
| **RNF-CAR-003** | El `precio_unitario` del ítem MUST congelarse en el momento de agregarlo (snapshot), independiente de cambios posteriores del precio del catálogo. |
| **RNF-CAR-004** | Las operaciones de borrado lógico y vaciado MUST ejecutarse de forma síncrona (`SETTINGS mutations_sync = 1`). |
| **RNF-CAR-005** | Toda orden creada en el checkout MUST quedar con `estado = "COMPLETADA"` y `canal = "web"` (valores fijos). |
| **RNF-CAR-006** | El acceso a datos MUST realizarse con `JdbcTemplate` sobre ClickHouse (sin JPA). |
| **RNF-CAR-007** | **PENDIENTE (atomicidad)**: el checkout ejecuta varias inserciones/mutaciones (orden_items, eventos, orden, vaciado) **sin transacción** (ClickHouse no es transaccional). Un fallo a mitad de proceso puede dejar estado parcial; debe definirse una estrategia de consistencia/compensación. |
| **RNF-CAR-008** | **PENDIENTE (objetivo de rendimiento)**: no existe un umbral de latencia medido para agregar al carrito ni para el checkout. Debe definirse y medirse antes de tratarlo como requisito vigente. |

---

## 6. Reglas de Negocio

| ID | Regla |
|----|-------|
| **RN-CAR-001** | No se permite finalizar la compra (checkout) con el carrito vacío. |
| **RN-CAR-002** | Solo los ítems con `activo = 1` cuentan para el carrito y para el checkout. |
| **RN-CAR-003** | El precio del ítem es el precio del producto en el momento de agregarlo (snapshot), no el precio vigente al hacer checkout. |
| **RN-CAR-004** | Toda orden generada por checkout queda en estado `COMPLETADA`; no existen estados intermedios ni un proceso de pago. |
| **RN-CAR-005** | Cada acción del embudo se registra como evento en `fact_eventos`: `add_to_cart` (agregar), `drop` (eliminar), `purchase` (checkout, una por producto, con `is_conversion = 1`). |
| **RN-CAR-006** | Eliminar un producto del carrito desactiva todas las filas activas de ese `producto_id` del usuario (la condición no distingue por `carrito_id`). |
| **RN-CAR-007** | El módulo opera siempre sobre el usuario en sesión (su `username` actúa como `user_id`). |

---

## 7. Entradas

- **Agregar** (`POST /api/carrito/agregar`), JSON:
  `{ "user_id": string, "producto_id": string, "cantidad"?: number (def 1) }`.
- **Listar carrito** (`GET /api/carrito/{userId}`): `userId` en la ruta.
- **Eliminar ítem** (`DELETE /api/carrito/{userId}/{productoId}`): `userId` y `productoId` en la ruta.
- **Checkout** (`POST /api/carrito/{userId}/checkout`): `userId` en la ruta; cuerpo vacío.
- **Autenticación**: cabecera `Authorization: Bearer <token>` en todas las operaciones.

---

## 8. Salidas (incluye mensajes de error)

**Éxito**

- **Agregar 200**: `{ "success": true, "mensaje": "Producto agregado al carrito" }`.
- **Listar 200**: `[ { carritoId, productoId, cantidad, precioUnitario, fechaAgregado, nombre, brand, categoriaId } ]`.
- **Eliminar 200**: `{ "success": true, "mensaje": "Producto eliminado del carrito" }`.
- **Checkout 200**: `{ "ordenId": "ORD-XXXXXXXX", "total": <float>, "items": <conteo> }`.

**Error**

- **Agregar 400**: `{ "error": "<mensaje>" }` (p. ej., "Producto no encontrado: <id>").
- **Listar 500**: `{ "error": "<mensaje>" }`.
- **Eliminar 500**: `{ "error": "<mensaje>" }`.
- **Checkout 400** (carrito vacío): `{ "error": "El carrito esta vacio" }`.
- **Checkout 500**: `{ "error": "<mensaje>" }`.

**Mensajes en cliente (UI del carrito)**

- "Producto eliminado" tras eliminar; "Error al eliminar" si falla.
- "Compra realizada con exito!" tras checkout exitoso (muestra el `ordenId`).
- Mensaje del backend o "Error en checkout" si el checkout falla.

---

## 9. Escenarios (Gherkin)

### Escenario 1 — Agregar al carrito (CU-07)

```gherkin
Dado un cliente autenticado viendo un producto existente
Cuando envía POST /api/carrito/agregar con user_id, producto_id y cantidad
Entonces el sistema obtiene el precio actual del producto
  Y persiste el ítem en carrito_items con activo = 1 y el precio congelado
  Y registra un evento add_to_cart en fact_eventos
  Y responde { success: true, mensaje: "Producto agregado al carrito" }
```

### Escenario 2 — Modificar la cantidad de un ítem (comportamiento actual)

```gherkin
Dado un cliente con un producto ya en el carrito
Cuando vuelve a agregar el mismo producto con una nueva cantidad
Entonces el sistema inserta una NUEVA fila en carrito_items para ese producto
  Y el carrito muestra ambas filas del mismo producto
Nota: NO existe una operación de "actualizar cantidad" de una línea existente (PENDIENTE).
```

### Escenario 3 — Eliminar ítem (CU-08)

```gherkin
Dado un cliente con productos en el carrito
Cuando envía DELETE /api/carrito/{userId}/{productoId}
Entonces el sistema marca como inactivas (activo = 0) las filas activas de ese producto
  Y registra un evento drop en fact_eventos
  Y responde { success: true, mensaje: "Producto eliminado del carrito" }
```

### Escenario 4 — Checkout exitoso con generación de orden y evento purchase (CU-09)

```gherkin
Dado un cliente autenticado con al menos un ítem activo en el carrito
Cuando envía POST /api/carrito/{userId}/checkout
Entonces el sistema genera un ordenId con formato ORD-XXXXXXXX
  Y inserta cada ítem en orden_items
  Y registra un evento purchase (is_conversion = 1) por cada producto en fact_eventos
  Y crea la orden en ordenes con estado "COMPLETADA", canal "web" y el total calculado
  Y vacía el carrito (activo = 0 en todos los ítems)
  Y responde { ordenId, total, items }
```

### Escenario 5 — Intento de checkout con carrito vacío (CU-09)

```gherkin
Dado un cliente autenticado cuyo carrito no tiene ítems activos
Cuando envía POST /api/carrito/{userId}/checkout
Entonces el sistema responde HTTP 400 con el mensaje "El carrito esta vacio"
  Y no se genera ninguna orden ni evento purchase
```

---

## 10. Criterios de Aceptación

| ID | Criterio |
|----|----------|
| **CA-CAR-001** | Agregar un producto existente crea una fila activa en `carrito_items` con el precio congelado y genera un evento `add_to_cart`. |
| **CA-CAR-002** | Agregar un producto inexistente devuelve HTTP 400 y no crea fila en el carrito. |
| **CA-CAR-003** | `GET /api/carrito/{userId}` devuelve solo ítems activos del usuario, con nombre, marca y categoría del producto, ordenados por fecha descendente. |
| **CA-CAR-004** | Eliminar un ítem lo marca como inactivo (`activo = 0`), genera evento `drop` y deja de aparecer en el carrito. |
| **CA-CAR-005** | El total mostrado equivale a la suma de `precioUnitario × cantidad` de los ítems activos. |
| **CA-CAR-006** | El checkout con carrito no vacío crea la orden en `ordenes` (estado COMPLETADA, canal web), inserta los `orden_items`, registra un `purchase` por producto y vacía el carrito. |
| **CA-CAR-007** | El checkout devuelve `ordenId` con formato `ORD-` + 8 caracteres hexadecimales en mayúsculas, el `total` y el número de `items`. |
| **CA-CAR-008** | El checkout con carrito vacío devuelve HTTP 400 "El carrito esta vacio" sin crear orden. |
| **CA-CAR-009** | Todas las operaciones rechazan peticiones sin token JWT válido. |

---

## 11. Restricciones

- **Base de datos única**: el carrito reside en `retailmind.carrito_items`; las órdenes en
  `retailmind.ordenes` y `retailmind.orden_items`; los eventos en `retailmind.fact_eventos`.
- **Acceso a datos**: `JdbcTemplate` (sin JPA). `getCarrito` usa consulta parametrizada; el resto de
  inserciones/mutaciones se construyen por concatenación (ver PENDIENTE de seguridad).
- **Autenticación obligatoria**: el módulo no opera de forma anónima.
- **Sin transacciones**: ClickHouse no es transaccional; el checkout no es atómico (RNF-CAR-007).
- **Sin pago real**: el checkout no integra pasarela de pago; la orden nace COMPLETADA.
- **Stack fijado**: Spring Boot 3.5 (backend) y Angular 17 standalone con Angular Material
  (frontend); su cambio requiere enmienda a la constitución.

---

## 12. Dependencias

- **Módulo de Catálogo (002)**: `CarritoService` consulta `ProductoCatalogoService.getProductoById`
  para obtener el precio al agregar, y reutiliza `registrarEvento` para los eventos del embudo.
- **Módulo de Autenticación (001)**: provee el usuario en sesión (`username`) usado como `user_id`.
- **Módulo de Pedidos / Historial (OO-04)**: consume las tablas `ordenes`/`orden_items` que este
  módulo genera, pero la visualización del historial y el detalle de orden son **otro módulo** → ver
  Fuera de Alcance.
- **ClickHouse** con las tablas `carrito_items`, `ordenes`, `orden_items`, `productos_catalogo` y
  `fact_eventos` (las tablas de tienda se crean con `etl/extraccion/13_create_shop_tables.py`).
- **Constitución** (`.specify/memory/constitution.md`), Principios II (operativa primero) y V
  (seguridad: requiere autenticación).

---

## 13. Fuera de Alcance

- **Historial de pedidos / "Mis pedidos" y detalle de orden (OO-04)**: módulo independiente que lee
  las órdenes creadas aquí. Solo se menciona como relación/dependencia.
- **Wishlist**: módulo independiente.
- **Modificar la cantidad** de una línea existente del carrito: **no implementado** (no hay endpoint
  de actualización; cada agregar inserta una fila nueva) → PENDIENTE.
- **Pasarela de pago y estados de orden** (pendiente, pagada, enviada, cancelada): no implementado;
  la orden nace `COMPLETADA`.
- **Validación y descuento de stock / inventario**: no implementado (no se verifica `stock` al
  agregar ni al hacer checkout).
- **Consolidación de líneas duplicadas** del mismo producto en el carrito: no implementado.

---

## 14. KPI y Criterios de Éxito Medibles

- **KPI oficial**: *tiempo promedio desde `add_to_cart` hasta `purchase`* (tiempo de checkout).
  - **SC-CAR-001 (objetivo)**: reducir el tiempo medio entre el primer `add_to_cart` y el `purchase`
    de la misma sesión/usuario.
  - **Medible parcialmente**: `fact_eventos` registra `add_to_cart` y `purchase` con `timestamp_utc`,
    por lo que el indicador es **derivable** correlacionando ambos eventos por `user_id`/`session_id`.
  - **PENDIENTE de instrumentación**: no existe hoy un endpoint ni cálculo que compute este KPI; debe
    implementarse la consulta de correlación de eventos para reportarlo.

---

## 15. Trazabilidad

| Requisito | OO | CU | OT | OE |
|-----------|----|----|----|----|
| RF-CAR-001 Agregar al carrito | OO-02 | CU-07 | OT-01 | OE-01 |
| RF-CAR-002 Obtener precio del catálogo | OO-02 | CU-07 | OT-01 | OE-01 |
| RF-CAR-003 Persistir ítem | OO-02 | CU-07 | OT-01 | OE-01 |
| RF-CAR-004 Evento add_to_cart | OO-02 | CU-07 | OT-01 | OE-01 |
| RF-CAR-005 Listar carrito | OO-02 | CU-08 | OT-01 | OE-01 |
| RF-CAR-006 Eliminar ítem | OO-02 | CU-08 | OT-01 | OE-01 |
| RF-CAR-007 Evento drop | OO-02 | CU-08 | OT-01 | OE-01 |
| RF-CAR-008 Calcular total | OO-02 | CU-08 | OT-01 | OE-01 |
| RF-CAR-009 Checkout | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-010 Rechazar carrito vacío | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-011 Generar ordenId | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-012 Insertar orden_items | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-013 Evento purchase | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-014 Crear orden | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-015 Vaciar carrito | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-016 Confirmación de orden | OO-02 | CU-09 | OT-01 | OE-01 |
| RF-CAR-017 Requiere autenticación | OO-02 | CU-07/08/09 | OT-01 | OE-01 |

**Leyenda de objetivos**:

- **OE-01**: Maximizar conversiones y ventas en la tienda online.
- **OT-01**: Mejorar la experiencia de navegación del cliente.
- **OO-02**: Agilizar el proceso de compra desde la selección hasta el checkout.
- **CU-07**: Agregar al carrito. **CU-08**: Gestionar carrito. **CU-09**: Finalizar compra.

---

## 16. Pendientes detectados (no inventar; tratar como trabajo futuro)

- **PEND-CAR-01**: No existe operación para **modificar la cantidad** de una línea del carrito; cada
  "agregar" inserta una fila nueva (posibles líneas duplicadas del mismo producto).
- **PEND-CAR-02**: El checkout **no es atómico** (sin transacción): un fallo intermedio puede dejar
  orden_items/eventos/orden o vaciado en estado parcial.
- **PEND-CAR-03**: Las inserciones y mutaciones (agregar, eliminar, checkout) se construyen por
  **concatenación de cadenas** (no parametrizadas) → riesgo de inyección a endurecer (consistente con
  PEND-AUT-04 y PEND-CAT-03).
- **PEND-CAR-04**: No se valida ni descuenta **stock** al agregar ni al hacer checkout.
- **PEND-CAR-05**: No hay **pasarela de pago** ni estados de orden; toda orden nace `COMPLETADA`.
- **PEND-CAR-06**: El **KPI de tiempo de checkout** es derivable de `fact_eventos` pero no está
  instrumentado (sin consulta/endpoint que lo calcule).
