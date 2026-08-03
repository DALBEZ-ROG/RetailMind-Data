# Cierre del trabajo de uniformidad de interfaz — RetailMind

**Fecha**: 2026-08-02 · **Alcance**: `retailmind-frontend/src/app` (31 pantallas de gestión)
**Documentos de referencia**: `docs/UNIFORMIDAD_INTERFAZ.md` (auditoría de partida) ·
`docs/PATRON_UI.md` (el patrón construido y sus 23 trampas)
**Estado**: fases 0, 1 y 2 completas **+ Prioridad 1 de §4 cerrada (2026-08-02)**.
**15 pantallas alineadas · 7 excluidas por naturaleza de proceso · 9 pendientes por alcance**,
con la **regla 5 ampliada cerrada en las 31**: cero acciones irreversibles sin confirmar y cero
`confirm()` nativos.

---

## Resumen ejecutivo

El cliente pidió cinco reglas de uniformidad: entrar por una **grilla de búsqueda**, ofrecer
**Nuevo / Modificar / Eliminar / Ver** sobre el registro seleccionado, **declarar el modo** del
formulario, dejar **solo Aceptar y Cancelar**, y **confirmar antes de eliminar**. La auditoría de
partida encontró que **ninguna** de las 31 pantallas cumplía las cinco y contabilizó 136
desviaciones.

Se construyeron cuatro piezas comunes y se alinearon **15 pantallas** —las que se abren en una
demostración—, cerrando **68 desviaciones (50 %)**. Las cuatro piezas cubrieron las 15 pantallas
**sin modificarse ni una vez**.

Las 16 restantes se dividen en dos grupos, y la distinción es la parte importante de este
documento:

- **7 pantallas quedan fuera por NATURALEZA, no por falta de tiempo.** Recepciones, Despachos,
  Transferencias, Ajustes de Inventario, Preparación de Pedidos, Devoluciones (RMA) y Devolución
  a Proveedor **no mantienen un catálogo: ejecutan un paso de un proceso con compuertas**. No
  tienen un registro que dar de alta y de baja, sino transiciones de estado con un rol distinto
  cada una y guardias que el backend hace cumplir. Aplicarles el patrón de mantenimiento no las
  uniformaría: las rompería. El caso más claro es la regla 4: renombrar «Aprobar», «Marcar
  PREPARADO», «Reembolsar $X» y «Devolver al almacén» a un único «Aceptar» **borraría la
  información de qué transición se está ejecutando**, y varias de ellas son irreversibles.
- **9 pantallas quedan pendientes por ALCANCE, sin excusa técnica.** Proveedores, Gestión de
  Datos, Perfil, Tickets y los cuatro documentos transaccionales (Órdenes de Compra, Facturas de
  Compra, Pedidos de Venta, Facturas de Venta) más Mis Pedidos. Se sabe qué les falta y cuánto
  cuesta (≈47 h, §4). No se hicieron porque el alcance acordado terminaba en la fase 2.

**Si en la feria preguntan por una pantalla no alineada**, la respuesta es una de estas dos, y hay
que decir cuál: *«esa es de proceso: su patrón es otro y está documentado por qué»* (las 7), o
*«esa está identificada, priorizada y presupuestada, y no entró en el alcance de esta entrega»*
(las 9). Lo que no se dice es que se olvidó.

Importante: **excluir por naturaleza no equivale a excusarlas de todo**. En las 7 de proceso
siguen siendo exigibles la regla 1 (entrar por una cola de trabajo con criterios) y la regla 5
ampliada (confirmar cualquier acción irreversible, no solo «Eliminar»).

**La regla 5 ampliada ya está cerrada** (fase 3, 2026-08-02): las tres acciones irreversibles que
no preguntaban nada —aprobar una orden de compra, devolver un envío al almacén y rechazar una
devolución— ahora confirman explicando la consecuencia real, y **no queda ningún `confirm()`
nativo del navegador en todo el frontend**. La regla 1 en esas pantallas sigue declarada como
pendiente en §4, no dada por buena.

---

## 1. Qué se hizo y qué se logró

### Antes y después

| Métrica | Antes (auditoría) | Después (hoy) |
|---|---|---|
| Pantallas que cumplen las **cinco** reglas | **0 / 31** | **15 / 31** |
| Pantallas que cumplen tres reglas | 1 (Productos) | — (las 15 cumplen cinco) |
| Desviaciones abiertas | 136 | **68 cerradas** (las de las 15 alineadas) |
| Etiquetas distintas para el botón de confirmar | 26 | 1 («Aceptar») en las 15 alineadas |
| Pantallas con `window.confirm()` nativo | 4 | **0** (los 5 últimos sustituidos en la fase 3) |
| Acciones irreversibles verificadas sin confirmación | 3 | **0** (§3) |
| Uso real de `ConfirmDialogComponent` | **0 llamadas** (código muerto) | 15 pantallas |

> **Nota de rigor sobre la cifra 136.** El resumen de la auditoría declara 145 celdas evaluables
> y 12 cumplimientos, lo que daría 133 desviaciones, no 136; la diferencia viene del tratamiento
> de los cumplimientos parciales (✱). No se ha reconciliado esa discrepancia. Las **68
> desviaciones cerradas** de este documento están contadas una por una sobre la tabla maestra de
> la auditoría (§2 de `UNIFORMIDAD_INTERFAZ.md`), no derivadas del titular.

