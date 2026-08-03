# Auditoría de uniformidad de interfaz — RetailMind

**Fecha**: 2026-08-02 · **Alcance**: `retailmind-frontend/src/app` · **Tipo**: diagnóstico READ-ONLY
(no se modificó ni un archivo de código) · **Patrón auditado**: las cinco reglas del cliente.

> **Fuera de alcance por decisión explícita**: los **69 informes tácticos**
> (`features/operativo/informes/`, pantalla genérica `informes-departamento.component`), los
> **7 tableros estratégicos** (`features/operativo/tableros/`) y toda la analítica de
> ClickHouse (`features/analytics/`). Son pantallas de solo lectura: no crean, no editan y no
> borran registros, así que el patrón —que es de formularios de gestión— no les aplica.
> Tampoco se auditan `kardex`, `seguridad/accesos`, `admin/reportes` ni `admin-etl` (consulta y
> operación de sistema) ni `inicio` (menú). El **Anexo A** trata la tienda del cliente aparte.

---

## 1. Resumen ejecutivo

| Métrica | Valor |
|---|---|
| Pantallas de gestión auditadas | **31** |
| Pantallas que cumplen las **cinco** reglas | **0** |
| Pantallas que cumplen **tres** reglas | 1 (Productos y Variantes) |
| Celdas evaluadas (31 pantallas × 5 reglas) | 155, de las cuales 10 son N/A (regla 5 sin borrado) → **145 evaluables** |
| Cumplimientos | **12** de 145 (8,3 %) |
| **Desviaciones totales** | **136** |
| — severidad **ALTA** | **54** |
| — severidad **MEDIA** | **71** |
| — severidad **BAJA** | **11** |
| Esfuerzo total estimado | **~85 h** (≈ 11 jornadas de trabajo) |

### Cumplimiento por regla

