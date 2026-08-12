# Banco de pruebas: SQL relacional (PostgreSQL) frente a columnar (ClickHouse)

**Fecha de ejecución**: 2026-08-07 · **Base de pruebas**: `retailmind_benchmark`
(PostgreSQL, permanente) · **Guiones**: `retailmind/benchmark/`

> ## ⚠ VIGENCIA DE ESTAS CIFRAS — nota del 2026-08-11
>
> **Todo lo que sigue describe el almacén tal como estaba el 2026-08-07, y ese
> almacén ya no existe.** Las mediciones NO se han rehecho.
>
> | | al medir (2026-08-07) | hoy (2026-08-11) |
> |---|---|---|
> | Modelo del DWH | **66.082** filas | **32,60 M** filas |
> | `fact_eventos` (escala grande) | 2.823.245 filas | sin cambio |
> | Pedidos en la capa operativa | 4.083 | **2.999.991** |
> | Base `retailmind` | ~250 MB | **13 GB** |
>
> Entre medias entraron las cuatro fases de la carga masiva (ver `CLAUDE.md`),
> que llevaron la capa operativa a 3 millones de pedidos en una década.
>
> **Qué sigue siendo válido y qué no:**
>
> * **La CURVA sigue siendo válida, y es la conclusión del documento.** El
>   experimento no afirma «ClickHouse gana 16,96×»: afirma que la ventaja es
>   función del volumen, y lo demuestra con dos escalas. Eso no caduca.
> * **El punto «a 66.082 filas PostgreSQL gana 0,74×» ya no describe a
>   RetailMind.** Ese punto era el extremo pequeño de la curva, y el sistema se
>   ha ido al otro extremo: hoy el modelo del DWH tiene **493 veces** las filas
>   que tenía al medirlo, y está por encima incluso de `fact_eventos`, que era
>   la escala grande del experimento. Leerlo hoy como «para RetailMind da igual
>   el motor» sería exactamente la lectura equivocada.
> * **Los tiempos absolutos de las cuatro consultas no son reproducibles** sin
>   volver a cargar `retailmind_benchmark`, que conserva la copia de 2026-08-07.
>
> No se rehace aquí a propósito: rehacerlo es un experimento con su propio
> método (§7) y mezclarlo con una nota de vigencia solo produciría un documento
> que no se sabe de cuándo habla.

---

## 1. La pregunta

> ¿Cuánto más rápida es una base columnar que una relacional para las consultas
> de agregación que sirven los informes de RetailMind?

La pregunta, así planteada, no tiene una sola respuesta, y ese es el resultado
del experimento. **La ventaja de un motor columnar es función del VOLUMEN y de
la FORMA de la consulta.** A decenas de miles de filas es marginal o negativa; a
millones se dispara hasta un orden de magnitud.

Por eso se midió a **dos escalas** con los mismos motores, la misma máquina y el
mismo arnés. Una sola cifra —la que fuera— habría sido una anécdota. Dos escalas
dibujan la curva, que es lo que de verdad justifica la arquitectura del sistema:
RetailMind sirve los informes **simples** desde PostgreSQL y los **compuestos**
desde ClickHouse, y este experimento dice si ese corte está en el sitio correcto.

### La advertencia que se tomó en serio

El riesgo declarado era «sería el colmo que la columnar salga más lenta». **A
escala pequeña, PostgreSQL efectivamente gana**, y este documento lo reporta como
resultado principal de esa escala, no como una nota al pie. No se ajustó ninguna
consulta para invertirlo. Ese resultado es el que da credibilidad al otro: si el
mismo arnés que mide 17× a favor de ClickHouse a 2,8 millones de filas mide 0,69×
a favor de PostgreSQL a 36 mil, el arnés no está amañado.

---

## 2. Qué se comparó exactamente

Los dos motores operan sobre **exactamente las mismas filas**. No se generaron
datos sintéticos: se copiaron los datos reales de ClickHouse a PostgreSQL.

| Escala | Origen | Filas | Destino en PostgreSQL |
|---|---|---:|---|
| Pequeña | `retailmind_dwh` (ClickHouse), 21 tablas del modelo | **66.082** | esquema `dwh` |
| Grande | `retailmind.fact_eventos` (ClickHouse legada) | **2.823.245** | esquema `web` |

La copia de `fact_eventos` se hizo **completa**: no hubo muestreo, ni recorte, ni
límite de tiempo o espacio que obligara a reducirla. Tardó **5,2 segundos**.

