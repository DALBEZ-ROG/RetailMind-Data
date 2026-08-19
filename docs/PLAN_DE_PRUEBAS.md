# RetailMind — Plan de Pruebas Completo

> **Estado**: v1.0 — redactado el 2026-08-18.
> **Alcance**: el sistema entero (backend Spring Boot, frontend Angular, motor PostgreSQL con su
> seguridad, almacén ClickHouse, ETL y los dos modelos), en los **cuatro estados de datos** en que
> el sistema puede encontrarse.
> **Premisa**: este plan NO parte de que el sistema esté fallando. Parte de que **nadie lo ha
> probado nunca de forma sistemática**: `retailmind-backend/src/test/` está vacío y no hay un solo
> `.spec.ts` en el frontend. Todo lo verificado hasta hoy se hizo con guiones puntuales de una
> sesión (`retailmind/validar_*.py`, `matriz_*.py`, `verificar_*.js`), que comprueban **cifras**,
> no **comportamiento**, y que nadie vuelve a correr.

---

## 1. Por qué este plan no es la pirámide de pruebas de siempre

En un sistema normal la lógica vive en el código y se prueba con unitarias. **Aquí no.** En
RetailMind la mayor parte de las reglas que pueden fallar viven **fuera de Java**:

| Dónde vive la regla | Ejemplos | Qué la prueba unitaria de Java NO ve |
|---|---|---|
| **Triggers de PostgreSQL** | totales de pedido y factura, `usos_actuales` del cupón, `monto_total` de la devolución, encadenado del kardex | Un mock de `JdbcTemplate` devuelve lo que le digas: el trigger nunca corre |
| **RLS + GRANT + horario** | aislamiento del cliente, segregación financiera de bodega/despacho, ventanas horarias | Con `@MockBean` no hay `set_config('role', …)`: **todo pasa** |
| **Columnas GENERATED** | `subtotal` de línea, totales de cabecera | Se calculan en el motor al insertar |
| **SQL de los informes** | 30 simples + 43 rutas compuestas | Un test que no toca la base valida un `String` |
| **ClickHouse** | 21 tablas, 49 controles | Semántica distinta a PG (LEFT JOIN rellena con el DEFECTO del tipo, no con NULL) |

**Consecuencia de diseño del plan**: el peso está en **pruebas de integración contra motor real**
(N3) y **pruebas de API punta a punta** (N4), no en unitarias con mocks. Las unitarias se reservan
para lo que es **cálculo puro en Java** — y ahí sí son las adecuadas: `DescuentosService`
(prorrateo del cupón), `prevision_demanda.py` (no abre ninguna conexión, por diseño),
`pesoTotalPedido`, los normalizadores de `transformar()` del ETL con su regla de escape.

**Regla número uno de este plan**: *una prueba que pasa contra una base vacía y contra una base
llena, sin cambiar nada, probablemente no está probando nada.* Ver §3.

---

## 2. Sistema bajo prueba (inventario medido, no estimado)

| Pieza | Cantidad | Fuente de la medida |
|---|---|---|
| Controladores REST | **46** | `find … -name "*Controller.java"` |
| Endpoints (método HTTP) | **362** | `pruebas/comun/catalogo.py`, extraídos del código. El `grep` de 390 contaba además los `@RequestMapping` de clase |
| Servicios | **70** | `find … -name "*Service*.java"` |
| Rutas Angular | **69** | `app.routes.ts` |
| Tablas PostgreSQL | **113** | base `retailmind` |
| Tablas del modelo DWH | **21** (+`etl_ejecucion`) | `system.tables` sin `%_new` |
| Roles de motor | **9** `grp_*` + `retailmind_app` + `retailmind_etl` | `pg_roles` |
| Políticas RLS | **95** en 50 tablas | `pg_policies` |
| Triggers de horario | **34** `trg_horario_*` | `pg_trigger` |
| GRANT | **1.355** + 109 ACL de columna | `aclexplode()` |
| Scripts SQL | **125** (01-110 + `99_revert_*`) | `retailmind/sql/postgres/` |
| Informes simples | **30** | catálogo táctico |
| Rutas de informe compuesto | **43** | ídem |
| Tableros estratégicos | **7** | `/api/tableros/*` |
| Modelos | **2** (previsión, alerta) | Fases E2/E3 |
| **Pruebas automatizadas existentes** | **0** al escribir el plan; hoy **4 suites** en `pruebas/` | — |

---

## 3. El eje que estructura todo: los CUATRO estados de datos

Esta es la petición central del plan. **Un sistema de este tipo falla de formas distintas —y
excluyentes— según cuántas filas tenga la base.** Un caso de prueba sin estado declarado es un
caso incompleto.

### E0 · Base VACÍA — esquema creado, cero datos de negocio

Scripts **01-24 + 27** (esquema, triggers, roles, privilegios, RLS, horario, usuario `app`,
usuarios de login). **Sin** los seeds 25/26 ni ninguna fase de carga.
Es el estado de una **instalación nueva de un cliente el primer día**, y es el que nunca se ha
probado.

**Familias de fallo que SOLO aparecen aquí** (hipótesis a verificar, cada una con su caso):

