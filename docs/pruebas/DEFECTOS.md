# Registro de defectos — Plan de Pruebas

Una fila por defecto encontrado al ejecutar `docs/PLAN_DE_PRUEBAS.md`.
Los que no se corrijan pasan a `DEUDA_TECNICA.md` con su ficha.

**Severidad** (§6 del plan): S1 crítico · S2 grave · S3 medio · S4 leve.
Un **fallo silencioso es S1 por definición**, aunque el síntoma parezca leve.

---

## Estado tras las corridas 1 y 2 — 2026-08-18/19

### Suites implementadas y su resultado

| Suite | Estado(s) | Casos | Resultado |
|---|---|---|---|
| **P02** barrido de endpoints × roles | E3, E0 | 35 + 11 | ✅ 0 respuestas 500 en 1.962 llamadas |
| **P03** seguridad de motor | E3 | 43 | ✅ 43/43 |
| **P04** validación de entrada | E3, E0 | 126 ×2 | ✅ 126/126 en ambos |
| **P05** compuertas de negocio | E1 | 21 | ✅ 21/21 |
| **P06** integridad e invariantes | E3, E1 | 17 ×2 | ✅ 17/17 · 16/17 + 1 omitido |
| **P07** estado vacío | E0 | 140 | ✅ 138/140 (2 hallazgos) |
| **P09/P10** ETL, almacén y modelos | E3 | 18 | ✅ 18/18 |
| **P11** interfaz (32 pantallas) | E3, E1 | 65 + 97 | ✅ 65/65 · 97/97 |
| **P13** resiliencia y degradación | E3 | 23 | ✅ 23/23 |
| **P12** rendimiento | E3 | 16 | ✅ los 8 endpoints bajo umbral tras D-11 |
| **P01** arranque y configuración | E3 | 9 | ✅ 9/9 |
| **P08** compuestos y tableros | E3 | 33 | ✅ 33/33 |

**Cobertura de estados**: los **CUATRO ejecutados** — E0, E1, E2 y E3.

**E2** se monta con `pruebas/estados/montar_e2.sh` desde el volcado
`deploy/postgres/initdb/01_retailmind.dump` (2026-08-03 20:25). Esa fecha es lo
que lo hace válido: la contenerización fue el 3 de agosto y la carga masiva el
10/11, así que el volcado es una foto REAL del seed, sin reconstruir ni revertir
nada. Resultado sobre E2: **P03 43/43 · P04 126/126 · P06 17/17**.

Dos cosas que E2 enseñó y que ningún otro estado podía enseñar:

1. **El volcado trae el esquema del 3 de agosto y el código ha avanzado.** El
   script 87 creó `rol_personalizado` el día 6 y la consulta de login la une, así
   que sin él **nadie entra**, con un `bad SQL grammar` que no parece un problema
   de esquema. El montador aplica el DDL posterior (86, 87, 88, 91, 106, 110, 111)
   y deja fuera a propósito los de DATOS (92-105): meterlos convertiría E2 en E3.
2. **Es el único estado con ORÁCULO**, y por eso destapó D-12.

### Defectos

| ID | Sev | Estado datos | Situación |
|---|---|---|---|
| D-01 | S2 | E3 | ✅ **CORREGIDO Y VERIFICADO** |
| D-02 | S2 | E3 | ✅ **CORREGIDO Y VERIFICADO** |
| D-05 | S2 | E0 | ✅ **CORREGIDO Y VERIFICADO** |
| D-08 | S2 | E3 | ✅ **CORREGIDO Y VERIFICADO** |
| D-10 | S3 | — | ✅ **CORREGIDO** |
| D-03 | S3 | E3 | ✅ **CORREGIDO** — los 8 endpoints bajo umbral tras D-11 |
| D-04 | S3 | E3 | ✅ **CORREGIDO** (endpoint huérfano retirado) |
| D-06 | S3 | E0 | ✅ **CORREGIDO** (200 con salvedad, no 409) |
| D-07 | S3 | E0 | ✅ **CORREGIDO** (sello de origen + guardia) |
| D-09 | S2 | E0/E1 | ✅ **CORREGIDO Y VERIFICADO** (pantalla Red Logística) |
| D-11 | S2 | E3 | ✅ **CORREGIDO Y VERIFICADO** (script 111) |
| D-13 | S2 | E3 | ✅ **CORREGIDO Y VERIFICADO** (perfil: género imposible de guardar) |
| D-14 | S1 | E3 | ✅ **CORREGIDO Y VERIFICADO** (perfil: la fecha de nacimiento se borraba sola) |
| D-15 | S2 | E3 | ✅ **CORREGIDO Y VERIFICADO** (el pedido recién hecho caía en la última página) |
| D-16 | S2 | E1 | ✅ **CORREGIDO Y VERIFICADO** (el alta de un CLIENTE no le daba ficha de cliente) |


---

## D-16 · Un usuario dado de alta como CLIENTE no era un cliente — ✅ CORREGIDO

**Cómo se destapó**: haciendo falta uno. Se pidió un cliente vacío para probar
la tienda, se creó por el camino de la aplicación —`POST /api/auth/register`,
que es lo que hay detrás de `/admin-usuarios`— y el usuario resultante podía
entrar, ver el catálogo… y nada más.

**Causa raíz**: `PostgresUserRepository.crearUsuario` escribía `usuario` y
`usuario_rol` y ahí terminaba. Un cliente de la tienda necesita ADEMÁS su fila
en `cliente`: el login resuelve `cliente_id` uniendo `cliente.usuario_id`, y
con eso en null el aspecto `PgSessionRoleAspect` **no fija `app.cliente_id`**,
que es la variable de la que cuelga toda la RLS de la tienda.