### Las cuatro piezas comunes

Construidas en la fase 0 y verificadas hoy en `retailmind-frontend/src/app/core/`:

| Pieza | Qué regla resuelve | Estado |
|---|---|---|
| `ConfirmService` + `ConfirmDialogComponent` | 5 · confirmación con estilo Dubai y hueco para la consecuencia | Usada por las 15 pantallas alineadas (verificado: 15 archivos importan `ConfirmService`) |
| `<app-modo-form>` | 3 · chip «Modo Nuevo / Actualizar / Eliminar / Consulta» | Sin modificar desde su creación |
| `<app-acciones-registro>` | 2 · las cuatro opciones sobre la fila seleccionada | Sin modificar desde su creación |
| `.btn-aceptar` / `.btn-cancelar` (en `styles.scss`) | 4 · dos botones y solo dos | Sin modificar desde su creación |

**El dato relevante para la defensa**: las cuatro piezas absorbieron los 15 casos —incluidos los
raros: una campaña sin bandera `activo`, una asociación producto-promoción sin ficha, un
suscriptor que *es* su email, un usuario que no se puede borrar— **sin una sola modificación**.
Los `@Input()` `mostrarNuevo/Modificar/Eliminar/Ver`, `puedeEliminar` y `motivoNoEliminable`
bastaron. Eso es lo que demuestra que el patrón es un patrón y no quince copias parecidas.

### Coste real por fase

| Fase | Estimado en la auditoría | Entregado |
|---|---|---|
| 0 · Piezas comunes | 8 h | 1 servicio + 2 componentes + 2 clases CSS globales |
| 1 · Molde + cadenas (10 pantallas) | 14 h | **10 diálogos nuevos + 1 utilidad compartida** (`vigencia.util.ts`); 0 componentes nuevos en `core/` |
| 2 · Alta visibilidad (5 pantallas) | 16 h | **6 diálogos nuevos + 1 servicio Angular (25 líneas) + 2 clases Java de backend + 2 clases CSS compartidas**; 0 componentes nuevos en `core/` |

Hoy existen **18 archivos `*-dialog.component.ts`** del patrón (12 de la fase 1 contando los dos
de Productos que se reescribieron, 6 de la fase 2).

La fase 2 fue la única que necesitó **backend**: la pantalla de Usuarios no tenía forma de
modificar un registro existente porque los endpoints no existían. Se añadieron
`PUT /api/auth/usuarios/{id}`, `PATCH /api/auth/usuarios/{id}/activo` y `GET /api/auth/roles`,
todos ADMIN, dentro de una sola `@Transactional` (para que corran bajo `SET LOCAL ROLE` y el log
de auditoría se confirme con el cambio), con el rol validado por lista blanca contra la tabla
`rol` y **sin que la contraseña se lea, se devuelva ni se registre**.

---

## 2. El argumento central: pantallas de mantenimiento vs. pantallas de proceso

### La distinción

Las cinco reglas del cliente describen, en conjunto, **un patrón concreto: el CRUD de
mantenimiento de un maestro**.

> grilla con criterios → selección de una fila → Nuevo / Modificar / Eliminar / Ver →
> formulario con su modo declarado y dos botones → confirmación al eliminar.

Ese patrón presupone tres cosas sobre la entidad: **(a)** que existe una lista de registros que se
mantiene, **(b)** que un mismo actor puede crearlos, corregirlos y darlos de baja, y **(c)** que
las operaciones son reversibles o al menos independientes entre sí. Un producto, una marca, un
cupón, un usuario o una ventana horaria cumplen las tres. Para ellos el patrón es correcto, y es
lo que se aplicó en las 15 pantallas alineadas.

Hay pantallas donde **ninguna de las tres se cumple**. No mantienen un catálogo: **ejecutan un
paso de un proceso de negocio con compuertas**. Su entidad no se «da de alta», se *hace avanzar*;
no la gobierna un actor, sino una cadena de roles; y sus pasos no son reversibles, sino que abren
compuertas que la base de datos hace cumplir. En ellas, el patrón de mantenimiento no es una
mejora pendiente: es un molde que no encaja.

A continuación, el caso concreto de cada una, con la evidencia verificada.

### 2.1 Recepción de Mercancía — `/operativo/compras/recepciones`

**No hay una lista de recepciones que mantener.** El punto de partida es una **orden de compra
aprobada y pendiente de recibir**: el selector de la pantalla está acotado a eso y lo dice en su
propio `mat-hint` — *«Solo órdenes aprobadas por Gerencia (o con recepción parcial)»*
(`recepciones.component.html:17`). Lo que la pantalla produce es un comprobante de recepción, que
nadie edita ni borra después: es un hecho.