| # | Familia | Mecanismo | Dónde impacta |
|---|---|---|---|
| V-a | **División por cero** | tasas, porcentajes, ticket medio, márgenes, MAPE, lift: todos tienen un denominador que aquí vale 0 | 30 informes simples, 43 compuestos, 7 tableros |
| V-b | **`queryForObject` sin filas** | lanza `EmptyResultDataAccessException` → el handler lo traduce a **404**. Un listado vacío debe dar **200 con lista vacía**, no 404 | cualquier KPI resuelto con `queryForObject` |
| V-c | **Agregado sobre conjunto vacío** | `argMax`, `min`, `max`, `any() OVER` en ClickHouse devuelven el **DEFECTO DEL TIPO**, no NULL (C3B.5) | ETL, tableros, T-7 |
| V-d | **Ancla temporal inexistente** | la alerta de abandono se ancla a `max(fecha_pedido)`; sin pedidos el ancla es NULL y **el modelo entero se indefine** | E3, y por herencia `/clientes-en-riesgo` |
| V-e | **Modelo sin serie** | la previsión necesita ≥ 2 años de historia para estimar factores estacionales; con 0 meses no hay `k` que estimar | E2, `/prevision-demanda` |
| V-f | **Controles que pasan VACÍAMENTE** | los 49 controles del ETL comparan PG contra CH. **0 = 0 pasa en verde.** Un ETL roto en E0 se declara correcto | `validar_dwh.py` — **falso verde, el defecto más peligroso del plan** |
| V-g | **Cadena de negocio sin cimientos** | checkout sin bodega / sin zona de envío / sin tarifa activa → `asignarEnvioPorZona` no encuentra transportista | `/api/carrito/checkout` |
| V-h | **Primer valor de secuencia** | `fn_siguiente_numero_ticket()` con 0 tickets del año; `seq_numero_documento` en su valor inicial; `TICK-2026-0001` | soporte, facturas, DP-…, RET-… |
| V-i | **Frontend con arrays vacíos** | dispersión (834 puntos), embudo, caja-y-bigotes, mapa de calor, barra de avance: los 4 trazados SVG con `[]` | pantalla genérica de tableros |
| V-j | **Paginación de 0 elementos** | `page 1 de 0`, `total=0`, y el cálculo de `Math.ceil(0/20)` | las 30 pantallas de informe |
| V-k | **Login imposible** | si el 23/27 no corrió, no hay usuarios: **el sistema es inaccesible y no lo dice** | arranque |

### E1 · Base MÍNIMA — un dato de cada cosa

E0 + exactamente **1 fila** por entidad del camino crítico (1 producto, 1 variante con peso y
precio > 0, 1 cliente, 1 bodega, 1 zona, 1 tarifa, 1 proveedor). Es el estado que **discrimina el
fallo de frontera**: `n=1` rompe medianas, cuartiles, `lagInFrame`, `row_number() > 1`,
desviaciones estándar y cualquier «comparar con el anterior».

### E2 · Base SEMBRADA — el seed histórico (~4.083 pedidos)