Las 21 tablas del modelo suman 66.082 filas, que confirma la cifra «~66.000» del
enunciado. Se excluye `etl_ejecucion` porque es la bitácora del ETL y no forma
parte del modelo dimensional (la base `retailmind_dwh` tiene 22 objetos; el
modelo son 21).

---

## 3. Las cinco consultas, y por qué esas

Ninguna es una búsqueda por clave. Las cinco recorren cientos de miles o millones
de filas y devuelven entre 10 y 162, que es la forma de todo informe de gestión.

### Escala pequeña — informes reales del sistema

**Q1 · OTD-VEN-06 — venta y margen por mes y categoría** (`fact_venta_linea`,
10.384 filas, `LEFT JOIN dim_fecha`).
Es el agregado que sirve hoy el informe compuesto de Ventas. Se eligió porque es
**el caso de libro de la ventaja columnar**: una tabla de **28 columnas** de la
que la consulta lee **8**, con un `GROUP BY` de dos claves. Un motor por filas
tiene que traer la fila entera del disco para mirar ocho campos; uno columnar lee
solo las ocho columnas. Si la ventaja columnar no aparece aquí, no aparece en
ninguna parte.

**Q2 · OTD-INV-04 — rotación de inventario por categoría**
(`fact_stock_mensual` 22.528 + `fact_movimiento_inventario` 13.289 = **35.817
filas** entre dos tablas).
Se eligió por tener una **forma distinta a propósito**: dos niveles de agregación
anidados (primero por mes, después por categoría) unidos por `LEFT JOIN` a un
segundo agregado sobre otra tabla. No es un escaneo plano. Sirve para comprobar
si la ventaja depende solo del ancho de la tabla o también de la estructura de la
consulta.

### Escala grande — analítica web (2.823.245 filas)

**Q3 · Tráfico por canal y acción, con conteos distintos exactos.**
18 grupos, dos `COUNT(DISTINCT)` de alta cardinalidad (455.550 sesiones y 6.810
usuarios). Es el agregado que sirve el panel de analítica web.

**Q4 · La misma agrupación SIN `COUNT(DISTINCT)`.**
Es el **control del experimento**. `COUNT(DISTINCT)` se resuelve en PostgreSQL
ordenando y en ClickHouse con una tabla hash: si toda la diferencia de Q3 saliera
de ahí, la comparación no diría nada sobre el modelo columnar. Q4 aísla ese
efecto — mismas filas, mismos grupos, solo sumas.

**Q5 · Top de productos: filtro + 1.700 grupos.**
Tercera forma: el `GROUP BY` no tiene 18 grupos sino **1.700**, y un filtro por
`user_action` deja 1.310.209 de 2.823.245 filas (53,6 %). Es donde un índice de
PostgreSQL tiene más que decir.

Las diez consultas —cinco por motor— están íntegras en
`retailmind/benchmark/consultas.py`, que es el **único** sitio donde viven: el
arnés que las mide y el verificador que compara sus resultados leen de ahí, así
que no puede haber dos versiones de la misma consulta.

### Reglas que cumplen las diez

1. **Los dos motores calculan lo mismo.** Nada de `uniq()` en ClickHouse: es
   HyperLogLog, es *aproximado*, y no sería la misma pregunta que el
   `COUNT(DISTINCT)` de PostgreSQL. Se usa `uniqExact()`, que es exacto y más
   caro.
2. **`COLLATE "C"` en todo `ORDER BY` sobre texto en PostgreSQL.** La base nace
   con ICU `es-EC` y ClickHouse ordena por bytes; sin esto «Electrónica» cae en
   otro sitio y las filas no se pueden contrastar en paralelo.
3. **`coalesce` en PostgreSQL donde ClickHouse rellena con el defecto del tipo.**
   Un `LEFT JOIN` sin pareja da `0` o cadena vacía en ClickHouse y `NULL` en
   PostgreSQL.
4. **Ninguna consulta se retocó para invertir un resultado.**

---

## 4. Metodología

### 4.1 Entorno declarado

Los dos motores corren **en contenedores de la misma máquina**, sobre el mismo
Docker, sin límites de CPU ni de memoria (`NanoCpus = 0`, `Memory = 0` en los
dos): comparten el host entero y compiten en igualdad.