**Y no es solo un formulario: dispara efectos automáticos.** La columna «Defectuosa (rechazo)»
de cada línea (`:56-63`) alimenta el pool de ítems defectuosos **sin intervención del usuario**.
En `ComprasService.java:312-326`, si `rechazada > 0` se inserta una fila en `item_defectuoso` con
`origen = 'recepcion'`, se le engancha el proveedor de la orden y se registra en auditoría con
`salidaStockVendible = false` — porque lo rechazado **jamás entra al inventario vendible**. Un
«Modificar» sobre una recepción ya registrada tendría que decidir qué hacer con ese ítem
defectuoso, con el movimiento de kardex y con el estado de la orden. No existe tal operación, y
ofrecer el botón sería prometerla.

### 2.2 Despachos — `/operativo/ventas/despachos`

**Opera sobre un pedido que ya está en un estado concreto**: `VentasService.java:859` rechaza
cualquier pedido que no esté en `preparado`. La pantalla no crea despachos; hace avanzar pedidos
ajenos.

**Sus acciones son transiciones, no un CRUD**: despachar (genera guía), marcar entregado,
registrar una novedad, reprogramar la entrega y devolver al almacén. Y **«Devolver al almacén»
lleva a un estado terminal**: `VentasService.java:1172` deja el pedido en `no_entregado`, del que
no hay salida. Un botón «Aceptar» genérico para las cinco no diría cuál se ejecuta, y una de
ellas cierra el pedido para siempre.

**Salvedad honesta**: esta pantalla **no tiene ni una `mat-table`** (verificado: 0 ocurrencias de
`mat-table` en `despachos.component.html`, frente a 20 `mat-form-field`). Que el patrón de
mantenimiento no le aplique **no justifica que no tenga una cola de despacho que consultar**. Eso
es un hueco real y está en §3 y §4 como pendiente, no como excusado.

### 2.3 Devoluciones (RMA) — `/operativo/ventas/devoluciones`

Es el caso más nítido, porque el reparto de roles está escrito en `SecurityConfig` y se puede
leer:

| Transición | Rol que la ejecuta (verificado en `SecurityConfig.java:150-168`) |
|---|---|
| Solicitar la devolución | **CLIENTE** (`POST /api/devoluciones`) |
| Iniciar revisión · Aprobar · Rechazar · Cerrar | **SOPORTE** (+ADMIN) |
| Marcar en tránsito | **DESPACHO** (+ADMIN) |
| Confirmar recepción en almacén | **DESPACHO o BODEGA** (+ADMIN) |
| Registrar inspección por ítem | **BODEGA** (+ADMIN) |
| Reembolsar | **GERENTE** (+ADMIN) |

Ocho transiciones internas más la solicitud que nace del cliente, **cada una con su endpoint, su
rol y sus guardias de estado propias** (409 si el ciclo no lo permite). «Rechazar» es terminal.
La inspección de BODEGA es la única que mueve kardex, y solo por los ítems marcados
`apto_reventa`.

Unificar esos botones en «Aceptar» no uniformaría nada: **eliminaría del interfaz la única señal
de qué paso del proceso está ejecutando quién**. Y las cuatro opciones del patrón no tienen
traducción: no hay «Nuevo» (nace del cliente), no hay «Modificar» (una devolución no se corrige,
avanza) y no hay «Eliminar» (se rechaza, que es una transición con su propio rol).

### 2.4 Devolución a Proveedor — `/operativo/compras/devoluciones-proveedor`

Mismo argumento, en espejo hacia el proveedor: `registrada → enviada → resuelta → cerrada`, con
BODEGA marcando defectuosos y COMPRAS gestionando la devolución
(`SecurityConfig.java:89-98`: `.../defectuoso` es ADMIN+BODEGA; crear, enviar, resolver y cerrar
son ADMIN+COMPRAS). La resolución bifurca en `nota_credito` o `reposicion`, **y solo la segunda
reingresa stock**. Además, su botón de alta opera sobre una **selección múltiple** de ítems
agrupados por proveedor, no sobre una fila: la barra de acción sobre «el registro seleccionado»
no describe lo que hace.

### 2.5 Transferencias entre Bodegas y Ajustes de Inventario

Las dos escriben en el kardex y **el asiento es el producto de la pantalla**. Una transferencia
genera **dos** movimientos —`salida_transferencia` en la bodega de origen y
`entrada_transferencia` en la de destino (`InventarioService.java:55-57`)—; un ajuste genera uno.
Un movimiento de kardex no se edita ni se borra: la cadena se encadena por `(fecha_creacion, id)`
y reescribirla invalidaría todos los saldos corridos posteriores.

Por eso **«Anular» no es «Eliminar»**: el bloque de anulación de Ajustes
(`ajustes.component.html:119-133`) registra un **contramovimiento** que revierte el stock, y lo
dice con esas palabras. Ese bloque es, además, **la mejor confirmación del sistema y el modelo de
contenido que copió el patrón**: explica la consecuencia real y **exige un motivo obligatorio**
(`:127`, el botón está `[disabled]` sin él) porque la base de datos lo necesita para el
contramovimiento. Sustituirlo por el diálogo genérico de `ConfirmService`, que devuelve un
booleano, **perdería ese campo**.

**Salvedad honesta**: en las dos pantallas el formulario de alta ocupa el primer bloque y el
historial va debajo sin filtros. Eso **sí** infringe la regla 1 y **sí** es corregible sin tocar
el flujo. Está en §3 y §4.