**Lo que lo hacía difícil de ver es que el alta parecía funcionar**: devuelve
`{"success": true, "id": 97}` y el usuario aparece en la lista con su rol
CLIENTE. El síntoma llega mucho después y en otra pantalla, medido:

```
POST /api/auth/register     -> 200  {"success":true,"id":97}
     (login del nuevo)      -> 200  token válido, rol CLIENTE
GET  /api/perfil            -> 200  "esCliente": false      ← aquí ya está roto
GET  /api/perfil/direcciones-> 409  "Esta operación es solo para clientes de la tienda"
```

Y hay una segunda mitad peor: donde la aplicación no comprueba `esCliente`, la
RLS **no da error, devuelve cero filas** — la misma trampa que documenta el
script 87 y que en su día dejó a `retailmind_etl` publicando 19 tablas vacías.

**Corrección**: `crearUsuario` crea también la ficha cuando el rol es CLIENTE,
en la MISMA transacción — las tres filas son un solo hecho, y un usuario sin
ficha es exactamente el estado que esto viene a impedir. **No hizo falta ni un
GRANT ni un script**: `grp_administrador` ya tenía INSERT sobre `cliente` y la
política `pol_horario` lo cubre; el hueco era solo de aplicación, igual que en
D-09.

**Lo que NO se tocó, y merece decirse**: `asignarRolUnico` sigue sin crear
ficha. Parecía la otra mitad del mismo agujero, pero al probarlo devolvió un
**409 deliberado** — `UsuarioAdminService.modificar` prohíbe cruzar la frontera
CLIENTE / personal interno en los dos sentidos, porque «la ficha de cliente y
sus pedidos quedarían huérfanos». Esa puerta ya estaba cerrada, y con mejor
criterio: añadir el INSERT allí habría sido código muerto que además sugiere
que la conversión es posible.

**Verificado** de punta a punta sobre el sistema corriendo:

- alta con rol **VENDEDOR** → **0** fichas de cliente (no se crea de más);
- alta con rol **CLIENTE** → ficha creada, y `/api/perfil` pasa de
  `esCliente: false` a **`true`**;
- perfil, direcciones, carrito, lista de deseos y productos comprados
  responden **200 con lista vacía** en vez de 409;
- aislamiento por RLS **medido con dos cuentas a la vez**: el cliente nuevo ve
  **0 pedidos** y `maria.lopez` **80**;
- recorrido en Chrome headless por las 8 pantallas del cliente: ninguna con
  `NaN`/`undefined`, estados vacíos explícitos en carrito, lista de deseos y
  Mis Pedidos, y **consola sin un solo error**.

---

## D-15 · El pedido recién hecho aparecía en la ÚLTIMA página — ✅ CORREGIDO

**Cómo se destapó**: uso real. Un cliente compra, entra en «Mis Pedidos» y su
compra no está por ninguna parte.

**Causa raíz**: `VentasService.listarPedidos` ordenaba `ORDER BY p.id DESC`.
En una base normal el id es cronológico; **en ésta no**: la carga masiva
escribió sus 3.000.000 de pedidos en **bandas de ids reservadas** —hasta
`2.100.055.830`— mientras la secuencia real va por **4.343**. Un pedido creado
hoy nace con id ≈ 4.344, o sea el **más bajo de la tabla**, y con `id DESC`
aterriza en la última página, cientos de páginas detrás del seed. No afectaba
solo al cliente: en el back-office, un vendedor tampoco veía los pedidos del
día en la primera página.

**Corrección**: `ORDER BY p.fecha_pedido DESC, p.id DESC`. El `id` se conserva
como desempate para que el orden sea total y la paginación estable (hay pedidos
que comparten `fecha_pedido` al microsegundo). El índice `idx_pedido_fecha` ya
existía; medido antes y después sobre el sistema vivo: **8-11 ms → 12-19 ms**,
misma escala.

**Lo que el orden por fecha NO arregla, y por eso hubo trabajo de pantalla**:
el catálogo de demostración tiene pedidos fechados **hasta 2034**, así que «el
más reciente» es legítimamente uno de 2034 y una compra de hoy queda en medio
—medido: el pedido real de `maria.lopez` (2026-08-12) tiene **51 pedidos con
fecha posterior**, o sea página 3—. Se resolvió donde se podía resolver:

- **buscador por número** en «Mis Pedidos», resuelto **en servidor** (el
  parámetro `q` ya existía y ninguna pantalla lo usaba), más filtro por estado;
  los dos quedan en la URL;
- la **confirmación del checkout** enlaza a `…/mis-pedidos?q=<número>`, así que
  «Ver este pedido» abre la lista ya filtrada por la compra recién hecha.

**Verificado** (`pruebas/p14_tienda.js`, sección 16): el orden se comprueba
sobre la fecha que llevan los propios números (`PED-AAAAMMDD-…`), y el buscador
localiza un pedido tomado a propósito de la **página 4**.

---

## D-13 · El género del perfil no se podía guardar (ni se leía) — ✅ CORREGIDO

**Cómo se destapó**: uso real. Al elegir un género en `/perfil` y pulsar
«Guardar cambios», la pantalla respondía con **400** y el mensaje genérico
«Los datos enviados no cumplen las reglas de la base de datos».

