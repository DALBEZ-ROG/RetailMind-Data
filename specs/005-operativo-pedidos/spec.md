# Feature Specification: Operativo - Pedidos del cliente

**Feature Branch**: `005-operativo-pedidos`

**Created**: 2026-06-18

**Status**: Draft

**Input**: Quinto módulo del nivel operativo de RetailMind. Especificación derivada del código real
implementado (Angular 17 + Spring Boot 3.5 + ClickHouse). Cubre la **consulta** del historial de
pedidos del cliente y el detalle de cada orden (solo lectura). La creación de órdenes (checkout) y la
supervisión administrativa de todos los pedidos son módulos aparte (fuera de alcance).

> **Nota metodológica**: por instrucción explícita, esta especificación documenta el
> comportamiento **ya implementado** e incluye detalles técnicos verificables (endpoints, tablas
> ClickHouse). Lo no implementado se marca como **PENDIENTE** y no se inventa.

---

## 1. Objetivo

Permitir que un cliente autenticado consulte el historial de sus pedidos y el detalle de cada orden
(ítems, cantidades, precios, total, fecha y estado), consultando las tablas `ordenes` y
`orden_items` en ClickHouse. Es un módulo de **solo lectura** que da seguimiento a las compras
realizadas y refuerza la confianza y el retorno del cliente a la tienda.

---

## 2. Usuarios / Actores

- **CLIENTE autenticado**: consulta su historial de pedidos y el detalle de cada orden. Es el actor
  principal del módulo.
- **ADMIN autenticado**: además del flujo de cliente, dispone de un endpoint para ver todos los
  pedidos (`/api/pedidos/admin/todos`), que pertenece al **módulo administrativo** (fuera de alcance).
- **Sistema (backend)**: recupera las órdenes filtradas por usuario y enriquece cada una con sus
  ítems.

> El módulo **requiere autenticación**: la ruta `/api/pedidos/{userId}` cae bajo
> `anyRequest().authenticated()` en `SecurityConfig` (requiere JWT), y la ruta de tienda
> `/mis-pedidos` está protegida por `authGuard`. La ruta `/api/pedidos/admin/**` exige rol `ADMIN`.

---

## 3. Contexto del problema

Tras comprar (checkout del módulo 003), el cliente necesita poder revisar qué compró, cuándo y por
cuánto. Sin un historial accesible, se pierde transparencia y disminuye la confianza y la
probabilidad de recompra. RetailMind necesita exponer al cliente sus propias órdenes con su detalle,
de forma clara y ordenada (lo más reciente primero), manteniendo la separación entre lo que ve un
cliente (solo lo suyo) y lo que ve un administrador (todo).

---

## 4. Requisitos Funcionales

| ID | Requisito |
|----|-----------|
| **RF-PED-001** | El sistema MUST listar los pedidos de un cliente mediante `GET /api/pedidos/{userId}` (requiere autenticación). |
| **RF-PED-002** | El sistema MUST filtrar las órdenes por `user_id` y ordenarlas por `fecha_orden` descendente (lo más reciente primero). |
| **RF-PED-003** | Cada orden devuelta MUST incluir: `ordenId`, `total`, `estado`, `fechaOrden`, `canal`. |
| **RF-PED-004** | Cada orden MUST incluir sus ítems (`productoId`, `cantidad`, `precioUnitario`, `nombre` vía JOIN con `productos_catalogo`) y el conteo `numItems`. |
| **RF-PED-005** | El detalle de la orden (sus ítems) MUST entregarse **embebido** en la misma respuesta del listado; no existe un endpoint de detalle por `ordenId` independiente. |
| **RF-PED-006** | La vista "Mis pedidos" MUST mostrar el historial y permitir ver el detalle de cada orden expandiéndola. |
| **RF-PED-007** | El módulo MUST ser de **solo lectura**: no crea, modifica, cancela ni elimina órdenes. |
| **RF-PED-008** | Todas las operaciones del módulo MUST requerir un usuario autenticado (token JWT válido). |

---

## 5. Requisitos No Funcionales (cuantificados)

| ID | Requisito |
|----|-----------|
| **RNF-PED-001** | La ruta `/api/pedidos/{userId}` MUST requerir token JWT válido (autorización `authenticated`). |
| **RNF-PED-002** | El historial MUST presentarse ordenado por `fecha_orden` descendente. |
| **RNF-PED-003** | El acceso a datos MUST realizarse con `JdbcTemplate` sobre ClickHouse (sin JPA). |
| **RNF-PED-004** | Ante un error en la consulta, el sistema responde HTTP 500 con `{ error }`; el cliente degrada a una lista vacía sin romper la vista. |
| **RNF-PED-005** | **PENDIENTE (rendimiento — patrón N+1)**: el servicio ejecuta una consulta de ítems por cada orden (bucle), lo que escala mal con muchas órdenes. Debe evaluarse una consulta agregada/única. |
| **RNF-PED-006** | **PENDIENTE (objetivo de rendimiento)**: no existe un umbral de latencia medido para la carga del historial. Debe definirse y medirse antes de tratarlo como requisito vigente. |

