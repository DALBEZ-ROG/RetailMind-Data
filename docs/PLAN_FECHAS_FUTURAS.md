# Plan — traer al pasado las fechas del catálogo de demostración

**Estado**: PENDIENTE DE EJECUCIÓN. Escrito el 2026-08-21 para empezar por aquí
en la siguiente sesión. Nada de esto se ha aplicado todavía.

---

## 1. El problema, medido

La carga masiva sembró **una década de operación, 2025-01-01 → 2034-12-31**.
Hoy es **2026-08-21**, así que el sistema enseña como hechos consumados
pedidos, facturas, envíos y cobros de hasta **ocho años en el futuro**.

| medida | valor |
|---|---|
| pedidos con fecha futura | **2.526.562 de 2.999.995 — el 84 %** |
| ventana de `pedido` | 2025-01-01 → 2034-12-31 |
| fecha máxima de TODO el sistema | **2035-03-23** (moderación de reseñas) |
| columnas de fecha en el esquema | 207 |
| columnas **con valores futuros** | **53, en 27 tablas** |
| valores futuros a mover | **~62 millones** |

Ya está causando daño visible: en «Mis Pedidos» el pedido más reciente es de
2034, y una compra hecha hoy queda enterrada entre ocho años de historial
posterior (defecto **D-15**, corregido a medias: se arregló el orden, no la
causa).

Las 53 columnas, por volumen:

```
historial_estado_pedido.fecha_creacion   17.035.957      pedido.fecha_pedido        2.526.562
movimiento_inventario.fecha_creacion      6.689.497      pedido.fecha_creacion      2.526.562
pedido_detalle.fecha_creacion             6.419.824      pago.fecha_pago            2.417.902
factura_venta_detalle.fecha_creacion      6.111.224      factura_venta.fecha_emision 2.405.277
seguimiento_envio.fecha_evento            5.329.399      envio.fecha_despacho       1.778.256
… y 43 más (envío, compras, devoluciones, tickets, reseñas, reembolsos)
```

**Excepción**: las cuatro últimas filas de la lista —`cupon.fecha_fin`,
`banner.fecha_fin`, `campana.fecha_*`, `promocion.fecha_fin`, 17 valores en
total— son fechas de VIGENCIA de marketing vigente hoy. Son futuras **a
propósito** y **no se tocan**.

---

## 2. Por qué toca más de lo que parece

Cinco cosas que no se ven mirando la tabla `pedido`:

1. **Los números de documento llevan la fecha dentro.** `PED-20341113-5655830`,
   `FV-20270930-…`, `OC-20250117-…`, `GUIA-20261017-…`, `TK-20250104-…`.
   Mover la fecha sin renumerar deja un pedido llamado «20341113» fechado en
   2025. Es visible en pantalla y **la propia suite P14 lee la fecha del número**
   para comprobar el orden.
2. **El kardex se encadena por `(fecha_creacion, id)`**, no por id. Cualquier
   transformación que altere el ORDEN RELATIVO de los movimientos invalida
   `stock_anterior`/`stock_nuevo` de toda la cadena. Una transformación
   **estrictamente monótona y uniforme** lo preserva; una compresión no lineal,
   no.
3. **El almacén analítico (21 tablas) y los dos modelos se derivan de estas
   fechas.** `dim_fecha` es un calendario generado para un rango fijo; la
   previsión de demanda ancla en el último mes cerrado; la alerta de abandono
   ancla en `max(fecha_pedido)`. Todo hay que recalcularlo, no parchearlo.
4. **48 disparadores `touch`** reescriben `fecha_actualizacion` en cada UPDATE.
   Si se dejan activos, el propio arreglo estampa `now()` en 27 tablas.
   Conviven con 34 `trg_horario_*` y 3 `trg_kardex_*`, que también se disparan.
5. **`meta_venta` tiene 133 metas fechadas** (2025-01 → 2026-08). Si las ventas
   se mueven y las metas no, OTD-VEN-15 compara contra el mes equivocado.

---

## 3. Las cuatro salidas, con su consecuencia medida