**Causa raíz**: el formulario ofrecía `F` / `M` / `O` y el CHECK del motor
—`cliente_genero_check`— solo admite `masculino`, `femenino`, `otro` y
`no_indica`. **Fallaba en las dos direcciones**: al escribir lo rechazaba la
base, y al leer, un género ya registrado (`femenino`) no casaba con ninguna
opción del desplegable, así que el campo aparecía **en blanco** aunque el dato
estuviera guardado. Los 50.070 clientes del seed tienen género, y ninguno se
veía.

**Corrección**, en las dos capas:

1. **Pantalla**: las opciones pasan a ser los cuatro valores canónicos, más
   «Sin especificar» (cadena vacía) para no registrarlo.
2. **Servicio** (`PerfilService.actualizarDatos`): lista blanca ANTES de tocar
   la base, con `IllegalArgumentException` → 400 que **nombra el campo y los
   valores admitidos**. Sin ella, cualquier otro cliente de la API seguiría
   recibiendo el 400 opaco de la restricción, que no dice qué campo falla. Es
   la regla 2 del proyecto («lista blanca»), que este servicio no aplicaba.

**Verificado**: los cuatro géneros se guardan y se releen puestos en la
pantalla; `genero=F` → 400 «El género debe ser uno de: masculino, femenino,
otro, no_indica».

---

## D-14 · «Guardar cambios» del perfil BORRABA la fecha de nacimiento — ✅ CORREGIDO

**Severidad S1 por definición del plan**: es un fallo **silencioso**. No había
error, ni aviso, ni rastro; el dato simplemente desaparecía.

**Cómo se destapó**: al revisar D-13 se vio que el formulario no ofrecía la
fecha de nacimiento **y tampoco la enviaba**, mientras que el UPDATE la
escribía siempre: `fecha_nacimiento = NULLIF(?, '')::date` con el parámetro a
`null` la ponía a NULL. Cualquier cliente que guardara su teléfono perdía su
fecha de nacimiento. **50.070 de los 50.072 clientes tienen una.**

**Corrección**:

1. **Pantalla**: la fecha se edita, con tope en hoy, y viaja siempre.
2. **Servicio**: un campo **ausente del cuerpo ya no se toca**; solo se borra
   si llega **presente y vacío** (`body.containsKey(...)` decide, y el UPDATE
   usa `CASE WHEN ?::boolean THEN … ELSE <columna actual> END`). Así el daño no
   depende de que una pantalla concreta se acuerde de mandar el campo. Se
   valida además el formato y que no sea futura, con mensajes propios.

**Verificado** (`pruebas/p14_tienda.js`, sección 15): guardar sin mandar la
fecha la conserva; mandarla vacía la borra a propósito; `14/03/1995` → 400 «debe
tener el formato AAAA-MM-DD»; `2099-01-01` → 400 «no puede ser posterior a hoy».

---

## D-01 · Falta un parámetro obligatorio → 500 en vez de 400 — ✅ CORREGIDO

**Cómo se destapó**: el barrido encontró `GET /api/gerencia/metas/vigente`
devolviendo **500 a los cuatro roles autorizados**. El endpoint estaba caído
para todo el mundo.

**Causa raíz — y por qué importa más que el endpoint**: no era
`MetasVentaService`. Era `MissingServletRequestParameterException` cayendo en el
`@ExceptionHandler(Exception.class)`.

`GlobalExceptionHandler` **no extiende `ResponseEntityExceptionHandler`**, así que
el manejador genérico se queda con toda excepción de Spring Web que no esté
declarada. El archivo ya documenta esto y declara tres casos… y dejó fuera al
resto de la familia. **El defecto no era un endpoint: era una clase entera de
errores de cliente reportándose como fallo del servidor.**

**Corrección** — los **seis miembros que faltaban**, no solo el que dio la cara:

| Excepción | Antes | Ahora |
|---|---|---|
| `MissingServletRequestParameterException` | 500 | **400** nombrando el parámetro |
| `HttpMessageNotReadableException` | 500 | **400** |
| `MissingServletRequestPartException` | 500 | **400** |
| `HttpMediaTypeNotSupportedException` | 500 | **415** |
| `MethodArgumentNotValidException` | 500 | **400** con los campos |
| `MaxUploadSizeExceededException` | 500 | **413** |

**Verificado**: `GET /api/gerencia/metas/vigente` → 400 «Falta el parametro
obligatorio «anio».»; con `?anio=2026&mes=8` → 200. Barrido completo: **0
respuestas 500 en 1.962 llamadas**.

---

## D-02 · Cuerpo JSON malformado → 500 en vez de 400 — ✅ CORREGIDO

Misma causa que D-01. **Alcance**: los **18 controladores con `@RequestBody`**.
Verificado: JSON malformado → 400; `Content-Type: text/plain` → 415.

---

## D-05 · Un fallo interno en el login era indistinguible de una contraseña mal tecleada — ✅ CORREGIDO

`AuthController.login` tenía un solo `catch (Exception e)` que devolvía la
respuesta genérica **sin registrar nada**. La respuesta genérica es correcta y
deliberada —no debe filtrar si el correo existe—, pero al meter en el mismo saco
el rechazo ESPERADO y el fallo INESPERADO, un sistema recién instalado con un
problema de configuración se presentaba como una contraseña equivocada, sin
dejar un solo rastro.

**Corrección**: dos `catch`. `LoginFallidoException` sigue igual (su motivo ya va
a `log_acceso`); cualquier otra excepción se registra con `logger.error` y su
traza. **La respuesta al cliente no cambia.**

---

## D-08 · La restricción horaria volvió a estrecharse — ✅ CORREGIDO