### 2.6 Preparación de Pedidos — `/operativo/ventas/preparacion`

No tiene entidad propia: es una **cola de trabajo** de BODEGA sobre pedidos ajenos, con dos
transiciones (iniciar preparación, marcar preparado).

Su particularidad es de seguridad, y es verificable en el motor: **BODEGA y DESPACHO no tienen
ningún privilegio sobre `pago` ni sobre `transaccion_pago`** (consultado
`information_schema.role_table_grants`: sobre esas dos tablas solo aparecen `grp_administrador`,
`grp_analista`, `grp_cliente`, `grp_gerente` y `grp_vendedor`; `grp_bodega` y `grp_despacho` no
figuran). Por eso la pantalla **no puede usar `obtenerPedido`** y se sirve de una consulta
dedicada, `colaPreparacion()` (`VentasService.java:713-734`), que no selecciona ni un importe.

Esto tiene una consecuencia directa sobre el patrón: **«uniformar» esta pantalla añadiéndole las
columnas que tienen las demás produciría un 403 del motor**, no una pantalla más bonita. La
segregación financiera es una decisión de diseño de la base de datos, no del frontend.

### Resumen del argumento

| | Pantalla de mantenimiento | Pantalla de proceso |
|---|---|---|
| Entidad | Un maestro que se mantiene | Un hecho que se registra o un documento que avanza |
| Actor | Uno, con las cuatro operaciones | Una cadena de roles, uno por transición |
| Operaciones | Alta, corrección, baja lógica | Transiciones con compuertas y guardias 409 |
| Reversibilidad | Sí (la baja lógica se restaura) | Varias son terminales |
| Efectos colaterales | Ninguno fuera de la fila | Kardex, stock, pool de defectuosos, auditoría |
| Patrón correcto | Las cinco reglas | Cola de trabajo → detalle → transición nombrada y confirmada |

---

## 3. Lo que SÍ se conservó de las reglas en las pantallas de proceso

Excluir el patrón completo no es excluir las cinco reglas. Regla por regla, esto es lo exigible en
una pantalla de proceso y lo que no:

| Regla | ¿Exigible en pantallas de proceso? | Razón |
|---|---|---|
| **1 · Grilla de búsqueda primero** | **SÍ, sin matices** | Una cola de trabajo es una grilla. Que Recepciones y Despachos se entren por un `mat-select`, y que Transferencias y Ajustes abran con el formulario, son defectos reales. Preparación abre con la cola —bien— pero **no tiene un solo `mat-form-field`**: cero criterios. |
| **2 · Nuevo / Modificar / Eliminar / Ver** | **NO, salvo «Ver»** | No hay registro que crear, corregir ni dar de baja. **«Ver» sí es exigible** —consultar el detalle completo antes de actuar— y la mayoría ya lo tiene. |
| **3 · Visibilidad del modo** | **NO en su forma literal; SÍ en su intención** | «Modo Nuevo/Actualizar/Eliminar/Consulta» no describe estos flujos. Lo que sí debe verse es **en qué paso del proceso está el documento y qué transición se va a ejecutar**. Hoy ninguna lo rotula. |
| **4 · Solo Aceptar y Cancelar** | **NO en el nombre; SÍ en el Cancelar** | El nombre del botón **es** información: «Aprobar», «Marcar PREPARADO», «Reembolsar $X» y «Devolver al almacén» no son intercambiables, y al menos dos son irreversibles. Renombrarlas a «Aceptar» sería un retroceso. Lo que **sí** es exigible es que **todo formulario de acción ofrezca Cancelar**, y hoy la mayoría no lo hace. |
| **5 · Confirmación al eliminar** | **SÍ, y AMPLIADA** | El criterio correcto no es «al eliminar», sino **«antes de cualquier acción irreversible»**. |

### La regla 5, ampliada: acciones irreversibles verificadas que hoy NO confirman

Este es el trabajo pendiente más barato y el de mayor valor defensivo:

| Acción | Pantalla | Por qué es irreversible | Estado |
|---|---|---|---|
| **Aprobar orden de compra** | Órdenes de Compra | `ComprasService.java:197-226`: `enviada → confirmada`. Es la compuerta que habilita recepción y factura; no hay endpoint para volver atrás. | **Ya confirma** — fase 3 (2026-08-02) |
| **Devolver al almacén** | Despachos | `VentasService.java:1155-1181`: deja el pedido en `no_entregado`, **estado terminal**. | **Ya confirma** — fase 3 (2026-08-02) |
| **Rechazar una devolución** | Devoluciones (RMA) | `DevolucionService.java:499-514`: estado terminal; cierra la devolución del cliente y deja su ticket resuelto. | **Ya confirma** — fase 3 (2026-08-02) |
| **Anular un ajuste** | Ajustes de Inventario | Registra un contramovimiento de kardex. | **Ya confirma** — y es el modelo de contenido del patrón |
| Descartar un reporte de abuso | Reseñas | Estado terminal (`atendido` y `descartado` no tienen salida). | **Corregido en la fase 2** |