| | |
|---|---|
| Host | Docker 29.6.2 sobre WSL2 (kernel 6.6.114.1), **32 CPU**, **7,9 GB** de RAM para Docker |
| PostgreSQL | **18.4** (imagen `postgres:18`), contenedor `retailmind-postgres-1` |
| ClickHouse | **26.4.2.10** (imagen `clickhouse/clickhouse-server:26.4.2.10`), contenedor `retailmind-clickhouse-1` |
| PostgreSQL, por defecto | `shared_buffers` 128 MB · `work_mem` 4 MB · `max_parallel_workers_per_gather` **2** · `max_worker_processes` 8 · `jit` on |
| ClickHouse | `max_threads` = **auto(32)** · `max_memory_usage` sin límite |

**Asimetría declarada y NO corregida**: ClickHouse usa hasta 32 hilos y
PostgreSQL como mucho 9 procesos (1 líder + 8 trabajadores), porque
`max_worker_processes = 8` es un parámetro de arranque del clúster y subirlo
exigiría **reiniciar el motor que sirve la base operativa**. No se hizo. Su
efecto se acota midiendo PostgreSQL en dos configuraciones (§4.3) y se ve
directamente en Q4: pasar de 2 a 8 trabajadores le baja el tiempo a la mitad.

### 4.2 Arnés simétrico

Los dos motores se miden **desde el mismo proceso Python**, con
`time.perf_counter()`, sobre una conexión TCP a `localhost`, **materializando el
resultado completo** en ambos casos. No se usa el reloj interno de cada motor
para el dato principal: cada uno mide cosas distintas. Lo que se cronometra es lo
que vería la aplicación —enviar, ejecutar y recibir—, que es exactamente lo que
hace el backend por JDBC.

- **Caché fría descartada**: la primera ejecución de cada consulta no cuenta.
- **11 repeticiones** medidas. Se reporta **mínimo, mediana y máximo**.
- **La caché de resultados de ClickHouse se APAGA** explícitamente
  (`use_query_cache=0`). Con ella encendida, la segunda repetición devolvería la
  respuesta guardada y la medición no valdría nada. PostgreSQL no tiene caché de
  resultados.
- Ambos motores trabajan **en caliente**: tras el calentamiento los datos están
  en la caché de página del sistema. Es la condición justa y la realista.

#### Piso de transporte

Para saber qué parte del tiempo es motor y qué parte es protocolo, se mide una
ida y vuelta vacía (`SELECT 1`) en cada motor:

| | mínimo | mediana |
|---|---:|---:|
| PostgreSQL (protocolo binario) | 0,30 ms | 0,32 ms |
| ClickHouse (HTTP) | 1,41 ms | 1,72 ms |

**Este piso NO se resta de las mediciones** — restarlo sería fabricar un número
que nadie mide. Se reporta para que se pueda leer la escala pequeña con
propiedad: en Q1, de los 9,7 ms de ClickHouse, ~1,7 ms son protocolo.

#### Dos decisiones del arnés que favorecían a PostgreSQL, y se corrigieron

Ambas se detectaron midiendo el piso de transporte, y las dos están declaradas
porque cambian el resultado de la escala pequeña:

1. **Conexión no reutilizada.** La primera versión abría un TCP nuevo a
   ClickHouse en cada repetición mientras psycopg2 reutilizaba uno solo. Se
   corrigió con una conexión HTTP persistente.
2. **`TCP_NODELAY`.** `http.client` de Python no lo activa; libpq (psycopg2) sí.
   Sin él, el algoritmo de Nagle esperaba el ACK diferido del otro extremo y
   **cada ida y vuelta a ClickHouse costaba ~43 ms fijos** —medidos— frente a
   0,32 ms de PostgreSQL. Eso no era el motor: era el socket, y se le estaba
   cargando entero a ClickHouse. Con las dos correcciones el piso bajó de
   43,37 ms a 1,41 ms.

### 4.3 Índices de PostgreSQL

Medir PostgreSQL sin índices sería inclinar el resultado y es inaceptable. Se le
dio la **mejor configuración que admite sin reiniciar el clúster**: un índice
**cubriente** (`INCLUDE`) por consulta, con las columnas de filtrado y agrupación
en la clave y las de agregación en la carga. Así el planificador puede resolver
el agregado con un *index only scan* sin visitar la tabla — que es lo más
parecido que PostgreSQL tiene a leer solo las columnas que necesita, o sea lo que
un motor columnar hace por construcción.

