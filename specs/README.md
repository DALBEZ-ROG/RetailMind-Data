# Especificaciones — Nivel Operativo de RetailMind

Índice de las especificaciones del **nivel operativo** de RetailMind (la capa que genera ventas y
engagement del cliente). Cada especificación se generó con **Spec Kit** siguiendo un enfoque de
**Desarrollo Dirigido por Especificaciones (SDD)** y documenta el comportamiento **ya implementado**
en el código (Angular 17 + Spring Boot 3.5 + ClickHouse), extraído del código real. Lo no
implementado se marca como `PENDIENTE`, sin inventar.

Cada feature vive en su carpeta `specs/NNN-...` con un `spec.md` y un checklist de calidad en
`checklists/requirements.md`.

---

## Índice de especificaciones

| Nº | Módulo | Carpeta | Código IDs | Caso(s) de uso | Objetivo operativo / estratégico | Ruta del spec.md |
|----|--------|---------|-----------|----------------|----------------------------------|------------------|
| 001 | Autenticación y control de acceso | `001-operativo-autenticacion` | AUT | CU-01, CU-02 | OO-12, OO-14 / OE-04 | `specs/001-operativo-autenticacion/spec.md` |
| 002 | Catálogo de productos | `002-operativo-catalogo` | CAT | CU-04, CU-05 | OO-01 / OE-01 | `specs/002-operativo-catalogo/spec.md` |
| 003 | Carrito de compras y checkout | `003-operativo-carrito` | CAR | CU-07, CU-08, CU-09 | OO-02 / OE-01 | `specs/003-operativo-carrito/spec.md` |
| 004 | Wishlist (lista de deseos) | `004-operativo-wishlist` | WIS | CU-10, CU-11, CU-12 | OO-03 / OE-01 | `specs/004-operativo-wishlist/spec.md` |
| 005 | Pedidos del cliente (historial) | `005-operativo-pedidos` | PED | CU-13, CU-14 | OO-04 / OE-01 | `specs/005-operativo-pedidos/spec.md` |
| 006 | Perfil del cliente | `006-operativo-perfil` | PER | CU-O08 (TA06) | sin OO en EVF04 / OE-04 | `specs/006-operativo-perfil/spec.md` |
| 007 | Recomendaciones | `007-operativo-recomendaciones` | REC | CU-O09 (TA06) | sin OO en EVF04 / OE-01, OE-04 | `specs/007-operativo-recomendaciones/spec.md` |

---

## Trazabilidad

Cada especificación incluye una tabla que vincula sus requisitos funcionales (RF-XXX-NNN) con los
objetivos estratégicos (OE), tácticos (OT), operativos (OO) y casos de uso (CU) de los documentos de
objetivos. Resumen por módulo:

- **001 Autenticación (AUT)** → OE-04 (seguridad/RBAC); OT-07 (identidades y credenciales seguras) y
  OT-08 (separar/proteger vistas por rol); OO-12 (JWT + hash en ClickHouse) y OO-14 (interfaces según
  rol); CU-01 (iniciar sesión), CU-02 (registrar usuario).
- **002 Catálogo (CAT)** → OE-01 (maximizar conversiones); OT-01 (experiencia de navegación);
  OO-01 (búsqueda/filtrado por categoría, marca y precio); CU-04 (ver catálogo), CU-05 (filtrar,
  incluye detalle y evento `view`).
- **003 Carrito y checkout (CAR)** → OE-01; OT-01; OO-02 (agilizar la compra hasta el checkout);
  CU-07 (agregar), CU-08 (gestionar), CU-09 (finalizar compra).
- **004 Wishlist (WIS)** → OE-01; OT-02 (engagement y retención); OO-03 (guardar productos de
  interés); CU-10 (agregar), CU-11 (listar), CU-12 (eliminar).
- **005 Pedidos (PED)** → OE-01; OT-02; OO-04 (seguimiento de pedidos del cliente); CU-13 (ver mis
  pedidos), CU-14 (ver detalle de orden).
- **006 Perfil (PER)** → OE-04; OT-07; **CU-O08** "Ver y editar mi perfil" (TA06). **Deuda de
  trazabilidad**: EVF04 no asigna OO ni CU propios al perfil; se usa CU-O08 de TA06.
- **007 Recomendaciones (REC)** → OE-01 y OE-04 (personalización); OT-02; **CU-O09** "Recibir
  recomendaciones personalizadas" (TA06). **Deuda de trazabilidad**: EVF04 no asigna OO ni CU propios
  a recomendaciones; se usa CU-O09 de TA06.

> Nota: los módulos 006 y 007 no tienen un OO/CU dedicado en EVF04. En sus specs la columna "OO"
> queda marcada como "sin asignación en EVF04" y la deuda se registra explícitamente, en lugar de
> forzar un mapeo inexistente.

---

## Deuda y hallazgos pendientes

Consolidado de los `PEND-*` más relevantes detectados al leer el código (cada uno está documentado en
la sección 16 de su spec). Se agrupan por tema transversal.