**La regla 5 ampliada queda CERRADA**: no hay ninguna acción irreversible verificada que no
confirme. Las tres de la fase 3 se añadieron **sin tocar nada más** de sus pantallas: no se
reestructuraron, no se les puso la barra de acciones y **el botón conserva su nombre**
(«Aprobar», «Devolver al almacén», «Rechazar»), porque en una pantalla de proceso el nombre del
botón es información y la regla 4 no le aplica (véase la tabla anterior).

En las 15 pantallas alineadas la regla 5 está cerrada y el mensaje explica siempre la consecuencia
real; el ejemplo más elaborado es el de Horarios de Acceso, que **calcula** cuántas ventanas
activas le quedan al rol y cambia de tono si es la última, porque desactivar la última deja a un
rol entero fuera del sistema.

#### Los tres mensajes, y la evidencia de backend que los respalda

Ninguno dice «¿está seguro?»: los tres describen lo que va a pasar, y cada frase se verificó
contra el servicio antes de escribirla.

| Acción | Consecuencia que se muestra | Verificado en |
|---|---|---|
| **Aprobar la orden** | «La orden pasará de «enviada» a «confirmada» y quedará habilitada para recibir mercancía y, tras la recepción completa, para facturarse. La aprobación NO se puede deshacer: el sistema no ofrece ninguna acción que devuelva la orden a «enviada».» | `aprobarOrden` escribe `estado = 'confirmada'` (no existe «aprobada» en el CHECK); `registrarRecepcion` rechaza `borrador`/`enviada` y `registrarFactura` exige `recibida` completa. `ComprasController` no expone ningún endpoint que revierta el estado. |
| **Devolver al almacén** | «El envío quedará «devuelto» y el pedido pasará a NO ENTREGADO, que es un estado terminal: ya no se podrá reprogramar la entrega ni entregarlo. El stock NO se reingresa aquí —eso lo decide la inspección física de bodega, como en la RMA— y el reembolso al cliente queda pendiente de gestionarse por ticket de soporte y gerencia. La alternativa reversible es «Reprogramar entrega».» | `devolverAlmacen` pone el envío en `devuelto` y llama a `cambiarEstadoPedido(…, "no_entregado")`; ninguna transición admite `no_entregado` como origen. No hay ni una llamada a `StockService` en el método, y el javadoc declara la vía de reembolso. |
| **Rechazar la devolución** | «"Rechazada" es un estado terminal: la devolución no admite ninguna transición posterior, no se recibe mercancía y no se reembolsa nada. El ticket de soporte asociado queda RESUELTO y el cliente recibe en él el motivo que escribiste; si responde, el ticket se reabre. El cliente conserva el producto y puede volver a solicitar la devolución mientras siga dentro del plazo de 30 días.» | `rechazar` escribe `rechazada` y llama a `mensajeTicket` + `ticketResuelto`; ningún `exigirTransicion` acepta `rechazada` como origen. El cupo por ítem se calcula con `d.estado <> 'rechazada'`, así que la mercancía rechazada **vuelve a estar disponible** para otra solicitud dentro de `PLAZO_DIAS_DEVOLUCION = 30`. |

---

## 4. Mejoras pendientes y su prioridad

Tomado del plan por fases de la auditoría (§6 de `UNIFORMIDAD_INTERFAZ.md`), con las estimaciones
originales.

### Prioridad 1 — Confirmar las acciones irreversibles (≈2 h) · **COMPLETADA (2026-08-02)**

Las tres acciones de la tabla anterior ya confirman con `ConfirmService`, y de paso se
sustituyeron **los cinco últimos `confirm()` nativos** del sistema (4 en Gestión de Datos y 1 en
Perfil). El servicio absorbió los cinco casos **sin modificarse**: sigue siendo el mismo de la
fase 0.

| Confirmación añadida | Pantalla | Qué se tocó |
|---|---|---|
| Aprobar orden de compra | `/operativo/compras/ordenes` | Solo `aprobarOrden()`: la llamada al backend se extrajo a `ejecutarAprobacion()` |
| Devolver al almacén | `/operativo/ventas/despachos` | Solo `devolverAlmacen()`, con `ejecutarDevolucionAlmacen()` |
| Rechazar devolución (RMA) | `/operativo/ventas/devoluciones` | Solo `rechazar()` |
| 4 × eliminar (evento, dimensión, producto, visitante) | `/gestion-datos` | Los 4 `confirm()` nativos; se añadieron dos helpers privados para poder NOMBRAR la dimensión que se borra |
| Eliminar dirección | `/perfil` | El `confirm()` nativo |

**Ninguna pantalla se reestructuró y ningún botón cambió de nombre.** Verificado por API y en
navegador (aceptar y cancelar en las cinco): al cancelar no sale ni una petición al backend y la
fila conserva incluso su `fecha_actualizacion`; al aceptar, la acción se comporta igual que antes.
Comprobado además que **no queda ni un `confirm()`, `alert()` o `prompt()` nativo en todo
`retailmind-frontend/src`**.

Efecto colateral: los cinco diálogos destaparon tres defectos preexistentes más (§5, filas 8-10).

### Prioridad 2 — Fase 3 de la auditoría: capa visual de las pantallas con compuertas (≈25 h)