| Índice | Tabla | Tamaño | Usos | Por qué |
|---|---|---:|---:|---|
| `ix_venta_q1` | `dwh.fact_venta_linea` | 952 kB | 48 | clave `(es_cancelado, mes, categoria)` = filtro + `GROUP BY`; carga = las 7 columnas agregadas |
| `ix_dimfecha_q1` | `dwh.dim_fecha` | 40 kB | 144 | clave del `LEFT JOIN` de Q1, con la etiqueta en la carga |
| `ix_stock_q2` | `dwh.fact_stock_mensual` | 1128 kB | 48 | clave `(categoria, mes)` = `GROUP BY` interno de Q2 |
| `ix_kardex_q2` | `dwh.fact_movimiento_inventario` | 800 kB | **0** | clave `(categoria)`; el planificador lo descartó (ver abajo) |
| `ix_eventos_q34` | `web.fact_eventos` | **211 MB** | 48 | clave `(channel, user_action)`; carga = las 6 columnas agregadas |
| `ix_eventos_q5` | `web.fact_eventos` | **171 MB** | 192 | clave `(user_action, product_id)` = filtro + `GROUP BY` |

Después de crearlos se ejecuta **`VACUUM ANALYZE`**. Sin él el mapa de
visibilidad está vacío y PostgreSQL **no** usa el *index only scan* aunque el
índice exista: el plan cae a un recorrido secuencial y el índice no sirve de nada.

**`ix_kardex_q2` no se usó nunca**, y se reporta tal cual: sobre 13.289 filas que
la consulta necesita **enteras**, el planificador prefiere un `Seq Scan`. Es una
decisión correcta suya, no un fallo del experimento.

Los **385 MB de índices cuentan en el espacio de PostgreSQL** que se reporta en
§6. Es correcto que cuenten: son el precio de estos tiempos.

#### La asimetría que favorece a PostgreSQL y que se dejó

ClickHouse **no recibió ninguna optimización equivalente**. Su clave de
ordenamiento en `fact_eventos` es `(session_id, event_index)`, que **no sirve de
nada** para agrupar por `channel`, `user_action` o `product_id`; y en
`fact_venta_linea` es `(fecha_pedido, categoria, producto_variante_id)`, sin
filtro por fecha que permita podar particiones. Se podrían haber creado
proyecciones o índices de salto, pero las bases de ClickHouse son de **solo
lectura** en este experimento. **PostgreSQL corre con índices hechos a medida
para cada consulta; ClickHouse, a pelo.** La ventaja medida de ClickHouse es, por
tanto, un suelo, no un techo.

### 4.4 PostgreSQL en dos configuraciones

Se reportan las dos, y ninguna es «la buena»:

- **Por defecto** — los valores con los que el sistema corre hoy. Es la verdad
  del despliegue.
- **Afinado** — `work_mem = 256 MB`, `max_parallel_workers_per_gather = 8`,
  costes de paralelismo a cero. Todos son ajustes **de sesión**: no se tocó
  `postgresql.conf` ni se ejecutó `ALTER SYSTEM`, porque este clúster también
  sirve la base operativa. Cierra la objeción de que se midió un PostgreSQL mal
  configurado.

### 4.5 Igualdad de resultados

Antes de dar por buena una medición se contrastan las dos respuestas **celda por
celda**. Una consulta más rápida que devuelve otro número no mide nada.

La comparación es **valor a valor y no cadena a cadena**, porque los dos motores
*imprimen* distinto: ClickHouse recorta los ceros finales de un `Decimal`
(`249.8` donde PostgreSQL escribe `249.80`). El valor es el mismo.

---

## 5. Resultados

Milisegundos. `mín / mediana / máx` de 11 repeticiones tras descartar el
calentamiento. La relación es **mediana de PostgreSQL ÷ mediana de ClickHouse**:
por encima de 1 gana ClickHouse, por debajo gana PostgreSQL.

### 5.1 Escala pequeña — 66.082 filas

| | Q1 · VEN-06 (10.384 filas) | Q2 · INV-04 (35.817 filas) |
|---|---:|---:|
| PostgreSQL por defecto | **6,8 / 7,2 / 7,9** | **7,4 / 7,8 / 8,0** |
| PostgreSQL afinado | 9,9 / 10,3 / 11,4 | 21,3 / 23,2 / 25,8 |
| ClickHouse | 9,0 / 9,7 / 12,2 | 10,9 / 11,3 / 15,7 |
| **Relación (defecto)** | **0,74×** → gana PostgreSQL | **0,69×** → gana PostgreSQL |
| Relación (afinado) | 1,06× | 2,06× |
| Filas devueltas | 162 | 10 |
| Resultado idéntico | **sí, exacto** | **sí, exacto** |

