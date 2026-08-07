# Propuesta de reestructuración de `DEUDA_TECNICA.md`

> **Estado: PROPUESTA. No se ha tocado `DEUDA_TECNICA.md`.**
> Este documento propone el formato nuevo, muestra entradas migradas de ejemplo y
> deja el inventario completo ya clasificado. Fecha del levantamiento: **2026-08-06**.

---

## 1. Por qué cambiar el formato

El archivo actual es **una tabla única de 37 filas «Vigente»** con una columna
`Severidad` (Alta/Media/Baja) y cuatro secciones de «Resuelto» acumuladas por fase.
Tres problemas concretos:

1. **Mezcla tres cosas que se defienden distinto.** «No hay bodega de cuarentena
   física» (decisión de alcance) y «el kardex no está protegido por el motor»
   (fragilidad real) comparten severidad `Baja` y la misma tabla. En una
   sustentación la primera se defiende y la segunda se explica; el documento no
   distingue.
2. **`Severidad` no responde la pregunta que importa.** Para un proyecto académico
   cerrado, lo que decide es *«¿esto es un fallo, un límite declarado, o algo que
   hay que saber?»*, no si es Alta o Baja.
3. **Hay filas obsoletas dentro de «Vigente».** Una está marcada **RESUELTO en su
   propia celda de descripción** (`Defectuoso queda como merma documental`) y sigue
   en la tabla de vigentes; otras cuatro se verificaron hoy como ya cerradas.

## 2. Estructura propuesta

Tres secciones de primer nivel, más un archivo histórico al final:

```
# Deuda técnica — RetailMind

## A. DEFECTOS            — se comporta mal. Debería arreglarse.
## B. FUERA DE ALCANCE    — no implementado a propósito. Se defiende, no se disculpa.
## C. FRAGILIDADES CONOCIDAS — funciona hoy, apoyado en algo que puede romperse sin aviso.

## D. Resuelto (histórico por fase)   ← lo que ya existe, sin cambios
```

Cada entrada usa la misma ficha de seis campos:

| Campo | Para qué sirve |
|---|---|
| **Qué es** | Una frase. El hecho, sin adjetivos. |
| **Dónde** | Archivo y línea, o tabla/objeto de BD. Verificable. |
| **Si no se toca** | La consecuencia real, no la hipotética. |
| **Costaría** | Orden de magnitud del arreglo (columna / trigger / fase). |
| **Sustentación** | `Sí — <cómo se cuenta>` / `No`. |
| **Verificado** | Fecha + la consulta o el grep que lo respalda. |

El campo **Verificado** es el que evita que el archivo vuelva a envejecer: obliga a
citar la evidencia, no la memoria.

### Por qué `Severidad` desaparece

La categoría ya carga esa información: todo lo de **B** es intencional (severidad
irrelevante) y todo lo de **C** funciona hoy (severidad = 0 hasta que el supuesto
se rompe). Solo **A** admitiría severidad — y hoy **A tiene 2 entradas**, con lo que
una escala de tres niveles sobre dos filas no aporta nada.

---

## 3. Tres entradas migradas (ejemplo)

### A-1 · La restauración del 24/7 dejó una ventana desviada · DEFECTO

- **Qué es**: `grp_analista`, domingo (`dia_semana = 0`), quedó en
  `00:00:00–23:30:00` en vez de `00:00:00–24:00:00`. Es la **única** fila desviada de
  las 56. El rol queda bloqueado **30 minutos de cada 10.080**, los domingos de 23:30
  a medianoche.
- **Dónde**: `grupo_horario`, fila `id = 54`.
- **Si no se toca**: si la demostración cae un domingo después de las 23:30, las
  pantallas del analista salen **vacías y sin un solo mensaje de error** —
  `pol_horario` es `cmd = ALL` y ALL incluye SELECT, así que RLS filtra en silencio.
  Es exactamente el fallo mudo que el script 88 se escribió para evitar.
- **Costaría**: un `UPDATE` de una fila, o volver a correr
  `90_horario_demo_restaurar.sql` (que además valida el resultado y aborta si algún
  rol conserva un minuto bloqueado).
- **Sustentación**: **Sí**, y conviene contarlo en positivo — el script 90 tiene un
  guardia que detecta justamente esto; lo que falló fue no ejecutarlo al final.