---

## 6. Reglas de Negocio

| ID | Regla |
|----|-------|
| **RN-PED-001** | Un cliente debe poder ver **únicamente sus propias** órdenes. **PENDIENTE**: el backend **no valida** que el `userId` de la ruta coincida con el usuario del token (ver PEND-PED-01). |
| **RN-PED-002** | Las órdenes se crean exclusivamente en el checkout del carrito (módulo 003); este módulo no las genera ni modifica. |
| **RN-PED-003** | El `estado` mostrado es el de la orden almacenada; en la implementación actual toda orden nace y permanece `COMPLETADA` (sin transiciones de estado). |
| **RN-PED-004** | En la interfaz, el módulo opera sobre el usuario en sesión (su `username` actúa como `user_id`). |
| **RN-PED-005** | El total mostrado es el `total` persistido en la orden al momento del checkout (no se recalcula). |

---

## 7. Entradas

- **Listar pedidos del cliente** (`GET /api/pedidos/{userId}`): `userId` en la ruta.
- **Autenticación**: cabecera `Authorization: Bearer <token>`.
- *(Relación, fuera de alcance)* **Todos los pedidos** (`GET /api/pedidos/admin/todos`): solo ADMIN.

---

## 8. Salidas (incluye mensajes de error)

**Éxito**

- **Listar 200**: arreglo de órdenes, cada una con:
  `{ ordenId, total, estado, fechaOrden, canal, numItems, items: [ { productoId, cantidad, precioUnitario, nombre } ] }`.
- **Cliente sin pedidos 200**: arreglo vacío `[]`.

**Error / casos especiales**

- **Listar 500**: `{ "error": "<mensaje>" }`.
- **Cliente (UI)**: si la carga falla, la vista muestra el historial vacío (degradación silenciosa,
  sin mensaje explícito de error).

---

## 9. Escenarios (Gherkin)

### Escenario 1 — Ver lista de mis pedidos (CU-13)

```gherkin
Dado un cliente autenticado con órdenes registradas
Cuando solicita GET /api/pedidos/{userId} con su propio username
Entonces el sistema responde HTTP 200 con sus órdenes ordenadas por fecha descendente
  Y cada orden incluye ordenId, total, estado, fechaOrden, canal y numItems
```

### Escenario 2 — Ver detalle de una orden (CU-14)

```gherkin
Dado un cliente autenticado viendo su historial
Cuando expande una orden de la lista
Entonces ve sus ítems embebidos (producto, cantidad, precio unitario, nombre)
  Y el detalle proviene de la misma respuesta del listado (no de un endpoint separado)
```

### Escenario 3 — Cliente sin pedidos (CU-13)

```gherkin
Dado un cliente autenticado que nunca ha comprado
Cuando solicita GET /api/pedidos/{userId}
Entonces el sistema responde HTTP 200 con una lista vacía
  Y la vista "Mis pedidos" muestra el estado sin pedidos
```

### Escenario 4 — Intento de ver una orden ajena (comportamiento actual)

```gherkin
Dado un cliente autenticado
Cuando solicita GET /api/pedidos/{otroUsername} con un username distinto al suyo
Entonces el sistema responde HTTP 200 con las órdenes de ESE otro usuario
  Porque el backend NO valida la propiedad contra el token (PENDIENTE de seguridad)
Nota: la interfaz solo usa el username propio, pero la API no impide la consulta cruzada.
```

---

## 10. Criterios de Aceptación

| ID | Criterio |
|----|----------|
| **CA-PED-001** | `GET /api/pedidos/{userId}` devuelve las órdenes del usuario ordenadas por fecha descendente, cada una con sus campos básicos. |
| **CA-PED-002** | Cada orden incluye sus ítems con `productoId`, `cantidad`, `precioUnitario`, `nombre` y el conteo `numItems`. |
| **CA-PED-003** | El detalle de ítems se obtiene de la misma respuesta del listado (sin endpoint de detalle separado). |
| **CA-PED-004** | Un cliente sin órdenes recibe HTTP 200 con lista vacía y la vista lo refleja. |
| **CA-PED-005** | El módulo no expone operaciones de escritura sobre las órdenes (solo lectura). |
| **CA-PED-006** | La operación rechaza peticiones sin token JWT válido. |
| **CA-PED-007** | **PENDIENTE**: validar que un cliente no pueda obtener órdenes de otro usuario (hoy no se impide a nivel de API). |

---

## 11. Restricciones

- **Base de datos única**: las órdenes residen en `retailmind.ordenes` y sus ítems en
  `retailmind.orden_items`; el nombre del producto se resuelve contra `productos_catalogo`.
- **Acceso a datos**: `JdbcTemplate` (sin JPA). Las consultas se construyen por concatenación de
  cadenas (ver PENDIENTE de seguridad).