### 5.2 Escala grande — 2.823.245 filas

| | Q3 · con `COUNT(DISTINCT)` | Q4 · control, sin distintos | Q5 · 1.700 grupos |
|---|---:|---:|---:|
| PostgreSQL por defecto | 3.279,7 / **3.340,8** / 3.386,7 | 189,2 / **192,4** / 235,3 | 1.391,9 / **1.411,0** / 1.427,3 |
| PostgreSQL afinado | 2.279,4 / **2.407,4** / 2.724,2 | 91,9 / **95,8** / 108,2 | 664,9 / **711,5** / 741,3 |
| ClickHouse | 167,7 / **197,0** / 353,6 | 50,4 / **53,1** / 55,5 | 81,8 / **83,3** / 88,6 |
| **Relación (defecto)** | **16,96×** | **3,62×** | **16,93×** |
| **Relación (afinado)** | **12,22×** | **1,80×** | **8,54×** |
| Filas devueltas | 18 | 18 | 25 |
| Resultado idéntico | **sí, exacto** | **sí, exacto** | **sí, exacto** |

### 5.3 Contraste con el reloj de cada motor

El dato principal es el del arnés (§4.2). Como control independiente, esto es lo
que declara cada motor por su cuenta —`X-ClickHouse-Summary` y el
`Execution Time` de `EXPLAIN ANALYZE`—. **Coinciden**, lo que descarta que el
arnés esté midiendo otra cosa:

| | Q1 | Q2 | Q3 | Q4 | Q5 |
|---|---:|---:|---:|---:|---:|
| ClickHouse, lado servidor | 6,9 | 9,1 | 192,8 | 49,2 | 80,4 |
| PostgreSQL defecto, `EXPLAIN ANALYZE` | 6,8 | 8,5 | 3.482,3 | 217,9 | 1.418,1 |
| PostgreSQL afinado, `EXPLAIN ANALYZE` | 10,2 | 22,4 | 2.393,1 | 98,3 | 736,9 |

### 5.4 La curva

| Escala | Filas recorridas | Relación con PostgreSQL por defecto |
|---|---:|---:|
| Q1 | 10.384 | **0,74×** (PostgreSQL más rápido) |
| Q2 | 35.817 | **0,69×** (PostgreSQL más rápido) |
| Q4 | 2.823.245 (solo sumas) | 3,62× |
| Q5 | 1.310.209 (1.700 grupos) | 16,93× |
| Q3 | 2.823.245 (con distintos exactos) | 16,96× |

Entre 36 mil y 2,8 millones de filas —un factor de **79× en volumen**— la
relación pasa de 0,69× a 16,96×: una mejora relativa de **24×**.

### 5.5 Qué dicen los planes de ejecución

| Consulta | PostgreSQL por defecto | PostgreSQL afinado |
|---|---|---|
| Q1 | `Index Only Scan` en las dos tablas, `Merge Left Join` | `Parallel Seq Scan`, 6 trabajadores |
| Q2 | `Index Only Scan` sobre stock, `Seq Scan` sobre kardex | `Parallel Seq Scan` en las dos, 6 trabajadores |
| Q3 | `Index Only Scan`, **vuelca a disco** (22.818 bloques temporales) | `Parallel Seq Scan`, 7 trabajadores |
| Q4 | `Parallel Seq Scan`, 2 trabajadores | `Parallel Seq Scan`, 7 trabajadores |
| Q5 | `Index Only Scan`, **ordenación externa en disco de 63,7 MB** | `Parallel Index Only Scan`, 7 trabajadores |

Los índices **se usan**: cuatro de las cinco consultas los aprovechan en la
configuración por defecto. Lo que ocurre en la escala grande es que ni el índice
cubriente evita el problema de fondo — Q3 y Q5 tienen que **ordenar millones de
filas y las vuelcan a disco**, porque `COUNT(DISTINCT)` en PostgreSQL se resuelve
ordenando. En la configuración afinada, con `work_mem` de 256 MB, deja de
volcar, y ahí está la mayor parte de la mejora del afinado.

---

## 6. Espacio en disco

Con **exactamente los mismos datos**. De PostgreSQL se cuenta el tamaño total
(tabla + TOAST + **índices**), porque sin los índices los tiempos medidos serían
otros. De ClickHouse, lo comprimido, que es lo que ocupa, con lo sin comprimir al
lado para que se vea de dónde sale la diferencia.

