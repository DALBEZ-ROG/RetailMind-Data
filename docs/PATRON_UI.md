# Patrón de interfaz de RetailMind

**Estado**: Fase 0 (piezas comunes), **Fase 1 COMPLETA** (10 pantallas) y **Fase 2 COMPLETA**
(5 pantallas de alta visibilidad) el 2026-08-02. **15 de las 31 pantallas auditadas** están
alineadas. Diagnóstico de partida: `docs/UNIFORMIDAD_INTERFAZ.md`.

| Fase | Pantallas | Estado |
|---|---|---|
| 0 · Piezas comunes | `ConfirmService`, `<app-modo-form>`, `<app-acciones-registro>`, `.btn-aceptar`/`.btn-cancelar` | ✔ |
| 1.1 · Molde | Productos y Variantes | ✔ |
| 1.2 · Gemelas del catálogo | Marcas · Categorías | ✔ |
| 1.3 · Marketing | Cupones · Promociones · Campañas · Banners · Newsletter | ✔ |
| 1.4 · Soporte | Categorías de Ticket · FAQ | ✔ |
| 2 · Alta visibilidad | Usuarios · Metas de Venta · Reseñas · Preguntas de Productos · Horarios de Acceso | ✔ |

Este documento es operativo, no teórico: describe **cómo se alinea una pantalla** copiando el
molde. Con la Fase 2 se cierra el alcance acordado.

---

## 1. Las cinco reglas del cliente

| # | Regla | Pieza que la resuelve |
|---|---|---|
| 1 | **Grilla de búsqueda primero.** Se entra por una LISTA con criterios claros, nunca por un formulario. | Estructura de la pantalla (§4) |
| 2 | **Opciones estándar** sobre el registro seleccionado: Nuevo · Modificar · Eliminar · Ver. | `<app-acciones-registro>` |
| 3 | **Visibilidad del modo**: Modo Nuevo / Modo Actualizar / Modo Eliminar / Modo Consulta. | `<app-modo-form>` |
| 4 | **Botones únicos**: solo **Aceptar** y **Cancelar**. | `.btn-aceptar` / `.btn-cancelar` |
| 5 | **Confirmación de seguridad** al eliminar. | `ConfirmService` |

**Módulo de referencia**: `/operativo/productos` (Productos y Variantes). Cumple las cinco.
Cuando dudes de cómo se hace algo, ábrelo y cópialo.

---

## 2. Las cuatro piezas comunes

### 2.1 `ConfirmService` — la confirmación (regla 5)

- **Servicio**: `core/services/confirm.service.ts`
- **Diálogo**: `core/components/confirm-dialog/confirm-dialog.component.ts`

> Este diálogo existía desde hacía meses en `features/admin/etl/confirm-dialog.component.ts`
> **sin que ninguna pantalla lo abriera**. Se movió a `core/`, se renombró su botón
> «Ejecutar» → «Aceptar» y se envolvió en un servicio. Ya está en uso real.

```ts
// API pública
confirmar(data: ConfirmacionData): Observable<boolean>
eliminacion(queSeElimina: string, consecuencia?: string): Observable<boolean>

interface ConfirmacionData {
  titulo: string;
  mensaje: string;
  consecuencia?: string;      // qué va a pasar de verdad
  textoAceptar?: string;      // por defecto 'Aceptar'
  tono?: 'peligro' | 'normal';
}
```

Emite **una** vez y se completa: no hace falta desuscribirse. Cancelar, `Esc` y el clic fuera
devuelven `false`, así que **no hacer nada es siempre lo seguro**.

```ts
this.confirmar.eliminacion(
  `el producto «${p.nombre}»`,
  'El producto y sus variantes dejarán de mostrarse en la tienda. No se borra nada: ' +
  'su histórico de ventas se conserva y puedes restaurarlo marcando «Activo» desde Modificar.'
).subscribe(ok => { if (ok) this.borrar(p); });
```

**Escribe siempre la `consecuencia`.** El modelo es el bloque de anulación de Ajustes de
Inventario, que dice «Se registrará un contramovimiento en el kardex que revierte el stock».
Un «¿Está seguro?» pelado no informa de nada: el usuario ya sabe que pulsó el botón. Y cuando
la baja es **lógica**, decirlo es obligatorio — «eliminar» y «desactivar» no son lo mismo y el
usuario tiene derecho a saber cuál de las dos está ejecutando.