| # | Qué | Consecuencia |
|---|---|---|
| **A** | **Desplazar TODO al pasado** con un intervalo único | Conserva los 3.000.000 de pedidos, todos los intervalos (tránsito, plazos de pago, SLA) y todos los invariantes. Hay que renumerar documentos y recargar el almacén. |
| B | Comprimir la década en el tiempo ya transcurrido | **Distorsiona los intervalos**: un envío de 3 días pasa a 2,4. Rompe los KPI de logística y de cuentas por pagar, que son medidas de duración. Descartada. |
| C | Borrar lo futuro | Se van **2.526.562 pedidos (84 %)** y con ellos el sentido de la carga masiva, el benchmark columnar y los modelos. Descartada. |
| D | No tocar nada y declararlo como proyección | Cuesta cero, pero el sistema sigue afirmando que cobró facturas en 2032. Es lo que hay hoy. |

### Recomendación: A, con desplazamiento de **9 años exactos**

| desplazamiento | ventana resultante | filas que seguirían en el futuro |
|---|---|---|
| 8 años | 2017-01-01 → 2026-12-31 | **126.426 pedidos** (sep–dic de 2026) |
| **9 años** | **2016-01-01 → 2025-12-31** | **0** |

Nueve años, y no una cifra ajustada al día de hoy, por dos razones:

- **Es un número entero de años, así que cada fila conserva su mes.** La
  estacionalidad del seed está escrita POR MES (diciembre 1,48) y es lo que
  sostiene el modelo de previsión; un desplazamiento en días la movería de mes
  y dejaría la documentación mintiendo.
- **Deja libres enero–agosto de 2026**, que es justo donde caen las compras
  reales hechas desde la aplicación. Un pedido de hoy pasa a ser, de verdad, el
  más reciente — que es lo que se pedía.

**Lo que cuesta, dicho antes de hacerlo**: no quedará actividad sembrada en
2026. Las pantallas ancladas a «hoy» (OTD-GER-01, que ya declara «día sin
movimiento») mostrarán el último movimiento en diciembre de 2025, y la meta de
agosto de 2026 se quedará sin ventas sembradas contra las que medir. Si se
prefiere tener datos más cerca de hoy, la alternativa es **8 años y 8 meses**
(ventana hasta 2026-04-30, cero filas futuras), a cambio de que cada mes se
desplace ocho posiciones y la estacionalidad documentada cambie de nombre.

**Decisión pendiente del usuario.** Lo demás de este plan vale para cualquiera
de las dos.

---

## 4. Procedimiento

Script SQL numerado **112** (`retailmind/sql/postgres/`), con su
`99_revert_fechas.sql`. Todo dentro de UNA transacción.

### Paso 0 · Respaldo y medición previa

- `pg_dump` completo a `deploy/` antes de nada. Con 17 GB no es instantáneo;
  es la marcha atrás real si algo sale mal a mitad.
- Guardar en `seed_backup.fec112_*` las **huellas md5** de las tablas que NO
  deben cambiar de contenido salvo en sus fechas (`pedido`, `factura_venta`,
  `pago`, `movimiento_inventario`), calculadas sobre las columnas NO temporales.
  Es lo que permitirá demostrar que solo se movieron fechas.
- Registrar el desplazamiento elegido en una tabla propia
  (`seed_backup.fec112_parametro`), porque la reversión lo necesita y porque
  sin él nadie sabrá dentro de un mes cuánto se movió.

### Paso 1 · Desactivar los disparadores

`SET session_replication_role = replica` para la sesión del script. Apaga los
93 disparadores de usuario de golpe, incluidos los 48 `touch`. **Al terminar se
vuelve a `origin` y se CUENTAN** los 34 `trg_horario_*`, 3 `trg_kardex_*` y 95
políticas RLS, que es la comprobación que este proyecto ya usa como defensa.

### Paso 2 · Desplazar las 49 columnas

Las 53 menos las 4 de vigencia de marketing. Una sola sentencia por tabla,
`UPDATE … SET col = col - :desplazamiento`, en orden indiferente: la
transformación es uniforme, así que ninguna restricción entre tablas se rompe a
mitad (las FK no son temporales y los CHECK de fecha son de fila, no de par).

Ojo con `date` vs `timestamptz`: `fecha_vencimiento` y `fecha_emision` de
compras son `date` en algunas tablas; el intervalo en años funciona igual, pero
el cast debe ser explícito para no perder el tipo.