**Cómo se destapó**: P03-008 comparó `grupo_horario` contra el 24/7 que dejó el
script 88 y encontró **dos ventanas fuera**:

| id | rol | día | ventana | efecto |
|---|---|---|---|---|
| 31 | `grp_analista` | **lunes** | 07:00–17:30 | ~13,5 h bloqueadas cada lunes |
| 55 | `grp_bodega` | **domingo** | 00:00–**23:59** | 1 minuto bloqueado cada domingo |

**Es la fragilidad C-11 reapareciendo**: ninguna de las dos las escribió un
script — las escribe la pantalla de admin (`HorariosAdminService`), que puede
reescribir esas ventanas. Ya había pasado una vez (el mismo `grp_analista`
domingo en 00:00-23:30, restaurado el 2026-08-07).

**Por qué nadie lo había visto, y por qué es S2 y no S4**:

1. **Es invisible hasta que llega el día.** Los ocho `esta_en_horario()` daban
   `t` en la corrida — porque no era ni lunes ni domingo. Un fallo que solo
   existe un día de la semana no aparece en ninguna prueba manual.
2. **`fuera_horario` bloquea el LOGIN entero** (script 53), así que el analista
   sencillamente no podía entrar los lunes por la tarde.
3. **`pol_horario` está declarada con `cmd = ALL`, y ALL incluye SELECT**: dentro
   de la ventana cerrada RLS no da 403 — **filtra y devuelve cero filas en
   silencio**.
4. La fila 55 es la trampa de la frontera que el script 88 documenta:
   `esta_en_horario()` compara con el intervalo **semiabierto**
   `[inicio, fin)`, y `23:59` deja fuera `23:59:00–24:00:00`. La única frontera
   que no pierde un instante es `24:00:00`.

**Corrección**: respaldo en `seed_backup.hor_20260819_prepruebas` y ejecución del
**script 90**, que es la herramienta que el proyecto ya tenía para esto:

```
UPDATE 2
NOTICE:  Guardia OK: 24/7 restaurado, 0 minutos bloqueados de 10080.
```

P03 reejecutada: **43/43**.

**Lo que queda abierto es la CAUSA**, no el síntoma: mientras la pantalla de
admin pueda escribir esas ventanas, volverá a pasar. La ficha C-11 sigue vigente;
lo nuevo es que ahora hay una prueba que lo detecta el mismo día.

---

## D-09 · No hay forma de dar de alta una bodega, un transportista ni una zona de envío — 🔶 abierto

**Cómo se destapó**: P05 intentó crear un pedido sobre E0 y falló. La causa no
era la compuerta: **no había ni una bodega**, y no hay manera de crear una.

**Verificado en el código, no supuesto**: cero `INSERT INTO` sobre `bodega`,
`transportista`, `metodo_envio`, `zona_envio` y `tarifa_envio` en **todo**
`retailmind-backend/src/main`. De esas cinco tablas solo existen **lecturas**
(`GET /api/referencias/bodegas`, `/api/referencias/transportistas`).
`GestionDatosController` no ayuda: administra dimensiones de ClickHouse.

**Consecuencias**:

- Una **instalación nueva no puede tomar un pedido** hasta que alguien ejecute
  SQL a mano. Es la familia **V-g** del plan («cadena de negocio sin cimientos»),
  confirmada.
- Una instalación en marcha **no puede abrir una segunda bodega, contratar un
  transportista ni cambiar su cobertura de envío** sin un DBA.
- La pantalla de **transferencias entre bodegas existe** y funciona… pero su
  operando no se puede crear desde la aplicación.

**Matiz honesto**: no es que el sistema esté roto — `deploy/postgres/initdb/`
restaura un volcado que ya trae estos datos, así que una instalación real arranca
poblada. Lo que falta es el **camino de provisión y de mantenimiento**. Por eso
es un hueco funcional (S2) y no un fallo de ejecución.

**Banco de pruebas asociado**: `pruebas/estados/montar_e1.sh` puebla esas cinco
tablas por SQL, y su cabecera explica que existe precisamente porque no hay API.

---

## D-10 · El README ofrecía `docker compose down -v` como comando de rutina — ✅ CORREGIDO

`README.md` lo listaba con un «(CUIDADO: borra datos)» entre paréntesis, mientras
`CLAUDE.md` lo prohíbe terminantemente: el `-v` destruye la base `retailmind`
entera y el `fact_eventos` de ClickHouse — **2.823.245 filas irreproducibles**,
por lo que su volumen va declarado `external: true`.

Un paréntesis no es una barrera: quien copia del README no está leyendo
`CLAUDE.md`. Sustituido por una advertencia explícita que nombra lo que se pierde
y da las dos alternativas seguras (`stop` y `down` a secas).

P13-P01-010 vigila que ningún guion del repo lo reintroduzca.


---

## D-11 · Con RLS activa, el rango de fecha deja de ser condición de índice — 🔶 abierto · **es la causa raíz de D-03**

**Es el hallazgo técnico más importante de esta tanda.** La misma consulta, la
misma tabla, el mismo índice y los mismos datos:

| | superusuario (sin RLS) | bajo `grp_administrador` (con RLS) |
|---|---|---|
| Tiempo | **5,0 ms** | **4.056 ms** |
| Rango de fecha | **`Index Cond`** — busca directo | **`Filter`** — fila a fila |
| Buffers leídos | **107** | **2.936.358** |
| Filas descartadas por el filtro | — | 472.568 |
| Estimación del planificador | 18.906 (real: 19.973) | **1** |

**810× más lento y 27.000× más E/S** para sumar 19.973 facturas de 2.855.380.