### 2.2 `<app-modo-form>` — el chip de modo (regla 3)

`core/components/modo-form/modo-form.component.ts`

```html
<app-modo-form [modo]="data.modo"></app-modo-form>
```

`ModoFormulario = 'nuevo' | 'actualizar' | 'eliminar' | 'consulta'` → pinta
«Modo Nuevo» (azul), «Modo Actualizar» (cian), «Modo Eliminar» (rojo), «Modo Consulta» (gris).

La nomenclatura es **literal y no se parafrasea**. «Editando cupón #12» informa del registro,
no del modo, y obliga a deducir el estado; ese era el defecto de las 8 pantallas que sí
rotulaban algo.

Va dentro del `<h2 mat-dialog-title>`, después del nombre de la entidad. El componente ya
neutraliza la regla global `.dubai-dialog .mat-mdc-dialog-title mat-icon`, que si no convertiría
el icono del chip en la insignia con degradado del título.

### 2.3 `<app-acciones-registro>` — las cuatro opciones (regla 2)

`core/components/acciones-registro/acciones-registro.component.ts`

```html
<app-acciones-registro
    entidad="producto" articulo="un"
    [haySeleccion]="!!filaSeleccionada"
    [nombreSeleccion]="filaSeleccionada?.nombre ?? null"
    [puedeEliminar]="!!filaSeleccionada?.activo"
    [motivoNoEliminable]="motivoProductoNoEliminable"
    (nuevo)="nuevoProducto()" (modificar)="modificarProducto()"
    (eliminar)="eliminarProducto()" (ver)="verProducto()">
</app-acciones-registro>
```

| Entrada | Para qué |
|---|---|
| `entidad`, `articulo` | Textos de ayuda («Selecciona **una variante** de la grilla») |
| `haySeleccion` | Habilita Modificar / Eliminar / Ver |
| `nombreSeleccion` | Muestra «✓ Seleccionado: X» — el usuario ve sobre qué va a actuar |
| `puedeEliminar` + `motivoNoEliminable` | Bloquea SOLO Eliminar, con su explicación en el tooltip |
| `mostrarNuevo/Modificar/Eliminar/Ver` | Ocultar una opción que la pantalla no ofrece |

**No contiene lógica de negocio**: solo emite eventos. Los tres botones dependientes se ven
**apagados, nunca ocultos** — así el usuario descubre que la selección es el paso previo sin
leer ninguna instrucción.

Una pantalla puede tener **varias barras** (Productos tiene dos: productos y variantes), cada
una con su propia selección.

### 2.4 `.btn-aceptar` / `.btn-cancelar` (regla 4)

Definidas en **`src/styles.scss`**, junto a `.btn-aplicar` y `.btn-limpiar`.

> **Por qué ahí y no en `operativo-shared.scss`**: el formulario del patrón es un DIÁLOGO, y el
> CDK lo monta en un overlay **fuera del árbol DOM del componente**. Una hoja con encapsulación
> de componente no lo alcanza. `operativo-shared.scss` lleva un comentario que apunta aquí.

```html
<mat-dialog-actions align="end">
  <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
  <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
</mat-dialog-actions>
```

`.btn-aceptar.peligro` lo pinta en rojo (lo usa el `ConfirmService` con `tono: 'peligro'`).

**Dos botones. Dos.** Nada de «Guardar y continuar», «Aplicar», «Emitir Orden» ni «Crear Cupón».
El diagnóstico contó **26 etiquetas distintas** para el mismo botón en 31 pantallas.

---

## 3. La baja lógica y el botón «Eliminar»

RetailMind **no borra registros de negocio**: desactiva (`PATCH .../activo` con `false`). Eso es
lo correcto para la integridad referencial — un producto con ventas no puede desaparecer. Pero
el cliente pide un botón «Eliminar», y el usuario merece saber qué hace.

La solución del molde, en tres partes:

1. El botón se llama **Eliminar** y hace la baja lógica.
2. La confirmación **dice que es lógica**: «No se borra nada: su histórico de ventas se conserva».
3. El formulario lleva una casilla **«Activo (si se desmarca, equivale a eliminar)»**, visible
   en Modo Actualizar y Modo Consulta, que es por donde se **restaura**.