### Seguridad — IDOR (falta de validación de propiedad)
Endpoints que reciben el `username`/`userId` por la ruta y **no lo validan contra el token**, por lo
que un usuario autenticado podría acceder a datos de otro:
- **PEND-PED-01** — `GET /api/pedidos/{userId}` (historial de pedidos ajenos).
- **PEND-PER-01** — `GET/PUT /api/perfil/{username}` (ver/editar perfil ajeno; cambiar contraseña
  ajena sigue requiriendo la contraseña actual).
- **PEND-REC-04** — `GET /api/recomendaciones/{username}` (expone indirectamente el historial de
  otro usuario).

### Seguridad — SQL por concatenación de cadenas
Consultas/mutaciones construidas concatenando strings (no parametrizadas) → riesgo de inyección.
Presente de forma transversal:
- **PEND-AUT-04**, **PEND-CAT-03**, **PEND-CAR-03**, **PEND-WIS-02**, **PEND-PED-02**,
  **PEND-PER-02**, **PEND-REC-05**.

### KPIs no instrumentados
Los KPIs oficiales no son calculables con los datos actuales por falta de instrumentación:
- **PEND-AUT-01** — intentos de acceso no autorizado bloqueados / total (sin contador de intentos).
- **PEND-CAT-04** — CTR de catálogo (solo hay evento `view` del detalle; faltan impresiones/clics).
- **PEND-CAR-06** — tiempo de checkout (derivable de `add_to_cart`→`purchase`, sin cálculo).
- **PEND-WIS-05** — wishlist rate (derivable de eventos `wishlist`/`view`, sin cálculo).
- **PEND-PED-05** — frecuencia de compra (derivable de `ordenes`, sin cálculo).
- **PEND-PER-05** — el perfil no expone un KPI claro en EVF04 ni en el código.
- **PEND-REC-01** — CTR de recomendaciones (meta 15%): no se registran impresiones ni clics.

### Integridad y consistencia de datos
- **PEND-CAR-02** — el **checkout no es atómico** (ClickHouse sin transacciones): un fallo intermedio
  puede dejar `orden_items`/eventos/orden/vaciado en estado parcial.
- **PEND-CAR-04** — no se valida ni descuenta **stock** al agregar ni en checkout.
- **PEND-CAR-05** — no hay **pasarela de pago** ni estados de orden; toda orden nace `COMPLETADA`.
- **PEND-PED-06** — no existe **máquina de estados** del pedido (siempre `COMPLETADA`).
- **PEND-WIS-04** — la wishlist usa **borrado físico** (sin historial).
- **PEND-REC-03** — el motor mezcla `dim_producto` (analítico) y `productos_catalogo` (tienda); si
  los IDs no están alineados, degrada la calidad de las recomendaciones.

### Validaciones débiles / solo en cliente
- **PEND-AUT-03** — `register` no valida formato de email ni fuerza de contraseña; el email se guarda
  en el campo `nombre`.
- **PEND-PER-03 / PEND-PER-04** — el backend no valida formato de email ni longitud de contraseña;
  esas validaciones solo existen en el cliente.

### Desfases UI/API y comportamiento funcional
- **PEND-CAT-01 / PEND-CAT-02** — filtros de marca/precio y búsqueda por texto no cableados en la UI
  de la tienda (la API sí los soporta parcialmente).
- **PEND-CAR-01** — no existe operación de "modificar cantidad" en el carrito (cada agregar inserta
  una fila nueva; posibles duplicados).
- **PEND-WIS-01** — la **eliminación** de la wishlist **no registra evento**; solo el alta registra
  `wishlist`.
- **PEND-WIS-03** — al agregar a wishlist no se valida que el producto exista.
- **PEND-REC-02** — el peso `0.5` del scoring es el valor por defecto del `multiIf` (cualquier acción
  no listada, incluida `view`), no exclusivo de `view`.
- **PEND-REC-06** — "productos vistos" excluye en realidad todos los productos con cualquier evento
  del usuario, no solo `view`.
- **PEND-REC-07 / PEND-REC-08** — recomendaciones no deterministas (orden `rand()`); pesos, umbral
  (10 eventos) y rango (±30%) fijos en código, no configurables.

### Otros
- **PEND-AUT-05** — el logout no invalida el token en el servidor (solo limpia el cliente).
- **PEND-AUT-06** — el rol `VIEWER` está definido en el enum pero no se usa en ningún flujo.
- **PEND-PED-03** — patrón **N+1** al cargar los ítems del historial (una consulta por orden).
- Varios módulos no tienen **paginación** (catálogo en parte, wishlist, pedidos).

> Sugerencia: la deuda de seguridad transversal (IDOR + SQL por concatenación) y la instrumentación
> de KPIs son buenas candidatas a specs de **limpieza/endurecimiento** posteriores, separadas de los
> módulos funcionales.

---

## Alcance

Este índice y las specs **001–007 cubren únicamente el nivel operativo** de RetailMind (la capa que
genera ventas y engagement del cliente). Los niveles **táctico** (sesiones, conversiones, funnel,
analytics por región/dispositivo/tráfico, reportes) y **estratégico** (dashboard ejecutivo y KPIs)
**quedan pendientes** de especificación en fases posteriores.