**Qué pasa exactamente**: con la política activa, la condición
`fecha_emision >= … AND < …` **se degrada de condición de índice a filtro**. El
índice cubriente `idx_factura_venta_fecha_cubriente` sigue apareciendo en el
plan —por eso el problema no salta a la vista— pero ya no se usa para BUSCAR: se
recorre entero y cada entrada se descarta una por una. La estimación se derrumba
a `rows=1` y el planificador elige además un plan paralelo con 5 trabajadores,
que multiplica el trabajo en vez de repartirlo.

**Lo que se descartó, midiéndolo**:

- **No es la función de la política.** `esta_en_horario(fn_grupo_actual())` no
  referencia ninguna columna, ya es `STABLE`, y medida aparte cuesta 2,1 ms una
  llamada y **4,2 ms veinte mil llamadas** — o sea que sí se está izando fuera
  del bucle. La función no es el cuello.
- **No es que falte el índice.** Existe y es exactamente el adecuado:
  `btree(fecha_emision) INCLUDE (estado, total)`.
- **No lo arregla `LEAKPROOF`.** Se probó `ALTER FUNCTION esta_en_horario(text)
  LEAKPROOF` —la hipótesis natural, porque bajo RLS un qual no-leakproof no
  puede empujarse por debajo del qual de seguridad— y **el plan no cambió**
  (2.936.363 buffers, idéntico). Se revirtió a `NOT LEAKPROOF` en el acto: no
  se deja tocado un atributo de seguridad que no aporta nada. P03 verificada
  después: **43/43**.

**Por qué esto importa más que los ocho informes lentos**:

1. **Explica por qué el filtro por defecto de D-03 apenas sirvió.** Acotar a 90
   días bajó el costo de envío de 17,1 s a 15,6 s — un 9 %— en vez de a menos
   de un segundo. No podía funcionar: si el rango de fecha no llega al índice,
   estrecharlo no reduce lo que se escanea, solo lo que se descarta.
2. **Afecta a toda consulta con rango de fecha sobre una tabla con RLS**, que
   son 50 tablas. Los ocho endpoints lentos son el síntoma visible.
3. **La medición del proyecto ya lo había rozado sin nombrarlo**: «como
   superusuario son 190 ms, o sea el coste ES la RLS» (bloque de OTD-VEN-01).
   Aquí queda medido con su mecanismo, que es lo accionable.

**Lo que NO hay que hacer**: quitar RLS. Es la seguridad del sistema y funciona
(P03, 43/43). El camino es entender por qué el qual no se empuja en PostgreSQL 18
y, si no hay remedio en el planificador, decidir por informe entre acotar el
conjunto de otra forma (como hizo `paginarConTope` en kardex) o precalcular en
el almacén, que es donde estas agregaciones se hacen en milisegundos.

---

## D-03 · Informes lentos en E3 — 🔶 parcialmente corregido

**Lo corregido**, con el patrón de OTD-VEN-01 (`paginarConTope` + los KPI solo
si el conjunto cabe en el tope + `salvedad` que lo explica):

| Informe | Barrido | Aislado antes | **Ahora** | Ganancia |
|---|---|---|---|---|
| `inventario/kardex` | 49,7 s | 24,8 s | **0,36 s** | **69×** |
| `logistica/envios` | 41,3 s | 20,9 s | **4,87 s** | **4,3×** |

En kardex la ganancia es enorme porque la pantalla hacía **dos** recorridos
completos de los 8.008.403 movimientos —el `count(*)` de cabecera (12.445 ms
medidos con `EXPLAIN ANALYZE`) y la consulta de agregados— y ahora no hace
ninguno mientras no se acote.

**Lo que NO funcionó, y por qué se deja dicho**: la otra mitad del patrón —el
filtro por defecto de 90 días en los cinco informes que AGREGAN— solo dio entre
un 7 % y un 21 %:

| Informe | sin filtro | con la ventana por defecto |
|---|---|---|
| `logistica/costo-envio` | 17,08 s | **15,57 s** |
| `compras/entregas-incompletas` | 8,35 s | **6,64 s** |
| `ventas/participacion-canal` | 6,49 s | **6,04 s** |
| `ventas/por-vendedor` | 5,34 s | **4,89 s** |
| `logistica/devoluciones` | 3,30 s | **2,95 s** |

La causa está en **D-11**: bajo RLS el rango de fecha no llega al índice, así
que estrechar la ventana no reduce el escaneo. **El cambio queda aplicado** —lo
pediste y además abrir un informe sobre los últimos 90 días en lugar de sobre
diez años es más sensato— pero **hay que saber que no resuelve el rendimiento**:
eso depende de D-11.

`ventas/avance-meta` (8,4 s) no tiene filtro de fecha —su filtro es un
`periodo`— y es justo la consulta con la que se diagnosticó D-11.

**Nota sobre el arnés**: P12 mide ahora las dos cosas —la ruta pelada como PEOR
CASO declarado (marcada `OMITIDO`, no fallo) y la ruta con el filtro que aplica
la pantalla, que es la que se exige—. Un `GET` sin `desde` tiene que seguir
significando «sin filtro»: el defecto lo pone la PANTALLA y no el servicio, para
que la API no devuelva un subconjunto haciéndolo pasar por el total.

---

## D-04 · `/api/admin/catalogo/productos` devolvía el catálogo entero sin paginar — ✅ CORREGIDO

`productos()` no declaraba ningún parámetro y devolvía los **6.217 productos**
en cada llamada, mientras el hermano `/productos/buscar` **sí** pagina.