El punto 3 no es decorativo: sin él, «Eliminar» sería un viaje de ida y se perdería la
reactivación que el `toggle_on/toggle_off` sí permitía. La alternativa —un quinto botón
«Restaurar»— rompería la regla 4.

Cuando el registro ya está inactivo, `puedeEliminar` es `false` y el tooltip explica por qué.

**`activo` viaja por su propio endpoint**, no en el cuerpo del PUT. El diálogo lo devuelve
aparte y la pantalla encadena las dos llamadas solo si la casilla cambió:

```ts
const { categoriaIds, activo, ...cambios } = res;
this.catalogo.editarProducto(original.id, cambios).pipe(
  switchMap(() => activo === original.activo ? of(null)
                                             : this.catalogo.activarProducto(original.id, activo))
).subscribe({ /* … */ });
```

---

## 4. La estructura de una pantalla alineada

Tres bloques, en este orden. Ver `productos-admin.component.html`.

```html
<div class="op-page">
  <div class="section-header"> <h2>…</h2> <p>…</p> </div>

  <!-- 1. CRITERIOS DE BÚSQUEDA (regla 1) — nunca un formulario de alta aquí -->
  <div class="op-card">
    <h3><mat-icon>search</mat-icon> Criterios de búsqueda</h3>
    <div class="form-grid"> … campos de filtro … </div>
    <div class="form-actions">
      <button class="btn-limpiar" (click)="limpiarFiltros()">Limpiar filtros</button>
      <button class="btn-limpiar" (click)="cargar()">Actualizar</button>
    </div>
  </div>

  <!-- 2. GRILLA + BARRA DE ACCIONES (regla 2) -->
  <div class="op-card">
    <h3><mat-icon>list</mat-icon> Catálogo ({{ total }})</h3>
    <app-acciones-registro …></app-acciones-registro>
    <div class="table-wrapper table-seleccionable">
      <table mat-table [dataSource]="filas">
        …
        <tr mat-row *matRowDef="let p; columns: columnas"
            (click)="seleccionarFila(p)"
            [class.fila-seleccionada]="filaSeleccionada?.id === p.id"></tr>
      </table>
    </div>
    <mat-paginator …></mat-paginator>
  </div>

  <!-- 3. (opcional) DETALLE del registro seleccionado, con su propia barra -->
</div>
```

Notas:

- **`.table-seleccionable`** (en `operativo-shared.scss`) pone el cursor de mano y la barra azul
  a la izquierda de la fila marcada. Sin señal visible, la selección no existe para el usuario.
- **La columna de acciones por fila desaparece.** Los iconos «editar / ver / toggle» dentro de la
  tabla se sustituyen por la barra. Ese era el patrón viejo y es lo que la regla 2 corrige.
- **Limpiar filtros / Actualizar no violan la regla 4**: son herramientas de la grilla, no
  botones de un formulario de acción.
- El formulario va **en un diálogo**, no en un bloque que empuja la tabla hacia abajo.

### Resincronizar la selección tras recargar

Después de `cargar()`, la fila seleccionada apunta a un objeto viejo. Hay que reapuntarla por id
o soltarla si ya no está en la página:

```ts
private resincronizarSeleccion(): void {
  if (!this.filaSeleccionada) return;
  const vigente = this.filas.find(f => f.id === this.filaSeleccionada!.id);
  this.filaSeleccionada = vigente ?? null;
}
```

Sin esto, «Eliminar» actúa sobre datos rancios después de cambiar de filtro o de página.

---

## 5. El diálogo de formulario

Ver `producto-dialog.component.ts` y `variante-dialog.component.ts`.

```ts
export interface XDialogData {
  registro?: XDetalle;              // presente en 'actualizar' y 'consulta'
  modo: ModoFormulario;
}
export type XDialogResultado = XBody & { activo: boolean };
```