### Paso 3 · Renumerar los documentos

Sustituir los ocho dígitos de fecha del `numero` por los de la fecha ya
desplazada, con `regexp_replace`. Afecta a `pedido`, `factura_venta`,
`orden_compra`, `factura_compra`, `envio.numero_guia`, `ticket_soporte`,
`devolucion`, `devolucion_proveedor` y `reembolso`.

**La unicidad se conserva sola y conviene entenderlo antes de ejecutarlo**: el
desplazamiento es uniforme, luego la parte de fecha se transforma de forma
inyectiva; dos números que se diferenciaban siguen diferenciándose, y dos que
compartían fecha siguen compartiéndola. No hay que reasignar secuencias. Aun
así, el script **verifica** el conteo de duplicados antes de confirmar.

### Paso 4 · Mover `meta_venta`

Las 133 metas históricas, con el mismo desplazamiento. La de agosto de 2026 que
se fijó a mano el 2026-08-17 **no se mueve**: es una decisión con fecha, no un
dato sembrado (ficha **C-21**).

### Paso 5 · Recargar el almacén

- Ampliar el rango de `dim_fecha` para que cubra la ventana nueva.
- Correr el DAG completo: **22 tareas, ~12 min**, y exigir **49/49 controles**.
- Los dos modelos se recalculan solos dentro de esa corrida.

### Paso 6 · Verificar

| qué | cómo | criterio |
|---|---|---|
| no queda futuro | el mismo barrido de las 207 columnas de este documento | 0 filas futuras salvo las 17 de marketing |
| el dinero no se movió | huellas md5 del paso 0 sobre columnas no temporales | idénticas |
| el kardex sigue sano | `p06_invariantes.py` | cadena íntegra, arranque en 0, cierre = `inventario` en las 11.407 posiciones |
| el cuadre contable | `p06_invariantes.py` | facturas − pagos = CxP, descuadre $0,00 |
| el almacén | `validar_dwh.py` | 49/49 |
| las pantallas | `p11_interfaz.js` y `p14_tienda.js` | 65/65 y 86/86 |
| los intervalos | media de días de tránsito, plazo de pago y SLA antes/después | iguales al decimal |

### Paso 7 · Documentar

`CLAUDE.md` (la tabla de «LA CARGA MASIVA» dice **ventana temporal 2025-01 →
2034-12** y hay que corregirla, más las cifras que citen años),
`docs/BENCHMARK_COLUMNAR.md`, `docs/pruebas/DEFECTOS.md` (cerrar del todo
**D-15**) y este plan, que pasa a ejecutado.

---

## 5. Riesgos

1. **Es la operación más destructiva hecha hasta ahora sobre esta base**: 62
   millones de valores en 27 tablas. Sin el `pg_dump` del paso 0 no hay vuelta
   atrás real, porque la reversión por script depende de que el propio script
   haya terminado bien.
2. **Los disparadores desactivados son un momento de fragilidad.** Si el script
   aborta entre el paso 1 y el final, la sesión termina y `session_replication_
   role` vuelve solo —es de sesión, no global—, pero conviene comprobarlo
   explícitamente antes de dar nada por bueno.
3. **Doce minutos de ETL con el almacén a medias**: durante la recarga, los
   informes compuestos sirven la corrida anterior (así está diseñada la carga
   atómica), así que no hay pantalla rota, pero las cifras del almacén y las de
   PostgreSQL no cuadrarán hasta que termine.
4. **`fact_eventos` de ClickHouse (2.823.245 filas) NO se toca**: es de la base
   legada, no lleva estas fechas y su volumen es irreproducible.

---

## 6. Orden de trabajo para mañana

1. Confirmar el desplazamiento: **9 años** (recomendado) o 8 años y 8 meses.
2. `pg_dump` completo.
3. Escribir y aplicar el script 112 con su reversión.
4. Renumerar documentos.
5. Recargar el almacén y exigir 49/49.
6. Pasar las cuatro suites de verificación.
7. Actualizar la documentación y cerrar D-15.

Estimación: **media jornada**, con la recarga del almacén como el tramo largo.