- **Verificado** (2026-08-06):
  ```sql
  SELECT rol_grupo, count(*) FILTER (WHERE NOT (hora_inicio='00:00' AND hora_fin='24:00'))
  FROM grupo_horario GROUP BY rol_grupo;   -- grp_analista: 1, el resto: 0
  ```
  El respaldo `seed_backup.hor88_grupo_horario_20260806` muestra esa fila en `23:59`
  **antes** del 88, y el 88 escribe `24:00` — luego el `23:30` se escribió **después**,
  por la pantalla de admin (`HorariosAdminService:61`), no por los scripts.

> *Nota de migración*: esta entrada **no existe** en el archivo actual. Es un hallazgo
> de este levantamiento.

---

### B-1 · Trazabilidad por lote y vencimiento (FEFO) · FUERA DE ALCANCE

- **Qué es**: la tabla `lote` existe y está vacía; no hay captura de lote en la
  recepción, ni stock por lote, ni salida FEFO en el despacho.
- **Dónde**: tabla `lote` (0 filas); FK ya previstas en
  `movimiento_inventario.lote_id` y `recepcion_detalle.lote_id`.
- **Si no se toca**: nada. El flujo retail general no lo necesita; el sistema es
  coherente sin ello.
- **Costaría**: una fase propia — toca recepción, inventario, kardex y despacho.
- **Sustentación**: **Sí**, como decisión de alcance: *se evaluó, se dimensionó y se
  pospuso*, con las FK dejadas listas. Eso es diseño, no omisión.
- **Verificado** (2026-08-06): `SELECT count(*) FROM lote;` → **0**.

> *Nota de migración*: en el archivo actual es la fila `Base (pre-fases) | Tabla lote
> huérfana | Media`. **Cambia de naturaleza**: hoy está catalogada como deuda
> `Media`; en realidad es un límite declarado, y la severidad la hacía parecer un
> pendiente.

---

### C-1 · El kardex no lo protege el motor, y hay dos implementaciones · FRAGILIDAD

- **Qué es**: el encadenamiento `stock_anterior → stock_nuevo` (hoy **1.406 de 1.406
  posiciones cuadradas**) no lo garantiza ningún trigger, restricción ni procedimiento.
  Lo sostiene el código de aplicación. Y hay **dos** escrituras del kardex: solo una
  consulta `tipo_movimiento.factor`; la otra codifica el signo a mano.
- **Dónde**:
  - `StockService.java:43-57` — lee `factor` y aplica `stockAnterior + factor * cantidad`.
  - `ComprasService.java:338` — `int stockNuevo = stockAnterior + it.cantidadRecibida();`
    (signo `+` fijo, nunca consulta `factor`).
  - Únicos dos `INSERT INTO movimiento_inventario` del backend
    (`StockService:69`, `ComprasService:342`).
- **Si no se toca**: hoy nada — `entrada_compra` tiene `factor = +1` y coinciden. Si
  alguien apuntara ese tipo de movimiento a un factor `-1`, la copia de Compras
  **invertiría el kardex sin error**: los CHECK de la tabla solo exigen
  `cantidad > 0`, `stock_anterior >= 0` y `stock_nuevo >= 0`, ninguno verifica que
  `stock_nuevo = stock_anterior ± cantidad`.
- **Costaría**: un trigger `BEFORE INSERT` que calcule `stock_nuevo` desde `factor`, o
  unificar las dos rutas en `StockService`. Lo primero es más barato y cierra también
  cualquier escritura futura.
- **Sustentación**: **Sí** — es el mejor ejemplo del proyecto de *dónde termina la
  seguridad de motor*: todo lo demás (privilegios, RLS, horario) está en la BD, y el
  invariante del kardex es la excepción que vive en la aplicación.
- **Verificado** (2026-08-06):
  ```sql
  SELECT tgname FROM pg_trigger WHERE tgrelid='movimiento_inventario'::regclass
    AND NOT tgisinternal;          -- solo trg_horario_movimiento_inventario
  SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
   WHERE conrelid='movimiento_inventario'::regclass AND contype='c';
  -- cantidad>0 · costo_unitario>=0 · stock_anterior>=0 · stock_nuevo>=0
  ```

---

## 4. Inventario completo ya clasificado

Leyenda de origen: **[R]** ya registrada en `DEUDA_TECNICA.md` · **[N]** nueva, de este
levantamiento.

### A. DEFECTOS (2)

| # | Entrada | Origen | Evidencia |
|---|---|---|---|
| A-1 | Ventana `grp_analista` domingo en `23:30` — 30 min bloqueados en silencio | **[N]** | `grupo_horario` id 54 |
| A-2 | **`GET /api/gerencia/metas/vigente` responde HTTP 500 hoy** — `venta_realFROM meta_venta` | **[N]** | `MetasVentaService:75`; ver ficha abajo |