```html
<h2 mat-dialog-title>
  <mat-icon>inventory_2</mat-icon>
  Producto
  <app-modo-form [modo]="data.modo"></app-modo-form>
</h2>
<mat-dialog-content>
  <mat-form-field appearance="outline">
    <mat-label>Nombre</mat-label>
    <input matInput [(ngModel)]="form.nombre" [disabled]="soloLectura" required>
  </mat-form-field>
  …
  <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
    Activo (si se desmarca, equivale a eliminar)
  </mat-checkbox>
</mat-dialog-content>
<mat-dialog-actions align="end">
  <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
  <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
</mat-dialog-actions>
```

```ts
get esNuevo(): boolean    { return this.data.modo === 'nuevo'; }
get soloLectura(): boolean { return this.data.modo === 'consulta'; }
get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

cancelar(): void { this.dialogRef.close(); }
aceptar(): void {
  if (this.soloLectura) { this.dialogRef.close(); return; }  // consulta: solo cierra
  if (this.valido) this.dialogRef.close(this.form);
}
```

- Se abre siempre con `panelClass: 'dubai-dialog'`.
- En **Modo Consulta** todo va `[disabled]` y `Aceptar` solo cierra, sin devolver nada. La
  pantalla distingue el caso porque `afterClosed()` emite `undefined`.
- En **Modo Actualizar** se precarga TODO. Ningún campo debe llegar vacío obligando a reescribir:
  por eso la pantalla pide el detalle completo *antes* de abrir el diálogo.

---

## 6. Receta para alinear la siguiente pantalla

1. Mover el formulario de alta/edición a un **diálogo** (`XDialogComponent`) con `modo`,
   `<app-modo-form>` y los dos botones. Si la pantalla ya tenía el formulario embebido, este es
   el paso que más cuesta.
2. Añadir **criterios de búsqueda** al primer `op-card` y dejar la grilla en el segundo.
3. Meter `<app-acciones-registro>` encima de la tabla y **borrar la columna de acciones por fila**.
4. Hacer la fila seleccionable: `(click)`, `[class.fila-seleccionada]`, `.table-seleccionable`,
   y `resincronizarSeleccion()` al recargar.
5. Convertir el `toggleActivo()` en `eliminarX()` con `ConfirmService.eliminacion(...)` y añadir
   la casilla «Activo» al diálogo para poder restaurar.
6. Comprobar: `ng build`, y en el navegador el alta, la edición, el chip en los tres modos, y
   Eliminar **aceptando y cancelando**.

**Pantallas fuera de alcance** (acordado con el cliente): Despachos, Recepciones, Transferencias,
Ajustes y Preparación. Son pantallas de **proceso**, no de mantenimiento: no tienen registro que
dar de alta ni de baja, sino transiciones de estado con compuertas de negocio. Se documentan
aparte con su justificación; no se les aplica este patrón.

---

## 7. Trampas encontradas al construir el molde

1. **Una pieza compartida que nadie invoca es código muerto.** `ConfirmDialogComponent` llevaba
   meses completo y sin un solo `dialog.open()`. Al terminar una fase, comprobar que la pieza
   está **usada**, no solo creada.
2. **El diálogo vive en el overlay del CDK.** Las clases de sus botones tienen que ser globales;
   una hoja con encapsulación de componente no llega hasta ahí. (§2.4)
3. **El título Dubai pinta cualquier `mat-icon` descendiente como insignia con degradado.** El
   chip de modo tiene que neutralizarlo o su icono sale deformado.
4. **`codigo_barras` es UNIQUE y `''` NO es NULL.** Mandar cadena vacía desde el formulario hacía
   que la **segunda** variante sin código de barras rebotara con un 400 del motor. En Postgres
   los NULL no colisionan entre sí, pero dos cadenas vacías sí. Bug **preexistente** (el código
   anterior mandaba el mismo `''`), detectado al probar el molde y corregido en
   `variante-dialog.component.ts`: sin código se manda `null`.
5. **`activo` no va en el cuerpo del PUT.** Tiene su propio endpoint; el diálogo lo devuelve
   aparte y la pantalla encadena. (§3)

---

## 8. Trampas encontradas al alinear las nueve pantallas de la Fase 1