| Escala | PostgreSQL: tablas | PostgreSQL: índices | **PostgreSQL: total** | **ClickHouse** | ClickHouse sin comprimir |
|---|---:|---:|---:|---:|---:|
| Pequeña (66.082 filas) | 13,8 MB | 2,9 MB | **17,3 MB** | **3,0 MB** | 7,6 MB |
| Grande (2.823.245 filas) | 398,9 MB | 382,1 MB | **781,1 MB** | **114,5 MB** | 369,2 MB |

- PostgreSQL ocupa **5,9×** lo que ClickHouse en la escala pequeña y **6,8×** en
  la grande (**4,7×** y **3,5×** si se le perdonan los índices).
- ClickHouse comprime **3,2×** sobre su propio dato en bruto (369,2 → 114,5 MB).
  Es el efecto directo de guardar cada columna junta: valores del mismo tipo y a
  menudo repetidos comprimen muchísimo mejor que una fila heterogénea.
- Base `retailmind_benchmark` completa: **806,8 MB**.

Merece leerse junto a §5: los 382 MB de índices que PostgreSQL necesita para
competir son, por sí solos, **más del triple** de lo que ClickHouse ocupa con los
mismos datos y sin índice alguno.

---

## 7. Verificaciones

### V1 — la base operativa `retailmind` no se tocó

La conexión desde el guion de verificación es de **solo lectura**
(`default_transaction_read_only = on`), que no es una promesa: si algo intentara
escribir, el motor lo rechazaría. Y `00_crear_base.py` sólo puede crear o borrar
`retailmind_benchmark`; cualquier otro destino aborta el proceso antes de abrir
la conexión (`comun.guardia_base`).

| Cifra | Antes | Después |
|---|---:|---:|
| Pedidos | 4.083 | 4.083 |
| Movimientos de kardex | 13.289 | 13.289 |
| Posiciones de inventario | 1.406 | 1.406 |
| Stock total | 133.226 | 133.226 |
| Tablas del esquema `public` | 111 | 111 |
| Políticas RLS | 95 | 95 |
| Roles `grp_*` | 9 | 9 |
| Usuarios | 89 | 89 |
| Facturas de venta | 3.887 | 3.887 |
| Total vendido (no cancelado) | 5.498.570,35 | 5.498.570,35 |

Las cinco primeras se tomaron **antes de crear la base de pruebas** y coinciden
con las del enunciado. El total vendido coincide con el que `CLAUDE.md` declara
verificado ($5.498.570,35).

### V2 — la copia es idéntica al origen, fila por fila

No se comparó un conteo: dos tablas con las mismas filas pero un decimal corrido
dan el mismo conteo y miden cosas distintas. Cada motor vuelca sus filas
**ordenadas por la clave, con las columnas normalizadas al mismo texto**, y se
contrasta el **MD5 del flujo completo**.

- **21 de 21 tablas del DWH: MD5 idéntico**, 66.082 filas de origen y 66.082 de
  destino.
- **`fact_eventos`: MD5 idéntico**, `03189993e0566cc7d19508630a62d58e` en los dos
  motores, 2.823.245 filas.

### V3 — los dos motores devuelven el mismo resultado

**Las cinco consultas: resultado idéntico, comparación exacta**, sin recurrir a
ninguna tolerancia. Se contrastan las 162, 10, 18, 18 y 25 filas celda por celda
en cada corrida; si una sola difiriera, el guion sale con código 1.

### V4 — el sistema sigue en pie

Los seis contenedores siguen `healthy` y `/api/health` responde. Ver §10.

---

## 8. Conclusión

**La tesis se sostiene, y con el matiz incómodo incluido.**

1. **A escala pequeña PostgreSQL gana, y es un hallazgo, no un fracaso.** Con
   66.082 filas, PostgreSQL resuelve el informe de ventas en 7,2 ms y el de
   rotación en 7,8 ms, contra 9,7 y 11,3 ms de ClickHouse: **0,74× y 0,69×**.
   Buena parte de esa diferencia ni siquiera es el motor —1,7 ms de los 9,7 de
   ClickHouse son el protocolo HTTP—, pero incluso descontando el piso de
   transporte (6,9 vs 8,0 ms en Q1; 7,5 vs 9,6 en Q2) **PostgreSQL sigue
   ganando**. La razón es simple: a este volumen no hay nada que optimizar. Los
   datos caben en la caché, el índice cubriente hace el trabajo, y la maquinaria
   de un motor columnar —vectorización, paralelismo entre 32 hilos, bloques de
   65.536 filas— es puro coste fijo cuando solo hay 10.384 filas que mirar.
   **La ventaja columnar no es gratis: hay que amortizarla.**