El estado sobre el que se construyeron y validaron todos los informes. Es la **línea base de
regresión**: las cifras de este estado están escritas en `CLAUDE.md` y en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md`, así que sirven de **oráculo**.

### E3 · Base MASIVA — el estado de HOY (2.999.995 pedidos, 17 GB)

**Familias de fallo que SOLO aparecen aquí**:

| # | Familia | Mecanismo | Evidencia ya observada |
|---|---|---|---|
| M-a | **Conteo bajo RLS** | `count`/`sum` sobre 3 M filas con RLS activa cuesta **4,58 s** (190 ms como superusuario: el coste ES la RLS) | VEN-01 abría con «No calculado» |
| M-b | **Filtro no empujable al índice** | `ep.codigo NOT IN (…)` sobre la tabla UNIDA → Parallel Seq Scan de 3 M. Con `p.estado_pedido_id = ANY(?::bigint[])` → Bitmap Index Scan, **20×** | medido: 10,5 s → 0,48 s |
| M-c | **Techo UInt32 del almacén** | 7 columnas del DWH son UInt32 (4.294.967.295); el id más alto va al **49 %** | tramos de la carga masiva |
| M-d | **Topes de infraestructura** | `max_partitions_per_insert_block` (120 > 100), `/dev/shm` de 64 MB, `execution_timeout` de 5 min del DAG | los tres ya corregidos |
| M-e | **Supuesto medido que caduca** | «máx. 1 recepción por OC» era casualidad del seed; una compra real en 2 actos tumbó 3 tablas del DWH | C6.1-C6.5, 2026-08-17 |
| M-f | **Escala de referencias congeladas** | 133 metas históricas dan avances del 874 % al 1.756 % | C-21 |
| M-g | **Exportación masiva** | PDF (iText) y Excel (POI) sobre un conjunto sin tope | sin medir |
| M-h | **Serie demasiado regular** | CV interanual 0,19-0,68 % ⇒ el modelo no supera al ingenuo y publica en `linea_base` | limitación declarada |

### Matriz de aplicabilidad (qué suite se corre en qué estado)

| Suite | E0 | E1 | E2 | E3 |
|---|:--:|:--:|:--:|:--:|
| P01 Arranque y salud | ✅ | ✅ | ✅ | ✅ |
| P02 Seguridad de aplicación | ✅ | ✅ | ✅ | ✅ |
| P03 Seguridad de motor | ✅ | ✅ | ✅ | ✅ |
| P04 Validación de entrada | ✅ | ✅ | ✅ | ✅ |
| P05 Reglas de negocio / compuertas | ⚠️ solo rechazos | ✅ | ✅ | ✅ |
| P06 Integridad e invariantes | ⚠️ trivial | ✅ | ✅ | ✅ |
| P07 Informes simples | ✅ **clave** | ✅ | ✅ oráculo | ✅ |
| P08 Compuestos y tableros | ✅ **clave** | ✅ | ✅ oráculo | ✅ |
| P09 ETL y almacén | ✅ **clave** | ✅ | ✅ | ✅ |
| P10 Modelos | ✅ **clave** | ✅ | ✅ | ✅ |
| P11 Frontend | ✅ **clave** | ✅ | ✅ | ✅ |
| P12 Rendimiento | ➖ | ➖ | ✅ | ✅ **clave** |
| P13 Resiliencia y degradación | ✅ | ➖ | ✅ | ✅ |

---

## 4. Niveles de prueba

| Nivel | Qué es | Herramienta | Contra qué corre |
|---|---|---|---|
| **N1 Unitaria** | cálculo puro en Java/Python, sin E/S | JUnit 5 + AssertJ; pytest | nada externo |
| **N2 Contrato** | forma de la respuesta: el sobre `{items,total,page,size,resumen}`, `ApiErrorDTO`, códigos HTTP | JUnit + `MockMvc` | contexto web, servicio simulado |
| **N3 Integración de motor** | triggers, GENERATED, RLS, GRANT, horario, secuencias | JUnit + `@SpringBootTest` contra **PostgreSQL real** (contenedor dedicado) | motor de verdad |
| **N4 API punta a punta** | los 390 endpoints × 9 roles, con JWT real | Python (`requests`) — el arnés `pruebas/` | sistema levantado |
| **N5 ETL / almacén** | 21 tablas, 49 controles, carga atómica | `validar_dwh.py` extendido | PG + CH |
| **N6 Interfaz** | las 69 rutas, consola limpia, estados vacíos | Puppeteer (Chrome headless) | frontend servido |
| **N7 Rendimiento** | latencia por percentil y plan de ejecución | Python + `EXPLAIN ANALYZE` | E3 |
| **N8 Resiliencia** | ClickHouse abajo, PG abajo, timeouts | `docker stop` + sondas | compose |

---

## 5. Suites de prueba

> Nomenclatura: `PNN-nnn`. Cada caso declara: **precondición · acción · resultado esperado ·
> criterio de fallo**, y el estado (o estados) de datos en que aplica.

### P01 — Arranque, configuración y salud

| ID | Caso | Esperado |
|---|---|---|
| P01-001 | `docker compose up -d` desde volumen vacío | 4 servicios `healthy` ≤ 60 s |
| P01-002 | Arranque **sin** `postgres.datasource.password` | el backend **falla al arrancar** (es deliberado), no arranca degradado |
| P01-003 | Arranque **sin** `jwt.secret` | ídem |
| P01-004 | `GET /api/health` sin ClickHouse | `status: UP`, `analytics: DEGRADED`, **≤ 5 s acotados** |
| P01-005 | `GET /api/health` sin PostgreSQL | responde (no cuelga) y declara la caída |
| P01-006 | Healthcheck del frontend | contra `127.0.0.1`, nunca `localhost` (resuelve a `::1` y nginx solo escucha IPv4) |
| P01-007 | **E0**: arranque con base sin usuarios | debe fallar de forma **diagnosticable**, no con un 401 genérico |
| P01-008 | `mvn compile && ng build` | 0 errores, 0 warnings nuevos |
| P01-009 | Reconstrucción `--build` no borra datos | el volumen sobrevive |
| P01-010 | Ningún `down -v` en ningún guion del repo | `grep` en todo el árbol = 0 coincidencias |

### P02 — Seguridad de aplicación (JWT, rutas, guards)

| ID | Caso | Esperado |
|---|---|---|
| P02-001 | Login de los 10 usuarios de demo | 200 + JWT con la authority correcta |
| P02-002 | Login con contraseña errónea | 401, y **fila en `log_acceso`** con motivo |
| P02-003 | Login de usuario inactivo / inexistente / fuera de horario | 401 con los 4 motivos de `LoginFallidoException` diferenciados en el log |
| P02-004 | **Matriz completa 362 endpoints × 9 roles** (218 de lectura barridos) | cada celda 200/403 según `SecurityConfig`; **0 discrepancias** |
| P02-005 | Petición sin token a ruta protegida | 401 |
| P02-006 | Token expirado / firmado con otro secreto / manipulado | 401, nunca 200 |
| P02-007 | Token de CLIENTE contra `/api/ventas/pedidos` | 403 |
| P02-008 | `roleGuard` de las 69 rutas Angular | espeja `SecurityConfig`; **ninguna ruta con endpoint accesible y pantalla cerrada** (o al revés) |
| P02-009 | `/api/gestion/fact-eventos/{pk}` GET/PUT/DELETE | **404** — retirados el 2026-08-07 (A-3) |
| P02-010 | Rutas de dinero enumeradas **por nombre**, nunca por comodín | inspección + prueba: un endpoint nuevo no hereda permiso |
| P02-011 | CORS solo desde el origen del frontend | preflight correcto |
| P02-012 | El JWT no viaja en la URL ni se registra en logs | `grep` en logs |

### P03 — Seguridad de motor (lo que de verdad protege)

| ID | Caso | Esperado |
|---|---|---|
| P03-001 | **Todo acceso a PG dentro de `@Transactional`** | análisis estático: 0 métodos fuera de transacción. *Fuera de ella corre sin privilegios y **se salta la seguridad de motor***, que es peor que fallar |
| P03-002 | `set_config('role','grp_x; DROP TABLE marca',true)` | «role does not exist» — el nombre viaja **ligado**, no concatenado |
| P03-003 | Aislamiento RLS del cliente | el cliente ve **solo sus** pedidos; nunca los 2.999.995 |
| P03-004 | RLS en las 50 tablas | ninguna devuelve fila ajena bajo `grp_cliente` |
| P03-005 | **RLS filtra en silencio** | con `pol_horario` activa y rol fuera de ventana, `SELECT` devuelve **0 filas sin error**. La prueba debe distinguir «0 filas» de «sin privilegio» |
| P03-006 | Segregación financiera | BODEGA y DESPACHO reciben 403 o respuesta **sin columnas de monto** en los endpoints marcados |
| P03-007 | Excepción declarada: BODEGA sí lee `precio_unitario` de los detalles | y la UI **no lo muestra** |
| P03-008 | Script 89 → 90 (demo horaria) | 89 bloquea un rol y **aborta si `esta_en_horario()` sigue true**; 90 restaura y **aborta si queda 1 minuto bloqueado** |
| P03-009 | Frontera `24:00:00` del intervalo semiabierto | ningún microsegundo fuera |
| P03-010 | Rol personalizado (script 87) recién creado | **403 del motor** hasta encender privilegios; con `USAGE` pero sin política RLS lee **0 filas** |
| P03-011 | `fn_admin_cambiar_permiso` verifica el privilegio **antes y después** | devuelve `aplicado`; un GRANT de no-propietario emite WARNING y no hace nada |
| P03-012 | Las 4 protecciones R1-R4 de la pantalla de permisos | `grp_administrador` intocable; identidad cerrada en ambas direcciones; solo `grp_*` destinatarios; solo tabla/columna |
| P03-013 | `retailmind_etl` es de solo lectura en **4 capas** | probar capas 2 y 4 **por separado** (con la sesión READ WRITE el motor sigue negando) |
| P03-014 | `retailmind_etl` con BYPASSRLS | `SELECT count(*) FROM pedido` da el total, no 0 |
| P03-015 | Inyección SQL en los 2 puntos de concatenación conocidos | `analytics/`, `GestionDatosService:86-96` — verificar que **no** son alcanzables con entrada de usuario |
| P03-016 | Contraseñas: 0 secretos en el índice de git | los 4 archivos fuera del índice; barrido del diff |

### P04 — Validación de entrada

| ID | Caso | Esperado |
|---|---|---|
| P04-001 | **Lista blanca de filtros**: valor no previsto en los 30 informes simples | **400**, nunca 500 ni resultado vacío silencioso |
| P04-002 | Ídem en los 43 compuestos y 7 tableros | 400 |
| P04-003 | Parámetro con tipo cambiado (`?varianteId=abc`) | 400 con el nombre del parámetro (no 500) |
| P04-004 | Ruta inexistente bajo `/api/**` | 404 (no 500) |
| P04-005 | Método HTTP equivocado | 405 (no 500) |
| P04-006 | Cuerpo JSON malformado / campo obligatorio ausente / tipo incorrecto | 400 con mensaje accionable |
| P04-007 | **Alta de variante**: peso ausente, `0` o negativo | **400**; con `1.234` → 201 |
| P04-008 | **Edición de variante**: peso omitido | **conserva** el anterior (`COALESCE`); con `0` → 400 sin modificar nada |
| P04-009 | **Precio** de variante: `0`, negativo o nulo | 400 al crear **y** al editar (el CHECK del motor admite `0`: la guarda es de aplicación) |
| P04-010 | Cantidades negativas o cero en pedido, ajuste, transferencia, recepción | 400 |
| P04-011 | Fechas invertidas (`desde` > `hasta`) | 400 |
| P04-012 | Paginación: `page` negativa, `size` = 0, `size` = 10.000 | 400 o tope declarado — nunca volcar la tabla |
| P04-013 | Textos en el límite y por encima de la longitud de columna | 400, no 500 por `DataIntegrityViolation` |
| P04-014 | Unicode, acentos y emoji en campos de texto | se persisten y se releen idénticos |
| P04-015 | Cupón: código inexistente, caducado, agotado, monto mínimo no alcanzado, ya usado por el cliente | 5 motivos **distintos y claros** |
| P04-016 | Tarjeta simulada: Luhn inválido, MM/AA vencida, CVV corto | 400; **jamás se persiste PAN ni CVV** (solo marca + últimos 4) |
| P04-017 | Parámetro `null` hacia PG en contexto no tipado | castear en SQL (`?::bigint`); sin cast → error del driver |
| P04-018 | Idempotencia: repetir una acción de estado ya aplicada | **409** con mensaje claro |

### P05 — Reglas de negocio y compuertas de estado

**Ciclo de compra** (orden → aprobación → recepción → factura → pago):

| ID | Caso | Esperado |
|---|---|---|
| P05-001 | Recibir sin aprobar | 409 |
| P05-002 | Facturar sin recepción completa | 409 |
| P05-003 | CxP o pago sin factura | 409 |
| P05-004 | Aprobar con rol distinto de GERENTE/ADMIN | 403 |
| P05-005 | **Recepción en varios actos** (el caso que tumbó el DWH) | permitido; `cantidad_recibida` = suma; el DWH usa la recepción **canónica** (la última por `fecha_recepcion, id`) |
| P05-006 | Rechazo en puerta | va al pool `item_defectuoso` sin tocar stock |

**Ciclo de venta** (confirmado → pagado → facturado → en_preparacion → preparado → despachado → entregado):

| ID | Caso | Esperado |
|---|---|---|
| P05-010 | Saltarse cualquiera de los 7 saltos | 409 |
| P05-011 | `registrarPago` sobre pedido de canal `web` | **409** (nace pagado) |
| P05-012 | `POST /api/ventas/pedidos` con canal `web` | rechazado |
| P05-013 | Facturar pedido que no está exactamente en `pagado` | 409 |
| P05-014 | Despachar pedido que no está `preparado` | 409 |
| P05-015 | Entregar con novedad abierta | bloqueado |
| P05-016 | Devolución antes de la entrega | rechazada, salvo `despachado` = rechazo en puerta |
| P05-017 | Plazo de 30 días de devolución | día 30 sí, día 31 no (frontera) |
| P05-018 | Checkout online | pedido nace **PAGADO** y **FACTURADO** en una sola transacción |
| P05-019 | Transportista asignado por zona | ciudad > provincia > país > tarifa activa más barata |
| P05-020 | Novedad de envío: 4.º intento de reprogramación | rechazado (máx. 3) |
| P05-021 | Dos novedades abiertas a la vez | rechazado |
| P05-022 | Devolver al almacén | pedido → `no_entregado` terminal, **sin** reingreso de stock |

**Descuentos**:

| ID | Caso | Esperado |
|---|---|---|
| P05-030 | Promoción por línea | gana la de mayor prioridad; solo las `acumulable` suman |
| P05-031 | Promoción + cupón | combinan (promo a la línea, cupón sobre el subtotal ya rebajado) |
| P05-032 | Dos cupones en un pedido | rechazado (UNIQUE `uso_cupon.pedido_id`) |
| P05-033 | Cupón al límite de usos, en **concurrencia** (N peticiones a la vez) | solo se aceptan los permitidos; el trigger con `FOR UPDATE` es el backstop |
| P05-034 | Cupón `envio_gratis` | **no** reescala IVA pero **sí** se prorratea en `factura_venta_detalle` |
| P05-035 | Prorrateo del cupón en la factura | ajuste de redondeo en la última línea; suma exacta al centavo |

**Reseñas y soporte**:

| ID | Caso | Esperado |
|---|---|---|
| P05-040 | Reseñar producto no comprado | 409 «Solo puedes reseñar productos que has comprado» |
| P05-041 | El selector ofrece solo productos comprados | conjunto = pedidos pagados→entregados (incluye `devuelto`) |
| P05-042 | Prioridad del ticket | **automática** por categoría; el cliente no la elige |
| P05-043 | Cliente responde un ticket `resuelto` | reapertura |
| P05-044 | SLA | urgente 2 h / alta 4 h / media 24 h / baja 72 h, con indicador VENCIDO |

### P06 — Integridad de datos e invariantes

| ID | Caso | Esperado | Estado |
|---|---|---|---|
| P06-001 | **Nunca escribir columnas GENERATED ni totales de cabecera** | análisis estático: 0 INSERT/UPDATE sobre ellas | todos |
| P06-002 | Ídem `fecha_actualizacion` y `usos_actuales` | 0 escrituras directas | todos |
| P06-003 | **Kardex: ecuación** `stock_anterior + cantidad×factor = stock_nuevo` | 0 filas incumpliendo | E2/E3 |
| P06-004 | **Kardex: encadenado** leyendo por `(fecha_creacion, id)` | 0 enlaces rotos, 0 cadenas mal arrancadas | E2/E3 |
| P06-005 | **Cierre del kardex vs `inventario.stock_actual`** posición por posición | 11.407 / 11.407, **0 descuadradas** | E3 |
| P06-006 | `factura_venta.total = pedido.total − pedido.costo_envio` | invariante real (la factura no factura el flete) | E2/E3 |
| P06-007 | Cuadre contable: facturas de compra − pagos = saldo CxP | **descuadre $0,00** | E2/E3 |
| P06-008 | `factura_venta` **no** es 1:1 con el pedido | la factura canónica (`DISTINCT ON`) es obligatoria; sin ella sobran filas | E2/E3 |
| P06-009 | `movimiento_id` no es único entre `pago` y `pago_proveedor` | la clave es el par `(sentido, movimiento_id)` | E2/E3 |
| P06-010 | `fecha_creacion` SIEMPRE explícita en el kardex | el trigger valida la FILA, no el ENLACE (C-2) | todos |
| P06-011 | Reversión de una siembra: aplicar → revertir → **bit-idéntico** (md5 por tabla) | idéntico, 3 veces | E2 |
| P06-012 | Numeración de documentos: sin huecos ni duplicados bajo concurrencia | secuencia global + lock | todos |
| P06-013 | `TICK-AAAA-NNNN` correlativo por año bajo lock | sin colisión con N clientes a la vez | todos |
| P06-014 | Huérfanos en el DWH en **ambas direcciones** | 0 | E2/E3 |

### P07 — Informes simples (30, contra PostgreSQL)

| ID | Caso | Esperado |
|---|---|---|
| P07-001 | Los 30 responden 200 en los 4 estados | sin 500 |
| P07-002 | **E0**: los 30 con base vacía | 200 + `{items:[], total:0}` + KPIs **explícitos** («sin datos»), no `NaN`, no `null` crudo, no 404 |
| P07-003 | Sobre uniforme `{items,total,page,size,resumen[]}` | los 30 |
| P07-004 | Todo método en `@Transactional(readOnly = true)` | análisis estático — sin él corre sin `SET LOCAL ROLE` |
| P07-005 | Guarda NULL por parámetro `(?::tipo IS NULL OR col = ?::tipo)` con el valor **dos veces** | 0 concatenaciones |
| P07-006 | Valores por defecto que son **diseño**: SOP-01 `pendientes`, VEN-01 `en_curso`, GER-04 `vigente`, GER-06 `vigente` | un GET sin filtro respeta el defecto **declarado en el frontend**, y el servicio solo lo traduce |
| P07-007 | VEN-01 con el defecto `en_curso` | 75.139 pedidos / $13.486.538,26 — **los tres KPI calculados**, ≤ 1 s |
| P07-008 | VEN-01 con «Todos los estados» | vuelve a «No calculado» **con la explicación visible** (comportamiento correcto, no bug) |
| P07-009 | La lista de terminales vive en **UNA** constante | filtro y KPI no pueden divergir |
| P07-010 | Los ids de estado se **resuelven desde los códigos**, jamás se escriben | `entregado` es el id 6, no el que sugiere el `orden` |
| P07-011 | VEN-02 recorta al VENDEDOR a lo suyo | `alcance: "propio"` |
| P07-012 | Carritos abandonados con `COALESCE(fecha_actualizacion, fecha_creacion)` | el trigger touch no dispara en los abandonados |
| P07-013 | GER-01 sin movimiento | fila explícita «Día sin movimiento» + KPI «Último día con pedidos» |
| P07-014 | Un `date` puro viaja formateado con `to_char` | no se muestra un día antes por UTC |
| P07-015 | COM-01: `pendiente_aprobacion` = `borrador`+`enviada` | no existe estado `aprobada` |
| P07-016 | COM-11 `alcance` arranca en `entregadas` | con «todas», el mejor proveedor (99,71 %) pasa a peor (91,77 %) |
| P07-017 | INV-08 y VEN-15: barra de avance **opt-in** | solo VEN-15 la declara |
| P07-018 | VEN-15 sin meta del mes | hoy da **409**; decidir si es el comportamiento correcto o debe ser 200 con aviso |
| P07-019 | Matriz de roles de los 30 | BODEGA/DESPACHO 403 donde hay dinero |

### P08 — Informes compuestos (43 rutas) y tableros (7)

| ID | Caso | Esperado |
|---|---|---|
| P08-001 | Los 43 + los 7 responden 200 | en los 4 estados |
| P08-002 | **E0**: con almacén vacío | 200 con series vacías y **denominador declarado**, no división por cero |
| P08-003 | `bloque()` **exige** `denominador` | revienta sin él, a propósito |
| P08-004 | Una respuesta por tablero, no una por elemento | 6 peticiones dejarían medio tablero pintado si CH cae a mitad |
| P08-005 | Los 2 elementos que salen de PostgreSQL (carrito abandonado, sobre-stock) | **siguen vivos con ClickHouse apagado** |
| P08-006 | Embudo T-1: «alcanzó este hito **o uno posterior**» | monótono; con la marca a secas pinta una fuga del 26 % inexistente |
| P08-007 | Salvedades obligatorias: costo vigente, moneda constante, sin tarifar, muestra débil | pintadas **encima** de la cifra |
| P08-008 | T-4 es el único tablero **sin dinero** | recorrer la respuesta entera buscando nombres monetarios, en los 5 roles: 0 |
| P08-009 | T-3 recorta a SOPORTE **por la consulta** | `bloquesOmitidos` declara cuáles |
| P08-010 | INV-10 filtra por `es_ajuste_real`, **jamás** por `naturaleza='ajuste'` | con el filtro obvio la merma se multiplica **381×** sin fallar ninguna suma |
| P08-011 | LOG-11: la zona se resuelve ciudad > provincia > país | agrupar por país manda 2.855 de 2.872 a una fila **sin error** |
| P08-012 | Los 24 envíos con `costo=0` son «sin tarifar», no gratis | excluidos del promedio y **declarado cuántos** |
| P08-013 | COM-04: el mes del gasto es el de la **FACTURA** | agrupar por la orden desplaza $4,6 M **sin descuadrar el total** |
| P08-014 | COM-12: frontera de partición con `row_number() > 1` | `lagInFrame` rellena con **0**, no con NULL |
| P08-015 | Ningún alias de agregado se llama como su columna | prefijo `t_` — `ILLEGAL_AGGREGATION` |
| P08-016 | División de `Decimal`/`Decimal` | los porcentajes en `toFloat64`; **el dinero NO** |
| P08-017 | `formatDateTime` usa `%i` para minuto | `%M` es el nombre del mes |
| P08-018 | `String.formatted()` interpreta el bloque **entero**, comentarios incluidos | un `%` suelto en un comentario tumba la consulta |
| P08-019 | Un bloque de texto Java **recorta el espacio final** de cada línea | `"""SELECT """ + col` → `SELECTpr.razon_social` |
| P08-020 | Matriz completa 50 rutas × 8 roles | 0 discrepancias |
| P08-021 | Cifra tomada de la **RESPUESTA HTTP** y contrastada contra PG | Δ = 0 en los controles ya escritos (132 + 71 + 41) |

### P09 — ETL y almacén

| ID | Caso | Esperado |
|---|---|---|
| P09-001 | DAG completo `retailmind_dwh` | 22/22 tareas `success`, 0 abortadas |
| P09-002 | Los 49 controles | exactos, Δ = 0 |
| P09-003 | **E0: los 49 controles con base vacía** | ⚠️ **el control debe DISTINGUIR «cuadra en 0» de «no se cargó nada»**. Hoy `0 = 0` pasa en verde: hace falta un control de **universo no vacío** o marcar la corrida como `sin_datos` |
| P09-004 | Carga atómica | staging `_new` → validar → `EXCHANGE TABLES`; si falla, **la publicada no se toca** |
| P09-005 | Primera carga de una tabla | `RENAME TABLE`, porque `EXCHANGE` exige que ambas existan |
| P09-006 | Apuntar el pipeline a la base legada | `BaseProhibida` |
| P09-007 | NULL contra columna `Decimal` no-Nullable | reproduce `InvalidOperation: [ConversionSyntax]`; el mensaje **no nombra columna ni fila ni tabla** |
| P09-008 | `peso_kg` y `margen_catalogo_pct` son `Nullable` | se arregla con `Nullable`, **nunca** con `COALESCE(…,0)` |
| P09-009 | Una regla de escape se reimplementa **COMPLETA** en el control | el de motivos traducía el sinónimo pero no el escape a `'Otro'` |
| P09-010 | Motivo de texto libre nuevo | cae en `'Otro'` y se registra en la bitácora, no aborta |
| P09-011 | El grano del control es el mismo que el de la tabla | 6 consultas de control llevaban el supuesto dentro y **acusaban a la tabla de un descuadre que estaba en el control** |
| P09-012 | Fan-out **dentro de un LATERAL** | `count(*)` vs `countDistinct(id)` pasa en verde: hace falta contrastar `lineas` contra el detalle |
| P09-013 | `SUM` sobre cero filas es NULL | no distingue «recibí 0» de «no hubo recepción»: usar un `count` |
| P09-014 | `etl_ejecucion`: `corrida` y `validar_dwh` no son tablas | sumarlas da el **doble exacto** (128.214 donde hay 64.085) |
| P09-015 | Una corrida escribe **22 pares** de marcadores, no uno | `argMax` colapsa |
| P09-016 | DAG pausado + disparo manual | queda `queued` y arranca de golpe al despausar: **despausar antes de disparar** |
| P09-017 | `DWH_CRON=-` | el `@Scheduled` del backend apagado; si no, dos procesos compiten por `EXCHANGE TABLES` |
| P09-018 | Medir el almacén | `system.tables` excluyendo `etl_ejecucion` y `%_new` |
| P09-019 | Reejecución del ETL sin cambios | idempotente, mismas cifras |
| P09-020 | Corte del ETL a mitad (matar la tarea) | la tabla publicada intacta, la corrida marcada |

### P10 — Modelos (previsión E2, alerta E3)

| ID | Caso | Esperado |
|---|---|---|
| P10-001 | El modelo de previsión **no abre ninguna conexión** | entran 2 vectores, sale una previsión: unitaria pura |
| P10-002 | **E0/E1: serie sin historia suficiente** | no publica basura: aborta o publica `linea_base` **diciéndolo** |
| P10-003 | `k` se **estima** de los datos (Stein), no se fija en 2 | con `k≈2` el modelo **se rechaza a sí mismo** (cobertura 100 % suspende el criterio) |
| P10-004 | El nivel de las razones es **estacionalmente neutro** | el filtrado persigue mayo y aplana los 12 factores a 0,98-1,02 |
| P10-005 | Cobertura medida sobre los **881 puntos**, no sobre 6 | una banda perfecta al 80 % da 6/6 el 26 % de las veces |
| P10-006 | Mes truncado detectado y publicado en `horizonte_efectivo` | la tabla sale idéntica se excluya o no |
| P10-007 | La banda se ensancha con el horizonte | exigible **serie a serie**, no fila a fila |
| P10-008 | **E0: alerta de abandono sin ancla** | `max(fecha_pedido)` NULL ⇒ debe **no publicar**, no publicar 69 alertas |
| P10-009 | La ventana es **relativa** (7 meses desde el ancla), nunca una fecha | escrita como fecha funciona **exactamente una vez** |
| P10-010 | Guardia de concentración | **aborta** si un cliente supera el 25 % de un mes; con 19 meses da **100 %** y el 2.º cliente sale como el más perdido |
| P10-011 | La recencia se ancla al almacén, **jamás al reloj** | si el ETL se para, los 69 cruzarían el umbral a la vez |
| P10-012 | Lift con el denominador de **su propio origen** + valor **p** | 1,99× sobre 14 positivos sin p se lee como éxito |
| P10-013 | Los clientes `sin_muestra` se publican | son los candidatos más fuertes y el modelo los expulsaba |
| P10-014 | Recorte del VENDEDOR por **nombre**, no por `vendedor_id` | el almacén guarda el nombre; deja 50 de 69 |
| P10-015 | La ventana de backtest **rueda** con el origen | fija mide 1,34; rodante 1,99 |

### P11 — Interfaz (69 rutas)

| ID | Caso | Esperado |
|---|---|---|
| P11-001 | Las 69 rutas cargan | Chrome headless, **consola sin errores de aplicación** |
| P11-002 | **E0: las 69 con base vacía** | estado vacío **explícito y legible** («aún no hay datos»), nunca tabla rota, `NaN`, `undefined` ni spinner eterno |
| P11-003 | **E0: los 4 trazados SVG con `[]`** | dispersión, embudo, caja-y-bigotes, mapa de calor no revientan |
| P11-004 | Errores al usuario siempre por `mensajeError()` de `api-error.util.ts` | 0 `alert()`, 0 volcados de excepción |
| P11-005 | Toda pantalla CRUD nueva imita `features/operativo/` | tabla Material + formulario colapsable + toggle activo |
| P11-006 | Ruta nueva enganchada en los **4 sitios** | `SecurityConfig` + `roleGuard` + sidebar (`canX`) + `routeMap` de breadcrumbs |
| P11-007 | El `roleGuard` de un área es la **UNIÓN** de quien ve al menos un informe | si no, el endpoint da 200 a una pantalla que el rol no puede abrir |
| P11-008 | Rótulos del SVG con `<title>` nativo | con 1.933 `matTooltip` el navegador deja de responder |
| P11-009 | Peso vacío en el diálogo de variante | «Aceptar» **deshabilitado**; aviso solo en las que YA venían vacías (`pesoOriginal` capturado al abrir) |
| P11-010 | Columna PESO marca `sin peso` en rojo | las 3 variantes 2427-2429 |
| P11-011 | Paginación server-side en las pantallas de listado | no se traen 3 M filas al navegador |
| P11-012 | Responsivo y sin scroll horizontal del `body` | tablas anchas con su propio `overflow-x` |

### P12 — Rendimiento (E3)

| ID | Caso | Umbral |
|---|---|---|
| P12-001 | Los 30 informes simples con su filtro por defecto | p95 ≤ **1,5 s** |
| P12-002 | Los 43 compuestos + 7 tableros | p95 ≤ **3 s** |
| P12-003 | Login | p95 ≤ 800 ms |
| P12-004 | Catálogo con búsqueda + filtro + paginación | p95 ≤ 1 s |
| P12-005 | Checkout completo | p95 ≤ 3 s |
| P12-006 | **Plan de ejecución** de las consultas críticas | Bitmap/Index Scan, **nunca Parallel Seq Scan sobre `pedido`** |
| P12-007 | Coste de la RLS medido aparte | `set_config` vs superusuario: documentar el factor (hoy 24×) |
| P12-008 | Carga del DAG completo | ≤ 20 min |
| P12-009 | Exportación PDF/Excel del conjunto máximo | con tope declarado; sin tope = defecto |
| P12-010 | Memoria del backend bajo 20 peticiones concurrentes de informe | sin OOM |
| P12-011 | El benchmark columnar se rehace a la escala de HOY | el punto pequeño de la curva (66.082) ya no existe: hoy son 26.971.498 |

### P13 — Resiliencia y degradación

| ID | Caso | Esperado |
|---|---|---|
| P13-001 | `docker stop clickhouse` | `status: UP` / `analytics: DEGRADED` ≤ 5 s; simples intactos; compuestos y tableros con `analiticaDisponible: false` |
| P13-002 | Recuperación | **sin reiniciar el backend** |
| P13-003 | Solo un fallo de **CONEXIÓN** degrada | una consulta mal formada se propaga como **500**; capturar todo `DataAccessException` disfrazaba bugs de SQL |
| P13-004 | `service_started` y **nunca** `service_healthy` para ClickHouse | inspección del compose |
| P13-005 | PostgreSQL caído | error claro; **sin base no hay sistema**, y debe decirlo |
| P13-006 | Airflow abajo | el sistema sirve el último dato publicado; la frescura se declara |
| P13-007 | ETL abortado a mitad | la tabla publicada conserva la corrida anterior y **los informes siguen coherentes** (ya observado el 2026-08-16) |
| P13-008 | Reinicio del contenedor de PG | datos intactos (viven en el volumen) |

---

## 6. Criterios de severidad y de salida

| Sev | Definición | Ejemplo real de este sistema |
|---|---|---|
| **S1 Crítico** | Pérdida o corrupción de datos, brecha de seguridad, o **cifra incorrecta que se muestra como correcta** | borrar 139 eventos creyendo borrar 1 (A-3); merma 381× por el filtro obvio |
| **S2 Grave** | Funcionalidad del camino crítico caída, o 500 en operación normal | 3 tablas del DWH sin publicar |
| **S3 Medio** | Comportamiento incorrecto con rodeo posible | 404 donde debía haber lista vacía |
| **S4 Leve** | Cosmético o mensaje mejorable | rótulo desactualizado |

**Un fallo silencioso es S1 por definición**, aunque el síntoma parezca leve: en este sistema la
familia de defectos más costosa no da error — RLS que devuelve 0 filas, LEFT JOIN que rellena con
el defecto del tipo, control que cuadra por coincidencia, filtro que casa con cero filas.

**Criterio de salida**: 0 defectos S1 y S2 abiertos; los S3 con ficha en `DEUDA_TECNICA.md`; la
matriz de roles con **0 discrepancias**; los 49 controles del ETL exactos **y** con universo no
vacío; los 4 estados de datos ejecutados.

---

## 7. Arnés de ejecución

```
pruebas/
├─ README.md
├─ comun/           conexión, JWT, aserciones, tabla de resultados
├─ estados/         montaje y desmontaje de E0 / E1 / E2 / E3
├─ p01_arranque/ … p13_resiliencia/
└─ informes/        resultado por corrida (JSON + Markdown)
```

Ejecución:

```bash
py -3 pruebas/correr.py --estado E3 --suite todas
py -3 pruebas/correr.py --estado E0 --suite P07,P08,P11   # el hueco no probado
py -3 pruebas/correr.py --caso P07-002
```

Cada corrida deja un informe con: caso, estado de datos, resultado, cifra observada, cifra
esperada, y **la petición exacta para reproducirlo**.

**Nota sobre E0 y E1**: se montan sobre una base **aparte** (`retailmind_pruebas`) en el mismo
contenedor, nunca sobre `retailmind`. El guardia de base del benchmark
(`comun.guardia_base`) es el precedente y el patrón a copiar.

---

## 8. Orden de ataque

El plan es grande; el orden importa porque unas suites descubren defectos que invalidan a otras.

| Fase | Suites | Por qué primero |
|---|---|---|
| **F1** | P01, P02, P03 | Si la seguridad no está donde se cree, todo lo demás se prueba con el rol equivocado |
| **F2** | P04, P05, P06 | Las reglas de negocio y los invariantes, en E2/E3 (hay oráculo) |
| **F3** | **P07, P08, P11 en E0** | **El hueco real**: la base vacía nunca se ha probado y ahí está el grueso de los defectos esperados |
| **F4** | P09, P10 | ETL y modelos, con el control de universo no vacío ya añadido |
| **F5** | P12, P13 en E3 | Rendimiento y degradación sobre el estado de hoy |

---

## 9. Registro de defectos

`docs/pruebas/DEFECTOS.md` — una fila por defecto: ID, caso que lo destapó, estado de datos,
severidad, causa raíz, corrección, verificación. Los que no se corrijan pasan a
`DEUDA_TECNICA.md` con su ficha, siguiendo la convención A/B/C ya establecida.