6. **El filtrado en cliente NO puede vivir en un getter.** `get filas() { return
   this.todas.filter(...) }` devuelve **un array nuevo en cada ciclo de detección de cambios**, y
   `mat-table` repinta la tabla entera cada vez. Se recalcula a mano en un campo:

   ```ts
   private todas: X[] = [];        // lo que llega del backend
   filas: X[] = [];                // lo que se pinta
   aplicarFiltros(): void { this.filas = this.todas.filter(...); this.resincronizarSeleccion(); }
   ```

   Se llama desde el `next` de `cargar()` y desde cada `(ngModelChange)` / `(selectionChange)`.
   Las nueve pantallas filtran en cliente porque sus endpoints devuelven la lista entera
   (decenas de filas). El día que una crezca, el filtro se sube al servidor como en Productos.

7. **Un filtro sobre una FK nullable necesita TRES estados, no dos.** «Todas», «sin
   padre / sin campaña» y «esta en concreto» son cosas distintas, y `null` ya está ocupado por
   la segunda. El tipo del filtro es `number | null | 'todos'`:

   ```ts
   filtroPadreId: number | null | 'todos' = 'todos';
   if (this.filtroPadreId !== 'todos' && c.categoria_padre_id !== this.filtroPadreId) return false;
   ```

8. **«Vigente» y «activo» son ejes INDEPENDIENTES.** Un cupón puede estar activo y caducado a
   la vez (el motor lo rechaza igual en el canje), o eliminado y todavía en ventana. La grilla
   ofrece los dos criterios por separado y la vigencia se calcula al vuelo —no es una columna—
   con `vigenciaDe()` en `marketing/vigencia.util.ts`, compartido por cupones, promociones,
   banners y campañas.

9. **No toda entidad tiene bandera `activo`.** La **campaña** no la tiene: su ciclo es el
   `estado` (borrador → activa → pausada → **finalizada**), y `finalizada` es TERMINAL —el
   backend rechaza cualquier cambio posterior—. Consecuencias para el patrón:
   - «Eliminar» = **finalizar**, y el mensaje **NO promete restauración**: dice que no se puede
     reactivar. Prometer un «restáuralo desde Modificar» que no existe sería mentir.
   - `puedeEliminar` mira `estado !== 'finalizada'`, no `activo`.
   - **Antes de escribir el diálogo, mira si la entidad tiene `activo`**: el backend es la
     autoridad, no la suposición de que todas se parecen.

10. **Las transiciones de ciclo de vida caben DENTRO de Modificar.** Campañas tenía tres
    botones sueltos en la fila (play / pause / stop) que rompían la regla 4. Ahora el `estado`
    es un campo más del formulario y se encadena por su propio PATCH, igual que `activo`:

    ```ts
    const { estado, ...cambios } = res;
    this.marketing.editarCampana(id, cambios).pipe(
      switchMap(() => estado === original.estado ? of(null)
                                                 : this.marketing.estadoCampana(id, estado))
    ).subscribe({ /* … */ });
    ```

    La funcionalidad no se pierde y la barra sigue teniendo cuatro opciones.

11. **Una ASOCIACIÓN no es un registro.** `promocion_producto` es un enlace: no tiene campos que
    modificar ni ficha que consultar. Su barra declara `[mostrarModificar]="false"` y
    `[mostrarVer]="false"` — el componente lo soporta y el resto no se desordena. Y su borrado
    **es FÍSICO** (`DELETE`, no hay bandera que devolver), así que el mensaje lo dice en
    mayúsculas: «ESTA ACCIÓN NO SE PUEDE DESHACER». **Comprueba siempre si el endpoint es un
    PATCH de `activo` o un DELETE de verdad antes de redactar la consecuencia.**

12. **Si falta el endpoint, se declara la limitación en vez de forzar la regla.** El backend no
    expone edición de suscriptor —un suscriptor ES su email—, así que en Newsletter «Modificar»
    solo gobierna el estado de la suscripción, con el email en solo lectura y un `mat-hint` que
    lo explica. Ese Modificar reducido es además **la única vía de reactivación**, que si no se
    habría perdido al sustituir el toggle.

13. **`mat-dialog-content` recorta el panel del autocompletado.** Un `<app-select-buscable>`
    dentro de un diálogo despliega su panel por debajo del borde y queda cortado: hay que poner
    `mat-dialog-content { overflow: visible; }` en los estilos de ese diálogo.

### Coste real de la Fase 1