2. **A escala grande la ventaja se dispara: 16,96×** en Q3 y **16,93×** en Q5
   contra PostgreSQL por defecto, y **12,22×** y **8,54×** contra un PostgreSQL
   afinado con ocho procesos paralelos y 256 MB de `work_mem`. En términos
   absolutos, un informe que ClickHouse sirve en 197 ms tarda **3,3 segundos** en
   PostgreSQL: la diferencia entre una pantalla que responde y una que hay que
   explicar al usuario.

3. **La forma de la consulta pesa tanto como el volumen.** El control Q4 lo
   demuestra: con las mismas 2.823.245 filas y los mismos 18 grupos, quitando los
   `COUNT(DISTINCT)`, la ventaja cae de 16,96× a **3,62×**. O sea: de la ventaja
   de Q3, un factor **3,62× es el modelo columnar** —leer solo las columnas que
   la consulta pide, sobre datos comprimidos y agrupados— y el factor **4,7×
   restante** (16,96 ÷ 3,62) es que ClickHouse resuelve los distintos con una
   tabla hash mientras PostgreSQL **ordena millones de filas y las vuelca a
   disco**. Las dos partes son diferencias reales —el informe pide esos
   distintos— pero no son la misma cosa, y presentar el 16,96× como si todo él
   fuera «lo columnar» sería vender de más.

4. **El corte arquitectónico de RetailMind está donde debe.** El sistema sirve
   los informes **simples** desde PostgreSQL —consultas sobre decenas de miles de
   filas, donde PostgreSQL es más rápido— y los **compuestos** desde ClickHouse
   —agregaciones sobre el almacén, donde la ventaja es de un orden de magnitud—.
   Este experimento no justifica el diseño a posteriori: lo **mide**.

5. **El espacio es la otra mitad del argumento**, y ahí no hay matiz: ClickHouse
   ocupa **6,8× menos** con los mismos 2,8 millones de filas, y los índices que
   PostgreSQL necesita para competir (382 MB) son por sí solos más del triple de
   todo lo que ClickHouse ocupa.

### Lo que este experimento NO demuestra

- **No es un veredicto sobre PostgreSQL como motor.** PostgreSQL es la base
  transaccional de RetailMind y ninguna de estas cinco consultas se parece a lo
  que hace ahí: escrituras, transacciones, RLS, restricciones de integridad.
  Cambiar el operativo a un motor columnar sería un error, y estos números no lo
  sugieren.
- **No mide concurrencia.** Todas las medidas son de una consulta a la vez sobre
  una máquina ociosa. Con veinte usuarios simultáneos las curvas serían otras.
- **No mide el coste de mantener el almacén.** Los 197 ms de ClickHouse
  presuponen un ETL que corre cada noche y que copia y transforma esas filas; ese
  coste existe y no está aquí.
- **La escala grande usa datos de navegación web, no del almacén.** Es la única
  tabla del sistema con millones de filas. Si el negocio creciera hasta que
  `fact_venta_linea` tuviera millones, la Q1 —hoy perdida por PostgreSQL— muy
  probablemente cambiaría de lado; este experimento sugiere dónde, pero no lo
  prueba.

---

## 9. Hallazgos incidentales

**`fact_eventos.event_pk` no es una clave, pese al nombre.** Tiene **50.000
valores distintos sobre 2.823.245 filas**, porque está definida como
`DEFAULT rowNumberInAllBlocks()` y ese contador **reinicia en cada bloque de
inserción**. La primera versión de la verificación V2 ordenaba por ella y daba
MD5 distintos con datos idénticos: cada motor desempataba el empate masivo a su
manera. Se ordena por todas las columnas. **Nada del sistema en producción
depende de esa columna** (las consultas de analytics agrupan por `session_id`),
así que no es un fallo activo, pero cualquier código futuro que la tome por
identificador único estará equivocado.

**Todo lo que el enunciado afirmaba resultó cierto al verificarlo**: 4.083
pedidos, 13.289 movimientos de kardex, 1.406 posiciones de inventario y 111
tablas en `retailmind`; 21 tablas y ~66.000 filas en `retailmind_dwh`
(66.082 exactas), con `fact_stock_mensual` 22.528, `fact_movimiento_inventario`
13.289, `fact_venta_linea` 10.384, `fact_flujo_caja` 4.981 y `fact_pedido` 4.083;
y `fact_eventos` con 2.823.245 filas. No hubo que corregir ninguna cifra.