**Lo que decidió la corrección**: el endpoint estaba **completamente huérfano**.
Verificado en las cuatro direcciones —frontend, otros servicios del backend,
guiones y documentación—: **cero consumidores**. La pantalla de productos usa
`/productos/buscar` desde siempre. Su javadoc decía «se mantiene para
compatibilidad», escrito cuando el catálogo tenía ~1.200 productos.

**Se retiró** el endpoint, el método del servicio y el método huérfano del
servicio Angular, en vez de paginarlo: cambiar la forma de la respuesta —de
lista a sobre— tendría el mismo riesgo sin la ventaja de eliminar la trampa, y
un endpoint sin tope es una invitación a construir encima. Mismo criterio que
la retirada de `/api/gestion/fact-eventos` (deuda A-3).

**Verificado**: `GET /productos` → **405** (el `POST` sigue existiendo, así que
«método no permitido» es la respuesta correcta — y sale bien gracias al arreglo
D-01, que antes la habría convertido en un 500); `/productos/buscar` → 200 con
20 de 6.217. Las 32 pantallas en navegador: **65/65**.

---

## D-06 · En una instalación nueva, «Avance de la meta» abre con un error — 🔶 abierto

`GET /api/informes/ventas/avance-meta` → **409** «No hay meta de ventas vigente».
El mensaje es claro y el 409 es coherente con la regla 3 del proyecto, pero en
una instalación nueva **no hay meta de ningún mes**, y el usuario está
*consultando* una pantalla, no ejecutando una acción que choque con un estado.

**Corrección (2026-08-19)**: 200 con el sobre vacío y el texto como salvedad,
que es como el resto del catálogo resuelve la ausencia de dato.

El razonamiento que la decidió: **un 409 es un guardia de estado** —«tu acción
choca con la situación actual»— y aquí no hay acción, el usuario está MIRANDO un
informe. En una instalación nueva no hay meta de NINGÚN mes, así que la pantalla
abría rota el primer día.

**Lo que NO cambia**: sin meta no se publica ningún avance. Los cuatro KPI salen
en `null` y **no en cero**, porque un 0 % se leería como «vendimos nada» cuando
lo cierto es «no hay contra qué medir». Un 0 % o un 100 % serían igual de falsos.

**Verificado**: sin meta (enero 2019) → **200**, 0 items, los cuatro KPI en
`null` y la salvedad diciendo qué falta y dónde arreglarlo; con meta (agosto
2026) → 200 con el avance del **92,0 %** de siempre, sin regresión.

---

## D-07 · Nada comprueba que el almacén corresponda a la base operativa — 🔶 riesgo latente

Con PostgreSQL **vacío**, los informes compuestos siguieron publicando cifras de
la carga masiva: *PostgreSQL 0 pedidos · ClickHouse 14.333.990 unidades vendidas*.

**Caracterización honesta**: lo provocó el banco de pruebas —el backend de E0
comparte el ClickHouse de E3—, así que **no es un defecto observado en
producción**. Lo que el experimento demuestra es que si esa desalineación
ocurriera (almacén restaurado de otra copia, backend repuntado, ETL parado mucho
tiempo), **ningún informe lo detectaría**.

**Lo que el sistema SÍ hace ya**: cada sobre compuesto trae `fuente`, `datosAl`
(medido: `17/08/2026 08:36`) y `analiticaDisponible`. La **frescura** está
resuelta y visible; lo que no existe es una comprobación de **correspondencia**.

Enlaza con **P09-003**, que es el mismo problema por el otro lado y que esta
tanda **sí cerró como prueba**: ver abajo.

---

## Lo que las suites nuevas aportan más allá de los defectos

### El control de universo no vacío (P09-003)

Los 49 controles de `validar_dwh.py` comparan PostgreSQL contra ClickHouse y
exigen igualdad al centavo. Son excelentes — y **pasan en verde cuando los dos
lados valen cero**. Un ETL que no cargó nada, o que publicó 21 tablas vacías, se
declara correcto.

`p09_etl.py` añade lo que faltaba: que **las 21 tablas del modelo tengan
universo**, y lo demuestra ejecutando el mismo patrón de control sobre un
conjunto vacío por construcción para enseñar que pasa. Es la misma clase de fallo
que el filtro `naturaleza='ajuste'` (merma ×381 sin que fallara una suma) y que
los seis controles que llevaban dentro el supuesto que comprobaban (C6.4): el
verde era real y la conclusión falsa.

### El invariante de diseño, probado y no supuesto (P13)

`docker compose stop clickhouse` y medición en vivo: **status UP · analytics
DEGRADED**, los informes simples intactos, los compuestos degradando con
`analiticaDisponible=false` dentro del tope de tiempo, y **recuperación sin
reiniciar el backend**. Las dos mitades que se olvidan —que la degradación esté
**acotada en el tiempo** (colgarse no es degradar) y que se recupere sola— ahora
están cubiertas.

### La interfaz contra la base vacía (P11)

Las 32 pantallas recorridas con Chrome headless **contra el backend de E1**, sin
reconstruir el frontend: se desvían las llamadas `/api/` en el navegador, de modo
que se prueba **el mismo bundle compilado** contra otra base. Resultado: 97/97,
sin un solo `NaN`, `undefined` ni `[object Object]`, y con estado vacío explícito.

---

## Falsos positivos del arnés (corregidos; se documentan para no repetirlos)

Han sido **once**, y el patrón se repite: el banco de pruebas falla primero y se
disfraza de defecto del sistema. Por eso cada hallazgo de arriba lleva escrito
cómo se descartó que fuera del arnés.