Ocho pantallas donde **no se toca el flujo**, solo la superficie:

| Pantalla | Qué falta | Coste |
|---|---|---|
| **Facturas de Venta** | **Solo invertir el orden**: la búsqueda por número/cliente/pedido y la paginación ya existen, pero llegan debajo del formulario de emisión. | BAJO — la corrección más barata del sistema |
| Pedidos de Venta | Criterios de búsqueda (número, cliente, estado, fecha) sobre 4.083 pedidos e invertir grilla/formulario | MEDIO |
| Órdenes de Compra | Criterios sobre 865 órdenes + invertir el orden (la confirmación en «Aprobar» ya está hecha) | MEDIO |
| Tickets de Soporte | Búsqueda por número `TICK-AAAA-NNNN`; ya tiene tres criterios | BAJO |
| Facturas de Compra | Criterios sobre 839 facturas + unificar los dos criterios de botón que conviven en la misma pantalla | MEDIO |
| Proveedores | Criterios sobre la grilla (hoy ninguno) + las cuatro opciones | MEDIO |
| Devoluciones (RMA) | Rótulo del paso (la confirmación en «Rechazar» ya está hecha) | MEDIO |

### Prioridad 3 — Fase 4: reestructuración real (≈22 h)

| Pantalla | Qué haría falta | Coste |
|---|---|---|
| **Despachos** | **Construir la cola de despacho que no existe** (0 tablas hoy) | ~8 h |
| **Recepciones** | Convertir el selector de orden en una grilla de órdenes pendientes de recibir | ~6 h |
| **Transferencias** y **Ajustes** | Invertir formulario/historial y dar criterios al historial. **El bloque de anulación de Ajustes no se toca.** | ~4 h c/u |
| **Preparación** | Criterios sobre la cola — **sin añadir columnas de dinero** (403 del motor) | BAJO |
| Perfil (direcciones) | Convertir tarjetas en grilla (el `confirm()` nativo ya está sustituido) | MEDIO |
| Gestión de Datos | Añadir «Ver» (los 4 `confirm()` nativos ya están sustituidos) | MEDIO |
| Mis Pedidos, Devolución a Proveedor | Criterios y etiquetas | BAJO |

> Nota: **ya no queda ningún `confirm()` nativo en el sistema.** Los cinco que quedaban —4 en
> Gestión de Datos y 1 en Perfil— se sustituyeron por `ConfirmService` en la fase 3
> (2026-08-02). Verificado con una búsqueda de `confirm(`, `alert(` y `prompt(` sobre
> `retailmind-frontend/src`: cero coincidencias fuera del comentario del propio
> `ConfirmService`, que cita `window.confirm()` para explicar a qué sustituye.

### Deuda de criterio, no de código

Si la fase 4 no entra en el alcance, **la recomendación es declararlo por escrito** —como hace
este documento— en vez de dejar grillas a medio construir. Cuatro pantallas de proceso con una
nota que explica por qué el patrón de mantenimiento no les aplica es una respuesta defendible;
una grilla incompleta, no.

---

## 5. Bugs preexistentes que el trabajo destapó

Resultado colateral: **ninguno de estos defectos se introdujo en este trabajo**. Todos estaban en
el código y solo aparecieron al recorrer las pantallas de forma sistemática y probar cada camino.