### B. FUERA DE ALCANCE (24)

Todas **[R]**, salvo indicación. Son límites declarados, no pendientes.

| # | Entrada | Evidencia (2026-08-06) |
|---|---|---|
| B-1 | Trazabilidad por lote / FEFO | `lote` = 0 filas |
| B-2 | `ajuste_inventario.estado='borrador'` sin flujo | 0 filas; no existe tabla de detalle del ajuste |
| B-3 | Sin método contra-entrega online | 0 coincidencias de `contra.?entrega` en el código |
| B-4 | Sin notificación proactiva al cliente (email/push) | 0 coincidencias de `JavaMailSender`/`smtp` |
| B-5 | GERENTE conserva acceso total a tickets | decisión de matriz |
| B-6 | Soporte ve todos los tickets (sin RLS por agente) | equipo pequeño |
| B-7 | Reapertura solo desde `resuelto`; `cerrado` terminal | guardia explícita |
| B-8 | `devolucion_detalle.accion` — solo `reembolso` implementado | **cambia de naturaleza**: el CHECK admite `cambio`/`credito` y **ya hay filas con `cambio`** (dato de seed), pero ninguna rama de flujo lo procesa |
| B-9 | Reembolso sin asiento negativo en `pago` | **cambia de naturaleza**: hoy existe tabla `reembolso` (85 filas); `transaccion_pago.tipo` sigue solo `autorizacion`/`captura` |
| B-10 | Guía de retorno sin costo ni tracking | número simulado `RET-…` |
| B-11 | `rechazado` en inspección consume cupo devolvible | sin apelación, a propósito |
| B-12 | VENDEDOR consulta el tablero RMA sin acciones | continuidad de matriz |
| B-13 | Pedido `no_entregado` sin reingreso formal de stock | 121 pedidos en ese estado |
| B-14 | `no_entregado` es terminal: sin re-despacho | compuerta exige `preparado` |
| B-15 | Preparación sin picking por ítem ni operario | se cruza con B-1 |
| B-16 | Override de transportista sin catálogo tarifado | excepción operativa deliberada |
| B-17 | Sin tope máximo en cupones porcentuales | `cupon` no tiene columna de tope (14 columnas, ninguna `%tope%`) |
| B-18 | Cupones solo en el checkout ONLINE | `aplicarCupon` invocado solo desde `CarritoService:271` |
| B-19 | Promociones solo por producto; sin precio promocional en la vitrina | — |
| B-20 | `uso_cupon` sin devolución del cupo al cancelar/devolver | ninguna ruta libera el uso |
| B-21 | Reseñas sin edición/borrado por el cliente ni filtro de lenguaje | 0 `PutMapping`/`DeleteMapping` en `resenas/` |
| B-22 | Rechazo TOTAL en puerta imposible en una línea | `CHECK (cantidad_recibida > 0)` |
| B-23 | Nota de crédito sin asiento en CxP; sin bodega de cuarentena; `devolucion_proveedor` sin `anulada`; reposición en un paso | estados = `registrada/enviada/resuelta/cerrada`; 0 bodegas con nombre «cuarentena» |
| B-24 | Trazabilidad futura: producto y marketing sin autor; ticket sin historial de estado | `log_auditoria.tabla` no contiene `producto`/`cupon`/`promocion`/`campana`/`banner`; no existe tabla de historial de ticket |
| B-25 | **OTD-GER-07** (efecto de promociones): 123 líneas promocionadas frente a 4.133 de base | **[N]** en este archivo; ya declarado en `docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` §12 |
| B-26 | Segmento B2B/B2C no medible: `pedido.canal` es el medio, no el segmento | **[N]** aquí; declarado en `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` |
| B-27 | Alerta de abandono **no viable como modelo entrenado** (correlación 0,039); se publica un modelo de proceso con su lift y su valor p en pantalla | **[N]** aquí; declarado en `DISENO_NIVEL_ESTRATEGICO.md` §5.2.2 |

### C. FRAGILIDADES CONOCIDAS (10)