Nueve pantallas, **10 archivos de diálogo nuevos + 1 utilidad compartida**, y **cero
componentes o servicios nuevos en `core/`**: las cuatro piezas de la Fase 0 cubrieron todo,
incluidos los dos casos raros (campaña sin `activo`, asociación sin ficha). La barra de
acciones no se tocó ni una vez — sus `mostrarX` y `puedeEliminar` bastaron.

---

## 9. Trampas encontradas al alinear las cinco pantallas de la Fase 2

14. **Si falta el endpoint y la regla es importante, se CONSTRUYE el endpoint.** La §8.12
    (Newsletter) dice que una limitación se declara en vez de forzarse, pero eso vale cuando
    la limitación tiene una razón de negocio. En **Usuarios** no la tenía: no poder corregir el
    rol de un usuario creado no era una decisión, era un hueco. Se añadieron
    `PUT /api/auth/usuarios/{id}` y `PATCH /api/auth/usuarios/{id}/activo`. **Criterio para
    distinguir los dos casos**: pregúntate si el backend *decidió* no exponerlo (grants por
    columna, estado terminal, entidad que es su propia clave) o si simplemente *no llegó a
    escribirse*. Lo primero se declara; lo segundo se construye.

15. **Antes de escribir la consecuencia de «Eliminar», mira las CLAVES FORÁNEAS, no solo el
    endpoint.** `/admin-usuarios` llamaba a un `DELETE` que borra físicamente… y que **no podía
    funcionar**: `usuario` tiene 32 FK apuntando a ella, y `cliente.usuario_id` es `RESTRICT`
    más cinco `NO ACTION` (`meta_venta.fijada_por`, `item_defectuoso.registrado_por`,
    `novedad_envio.*`, `devolucion_proveedor.registrado_por`,
    `historial_devolucion_proveedor.usuario_id`). Fallaba con **todos** los clientes y con casi
    todo el personal. La consulta que lo revela:

    ```sql
    SELECT tc.table_name, kcu.column_name, rc.delete_rule
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu USING (constraint_name)
    JOIN information_schema.referential_constraints rc USING (constraint_name)
    JOIN information_schema.constraint_column_usage ccu USING (constraint_name)
    WHERE tc.constraint_type = 'FOREIGN KEY' AND ccu.table_name = 'usuario';
    ```

    Un `DELETE` que la BD va a rechazar no es «borrado físico», es un 500. La pantalla hace la
    **baja lógica** (`activo = false`), que además es lo que de verdad cierra el acceso: el
    login lo rechaza con `USUARIO_INACTIVO` (§3).

16. **Las guardias del backend se anticipan en el FORMULARIO, no se descubren al Aceptar.** El
    servidor rechaza con 409 cambiar el rol del admin semilla y convertir un CLIENTE en personal
    interno (su ficha de `cliente` y sus pedidos quedarían huérfanos). El diálogo deja el
    selector de rol `[disabled]` con un `mat-hint` que lo explica, y la barra apaga «Eliminar»
    con su motivo cuando el registro es el admin del sistema o **tu propia cuenta**. La regla
    sigue viva en el backend —quien llame por API recibe su 409—; la interfaz solo evita el
    viaje en balde.

17. **Un diálogo con `<app-select-buscable>` necesita `autoFocus: false`.** `MatDialog`
    enfoca el primer elemento tabulable, y si ese elemento es el autocompletado, **su panel se
    abre solo y tapa los botones Aceptar/Cancelar**. Con 1.221 productos el panel ocupa la
    pantalla entera y el formulario parece no tener botones. Es la hermana de la §8.13 (que
    trataba el recorte del panel) y aparece en el ALTA del cliente de Reseñas y de Preguntas:

    ```ts
    this.dialog.open(XDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false });
    ```

18. **Cuando «Modificar» reúne varias acciones, hay que encadenarlas y llamar solo a lo que
    cambió.** Preguntas de Productos tenía tres botones sueltos —Publicar, Rechazar, Responder—
    y son DOS endpoints distintos. Dentro de Modificar se resuelven como `activo` en el resto
    de pantallas (§3), pero con dos condiciones en vez de una:

    ```ts
    const primero: Observable<unknown> = hayRespuesta
      ? this.servicio.responderPregunta(id, respuesta) : of(null);
    primero.pipe(switchMap(() => cambioEstado
      ? this.servicio.moderarPregunta(id, estado) : of(null))).subscribe({ /* … */ });
    ```

    **Tipa el primer observable como `Observable<unknown>`**: la unión de
    `Observable<{id:number}>` con `Observable<null>` no es invocable y `ng build` falla con un
    TS2349 que no señala la línea culpable.