| # | Defecto | Cómo apareció | Impacto real | Estado |
|---|---|---|---|---|
| 1 | **`DELETE /api/auth/usuarios/{email}` nunca pudo funcionar.** `usuario` tiene **32 claves foráneas** apuntando a ella; `cliente.usuario_id` es `RESTRICT` y otras cinco son `NO ACTION` (`meta_venta.fijada_por`, `item_defectuoso.registrado_por`, `novedad_envio.registrado_por/resuelto_por`, `devolucion_proveedor.registrado_por`, `historial_devolucion_proveedor.usuario_id`). | Al escribir la consecuencia de «Eliminar» hubo que mirar las FK, no solo el endpoint. | **El botón «Eliminar» de Usuarios fallaba con TODOS los clientes y con casi todo el personal.** Era la única acción de esa pantalla. | Corregido: la pantalla hace baja lógica (`activo = false`), que además es lo que de verdad cierra el acceso |
| 2 | **`grp_soporte` ausente de dos listas blancas**: `HorariosAdminService.ROLES_VALIDOS` (backend) y el array `roles` del frontend. | Al comparar la lista de la pantalla contra `SELECT DISTINCT rol_grupo FROM grupo_horario`. | Crear una ventana horaria para el rol SOPORTE era **imposible desde la interfaz**, pese a que la tabla ya tenía sus 7 ventanas sembradas (script 37). | Corregido en ambos |
| 3 | **`producto_variante.codigo_barras` es `UNIQUE` y `''` no es `NULL`.** El formulario enviaba cadena vacía. | Al probar el molde dando de alta una segunda variante sin código de barras. | En PostgreSQL dos `NULL` no colisionan, pero **dos cadenas vacías sí**: la segunda variante sin código rebotaba con un 400 del motor. Verificado: `UNIQUE (codigo_barras)`, sin índice parcial. | Corregido: sin código se envía `null` |
| 4 | **Desplegable de roles de Usuarios con 8 de los 9 roles** (faltaba SOPORTE), codificado a mano. | Misma comparación que el #2, contra `SELECT codigo FROM rol WHERE activo`. | No se podía crear un usuario de soporte desde la interfaz. | Corregido: la lista ya no se codifica, se pide a `GET /api/auth/roles` |
| 5 | **Mojibake en los días de la semana**: `'MiÃ©rcoles'` y `'SÃ¡bado'` en `horarios.component.ts`. | Al leer el archivo para reescribir la pantalla. | Dos de los siete días se mostraban corruptos en una pantalla que se enseña como diferenciador del proyecto. | Corregido |
| 6 | **`ConfirmDialogComponent` era código muerto.** Existía completo, con estilo Dubai, desde hacía meses, **sin un solo `dialog.open()`**. | Al buscar una pieza para la regla 5 y encontrarla ya escrita. | Las 17 desviaciones de la regla 5 se arrastraban teniendo la solución en el repositorio. | En uso por las 15 pantallas alineadas |
| 7 | **Un diálogo con `<app-select-buscable>` abría su panel de autocompletado sobre los botones.** `MatDialog` enfoca el primer elemento tabulable; si es el autocompletado, su panel se despliega solo. | Al probar el alta de reseña y de pregunta como CLIENTE. | Con 1.221 productos el panel ocupaba la pantalla: **el formulario parecía no tener botones**. | Corregido con `autoFocus: false` |
| 8 | **`GET /api/devoluciones/{id}` devuelve 403 al ADMIN.** A `grp_administrador` le falta el `SELECT` sobre `historial_estado_devolucion` — es el **único** grupo sin él (`grp_analista`, `bodega`, `cliente`, `despacho`, `gerente`, `soporte` y `vendedor` lo tienen). `obtener()` lee el historial, así que el motor corta con 42501 → 403. | Al probar el rechazo de una RMA como ADMIN: la bandeja carga (200) pero el detalle no abre. | **El ADMIN no puede abrir el detalle de NINGUNA devolución**, y por tanto no puede ejecutar ninguna de las transiciones que `SecurityConfig` le concede en esa pantalla. La prueba se hizo con SOPORTE, que sí funciona. | **Abierto** — exige un script SQL nuevo (`GRANT SELECT ON historial_estado_devolucion TO grp_administrador`); fuera del alcance de esta tarea, que es solo de frontend |
| 9 | **`fact_eventos.event_pk` NO es único.** La columna se define `DEFAULT rowNumberInAllBlocks()`, que reinicia el contador **en cada bloque de inserción**: hay 50.000 valores distintos para 2.823.245 filas, cada uno repetido entre **52 y 139 veces**. | Al capturar una fila para poder restaurarla tras probar el borrado: `SELECT * WHERE event_pk = 0` devolvió 139 filas. | El botón «Eliminar» de la pestaña Hechos ejecuta `DELETE WHERE event_pk = N` y por tanto **borra decenas de filas de golpe, no la que se ve**. La confirmación nueva avisa de que el borrado es físico, pero no puede saber cuántas filas caerán. | **Abierto** — es un defecto del modelo de la base legada de ClickHouse, no del frontend |
| 10 | **7 novedades de envío sembradas quedaron en un estado que el sistema real no produce**: `estado = 'abierta'` con su envío en `en_transito`, cuando registrar una novedad deja el envío en `fallido`. | Al pulsar «Devolver al almacén» sobre una de ellas: el backend respondió 409 *«El envío no está en estado de novedad»*. | **Esas 7 novedades no se pueden resolver por ninguna de las dos vías** (ni reprogramar ni devolver al almacén): quedan abiertas para siempre y bloquean la entrega del pedido. | **Abierto** — dato del seed, no código |

Los siete primeros están documentados con su causa y su corrección en `docs/PATRON_UI.md` (§7 los
de la fase 1, §9 los de la fase 2), para que no se repitan. Los tres últimos aparecieron al
verificar las confirmaciones de la fase 3 y **quedan abiertos**: los tres exigen tocar la base de
datos (un GRANT, el modelo de la tabla legada y el seed), y esta tarea era de frontend.

---

## 6. Estado final de las 31 pantallas

Leyenda: **A** = alineada (cumple las cinco reglas) · **P** = excluida por naturaleza de proceso ·
**X** = pendiente por alcance.