| # | Síntoma | Causa real |
|---|---|---|
| FP-01 | 110 fallos de 124 en P04 | `requests.Response.__bool__` devuelve `self.ok`: **una respuesta 400 es *falsy***, y `r.status_code if r else -1` convertía cada rechazo correcto en «sin respuesta» |
| FP-02 | `POST` sobre ruta GET daba 403, no 405 | Correcto: `/api/informes/**` acaba en `denyAll()` y Spring Security corre **antes** del enrutado |
| FP-03 | El login de E0 fallaba | El contenedor usaba una imagen de **hace dos meses**, y antes de eso no se unía a la red `retailmind` |
| FP-04 | «34 métodos sin `@Transactional`» | El análisis marcaba **constructores y records**, y su ventana de 40 líneas se colaba en el método siguiente. Con emparejado de llaves: **0** |
| FP-05 | 3 helpers de `Paginacion` sin `@Transactional` | Son `static` y reciben el `JdbcTemplate` por parámetro: corren en la transacción del llamador, y Spring **no proxea métodos estáticos** |
| FP-06 | «Reseñar sin comprar devuelve 201» | La prueba reseñaba **el producto que el cliente acababa de comprar y pagar** en la misma suite |
| FP-07 | «`down -v` en 5 sitios» | El barrido se leía **a sí mismo** y a la documentación que advierte contra el comando |
| FP-08 | «El compose no declara `service_started`» | El bloque incluye el comentario «a proposito NO service_healthy»: buscar la cadena suelta fallaba con el compose correcto delante |
| FP-09 | «-1 marcadores en la bitácora» | La columna es `tarea`, no `tabla`: la consulta erraba y el -1 se leía como hallazgo |
| FP-10 | «181 entradas en error en el ETL» | Son **historia** de tres semanas, incluido el incidente del 2026-08-17 ya reparado. La pregunta correcta es en qué estado quedó la **última** corrida de cada tarea (`argMax`): las 21 en éxito |
| FP-11 | El login del navegador no pasaba con el desvío | `origin: undefined` no quita una cabecera: deja la clave con valor indefinido y Chrome descarta el juego entero |

---

## Estado del criterio de salida

| Criterio | Estado |
|---|---|
| **Defectos abiertos** | ✅ **NINGUNO — los 12 cerrados y verificados** |
| 0 defectos S1 abiertos | ✅ ninguno encontrado |
| 0 defectos S2 abiertos | ✅ **los seis cerrados y verificados**: D-01, D-02, D-05, D-08, D-09 y D-11 |
| 0 respuestas 500 en el barrido | ✅ 0 de 1.962 llamadas |
| Invariantes de datos al centavo | ✅ 17/17 sobre 3 M pedidos y 8 M movimientos |
| Seguridad de motor | ✅ 43/43 |
| Invariante de degradación | ✅ 23/23, probado parando el contenedor |
| Los 4 estados de datos ejecutados | ✅ **E0, E1, E2 y E3** |
| Suites del plan implementadas | **13 de 13** |

---

## D-11 · Con RLS, el predicado se evaluaba una vez POR FILA — ✅ CORREGIDO

**Corrección**: script **`111_rls_initplan.sql`**. Envuelve cada llamada
independiente de la fila en un subselect escalar:

```
esta_en_horario(fn_grupo_actual())  →  (SELECT esta_en_horario(fn_grupo_actual()))
cliente_id = fn_cliente_actual()    →  cliente_id = (SELECT fn_cliente_actual())
```

Eso convierte el predicado en un **InitPlan**: PostgreSQL lo evalúa UNA vez al
arrancar la consulta en lugar de una vez por cada fila examinada. Se reescribieron
las **95 políticas**, con tres guardias que abortan la transacción si cambia el
número de políticas, si alguna pierde su predicado, rol o comando, o si la
compuerta horaria deja de discriminar.

**La seguridad no cambia y esto es lo esencial**: el predicado es EL MISMO. No se
relaja una condición, no se añade ni se quita un rol, no se toca un GRANT. Solo
cambia CUÁNTAS VECES se calcula una expresión cuyo valor es, por construcción,
idéntico para todas las filas de la consulta.

**Medido, antes → después**:

| | antes | después |
|---|---|---|
| Consulta patrón (sumar 19.973 facturas de 2.855.380) | 4.056 ms | **180 ms** |
| Buffers | 2.936.358 | **76.443** |

| Endpoint | antes | después |
|---|---|---|
| `logistica/costo-envio` | 17,08 s | **2,07 s** |
| `ventas/avance-meta` | 8,37 s | **0,32 s** |
| `compras/entregas-incompletas` | 8,35 s | **1,03 s** |
| `ventas/participacion-canal` | 6,49 s | **1,71 s** |
| `ventas/por-vendedor` | 5,34 s | **1,04 s** |
| `logistica/envios` | 4,87 s | **0,85 s** |
| `logistica/devoluciones` | 3,30 s | **0,47 s** |
| `inventario/kardex` | 0,36 s | **0,10 s** |

**Verificado**: P03 seguridad de motor **43/43** después de reescribir las 95
políticas; P06 invariantes **17/17**.

**Lo que se descartó midiendo, para que nadie lo repita**: no faltaba un índice
(el cubriente existe y es el correcto); no es la RLS en sí (reproducido en una
tabla sintética: con `USING (true)` el plan usa el índice y lee 241 buffers, con
la llamada a función 7.556); y **`LEAKPROOF` no lo arregla** — se probó marcando
`esta_en_horario` y `fn_grupo_actual`, el plan no se movió ni un buffer, y las
dos se revirtieron a `NOT LEAKPROOF`.