19. **«Eliminar» puede no ser el único camino a la acción destructiva.** En Horarios, la casilla
    «Activa» de Modificar desactiva la ventana igual que el botón Eliminar. **El riesgo no
    depende del botón por el que se llegue**, así que `guardar()` detecta el paso
    `activo: true → false` y lanza la MISMA confirmación:

    ```ts
    if (original.activo && !res.activo) { this.confirmarCierre(original, () => aplicar()); return; }
    ```

20. **La consecuencia se CALCULA, no se redacta una vez.** El mensaje de Horarios cuenta cuántas
    ventanas activas le quedan al rol y cambia de tono si es la última: «⚠ ES LA ÚLTIMA VENTANA
    ACTIVA DE ESTE ROL: sus usuarios quedan fuera del sistema TODOS LOS DÍAS». Un texto fijo
    diría lo mismo en el caso leve y en el catastrófico. También se añadió un aviso PERMANENTE
    en la pantalla (`.aviso-critico`) que lista los roles que ya se quedaron sin ninguna ventana
    activa: ese estado hay que verlo al entrar, no al abrir un diálogo.

21. **Una pantalla puede tener acciones que NO son ninguna de las cuatro, y entonces se
    declaran.** En Reseñas, el voto de utilidad y el reporte de abuso operan sobre la opinión de
    OTRO cliente: no son mantenimiento de un registro propio. Viven junto a la grilla como
    herramientas de la selección —igual que «Limpiar filtros» (§4)— y el reporte, que sí es un
    formulario, abre su diálogo con modo y dos botones. Cuando falte una de las cuatro opciones,
    la pantalla lo dice con `.nota-limitacion` (clase nueva en `operativo-shared.scss`) en vez
    de dejar el hueco: un botón ausente se lee como un olvido; una nota se lee como una decisión.

22. **Mojibake y listas blancas desincronizadas se ven al leer el archivo, no al usarlo.**
    `horarios.component.ts` tenía `'MiÃ©rcoles'` y `'SÃ¡bado'` en el array de días, y ni el
    frontend ni `HorariosAdminService.ROLES_VALIDOS` incluían **`grp_soporte`** (script 37),
    aunque la tabla ya tenía sus 7 ventanas sembradas: crear una ventana para soporte era
    imposible desde la interfaz. Al alinear una pantalla, **compara sus listas blancas contra la
    BD** (`SELECT DISTINCT rol_grupo FROM grupo_horario`, `SELECT codigo FROM rol WHERE activo`)
    — en Usuarios el desplegable ofrecía 8 de los 9 roles por el mismo motivo. Mejor aún:
    Usuarios ya no lleva lista, la pide a `GET /api/auth/roles`.

23. **La prueba de una pantalla de seguridad se hace con la víctima, no con el admin.** Horarios
    se verificó cerrando la ventana de HOY de `grp_vendedor` desde la interfaz y comprobando por
    API que `vendedor@retailmind.com` pasaba de 200 a **401**, que el ADMIN seguía operando
    (está exento) y que restaurar desde Modificar lo devolvía a 200. La prueba lleva una red de
    seguridad en un `finally` que reabre cualquier ventana que quedara cerrada: un fallo a mitad
    de la prueba no puede dejar a un rol fuera del sistema.

### Coste real de la Fase 2

Cinco pantallas, **6 diálogos nuevos**, **1 servicio Angular de 25 líneas**, **2 clases de
backend** (`UsuarioAdminService` + 4 endpoints en `AuthController`) y **dos clases compartidas
nuevas en `operativo-shared.scss`** (`.nota-limitacion`, `.aviso-critico`). Otra vez **cero
componentes o servicios nuevos en `core/`**: las cuatro piezas de la Fase 0 han cubierto las 15
pantallas sin una sola modificación.