| Regla | Cumplen | Fallan | Lectura |
|---|---|---|---|
| **1** · Grilla de búsqueda primero | 5 / 31 | 26 | 12 pantallas entran **directamente a un formulario**; otras 14 sí abren con la lista pero **sin ningún criterio de búsqueda** |
| **2** · Nuevo / Modificar / Eliminar / Ver | **0 / 31** | 31 | Ninguna pantalla ofrece las cuatro. «Eliminar» está sustituido en silencio por un **toggle Activar/desactivar** en 11 pantallas y «Ver» solo existe en 9 |
| **3** · Visibilidad del modo | 3 / 31 | 28 | Nadie usa la nomenclatura «Modo Nuevo / Modo Actualizar / Modo Eliminar / Modo Consulta». 8 pantallas rotulan **solo el modo edición** («Editando cupón #12») y dejan el alta sin rótulo |
| **4** · Solo Aceptar y Cancelar | **0 / 31** | 31 | **26 etiquetas distintas** para el botón de confirmar (Crear Cupón, Emitir Orden, Aplicar Ajuste, Transferir, Suscribir, Asociar, Despachar…). En 24 pantallas **no hay botón Cancelar dentro del formulario** |
| **5** · Confirmación al eliminar | 4 / 31 (10 N/A) | 17 | Las 4 que confirman usan **`window.confirm()` nativo del navegador**, no un diálogo del sistema. Existe un `ConfirmDialogComponent` con estilo Dubai… **sin usar en ninguna parte** |

### Los tres hallazgos que un evaluador externo verá primero

1. **Las 26 etiquetas del botón de confirmar.** Abrir Cupones («Crear Cupón»), Órdenes de Compra
   («Emitir Orden») e Inventario («Aplicar Ajuste») uno detrás de otro delata que cada pantalla se
   construyó por su cuenta. Es la desviación más barata de corregir y la más visible.
2. **Desactivar no pregunta nada.** En 11 pantallas el botón que hace de «Eliminar» es un icono
   `toggle_on/toggle_off` que ejecuta al primer clic, sin advertencia y sin deshacer visible.
   Es la regla 5 rota de la forma más literal.
3. **Tres módulos entran directo al formulario.** Transferencias, Ajustes y Despachos abren con el
   formulario de alta ocupando la pantalla y la lista debajo, fuera de la vista. Es exactamente lo
   que la regla 1 prohíbe.

---

## 2. Tabla maestra

Leyenda: ✔ cumple · ✘ no cumple · ✱ cumple parcialmente (cuenta como ✘) · — no aplica.

| # | Pantalla | Ruta | R1 Grilla | R2 Opciones | R3 Modo | R4 Botones | R5 Confirmar | Sev. máx |
|---|---|---|---|---|---|---|---|---|
| 1 | **Productos y Variantes** | `/operativo/productos` | ✔ | ✱ (falta Eliminar) | ✔ | ✘ | ✘ | ALTA |
| 2 | Marcas | `/operativo/catalogo/marcas` | ✘ | ✘ | ✔ | ✘ | ✘ | ALTA |
| 3 | Categorías | `/operativo/catalogo/categorias` | ✘ | ✘ | ✔ | ✘ | ✘ | ALTA |
| 4 | Órdenes de Compra | `/operativo/compras/ordenes` | ✘ | ✘ | ✘ | ✘ | — | ALTA |
| 5 | Recepciones | `/operativo/compras/recepciones` | ✘ | ✘ | ✘ | ✘ | — | ALTA |
| 6 | Facturas de Compra | `/operativo/compras/facturas` | ✘ | ✘ | ✘ | ✘ | — | ALTA |
| 7 | Devolución a Proveedor | `/operativo/compras/devoluciones-proveedor` | ✘ | ✘ | ✘ | ✘ | — | ALTA |
| 8 | Proveedores | `/operativo/compras/proveedores` | ✘ | ✘ | ✱ | ✘ | ✘ | ALTA |
| 9 | Transferencias | `/operativo/inventario/transferencias` | ✘ | ✘ | ✘ | ✘ | — | ALTA |
| 10 | Ajustes de Inventario | `/operativo/inventario/ajustes` | ✘ | ✘ | ✘ | ✘ | ✔ | ALTA |
| 11 | Pedidos de Venta | `/operativo/ventas/pedidos` | ✘ | ✘ | ✘ | ✘ | — | ALTA |
| 12 | Facturas de Venta | `/operativo/ventas/facturas` | ✘ | ✘ | ✘ | ✘ | — | ALTA |
| 13 | Preparación de Pedidos | `/operativo/ventas/preparacion` | ✘ | ✘ | ✘ | ✘ | — | MEDIA |
| 14 | Despachos | `/operativo/ventas/despachos` | ✘ | ✘ | ✘ | ✘ | ✘ | ALTA |
| 15 | Devoluciones (RMA) | `/operativo/ventas/devoluciones` | ✔ | ✘ | ✘ | ✘ | ✘ | ALTA |
| 16 | Mis Pedidos (cliente) | `/operativo/ventas/mis-pedidos` | ✘ | ✘ | ✘ | ✘ | — | MEDIA |
| 17 | Metas de Venta | `/operativo/gerencia/metas` | ✘ | ✘ | ✱ | ✘ | ✘ | ALTA |
| 18 | Cupones | `/operativo/marketing/cupones` | ✘ | ✱ (Ver usos) | ✱ | ✘ | ✘ | ALTA |
| 19 | Promociones | `/operativo/marketing/promociones` | ✘ | ✱ | ✱ | ✘ | ✘ | ALTA |
| 20 | Campañas | `/operativo/marketing/campanas` | ✘ | ✘ | ✱ | ✘ | ✘ | ALTA |
| 21 | Banners | `/operativo/marketing/banners` | ✘ | ✘ | ✱ | ✘ | ✘ | ALTA |
| 22 | Newsletter | `/operativo/marketing/newsletter` | ✘ | ✘ | ✘ | ✘ | ✘ | ALTA |
| 23 | Tickets de Soporte | `/operativo/soporte/tickets` | ✔ | ✘ | ✘ | ✘ | — | MEDIA |
| 24 | Categorías de Ticket | `/operativo/soporte/categorias` | ✘ | ✘ | ✱ | ✘ | ✘ | ALTA |
| 25 | Preguntas Frecuentes (FAQ) | `/operativo/soporte/faq` | ✘ | ✘ | ✱ | ✘ | ✘ | ALTA |
| 26 | Reseñas de Productos | `/operativo/resenas` | ✔ | ✘ | ✘ | ✘ | ✘ | ALTA |
| 27 | Preguntas de Productos | `/operativo/resenas/preguntas` | ✘ | ✘ | ✘ | ✘ | ✘ | ALTA |
| 28 | Horarios de Acceso | `/operativo/horarios` | ✘ | ✘ | ✘ | ✘ | — | MEDIA |
| 29 | Usuarios | `/admin-usuarios` | ✘ | ✘ | ✘ | ✘ | ✔ | ALTA |
| 30 | Gestión de Datos | `/gestion-datos` | ✔ | ✘ | ✱ | ✘ | ✔ | MEDIA |
| 31 | Perfil y Direcciones | `/perfil` | ✘ | ✱ | ✘ | ✘ | ✔ | ALTA |

**Cero pantallas con las cinco reglas.** La mejor es la #1 (3 de 5).

---

## 3. Detalle por pantalla

### 3.1 Catálogo

#### 1. Productos y Variantes — `/operativo/productos`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✔ | `productos-admin.component.html:11` campo *Buscar* (nombre, slug, SKU o marca) con debounce, `:17` filtro Marca, `:24` filtro Categoría, `:97` `mat-paginator` server-side. Es la **única** pantalla del sistema con búsqueda por texto + filtros + paginación de servidor. | — | — |
| R2 | ✘ | `:81` Ver variantes, `:84` Editar, `:31` Nuevo Producto → **tres de cuatro**. El cuarto, `:87` «Activar / desactivar», es una baja lógica que no se llama Eliminar ni se comporta como tal. | MEDIA | BAJO |
| R3 | ✔ | `producto-dialog.component.ts:29-30` → «Nuevo producto» / «Editar producto»; `variante-dialog.component.ts:28` idem. Ambos modos rotulados en el título del diálogo. Solo falta la nomenclatura literal «Modo…». | BAJA | BAJO |
| R4 | ✘ | `producto-dialog.component.ts:70-73` → **«Cancelar» + «Crear producto» / «Guardar cambios»**. Son exactamente dos botones y hay Cancelar; falla solo el nombre del botón de confirmar. Es la infracción más leve del sistema. | MEDIA | BAJO |
| R5 | ✘ | `productos-admin.component.ts:151` `toggleActivo()` llama al servicio sin ninguna advertencia. Desactivar un producto lo saca de la tienda al primer clic. | ALTA | MEDIO |

#### 2. Marcas — `/operativo/catalogo/marcas` · #3. Categorías — `/operativo/catalogo/categorias`

Son gemelas: mismo esqueleto, mismos defectos. Evidencia de Marcas (Categorías es idéntica ±10 líneas).

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `marcas-admin.component.html:43` la tabla existe pero **no hay ni un campo de búsqueda** (0 filtros en el archivo) y la tarjeta de acción/formulario ocupa el primer bloque (`:7-41`). Con el catálogo real la lista es inmanejable. | MEDIA | MEDIO |
| R2 | ✘ | `:70` Editar, `:73` toggle. **No hay Ver ni Eliminar.** El «Nuevo» vive en `:9` como un botón que se transforma en «Cancelar». | MEDIA | MEDIO |
| R3 | ✔ | `:19-20` → `{{ editandoId === null ? 'Nueva marca' : 'Editar marca' }}`. **Los dos modos rotulados** — junto con Categorías y los diálogos de Productos, es lo más cerca del patrón que hay hoy. | BAJA | BAJO |
| R4 | ✘ | `:36-38` un **único** botón: «Crear Marca» / «Guardar Cambios». No hay Cancelar dentro del formulario: cancelar exige subir al botón de arriba (`:9`), que cambia de texto según el estado. | ALTA | BAJO |
| R5 | ✘ | `marcas-admin.component.ts:91` `toggleActivo()` sin confirmación. | ALTA | MEDIO |

### 3.2 Compras

#### 4. Órdenes de Compra — `/operativo/compras/ordenes`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `ordenes-compra.component.html:10` la pantalla abre con «Nueva Orden de Compra» y su formulario completo (`:17-66`); la grilla `:81` queda debajo y **sin ningún criterio de búsqueda** (ni número, ni proveedor, ni fecha, ni estado) sobre 865 órdenes. | ALTA | MEDIO |
| R2 | ✘ | `:112` Ver detalle, `:115` Aprobar. **No hay Modificar ni Eliminar** — correcto por negocio (una orden emitida no se edita), pero la pantalla no lo dice: el usuario busca el botón y no está. | MEDIA | MEDIO |
| R3 | ✘ | Ningún rótulo de modo. El formulario aparece y desaparece sin decir qué es. | MEDIA | BAJO |
| R4 | ✘ | `:62` «Añadir línea», `:63` «Emitir Orden» y `:64` un chip de total, todo en la misma barra `form-actions`. **Tres elementos donde el patrón pide dos botones**, y ningún Cancelar. | ALTA | MEDIO |
| R5 | — | No hay borrado de órdenes. `:55` «Quitar línea» opera sobre el formulario en memoria, antes de guardar: no destruye nada persistido. | — | — |
| **extra** | ⚠ | `ordenes-compra.component.ts:150` `aprobarOrden()` se ejecuta al primer clic. **Aprobar es irreversible** y abre la compuerta de recepción/factura. No es «Eliminar», así que la regla 5 no lo cubre, pero un evaluador lo señalará. | MEDIA | MEDIO |

#### 5. Recepciones — `/operativo/compras/recepciones`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | **No hay grilla de entrada.** `recepciones.component.html:11` abre con un `mat-select` «Orden de compra» y, al elegir una, se despliega el formulario de cantidades. Es el caso más puro de «se entra directamente a un formulario». | ALTA | **ALTO** |
| R2 | ✘ | No existe ninguna de las cuatro opciones: es una pantalla de proceso, no de mantenimiento. | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo de modo. | MEDIA | BAJO |
| R4 | ✘ | Botón único «Confirmar Recepción» (texto dinámico «Confirmando…»). Sin Cancelar. | ALTA | BAJO |
| R5 | — | No borra. | — | — |

#### 6. Facturas de Compra — `/operativo/compras/facturas`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | Abre con el formulario «Registrar factura»; la grilla «Cuentas por pagar» va después y **sin criterios de búsqueda** sobre 839 facturas. | ALTA | MEDIO |
| R2 | ✘ | Solo «Ver PDF» y «Registrar pago». Sin Nuevo/Modificar/Eliminar/Ver homogéneos. | MEDIA | MEDIO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | «Registrar Factura» (botón único, sin Cancelar) y, en el sub-formulario de pago, **«Registrar Pago» + «Cancelar»** — dos botones, nombres no estándar. Dos criterios distintos en la MISMA pantalla. | ALTA | BAJO |
| R5 | — | No borra. | — | — |

#### 7. Devolución a Proveedor — `/operativo/compras/devoluciones-proveedor`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | Abre con el formulario «Marcar defectuoso tras la recepción (Bodega)» (`:17-29`). Sí hay filtros por estado en `:45` y `:158`, pero llegan después de dos formularios. | ALTA | MEDIO |
| R2 | ✘ | `:76` Ver; el resto son transiciones de ciclo (Enviar al proveedor, Registrar resolución, Cerrar caso). | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo de modo. | MEDIA | BAJO |
| R4 | ✘ | `:67` «Crear devolución con {{n}} ítem/s» — el texto del botón **cambia con el contenido**, lo más lejos posible de un botón fijo «Aceptar». Sin Cancelar. | ALTA | BAJO |
| R5 | — | No borra. | — | — |

#### 8. Proveedores — `/operativo/compras/proveedores`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `proveedores.component.html:9` **sí abre con la grilla** de proveedores — bien —, pero sin ningún criterio de búsqueda sobre ella (el autocompletado de `:83` es para asociar un producto, no para buscar proveedores). | MEDIA | MEDIO |
| R2 | ✘ | `:39` Ver productos, `:171` Editar condiciones, `:174` toggle. Sin Eliminar. El «Nuevo» (`:69`) solo aparece **dentro** de la ficha de un proveedor ya seleccionado. | MEDIA | MEDIO |
| R3 | ✱ | `:79` «Editando condiciones» aparece **solo** al editar; al asociar un producto nuevo no hay rótulo. | MEDIA | BAJO |
| R4 | ✘ | `:118-120` botón único «Asociar» / «Guardar Cambios». Sin Cancelar. | ALTA | BAJO |
| R5 | ✘ | `proveedores.component.ts:161` `toggleActivo()` sin confirmación. | ALTA | MEDIO |

### 3.3 Inventario

#### 9. Transferencias entre Bodegas — `/operativo/inventario/transferencias`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `transferencias.component.html:8` la pantalla **es** el formulario «Nueva transferencia»; el «Historial de transferencias» aparece en `:54`, por debajo del pliegue, y sin filtros. | ALTA | **ALTO** |
| R2 | ✘ | La grilla de historial no tiene columna de acciones: cero opciones sobre el registro seleccionado. | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | `:36` botón único «Transferir». Sin Cancelar. | ALTA | BAJO |
| R5 | — | No borra. | — | — |

#### 10. Ajustes de Inventario — `/operativo/inventario/ajustes`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `ajustes.component.html:8` igual que Transferencias: formulario primero, historial en `:70` sin filtros. | ALTA | **ALTO** |
| R2 | ✘ | `:102` solo «Anular ajuste». Sin Ver/Modificar/Eliminar. | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | `:37` «Aplicar Ajuste» (único) y, en el bloque de anulación, `:127` «Confirmar anulación» + `:130` «Cancelar». Otra vez dos criterios en la misma pantalla. | ALTA | BAJO |
| R5 | ✔ | `:119-133` **la mejor confirmación del sistema**: bloque dedicado, explica la consecuencia («Se registrará un contramovimiento en el kardex que revierte el stock»), exige motivo obligatorio y ofrece Cancelar. Es el modelo a imitar en contenido, aunque debería ser un diálogo modal. | — | — |

### 3.4 Ventas y Logística

#### 11. Pedidos de Venta — `/operativo/ventas/pedidos`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `pedidos-venta.component.html:9` abre con «Nuevo Pedido»; la grilla tiene paginador pero **cero criterios de búsqueda** (ni número, ni cliente, ni estado, ni fecha) sobre 4.083 pedidos. Es la pantalla donde más se nota. | ALTA | MEDIO |
| R2 | ✘ | Ver detalle sí; después, siete acciones de ciclo (pago, factura, despacho, entrega, devolución, PDF, nota). Sin Modificar ni Eliminar. | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | «Confirmar Pedido» (único, sin Cancelar) y el sub-formulario de cobro con «Confirmar pago» + «Cancelar». | ALTA | BAJO |
| R5 | — | No borra. | — | — |

#### 12. Facturas de Venta — `/operativo/ventas/facturas`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `facturas-venta.component.html:47` **sí tiene** búsqueda por número/cliente/pedido y paginación — de lo mejor del sistema —, pero llega **después** del formulario «Emitir factura» que abre la pantalla. Basta invertir el orden. | ALTA | BAJO |
| R2 | ✘ | Ver + PDF. Sin Nuevo/Modificar/Eliminar homogéneos. | MEDIA | MEDIO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | «Emitir Factura» (único). El panel de detalle cierra con «Cerrar», otro verbo más. | ALTA | BAJO |
| R5 | — | No borra (la anulación no está expuesta en UI). | — | — |

#### 13. Preparación de Pedidos — `/operativo/ventas/preparacion`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | Abre con la cola (bien) pero sin ningún criterio de búsqueda: `preparacion-pedidos.component.html` no tiene un solo `mat-form-field`. | MEDIA | MEDIO |
| R2 | ✘ | Ver detalle + dos transiciones («Iniciar preparación», «Marcar PREPARADO»). | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | Dos botones de transición y un «Cerrar». Ningún par Aceptar/Cancelar. | ALTA | BAJO |
| R5 | — | No borra. | — | — |

#### 14. Despachos — `/operativo/ventas/despachos`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | **No hay ni una `mat-table` en el archivo.** `despachos.component.html:11` abre con un selector «Pedido a despachar» y todo lo demás son formularios (entrega, novedad, reprogramación). No hay lista de despachos que consultar. | ALTA | **ALTO** |
| R2 | ✘ | Ninguna de las cuatro. | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | «Despachar (genera guía)», «Marcar Entregado», «Reprogramar entrega (nuevo intento)», «Devolver al almacén». **Cuatro verbos distintos**, ningún Cancelar. | ALTA | MEDIO |
| R5 | ✘ | «Devolver al almacén» deja el pedido en `no_entregado`, un estado **terminal**, y no pregunta nada. Es destructivo en el sentido de la regla 5. | ALTA | MEDIO |

#### 15. Devoluciones (RMA) — `/operativo/ventas/devoluciones`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✔ | `devoluciones.component.html:12` filtro por estado sobre la bandeja, que es lo primero de la pantalla. Cumple, aunque un criterio por número o cliente lo mejoraría. | — | — |
| R2 | ✘ | Ver detalle + nueve transiciones de ciclo. Sin Nuevo (nace del cliente), sin Modificar, sin Eliminar. | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo de modo. | MEDIA | BAJO |
| R4 | ✘ | Nueve etiquetas distintas: «Iniciar revisión (Soporte)», «Aprobar y generar guía», «Rechazar», «Marcar en tránsito (Despacho)», «Confirmar recepción en almacén», «Registrar inspección (solo lo APTO reingresa)», «Reembolsar $X», «Cerrar devolución (resuelve el ticket)». | ALTA | MEDIO |
| R5 | ✘ | **«Rechazar» es un estado terminal** y se ejecuta al primer clic, sin confirmación. Cierra la devolución del cliente para siempre. | ALTA | MEDIO |

#### 16. Mis Pedidos (cliente) — `/operativo/ventas/mis-pedidos`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | Abre con la lista (bien) pero sin criterios de búsqueda. Con pocos pedidos por cliente el impacto es bajo. | MEDIA | MEDIO |
| R2 | ✘ | Ver, PDF, «Devolver productos», «Novedad en la entrega», «Reseñar». | MEDIA | MEDIO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | «Enviar solicitud», «Devolver productos», «Cancelar» sueltos. | ALTA | BAJO |
| R5 | — | No borra. | — | — |

### 3.5 Gerencia

#### 17. Metas de Venta — `/operativo/gerencia/metas`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `metas-venta.component.html:11` acción de alta primero, grilla después, sin filtros por año/mes/departamento (los tres criterios naturales, y los tres ya existen como campos del formulario en `:23`, `:29`, `:35`). | MEDIA | MEDIO |
| R2 | ✘ | Editar + toggle. Sin Ver ni Eliminar. | MEDIA | MEDIO |
| R3 | ✱ | `:20` «Editando meta #{{id}}» **solo** en edición; el alta no se rotula. | MEDIA | BAJO |
| R4 | ✘ | `:51-52` botón único «Crear Meta» / «Guardar Cambios». | ALTA | BAJO |
| R5 | ✘ | `metas-venta.component.ts:105` toggle sin confirmación. | ALTA | MEDIO |

### 3.6 Marketing — las cinco pantallas más uniformes entre sí… y las cinco desviadas igual

Cupones (#18), Promociones (#19), Campañas (#20), Banners (#21) y Newsletter (#22) comparten
plantilla línea por línea. **Su uniformidad interna es un activo**: corregir una corrige las cinco
con el mismo diff. Evidencia con Cupones:

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `cupones.component.html:9` botón de alta primero, grilla en `:69`, **cero criterios de búsqueda** (ni código, ni vigencia, ni estado) sobre 33 cupones. Idéntico en las otras cuatro. | MEDIA | MEDIO |
| R2 | ✱ | `:118` «Ver usos» (¡el único «Ver» de marketing!), `:122` Editar, `:125` toggle. Sin Eliminar. Promociones añade `:151` «Quitar de la promoción»; Campañas añade play/pause/stop; Banners y Newsletter no tienen Ver. | MEDIA | MEDIO |
| R3 | ✱ | `:19` «Editando cupón #{{id}}» solo en edición. Igual en Promociones `:19`, Campañas `:19`, Banners `:19`. Newsletter **no rotula nada**. | MEDIA | BAJO |
| R4 | ✘ | `:61-63` botón único «Crear Cupón» / «Guardar Cambios». Newsletter usa «Suscribir» (`newsletter.component.html:26`), un quinto verbo. | ALTA | BAJO |
| R5 | ✘ | `cupones.component.ts:99`, `promociones.component.ts:108`, `banners.component.ts:98`, `newsletter.component.ts:68` → cuatro `toggleActivo()` sin confirmación. Además `promociones.component.ts:135` `quitar()` **borra la asociación producto-promoción sin preguntar**: es un DELETE real, no una baja lógica. | ALTA | MEDIO |

### 3.7 Soporte y Opiniones

#### 23. Tickets de Soporte — `/operativo/soporte/tickets`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✔ | `tickets.component.html:99` Estado, `:106` Categoría, `:113` Prioridad, sobre la bandeja. Tres criterios reales. Falta buscar por número `TICK-AAAA-NNNN`. | — | — |
| R2 | ✘ | Ver detalle + «Tomar este ticket», «Aplicar estado», «Cambiar prioridad», «Asignar», «Enviar». Sin Modificar ni Eliminar. | MEDIA | ALTO |
| R3 | ✘ | Sin rótulo de modo. | MEDIA | BAJO |
| R4 | ✘ | `:80` «Crear Ticket» y cuatro botones de acción más en el detalle. | ALTA | MEDIO |
| R5 | — | No borra. | — | — |

#### 24. Categorías de Ticket · #25. FAQ

Clones de la plantilla de marketing. `categorias-ticket.component.html:10-11` (toggle Nuevo/Cancelar),
`:19` rótulo solo en edición, `:38-39` botón único «Crear Categoría» / «Guardar Cambios»,
`categorias-ticket.component.ts:85` toggle sin confirmar. FAQ: `:13-14`, `:22`, `:45-46`,
`faq.component.ts:110`. **R1 MEDIA · R2 MEDIA · R3 MEDIA · R4 ALTA · R5 ALTA**, esfuerzo BAJO en
R3/R4 y MEDIO en R1/R2/R5.

#### 26. Reseñas de Productos — `/operativo/resenas`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✔ | `resenas.component.html:168` y `:257` filtro por estado en las dos bandejas (mis reseñas / moderación). | — | — |
| R2 | ✘ | Votar, reportar, «Atender», «Descartar». Sin las cuatro opciones. | MEDIA | MEDIO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | «Enviar Reseña», «Enviar reporte», «Atender», «Descartar» + un «Cancelar» suelto en `:14`. | ALTA | BAJO |
| R5 | ✘ | «Descartar» un reporte de abuso es irreversible y no confirma. | ALTA | MEDIO |

#### 27. Preguntas de Productos — `/operativo/resenas/preguntas`

`preguntas.component.html` **no tiene ni una `mat-table`**: la lista son tarjetas. Hay filtro por
estado en `:41`, pero no es una grilla. R1 ✘ ALTA (esfuerzo ALTO: hay que construir la grilla).
R4 ✘ ALTA: «Enviar Pregunta», «Responder», «Publicar», «Rechazar», «Publicar respuesta».
R5 ✘ ALTA: «Rechazar» una pregunta no pregunta nada.

### 3.8 Seguridad y Administración

#### 28. Horarios de Acceso — `/operativo/horarios`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `horarios.component.html:9` alta primero, grilla en `:47`, sin filtro por rol ni por día. | MEDIA | MEDIO |
| R2 | ✘ | Solo Editar (`:96`). Sin Ver, sin Eliminar. | MEDIA | MEDIO |
| R3 | ✘ | Sin rótulo. Peor: la edición es **inline dentro de la fila** (`:61-90`), un tercer paradigma de formulario que no existe en ninguna otra pantalla. | MEDIA | MEDIO |
| R4 | ✘ | `:41` «Crear Ventana» y, en la fila, dos **iconos** (`:100` guardar, `:103` cerrar) sin texto: los botones de confirmar/cancelar aquí no tienen etiqueta. | ALTA | MEDIO |
| R5 | — | No hay borrado (se desactiva desde la edición inline). | — | — |
| **extra** | ⚠ | Desactivar la ventana equivocada **deja a un rol entero fuera del sistema** (el motor bloquea por `esta_en_horario()`). Debería confirmar aunque no sea «Eliminar». | ALTA | MEDIO |

#### 29. Usuarios — `/admin-usuarios`

**La pantalla más desviada del sistema y, por el enunciado del cliente («por ejemplo, Clientes»),
la que un evaluador abrirá primero.**

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | `admin-usuarios.component.html:47` tabla sin búsqueda, sin filtro por rol, sin paginación. El ejemplo textual del cliente («cédula, apellido, fecha») no tiene ni un equivalente. | ALTA | MEDIO |
| R2 | ✘ | `:76` **solo Eliminar**. No hay Modificar ni Ver: una vez creado el usuario, no se puede corregir su rol desde la interfaz. Es la única pantalla donde falta la opción de modificar un registro existente. | **ALTA** | ALTO |
| R3 | ✘ | Sin rótulo. | MEDIA | BAJO |
| R4 | ✘ | `:40-42` botón único «Crear» — la etiqueta más escueta del sistema — y el «Cancelar» vive en el botón de arriba (`:6`). | ALTA | BAJO |
| R5 | ✔ | `admin-usuarios.component.ts:63` `confirm('Eliminar usuario ' + username + '?')`. **Cumple el fondo, no la forma**: es el diálogo nativo del navegador, gris, en inglés en algunos navegadores, y rompe el estilo Dubai. | BAJA | BAJO |
| **extra** | ⚠ | La pantalla no usa `operativo-shared.scss` sino `admin-usuarios.component.scss` propio, con `mat-card` en vez de `op-card`. Es la más divergente también en estructura. | MEDIA | MEDIO |

#### 30. Gestión de Datos — `/gestion-datos`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✔ | `gestion-datos.component.html:29` filtro por semana + `:36` «Filtrar» / `:39` «Limpiar», seis tablas con paginación. Cumple. | — | — |
| R2 | ✘ | Editar + Eliminar + Agregar. **Sin Ver.** Y el «Agregar» está repetido cinco veces (una por pestaña). | MEDIA | MEDIO |
| R3 | ✱ | `:50` «Editando Evento» solo en edición. | MEDIA | BAJO |
| R4 | ✘ | `:118-121` **«Cancelar» + «Guardar cambios»** — dos botones y hay Cancelar, solo falla el nombre. Es, junto a los diálogos de Productos, la infracción más leve. | MEDIA | BAJO |
| R5 | ✔ | `gestion-datos.component.ts:168, 208, 234, 260` — cuatro `confirm()` nativos, uno de ellos con la consecuencia explícita («Esta accion no se puede deshacer»). Fondo correcto, forma nativa. | BAJA | BAJO |

#### 31. Perfil y Direcciones — `/perfil`

| Regla | Estado | Evidencia | Sev. | Esfuerzo |
|---|---|---|---|---|
| R1 | ✘ | Las direcciones son **tarjetas apiladas** (`perfil.component.html:200`), no una grilla, y no hay criterios. Es una pantalla de perfil, pero contiene un CRUD completo. | ALTA | ALTO |
| R2 | ✱ | `:218` Predeterminada, `:222` Editar, `:225` Eliminar, `:127` Nueva. **Tres de cuatro** (falta Ver, que en tarjetas es redundante). Es, con Productos, lo más cerca de la regla 2. | MEDIA | MEDIO |
| R3 | ✘ | `:188` el botón dice «Actualizar dirección» / «Guardar dirección», pero **no hay rótulo de modo** en el formulario. | MEDIA | BAJO |
| R4 | ✘ | `:185-190` **«Guardar/Actualizar dirección» + «Cancelar»** — dos botones, con Cancelar. Solo falla el nombre. | MEDIA | BAJO |
| R5 | ✔ | `perfil.component.ts:176` `confirm('¿Eliminar la dirección "…"?')` con el alias en el mensaje. Bien planteado, forma nativa. | BAJA | BAJO |

---

## 4. El módulo de referencia

### Molde principal: **Productos y Variantes** (`/operativo/productos`)

Es el único que cumple tres reglas y el único cuya **estructura** ya es la que el patrón exige.

| Por qué es el molde | Evidencia |
|---|---|
| Separa la lista del formulario | La grilla vive en la pantalla; el alta/edición vive en un **diálogo modal** (`ProductoDialogComponent`, `VarianteDialogComponent`). En las otras 30 pantallas el formulario es un bloque que empuja la tabla hacia abajo. |
| Grilla con criterios de verdad | Búsqueda por texto con debounce + dos filtros + paginación **server-side** (`productos-admin.component.html:11-28, 97`). Es la única pantalla preparada para volumen real (1.221 variantes). |
| Rotula los dos modos | `producto-dialog.component.ts:29-30` → «Nuevo producto» / «Editar producto». |
| Ya tiene exactamente dos botones | `producto-dialog.component.ts:70-73` → `mat-dialog-actions` con **Cancelar** + confirmar. La estructura del patrón ya está: solo falta renombrar el segundo a «Aceptar». |
| Tiene «Ver» explícito | `productos-admin.component.html:81` con icono `visibility`. |

**Lo que le falta para ser el patrón completo (3 cambios pequeños):**
1. Renombrar «Crear producto» / «Guardar cambios» → **«Aceptar»** (regla 4).
2. Añadir un chip de modo con la nomenclatura literal: «Modo Nuevo», «Modo Actualizar»,
   «Modo Consulta», «Modo Eliminar» (regla 3).
3. Convertir el toggle `Activar/desactivar` en un botón **«Eliminar»** que abra confirmación —
   manteniendo la baja lógica por detrás, que es lo correcto para la integridad referencial
   (regla 2 + regla 5).

Hecho eso, la plantilla se replica: **grilla con filtros → selección → barra Nuevo/Modificar/
Eliminar/Ver → diálogo con chip de modo y dos botones Aceptar/Cancelar**.

### Molde secundario: el diálogo de confirmación que ya existe y nadie usa

`features/admin/etl/confirm-dialog.component.ts` es un `ConfirmDialogComponent` completo, con
icono de advertencia, título, mensaje y dos botones (`:25-26`). **No se abre desde ningún sitio**
—`dialog.open()` solo aparece para los diálogos de producto/variante y el de región—: es código
muerto. Es exactamente la pieza que la regla 5 necesita. Basta moverlo a `core/components/`,
renombrar «Ejecutar» → «Aceptar» y envolverlo en un `ConfirmService`. **Las 17 desviaciones de la
regla 5 se cierran con ese servicio y una línea por llamada.**

### Molde de contenido para la confirmación: **Ajustes de Inventario**

`ajustes.component.html:119-133` explica la consecuencia («Se registrará un contramovimiento en el
kardex que revierte el stock») y exige un motivo. Es lo que debe decir un diálogo de eliminación,
no un «¿Está seguro?» pelado.

---

## 5. Pantallas de riesgo — tocar aquí puede romper algo ya probado

Ordenadas por riesgo descendente. En todas ellas la recomendación es **corregir solo la capa
visual** (etiquetas, chip de modo, diálogo de confirmación) y **no reestructurar el flujo**.

| Pantalla | Qué hay detrás | Qué se rompe si se reestructura |
|---|---|---|
| **Recepciones** | Compuerta orden→aprobación→recepción completa. `cantidad_rechazada` alimenta automáticamente el pool `item_defectuoso` (script 45). | Convertirla en grilla-primero obliga a rehacer el selector de orden y el cálculo de cantidades por línea. **El rechazo en puerta es automático**: un cambio en el formulario puede dejar ítems defectuosos sin crear. |
| **Devoluciones (RMA)** | Nueve transiciones con **un rol por transición** (SOPORTE valida, DESPACHO transporta, BODEGA inspecciona, GERENTE reembolsa). La inspección mueve kardex vía `StockService`. | Unificar los nueve botones en «Aceptar» borraría la información de qué transición se está ejecutando. Cada botón es una llamada distinta con guardias 409 propias. |
| **Despachos** | Pedido en estado `preparado`, override de transportista registrado en historial, novedades con máximo 3 reprogramaciones, «Devolver al almacén» → estado terminal `no_entregado`. | No tiene grilla: dársela es **rehacer la pantalla entera**. Además convive con `envio`, `seguimiento_envio`, `novedad_envio` y `log_auditoria`. |
| **Devolución a Proveedor** | Dos orígenes del pool (`rma` y `recepcion`), resolución `nota_credito` vs `reposicion` (esta última **reingresa stock** con kardex nuevo). | El botón «Crear devolución con N ítem/s» depende de una selección múltiple con `Set`; tocar la selección rompe el agrupado por proveedor. |
| **Ajustes de Inventario** | Contramovimiento de kardex al anular; la cadena se encadena por `(fecha_creacion, id)`. | El bloque de anulación es el **único que ya cumple la regla 5**. Convertirlo en diálogo genérico perdería el campo de motivo obligatorio, que la BD necesita. |
| **Órdenes de Compra** | Aprobación GERENTE/ADMIN es la compuerta que habilita recepción y factura. Sin aprobar no se recibe. | «Aprobar» debería confirmar, pero **no debe cambiar de endpoint ni de estado**: `aprobarOrden` deja la orden en `confirmada`, no en «aprobada». |
| **Pedidos de Venta** | `crearPedido` aplica promociones por línea y cupón sobre el subtotal; `canal` discrimina web vs interno y `registrarPago` rechaza los web con 409. | Tocar el formulario de alta puede alterar el cuerpo que se envía; el descuento y el IVA los recalcula el backend a partir de él. |
| **Preparación de Pedidos** | Consulta dedicada porque **BODEGA no lee `pago`**: no usa `obtenerPedido`. | Añadir columnas «para uniformar» puede pedir campos que el rol no tiene privilegio de leer → 403 del motor. |
| **Transferencias** | Escribe kardex en dos bodegas. | Riesgo bajo en el formulario, alto si se toca el envío de cantidades. |
| **Horarios de Acceso** | `grupo_horario` + `esta_en_horario()` + triggers. Es la pantalla que puede dejar a un rol —**o a ti mismo**— fuera del sistema. | Cualquier cambio debe probarse con un usuario no-admin y con la ventana abierta de par en par. |
| **Facturas de Compra / de Venta** | Compuertas de facturación y CxP; los totales los ponen **triggers**, nunca la UI. | El formulario no debe enviar totales. Si al «uniformar» se añade un campo de total, la BD lo rechaza o —peor— se descuadra. |

**Sin riesgo de negocio** (CRUD puro, corregir con confianza): Marcas, Categorías, Cupones,
Promociones, Campañas, Banners, Newsletter, Categorías de Ticket, FAQ, Metas de Venta, Usuarios,
Productos y Variantes.

---

## 6. Priorización recomendada

Criterio: **qué se abre en una demostración o en una evaluación por terceros**, cruzado con
**cuánto cuesta** y **cuánto riesgo tiene**.

### Fase 0 — Construir las piezas comunes (≈ 8 h) · *hacer antes que nada*

1. Mover `ConfirmDialogComponent` a `core/components/`, renombrar «Ejecutar» → «Aceptar»
   y envolverlo en un `ConfirmService.confirmar(titulo, mensaje): Observable<boolean>`.
2. Crear un componente `<app-modo-form modo="nuevo|actualizar|eliminar|consulta">` que pinte el
   chip «Modo Nuevo» / «Modo Actualizar» / «Modo Eliminar» / «Modo Consulta» con el estilo Dubai.
3. Crear un `<app-acciones-registro>` con los cuatro botones Nuevo / Modificar / Eliminar / Ver,
   deshabilitados los tres últimos mientras no haya fila seleccionada.
4. Añadir a `operativo-shared.scss` las clases `.btn-aceptar` y `.btn-cancelar`.

> Sin esta fase, las 136 correcciones se hacen 31 veces a mano y vuelven a divergir.

### Fase 1 — El molde y la cadena de marketing (≈ 14 h) · *máximo retorno visible*

| Orden | Pantalla | Por qué primero |
|---|---|---|
| 1 | **Productos y Variantes** | Es el molde. Tres cambios pequeños y queda como referencia viva. |
| 2 | **Marcas** + **Categorías** | Los dos CRUD más simples del sistema; validan que la plantilla se replica. |
| 3 | **Cupones, Promociones, Campañas, Banners, Newsletter** | Cinco pantallas con **la misma plantilla**: el mismo diff cinco veces. Cinco pantallas alineadas por el precio de una y media. |
| 4 | **Categorías de Ticket** + **FAQ** | Clones de la anterior. Siete pantallas idénticas en total. |

Al cerrar la fase 1: **10 de 31 pantallas** con el patrón completo, todas sin riesgo de negocio, y
la plantilla probada en tres familias distintas.

### Fase 2 — Lo que el evaluador abre a propósito (≈ 16 h)

| Orden | Pantalla | Por qué |
|---|---|---|
| 5 | **Usuarios** | El cliente puso «Clientes» como ejemplo textual de la regla 1. Es la pantalla más desviada y **la única sin opción de modificar**. Un evaluador que busque el fallo, lo encuentra aquí en treinta segundos. |
| 6 | **Metas de Venta** | CRUD limpio, cero riesgo, y es pantalla de gerencia (se enseña en la demo). |
| 7 | **Reseñas** + **Preguntas de Productos** | Preguntas necesita grilla (esfuerzo ALTO); Reseñas solo etiquetas y confirmación. |
| 8 | **Horarios de Acceso** | Muy visible («seguridad por horario» es un diferenciador del proyecto) y de riesgo controlado si solo se toca la capa visual. **Probar con un usuario no-admin.** |

### Fase 3 — Las pantallas con compuertas, solo capa visual (≈ 25 h)

| Orden | Pantalla | Alcance permitido |
|---|---|---|
| 9 | **Pedidos de Venta** | Añadir barra de búsqueda (número, cliente, estado, fecha) e invertir el orden grilla/formulario. **No tocar** el cuerpo del alta ni las transiciones. |
| 10 | **Órdenes de Compra** | Igual + confirmación en «Aprobar». |
| 11 | **Facturas de Venta** | **Solo invertir el orden**: la búsqueda ya existe. Es la corrección más barata de toda la fase. |
| 12 | **Tickets de Soporte** | Añadir búsqueda por número; renombrar botones. |
| 13 | **Facturas de Compra**, **Proveedores**, **Devoluciones (RMA)** | Etiquetas, chip de modo y confirmación en «Rechazar». |

### Fase 4 — Reestructuración real (≈ 22 h) · *decidir si entra en el alcance*

| Pantalla | Por qué es cara |
|---|---|
| **Despachos** | No tiene grilla. Dársela es **construir una pantalla nueva**: cola de despacho + detalle + los tres formularios actuales. ~8 h y toca un flujo con novedades y estados terminales. |
| **Recepciones** | Mismo caso: el selector de orden tendría que pasar a ser una grilla de órdenes pendientes de recibir. ~6 h. |
| **Transferencias** y **Ajustes** | Invertir formulario/historial y dar filtros al historial. ~4 h cada una; el bloque de anulación de Ajustes **no se toca**. |
| **Perfil (direcciones)** | Convertir tarjetas en grilla en una pantalla de cliente. Bajo valor de evaluación. |
| **Preparación de Pedidos**, **Mis Pedidos**, **Devolución a Proveedor**, **Gestión de Datos** | Bajo retorno; dejar para el final. |

> **Recomendación honesta sobre la fase 4**: si el tiempo aprieta, **decláralas fuera de alcance
> por escrito** en vez de hacerlas a medias. Cuatro pantallas de proceso con una nota explicando
> por qué el patrón de mantenimiento no les aplica es una respuesta defendible ante un evaluador;
> una grilla a medio construir, no.

### Resumen de esfuerzo

| Fase | Horas | Pantallas alineadas (acumulado) |
|---|---|---|
| 0 · Piezas comunes | 8 | 0 |
| 1 · Molde + cadenas | 14 | 10 / 31 |
| 2 · Alta visibilidad | 16 | 15 / 31 |
| 3 · Compuertas, capa visual | 25 | 23 / 31 |
| 4 · Reestructuración | 22 | 31 / 31 |
| **Total** | **~85 h** | |

Con las fases 0-2 (**38 h**) quedan alineadas las **15 pantallas que se abren en una demostración**,
que es el objetivo real de la evaluación.

---

## Anexo A — La tienda del cliente (fuera de la tabla maestra)

`shop` (`/shop`), `carrito` (`/shop/carrito`), `checkout` (`/shop/checkout`) y `wishlist`
(`/wishlist`) permiten crear y borrar registros (ítems de carrito, ítems de wishlist, pedidos),
pero **no son formularios de mantenimiento**: son un escaparate de comercio electrónico, donde el
patrón del cliente (grilla → Nuevo/Modificar/Eliminar/Ver → Aceptar/Cancelar) sería un retroceso
de usabilidad. No se auditan contra las cinco reglas.

Dos observaciones que sí conviene registrar, porque un evaluador puede abrirlas:

- `carrito.component.ts:75` `eliminarItem()` y `wishlist.component.ts:60` `eliminarDeWishlist()`
  **borran sin confirmar**. En una tienda es el comportamiento estándar (Amazon tampoco pregunta),
  pero conviene tener el argumento preparado.
- `checkout.component.html:253-256` el botón de pago cambia de texto en tres estados
  («Pagar y confirmar pedido» / «Procesando pago…» / «Reintentar pago»). Es correcto para un
  checkout y deliberadamente distinto del patrón de back-office.

## Anexo B — Pantallas de solo lectura y de operación de sistema (excluidas)

| Pantalla | Ruta | Motivo de exclusión |
|---|---|---|
| 69 informes tácticos | `/operativo/informes/{departamento}` | Solo lectura (una pantalla genérica) |
| 7 tableros de dirección | `/operativo/tableros/*` | Solo lectura |
| Kardex | `/operativo/inventario/kardex` | Consulta de movimientos |
| Intentos de Acceso | `/operativo/seguridad/accesos` | Consulta de seguridad |
| Reportes | `/admin/reportes` | Exportación a Excel |
| Administración ETL | `/admin-etl` | Operación de pipeline |
| Inicialización | `/inicializacion` | Operación de sistema. **Nota positiva**: su reset destructivo (`inicializacion.component.html:125-136`) exige escribir «CONFIRMAR» y ofrece «Cancelar» + «ELIMINAR TODO». Es la confirmación más fuerte del sistema y demuestra que el equipo sabe hacerla; simplemente no se aplicó al resto. |
| Analytics (dashboard, sesiones, conversiones, funnel, región, dispositivo, tráfico) | `/dashboard`, `/sesiones`, … | Solo lectura (ClickHouse). `region-dialog.component.html:30` cierra con un único botón «Cerrar», coherente con un diálogo informativo. |
| Inicio | `/inicio`, `/inicio/:area` | Menú de navegación |
| Login | `/login` | Autenticación |
| Recomendaciones | `/recomendaciones` | Solo lectura (cliente) |