**Consecuencia sobre D-03**: como el arreglo ataca la causa, los ocho endpoints
lentos quedaron bajo el umbral **sin tocar ni una consulta de informe**. Por eso
se revirtió el filtro por defecto de 90 días: su justificación era el rendimiento
y dejó de aplicar.

---

## D-09 · No había forma de dar de alta una bodega ni la red de envío — ✅ CORREGIDO

**Corrección**: pantalla **Red Logística** (`/operativo/red`, solo ADMIN) con
CRUD de las cinco tablas.

- Backend: `admin/red/RedLogisticaService` + `RedLogisticaController`
  (17 endpoints). **No hizo falta ni un GRANT ni un script SQL**: el motor ya
  concedía a `grp_administrador` INSERT/UPDATE/DELETE sobre las cinco tablas —
  el hueco estaba únicamente en la capa de aplicación.
- `SecurityConfig` no se tocó: la línea `/api/admin/**` ya reserva la rama a ADMIN.
- Frontend: una pantalla con cinco pestañas —son tablas de CONFIGURACIÓN que
  solo tienen sentido juntas: una tarifa necesita su zona y su método, y un
  método su transportista— enganchada en los cuatro puntos de la regla 6
  (`nav-model`, `app.routes.ts`, sidebar y `routeMap` de breadcrumbs).
- **Baja lógica, nunca borrado**: una bodega o un transportista está referenciado
  por pedidos, envíos y kardex históricos. Se desactiva con el toggle `activo`.

**Validaciones que el motor no impone y la aplicación sí**: costo base y costo
por kilo no negativos; peso máximo mayor que el mínimo (si no, el tramo no cubre
ningún envío); plazo máximo no menor que el mínimo; y **una zona de ciudad debe
declarar su provincia** — la resolución del envío compara los dos niveles, así
que sin la provincia la zona quedaría creada, visible y sin efecto.

**Verificación 1 — en navegador** (Chrome headless, 14 comprobaciones, todas en
verde): la ruta abre, las cinco pestañas están, muestra datos reales, consola sin
errores, el enlace aparece en el sidebar; y **GERENTE no ve el enlace y el guard
lo rechaza**.

**Verificación 2 — la que cierra el defecto** (`pruebas/p05_puesta_en_marcha.py`,
**13/13**): partiendo de una base con **cero bodegas, cero transportistas, cero
zonas y cero tarifas**, se puso el sistema en marcha **sin ejecutar una sola línea
de SQL** — bodega → transportista → método → zona → tarifa → marca → categoría →
producto → variante → stock por ajuste de inventario → **y un pedido real**. Eso
era exactamente lo imposible antes.

---

## D-12 · La cifra de kardex «antes de la carga» estaba mal en CLAUDE.md — ✅ CORREGIDO

**Cómo se destapó**: montando E2 desde el volcado del 2026-08-03 y contrastando
sus siete medidas contra la tabla de «LA CARGA MASIVA» de `CLAUDE.md`. Seis
cuadraron **exactas** —pedidos 4.083, líneas 10.384, posiciones 1.406, facturas
3.887, clientes 72, variantes 1.221— y una salió al 57 %:

| medida | documentado | real |
|---|---|---|
| movimientos de kardex | 23.289 | **13.288** |

**Causa raíz, localizada por el tramo de ids**: la columna del ANTES incluía los
**10.000 movimientos de la Fase 0**, que es la primera fase de la carga masiva.
Medido en la base viva: `id < 900.000.000` (pre-carga) = **13.310**; Fase 0
(`9e8 … 1e9`) = **10.000**; suma = **23.310**, que es la cifra publicada.

**El archivo ya se contradecía a sí mismo**: el bloque de la Fase 3B declara
`fact_movimiento_inventario` (**13.287**) para ese mismo período — tres fuentes
independientes en el rango de 13,3k contra una de 23,3k.

**Por qué importa aunque sea documentación**: `CLAUDE.md` se declara autoridad
(«las cifras que mandan»), así que alguien la usaría como oráculo y perseguiría
un descuadre de 10.000 movimientos que no existe.

**Corrección**: cifra corregida a 13.310 y añadida una tercera advertencia a la
tabla explicando el error y la regla que lo evita — **al medir el «antes» de una
carga, filtra por el tramo de ids reservado, que es el criterio de procedencia
que esa misma sección declara**, no por fecha ni de memoria.

---

## Falsos positivos añadidos en esta tanda

| # | Síntoma | Causa real |
|---|---|---|
| FP-12 | «T-4 devuelve 4 importes a los 5 roles» | `es_total` es una BANDERA, y **en Python `bool` es subclase de `int`**: `isinstance(True, (int, float))` es cierto. Más la subcadena «total» dentro de `es_total`. T-4 no lleva ni un importe |
| FP-13 | «El healthcheck del frontend usa localhost» | La palabra está en el COMENTARIO que explica por qué NO se usa. Reincidencia exacta de FP-08: ahora se mira solo la línea `test:` |
| FP-14 | «El backend no compila» | Maven se invocaba desde la raíz del repositorio, donde no hay `pom.xml`: `MissingProjectException` parece error de compilación y es de directorio |
| FP-15 | «4 fallos de paginación en productos» | La suite apuntaba al endpoint que ella misma acababa de hacer retirar (D-04). El 405 era correcto; la lista de rutas paginadas señalaba ahora a `/productos/buscar` |