| # | Entrada | Origen | Evidencia (2026-08-06) |
|---|---|---|---|
| C-1 | Kardex sin protección de motor + dos implementaciones, una sin `factor` | **[N]** | `StockService:43-57` vs `ComprasService:338` |
| C-2 | `movimiento_inventario.fecha_creacion DEFAULT now()` = instante de **inicio de transacción**; el kardex se lee por `(fecha_creacion, id)` | **[N]** | `column_default = now()`; hoy inocuo: sin concurrencia real |
| C-3 | 4 concatenaciones que **dependen de una línea en blanco**: borrarla las rompe en silencio | **[N]** | `MetasVentaService:61`, `ResenasService:183`, `ResenasService:468`, `CatalogoAdminService:132` — ver §6 |
| C-4 | Contraseña del admin en **9 archivos versionados** | **[N]** | ver §5 |
| C-5 | `dim_fecha` con rango codificado en duro hasta `2026-12-31` | **[N]** | `tablas/dim_fecha.py:28-29` |
| C-6 | Sin columna de procedencia seed/no-seed; marcador `[SEED-*]` incompleto | **[N]** | 254 filas sin marca, 93 de ellas anteriores a 2026 |
| C-7 | Una corrida de Airflow escribe **22 pares** de marcadores `corrida`, no uno | **[N]** | 22 `en_curso` + 22 cierres en la corrida de las 10:30 |
| C-8 | `grp_bodega` conserva SELECT de `precio_unitario` en los detalles | **[R]** | excepción documentada de la segregación financiera |
| C-9 | `grp_compras` con INSERT/UPDATE de `inventario` a nivel de motor | **[R]** | `INSERT,SELECT,UPDATE` sobre `inventario` |
| C-10 | Backfill parcial de autores históricos | **[R]** | `factura_compra.registrado_por` NULL en **13 de 839** |
| C-11 | `grupo_horario` **no está congelado**: la pantalla de admin puede reescribir las ventanas 24/7 | **[N]** | `HorariosAdminService:50/61`; es la causa de A-1 |

---

## 5. La contraseña del admin en archivos versionados (C-4)

Buscando la contraseña del admin (la que `CLAUDE.md` documenta en su sección de
credenciales de desarrollo) con `git grep -l` → **9 archivos**, confirmado. *El valor
literal se omite aquí a propósito: este documento no necesita reproducirlo para
sostener el punto, y hacerlo lo convertiría en el décimo archivo de la lista.*

| Archivo | Línea | Naturaleza |
|---|---|---|
| `retailmind/sql/postgres/23_seed_roles_admin.sql` | 8, **28** | **el seed real** (`crypt('<clave>', gen_salt('bf', 10))`) |
| `retailmind/matriz_alerta_cliente.py` | 66 | script de verificación |
| `retailmind/matriz_prevision.py` | 60 | script de verificación |
| `retailmind/matriz_tableros.py` | 46 | script de verificación |
| `retailmind/validar_tableros.py` | 45 | por defecto de `RETAILMIND_PASS` |
| `deploy/verificar_v11.sh` | 46 | script de humo |
| `docs/DESPLIEGUE_EJECUTADO.md` | 67 | documentación |
| `.kiro/steering/tech.md` | 189 | documentación |
| `CLAUDE.md` | 127 | documentación |

**Matiz importante para la sustentación**: esto **no** contradice la afirmación de que
«no hay secretos en el repositorio». Los cuatro secretos internos rotados
(superusuario, `retailmind_app`, `retailmind_etl`, `jwt.secret`) sí están fuera del
índice. Lo que queda es una **credencial de demostración documentada a propósito** —
el problema no es que se filtre, es que si algún día ese usuario dejara de ser de
demo, hay nueve sitios que actualizar y uno de ellos crea la cuenta.

---

## 6. Sobre las «51 concatenaciones frágiles»

El mecanismo que describía el reporte previo es **real y está bien identificado**. Lo que
no se sostiene es el **recuento** (51) ni el reparto por archivo — y, sobre todo, el
reporte daba por hecho que **todas funcionan**, cuando **una ya está rota en producción**.

### El mecanismo, con precisión

El patrón es `"""  + CONSTANTE + """`: la línea **cierra** un bloque de texto y **abre**
otro. Hay que mirar los dos lados:

- **Lado del cierre**: si el `"""` de cierre va en su propia línea, Java garantiza un
  `\n` final. Ahí no hay fragilidad posible.
- **Lado de la apertura**: el contenido del bloque siguiente empieza en la **línea
  posterior**. Si esa línea está en blanco, el contenido arranca con `\n` y separa. Si
  no lo está, arranca pegado — y entonces todo depende de si la CONSTANTE interpolada
  termina en salto de línea.