| # | Pantalla | Ruta | Estado | Nota |
|---|---|---|---|---|
| 1 | Productos y Variantes | `/operativo/productos` | **A** | El molde del patrón |
| 2 | Marcas | `/operativo/catalogo/marcas` | **A** | |
| 3 | Categorías | `/operativo/catalogo/categorias` | **A** | Filtro de FK nullable con tres estados |
| 4 | Órdenes de Compra | `/operativo/compras/ordenes` | **X** | Documento transaccional: aplica R1/R3/R4/R5; no aplica Modificar/Eliminar (una orden emitida no se edita). **R5 cerrada**: «Aprobar» ya confirma |
| 5 | Recepciones | `/operativo/compras/recepciones` | **P** | Parte de una orden aprobada; el rechazo en puerta crea ítems defectuosos automáticamente |
| 6 | Facturas de Compra | `/operativo/compras/facturas` | **X** | Documento transaccional; dos criterios de botón distintos en la misma pantalla |
| 7 | Devolución a Proveedor | `/operativo/compras/devoluciones-proveedor` | **P** | Ciclo con roles; el alta opera sobre selección múltiple, no sobre una fila |
| 8 | Proveedores | `/operativo/compras/proveedores` | **X** | **CRUD puro: el patrón le aplica entero.** Pendiente solo por alcance |
| 9 | Transferencias | `/operativo/inventario/transferencias` | **P** | Escribe dos movimientos de kardex. R1 sí exigible (pendiente) |
| 10 | Ajustes de Inventario | `/operativo/inventario/ajustes` | **P** | Contramovimiento con motivo obligatorio; **ya cumple la regla 5** y es el modelo de contenido |
| 11 | Pedidos de Venta | `/operativo/ventas/pedidos` | **X** | Documento transaccional; sin criterios sobre 4.083 pedidos |
| 12 | Facturas de Venta | `/operativo/ventas/facturas` | **X** | **La corrección más barata: solo invertir el orden**; la búsqueda ya existe |
| 13 | Preparación de Pedidos | `/operativo/ventas/preparacion` | **P** | Cola de BODEGA; consulta dedicada porque el rol no tiene privilegio sobre `pago` |
| 14 | Despachos | `/operativo/ventas/despachos` | **P** | Transiciones sobre pedido `preparado`; `no_entregado` es terminal y **ya confirma**. **Sin ninguna tabla: la cola es un hueco real** |
| 15 | Devoluciones (RMA) | `/operativo/ventas/devoluciones` | **P** | Ocho transiciones internas, un rol cada una. **«Rechazar» ya confirma** |
| 16 | Mis Pedidos (cliente) | `/operativo/ventas/mis-pedidos` | **X** | Consulta del cliente; bajo valor de evaluación |
| 17 | Metas de Venta | `/operativo/gerencia/metas` | **A** | |
| 18 | Cupones | `/operativo/marketing/cupones` | **A** | |
| 19 | Promociones | `/operativo/marketing/promociones` | **A** | Incluye la asociación producto-promoción, con borrado FÍSICO declarado |
| 20 | Campañas | `/operativo/marketing/campanas` | **A** | Sin bandera `activo`: «Eliminar» = finalizar, y el mensaje no promete restauración |
| 21 | Banners | `/operativo/marketing/banners` | **A** | |
| 22 | Newsletter | `/operativo/marketing/newsletter` | **A** | «Modificar» reducido al estado: un suscriptor *es* su email (limitación declarada) |
| 23 | Tickets de Soporte | `/operativo/soporte/tickets` | **X** | Mixta: el alta es mantenimiento, el ciclo es proceso. Ya cumple R1 con tres criterios |
| 24 | Categorías de Ticket | `/operativo/soporte/categorias` | **A** | |
| 25 | Preguntas Frecuentes (FAQ) | `/operativo/soporte/faq` | **A** | |
| 26 | Reseñas de Productos | `/operativo/resenas` | **A** | Dos bandejas, dos barras de acciones |
| 27 | Preguntas de Productos | `/operativo/resenas/preguntas` | **A** | No tenía tabla: se construyó la grilla |
| 28 | Horarios de Acceso | `/operativo/horarios` | **A** | Confirmación que calcula el impacto; probada dejando fuera a un rol real y restaurándolo |
| 29 | Usuarios | `/admin-usuarios` | **A** | Era la más desviada; necesitó backend nuevo |
| 30 | Gestión de Datos | `/gestion-datos` | **X** | Ya cumple R1 y R5: los **4 `confirm()` nativos** son ahora `ConfirmService`. Falta «Ver» |
| 31 | Perfil y Direcciones | `/perfil` | **X** | CRUD de direcciones en tarjetas (falta la grilla). R5 cerrada: su `confirm()` nativo es ahora `ConfirmService` |

**Totales: 15 alineadas (A) · 7 excluidas por naturaleza de proceso (P) · 9 pendientes por
alcance (X).**

### Fuera de la tabla (excluidas desde el diagnóstico)

No se auditaron ni se cuentan aquí, por decisión explícita en `UNIFORMIDAD_INTERFAZ.md`: los **69
informes tácticos**, los **7 tableros estratégicos** y toda la analítica de ClickHouse —son de
**solo lectura**: no crean, no editan y no borran, así que un patrón de formularios de gestión no
les aplica—; Kardex, Intentos de Acceso, Reportes, Administración ETL e Inicialización
—consulta y operación de sistema—; e Inicio y Login. La **tienda del cliente** (`/shop`, carrito,
checkout, wishlist) se trata en el Anexo A del diagnóstico: es un escaparate de comercio
electrónico, donde el patrón de back-office sería un retroceso de usabilidad.

Vale la pena citar una de ellas en la feria: la pantalla de **Inicialización** exige escribir
literalmente «CONFIRMAR» antes de su reset destructivo. **Es la confirmación más fuerte del
sistema y demuestra que el equipo sabía hacerlas**; el problema nunca fue de capacidad, fue que
no existía un patrón compartido. Ahora existe.