**Dos trampas de medición documentadas** por si se repite el experimento en otro
contexto: `TCP_NODELAY` (§4.2) y el `%M` de `formatDateTime` de ClickHouse, que
es el **nombre del mes** y no el minuto — con el patrón «obvio» la verificación
V2 acusaba 21 tablas distintas por cómo se imprime una hora (`17:August:19` en
vez de `17:17:19`), no por los datos. El minuto es `%i`.

---

## 10. Reproducir el experimento en vivo

Todo se ejecuta desde `retailmind/benchmark/`. Requisitos: los contenedores
`retailmind-postgres-1` y `retailmind-clickhouse-1` levantados, y `psycopg2` en
el Python del equipo.

```bash
cd retailmind/benchmark
export PYTHONIOENCODING=utf-8      # en PowerShell: $env:PYTHONIOENCODING='utf-8'
```

| Paso | Comando | Tarda | Qué demuestra |
|---|---|---:|---|
| 0 | `py -3 00_crear_base.py --recrear` | **1 s** | Crea `retailmind_benchmark` aislada. Es lo único que borra algo. |
| 1 | `py -3 01_cargar.py` | **21 s** | Copia 66.082 + 2.823.245 filas de ClickHouse a PostgreSQL. |
| 2 | `py -3 02_verificar_copia.py` | **27 s** | **V2**: MD5 idéntico en las 22 tablas. |
| 3 | `py -3 03_indexar.py` | **8 s** | Crea los 6 índices y `VACUUM ANALYZE`. |
| 4 | `py -3 04_medir.py --reps 11` | **113 s** | **La medición y V3.** Es el paso que se enseña. |
| 5 | `py -3 05_espacio.py` | **1 s** | Espacio en disco de los dos motores. |
| 6 | `py -3 06_verificar_sistema.py --comparar antes` | **2 s** | **V1 y V4**: la base operativa intacta. |

**Total desde cero: ~3 minutos.** La base queda **permanente**: si ya está
cargada, basta con el paso 4 (**113 s**), o `--reps 5` (**~60 s**) si el tiempo
aprieta.

Para una demostración corta, el paso 4 solo ya enseña todo: imprime el piso de
transporte, las cinco consultas con mín/mediana/máx en los tres perfiles, la
relación de velocidad y la confirmación `resultado identico: SI` en cada una.

### Consultar la base a mano

```bash
# Desde el host (pide la clave de deploy/secrets/pg_superuser.txt)
psql -h localhost -p 5432 -U postgres -d retailmind_benchmark

# O desde dentro del contenedor, sin clave
docker compose exec postgres psql -U postgres -d retailmind_benchmark

# Los datos:  esquema `dwh` (21 tablas del almacén) y `web` (fact_eventos)
\dt dwh.*
\dt web.*
```

### Archivos

| Ruta | Qué es |
|---|---|
| `retailmind/benchmark/comun.py` | Conexiones, traducción de tipos ClickHouse→PostgreSQL, copia en flujo y el guardia que impide escribir fuera de `retailmind_benchmark` |
| `retailmind/benchmark/consultas.py` | Las diez consultas (cinco × dos motores). Único sitio donde viven |
| `retailmind/benchmark/00_crear_base.py` | Crea la base aislada |
| `retailmind/benchmark/01_cargar.py` | Copia los datos desde ClickHouse |
| `retailmind/benchmark/02_verificar_copia.py` | **V2** — MD5 fila por fila |
| `retailmind/benchmark/03_indexar.py` | Índices + `VACUUM ANALYZE` |
| `retailmind/benchmark/04_medir.py` | **La medición y V3** |
| `retailmind/benchmark/05_espacio.py` | Espacio en disco |
| `retailmind/benchmark/06_verificar_sistema.py` | **V1 y V4** |
| `retailmind/benchmark/_diff.py` | Auxiliar de depuración: primera diferencia entre origen y copia |
| `retailmind/benchmark/resultados.json` | Tiempos crudos + planes de ejecución completos |
| `retailmind/benchmark/espacio.json` | Cifras de espacio |
| `retailmind/benchmark/v1_antes.json` · `v1_despues.json` | Cifras de control de la base operativa |