Es decir: **la línea en blanco solo es load-bearing cuando la constante interpolada no
acaba en `\n`.**

### La medición

De las 57 coincidencias de `grep -rn '"""\s*+'`, las que cierran y reabren en la misma
línea son **15**, y se reparten así:

| Caso | N | Estado |
|---|---|---|
| **Dependen de una línea en blanco** — borrarla las rompe en silencio | **4** | Frágiles → **C-3** |
| **Sin línea en blanco**, pero el fragmento interpolado (`filtro`, `tabla`, `from`, `where`) **sí acaba en `\n`** | **10** | Seguras |
| **Sin línea en blanco y la constante NO acaba en `\n`** | **1** | **YA ROTA → A-2** |

Los 4 frágiles son `MetasVentaService:61`, `ResenasService:183`, `ResenasService:468` y
`CatalogoAdminService:132`.

### A-2 · `GET /api/gerencia/metas/vigente` responde 500 · DEFECTO

- **Qué es**: `VENTA_REAL_SQL` cierra **en línea** (`MetasVentaService:47`,
  `… END AS venta_real"""`), así que **no** acaba en `\n`. `listar()` compensa con una
  línea en blanco (línea 62) y funciona. `vigente()` **no la tiene** (línea 75 → 76), y
  produce `… END AS venta_realFROM meta_venta m`.
- **Dónde**: `MetasVentaService.java:75`.
- **Si no se toca**: el endpoint está **caído hoy**. Es la única meta consultable por
  período; la pantalla que la use verá el error genérico del handler.
- **Costaría**: una línea en blanco, o darle a `VENTA_REAL_SQL` un `\n` final — con lo
  que además los otros 3 dejarían de depender de un espacio invisible.
- **Sustentación**: **Sí, si se pregunta.** Es el ejemplo perfecto de por qué el
  proyecto documenta esta trampa: el mismo fichero tiene la versión correcta y la
  incorrecta a 14 líneas de distancia, y solo una falla.
- **Verificado en vivo** (2026-08-06):
  ```
  GET /api/gerencia/metas          → HTTP 200
  GET /api/gerencia/metas/vigente  → HTTP 500
  backend log: PSQLException: ERROR: syntax error at or near "meta_venta"
  ```

### Lo que el reporte previo listaba y no coincide

`InformesVentasCompuestosService:155` **no tiene ninguna coincidencia**; en cambio
`SoporteService`, `ResenasService` y `AccesoService` sí las tienen y no estaban
listados. Los recuentos por archivo tampoco cuadran (p. ej. Inventario 13 vs. **14**
reales, Ventas 4 vs. **7**).

Conviene señalar que **el equipo ya identificó la trampa** y la documentó en el propio
código, evitándola deliberadamente:

```java
// InformesComprasService.java:541-544
// OJO: va SIN bloque de texto a propósito. Un text block de Java recorta
// el espacio final de cada línea, así que `"""SELECT """ + etiqueta`
// produce `SELECTpr.razon_social` — sintaxis inválida, y solo revienta en
// tiempo de ejecución.
```

Además, la distribución por archivo del reporte previo tampoco coincide con la real
(p. ej. `InformesVentasCompuestosService:155`, citado en el reporte, **no tiene ninguna
coincidencia**; en cambio `SoporteService`, `ResenasService` y `AccesoService` sí y no
estaban listados).

---

## 7. `TODO` / `FIXME` / `HACK` / `XXX`

**Cero marcadores reales** en todo el código (Java, TypeScript, Python, SQL, HTML, SCSS).

Las 26 coincidencias de una búsqueda ingenua son **todas** la palabra española «todo»
(= *all*) en prosa de comentarios: *«**Todo** @Transactional…»*, *«**TODO** método va en
`@Transactional(readOnly = true)`…»*. No hay ni un pendiente marcado en el código.

Lo que sí existe, y es la forma en que este proyecto registra sus límites, son las
**salvedades declaradas en la propia respuesta de la API** — un campo `salvedad` que
viaja al usuario. Hay 8 constantes de ese tipo (`SALVEDAD_MES_FACTURA`,
`SALVEDAD_PARES`, `SALVEDAD_CICLO`, `SALVEDAD_RECHAZO`, `SALVEDAD_COSTO`,
`SALVEDAD_MUESTRA`, más las de costo vigente y moneda constante). Eso es mejor que un
`TODO`: la limitación se muestra **encima de la cifra**, no enterrada en el código.