- **Solo lectura**: el módulo no crea ni altera órdenes.
- **Autenticación obligatoria**: el módulo no opera de forma anónima.
- **Estado único**: no hay máquina de estados de pedido; toda orden está `COMPLETADA`.
- **Stack fijado**: Spring Boot 3.5 (backend) y Angular 17 standalone con Angular Material
  (frontend); su cambio requiere enmienda a la constitución.

---

## 12. Dependencias

- **Módulo de Carrito y Checkout (003)**: genera las órdenes (`ordenes`/`orden_items`) que este
  módulo consulta. La creación es responsabilidad de ese módulo → ver Fuera de Alcance.
- **Módulo de Catálogo (002)**: provee el `nombre` del producto vía JOIN con `productos_catalogo`.
- **Módulo de Autenticación (001)**: provee el usuario en sesión (`username`) usado como `user_id`.
- **Módulo administrativo de pedidos (OO-15)**: el endpoint `/api/pedidos/admin/todos` (solo ADMIN)
  pertenece a ese módulo → ver Fuera de Alcance.
- **ClickHouse** con las tablas `ordenes`, `orden_items` y `productos_catalogo` pobladas.
- **Constitución** (`.specify/memory/constitution.md`), Principios II (operativa primero) y V
  (seguridad: requiere autenticación y control de acceso por usuario).

---

## 13. Fuera de Alcance

- **Creación de la orden (checkout)**: pertenece al módulo de Carrito (003).
- **Supervisión administrativa de todos los pedidos (OO-15)**: ver/filtrar todas las órdenes por el
  ADMIN (`GET /api/pedidos/admin/todos`) es un módulo administrativo aparte.
- **Cancelación, devolución o cambio de estado** de un pedido: no implementado.
- **Facturación / generación de comprobantes** del pedido: no implementado en este módulo.
- **Seguimiento logístico / envío** (tracking): no implementado.
- **Paginación del historial**: no implementada (se devuelve la lista completa del usuario).

---

## 14. KPI y Criterios de Éxito Medibles

- **KPI oficial**: *número de pedidos por cliente / periodo* (frecuencia de compra).
  - **SC-PED-001 (objetivo)**: incrementar la frecuencia media de compra por cliente en un periodo
    dado, como señal de retención.
  - **Medible parcialmente**: la tabla `ordenes` contiene `user_id` y `fecha_orden`, por lo que el
    indicador es **derivable** agregando órdenes por usuario y periodo.
  - **PENDIENTE de instrumentación**: no existe hoy un endpoint ni cálculo que compute este KPI; debe
    implementarse la consulta de agregación para reportarlo.

---

## 15. Trazabilidad

| Requisito | OO | CU | OT | OE |
|-----------|----|----|----|----|
| RF-PED-001 Listar pedidos del cliente | OO-04 | CU-13 | OT-02 | OE-01 |
| RF-PED-002 Filtrar por usuario y ordenar | OO-04 | CU-13 | OT-02 | OE-01 |
| RF-PED-003 Datos de la orden | OO-04 | CU-13 | OT-02 | OE-01 |
| RF-PED-004 Ítems de la orden | OO-04 | CU-14 | OT-02 | OE-01 |
| RF-PED-005 Detalle embebido | OO-04 | CU-14 | OT-02 | OE-01 |
| RF-PED-006 Vista "Mis pedidos" con detalle | OO-04 | CU-13/CU-14 | OT-02 | OE-01 |
| RF-PED-007 Solo lectura | OO-04 | CU-13/CU-14 | OT-02 | OE-01 |
| RF-PED-008 Requiere autenticación | OO-04 | CU-13/CU-14 | OT-02 | OE-01 |

**Leyenda de objetivos**:

- **OE-01**: Maximizar conversiones y ventas en la tienda online.
- **OT-02**: Incrementar el engagement y la retención del cliente.
- **OO-04**: Dar seguimiento completo a los pedidos realizados por el cliente.
- **CU-13**: Ver mis pedidos. **CU-14**: Ver detalle de orden.

---

## 16. Pendientes detectados (no inventar; tratar como trabajo futuro)

- **PEND-PED-01** (seguridad): el endpoint `GET /api/pedidos/{userId}` **no valida la propiedad**:
  cualquier usuario autenticado puede consultar las órdenes de otro pasando su `username` en la ruta
  (Broken Object Level Authorization / IDOR). Debe forzarse que el `userId` coincida con el usuario
  del token.
- **PEND-PED-02** (seguridad): las consultas se construyen por **concatenación de cadenas** (no
  parametrizadas) → riesgo de inyección (consistente con PEND-AUT-04, PEND-CAT-03, PEND-CAR-03,
  PEND-WIS-02).
- **PEND-PED-03** (rendimiento): patrón **N+1** al cargar los ítems (una consulta por orden).
- **PEND-PED-04**: no hay **paginación** del historial.
- **PEND-PED-05**: el **KPI de frecuencia de compra** es derivable de `ordenes` pero no está
  instrumentado (sin consulta/endpoint que lo calcule).
- **PEND-PED-06**: no existe **máquina de estados** del pedido (toda orden es `COMPLETADA`); el
  seguimiento de estado real no está implementado.
