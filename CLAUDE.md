# RetailMind — contexto para Claude Code

Tienda PyME con back-office completo. **PostgreSQL (BD `retailmind`, 113 tablas — medidas el
2026-08-17; eran 111 antes de `rol_personalizado` del script 87) es la ÚNICA
base transaccional** — incluida la TIENDA DEL CLIENTE (catálogo `/api/catalogo`, carrito, wishlist,
perfil/direcciones, checkout y mis pedidos, migrados 2026-07-11). El catálogo arrancó con ~1.214
productos del dataset original vía ETL puntual y **hoy son 6.217 productos / 6.224 variantes**,
porque la Fase 0 de la carga masiva sembró 5.000 más (ver la tabla de cifras de «LA CARGA MASIVA»,
que manda sobre cualquier número anterior de este archivo). **Desde el 2026-08-03 PostgreSQL
corre EN UN CONTENEDOR** (puerto 5432), así que la vieja frase «con Docker apagado todo funciona»
YA NO VALE: sin Docker no hay base. Lo que SÍ sigue en pie —y es el invariante de diseño que hay
que respetar— es que **ClickHouse es solo analítica** (paquete `analytics/` + señal de eventos para
recomendaciones): con ClickHouse apagado TODO el sistema funciona y solo analytics/recomendaciones
se degradan con aviso (probado: `status: UP` / `analytics: DEGRADED` en ~5 s acotados, informes
simples intactos, compuestos y tableros con `analiticaDisponible: false`, y recuperación **sin
reiniciar el backend**). Por eso el compose declara `clickhouse: service_started` y NUNCA
`service_healthy`. Si algún documento viejo dice "PostgreSQL eliminado", describe la tienda sobre
ClickHouse o afirma que PostgreSQL corre local, está desactualizado: ignóralo.

## Stack

- **Backend** `retailmind-backend/`: Spring Boot 3.5.0, Java 17, Maven, `JdbcTemplate` (sin JPA).
  Dual datasource: `pgJdbcTemplate` (cualificado, `DataSourceTransactionManager` **@Primary**) +
  ClickHouse para analytics. Spring Security JWT (login = email en el campo `username`).
  iText 5 (PDF de facturas) + Apache POI (Excel).
- **Frontend** `retailmind-frontend/`: Angular 17 standalone, diseño "Dubai", Angular Material.
  Pantallas operativas en `features/operativo/` (incluye `marketing/`); estilos compartidos
  `operativo-shared.scss`; errores con `core/services/api-error.util.ts`.
- **ETL** `retailmind/`: Python 3.12. El pipeline VIGENTE es **PostgreSQL → ClickHouse**
  (`retailmind/etl/dwh/`, orquestado por Airflow). El viejo `PocketBase → Parquet → ClickHouse`
  ya NO existe: `pocketbase` se eliminó del compose y de ese tramo solo queda
  `retailmind/etl/carga/`, que creó las tablas de la base LEGADA y no se vuelve a correr.
  El DDL operativo vigente de PostgreSQL está en `retailmind/sql/postgres/` (scripts numerados
  **01-91**, más los `99_revert_*` de las siembras).
- **Tienda del cliente** (solo rol CLIENTE, guard + SecurityConfig): backend en paquetes
  `catalogo/`, `carrito/`, `wishlist/`, `perfil/`, `recomendaciones/` contra `pgJdbcTemplate`;
  el id público de producto es el de la VARIANTE. El checkout llama a `VentasService.crearPedido`
  (mismo pedido que el back-office, stock vía `StockService`). El script
  `34_grants_tienda_cliente.sql` da a `grp_cliente` lo que el checkout necesita (inventario,
  movimiento_inventario, tipo_movimiento, bodega, historial_estado_pedido + políticas RLS de
  horario). Eventos a ClickHouse solo best-effort (`EventoTiendaService`).

## Seguridad a nivel de BD (lo más importante)

- 9 roles de grupo en PostgreSQL: `grp_administrador, grp_gerente, grp_vendedor, grp_compras,
  grp_bodega, grp_despacho, grp_cliente, grp_analista, grp_soporte` — con matriz de privilegios
  GRANT, **RLS** (cliente aislado vía `app.cliente_id`) y **restricción por horario**
  (`grupo_horario` + `esta_en_horario()` + triggers). Admin exento de horario; soporte 24/7.
  **Desde el 2026-08-06 (script 88) las VENTANAS de los 8 roles están en 24/7**, así que hoy el
  horario no bloquea a nadie: lo que cambió son las FILAS de `grupo_horario`, no el mecanismo
  —triggers, políticas y funciones siguen intactos y verificados— (ver el bloque de scripts
  88-90 al final).
  OJO al crear un rol nuevo: además de los GRANTs necesita `GRANT USAGE ON SCHEMA public`
  (el script 19 lo revocó a PUBLIC) y política RLS propia en cada tabla con RLS (las
  pol_horario enumeran los grupos). Patrón completo en `37_rol_soporte.sql`.
- La app conecta como `retailmind_app` (LOGIN **NOINHERIT**, sin privilegios de negocio) y asume
  el rol del usuario **por transacción** con **`set_config('role', ?, true)`** (aspecto
  `security/PgSessionRoleAspect`, excluye `analytics/`). **Desde el 2026-08-06 ya NO es
  `SET LOCAL ROLE ` + nombre**: es equivalente (mismo GUC, mismo alcance de transacción) pero el
  nombre del rol viaja como **PARÁMETRO LIGADO**, así que no se concatena un identificador en
  ningún punto. Ese cambio es lo que permite que el rol venga de la BD —los roles personalizados
  del script 87— sin debilitar nada: la garantía de no-inyección ya no depende de que el nombre
  salga del enum `DbGroupRole`, sino de que NUNCA se concatena. Probado:
  `set_config('role','grp_x; DROP TABLE marca',true)` → «role does not exist». `app.cliente_id`
  viaja por el mismo camino (verificado: el cliente sigue viendo sus 21 pedidos, no 4.083).
- Consecuencia: **TODO acceso a Postgres debe ir dentro de `@Transactional`** — si no, corre sin
  privilegios y falla o (peor) se salta la seguridad de motor.
- La BD devuelve SQLState 42501 por privilegio/horario; `GlobalExceptionHandler` lo traduce a 403.

## Reglas de oro

1. **Nunca** escribir columnas GENERATED ni totales de cabecera (los ponen triggers). Tampoco
   `fecha_actualizacion` (trigger touch) ni contadores como `usos_actuales`.
2. Validación por **lista blanca** + parámetros JdbcTemplate; nunca concatenar SQL. **La regla
   rige para todo lo OPERATIVO y para los informes/tableros.** Dos zonas heredadas NO la cumplen
   y no son excusa para escribir código nuevo así: `analytics/` construye su SQL pegando texto
   (documentado en `InformeCompuestoServiceBase:64`) y `admin/gestion/GestionDatosService:86-96`
   concatena hasta el NOMBRE de la columna (ver **A-3** en `DEUDA_TECNICA.md`).
3. Guardias de estado/idempotencia con mensaje claro: `IllegalArgumentException` → 400,
   `IllegalStateException` → 409, `NoSuchElementException` → 404 (vía `GlobalExceptionHandler`).
4. Errores al usuario siempre con `mensajeError()` de `api-error.util.ts`.
5. Pantalla CRUD nueva = imitar `features/operativo/` (tabla Material + formulario colapsable +
   toggle activo, ej. `productos-admin` o `marketing/cupones`). Servicio Angular de ~15 líneas
   con `environment.apiUrl`.
6. Ruta nueva = `SecurityConfig` (backend) + `roleGuard([...])` en `app.routes.ts` + entrada en
   sidebar (`app.component.html`, getters `canX`) + `routeMap` de breadcrumbs (`app.component.ts`).
7. No modificar tablas existentes ni sus triggers; no tocar `analytics/` ni ClickHouse desde lo
   operativo.
8. Parámetros null hacia PG en contexto no tipado: castear en SQL (`?::bigint`, `NULLIF(?,'')::timestamptz`).

## Cómo correr

**TODO está contenerizado desde el 2026-08-03** (ver `docs/DESPLIEGUE_EJECUTADO.md`). El compose
raíz declara **9 servicios** y `pocketbase` se ELIMINÓ: `postgres` y `clickhouse` (sin perfil,
siempre arrancan), `backend` y `frontend` (perfil `demo`), `etl` y `pgadmin` (perfil `tools`, a
demanda) y `airflow-init`/`airflow-webserver`/`airflow-scheduler` (perfil `airflow`, ver el bloque
de orquestación al final). Como `.env` fija `COMPOSE_PROFILES=demo`, un `up -d` a secas levanta
**los cuatro primeros**:

```bash
# TODO el sistema (los 4 servicios del perfil demo, listo en ~28 s)
docker compose up -d

# Tras cambiar código Java o Angular: SIN --build el contenedor sigue con la imagen vieja
docker compose up -d --build

# MODO DESARROLLO: solo la base y la analítica en Docker; backend y frontend a mano
docker compose up -d postgres clickhouse
docker compose stop backend frontend      # libera 8080 y 4200
cd retailmind-backend  && mvn spring-boot:run     # necesita application-local.properties
cd retailmind-frontend && npm start

# Verificación mínima antes de dar por bueno un cambio
mvn compile   &&   ng build
```

**Puertos**: el **5432 es el CONTENEDOR** (PostgreSQL 18.4, la base VIVA). El PostgreSQL **local**
(18.3 Windows, servicio `postgresql-x64-18`) se movió al **5433** y ahí viven las 12 bases de otras
materias más una copia congelada de `retailmind` — sirve de marcha atrás, no se desinstala. Para
inspeccionar el esquema usa el MCP `retailmind` (solo lectura), que ya apunta al contenedor.

**Secretos**: hay que distinguir DOS cosas, porque este archivo contiene una de ellas.
**(a) Los secretos INTERNOS —los que nadie teclea— no están en el código ni aquí**: el
superusuario `postgres`, los roles `retailmind_app` y `retailmind_etl`, y el `jwt.secret` se
rotaron el 2026-08-03 y viven **fuera del índice de git** (los cuatro archivos que se enumeran
abajo). **(b) Las credenciales de DEMOSTRACIÓN sí están escritas**, en la sección «Credenciales
de desarrollo» de este mismo archivo y en otros ocho documentos y scripts: son deliberadas —sin
ellas nadie puede entrar a probar el sistema— y su coste está registrado como **C-4** en
`DEUDA_TECNICA.md` (9 archivos versionados, uno de los cuales **crea la cuenta**). Lo que no
puede decirse es «no hay contraseñas en este archivo»: las hay, y son las de demo.
`application.properties` dejó de
tener valores por defecto para `postgres.datasource.password` y `jwt.secret` — la app **falla al
arrancar** si faltan, a propósito. Fuera de Docker los toma de
`retailmind-backend/application-local.properties` (gitignored, vía `spring.config.import` con
`optional:`); dentro, del entorno del compose. Las credenciales de motor viven en `.env`,
`retailmind/.env` y `deploy/secrets/pg_superuser.txt` — esos tres más
`application-local.properties` son los **cuatro archivos con secretos, todos fuera del índice de
git** (verificado). `.env.example` es la plantilla versionada: lleva las CLAVES sin los VALORES.

**Trampas del despliegue** (detalle en `docs/DESPLIEGUE_EJECUTADO.md` §8):
- Un cambio de **Java/Angular NO entra solo**: la imagen está horneada, hace falta `--build`. El
  **Python del ETL sí es inmediato** porque `./retailmind` va montado, no copiado.
- Los **datos viven en el volumen, no en la imagen**: reconstruir NO los borra.
- Un **script SQL nuevo NO se aplica solo**: `deploy/postgres/initdb/` corre una única vez, con el
  volumen vacío. Para aplicarlo:
  `docker compose exec -T postgres psql -U postgres -d retailmind < ruta/script.sql`.
- Ningún `down` debe llevar **`-v`**: el volumen de ClickHouse guarda un dato irreproducible
  (`fact_eventos`, 2.823.245 filas) y por eso va declarado `external: true`.

## Cierre de sesión («cerramos por hoy»)

Procedimiento fijo para dejar el día cerrado. **Se ejecuta entero, en este orden.**

**1. Verificar antes de tocar el índice.**

```bash
git status                       # completo, y se REPORTA
git ls-files --error-unmatch .env    # debe fallar: .env NUNCA se rastrea
git status --porcelain | grep -i '\.env'   # no debe devolver nada
```

Los **cuatro archivos con secretos** —`.env`, `retailmind/.env`,
`deploy/secrets/pg_superuser.txt` y `retailmind-backend/application-local.properties`— van
fuera del índice, y ahí se quedan.

**2. Barrido de credenciales sobre lo que se va a commitear** (no sobre el repo entero: sobre
el DIFF y los archivos nuevos). Es el paso que más veces ha salvado un commit:

```bash
git diff -U0 | grep '^+' | grep -viE '^\+\+\+' \
  | grep -inE 'password|passwd|contrasen|secret|token|api[_-]?key|BEGIN .*PRIVATE'
```

Un javadoc citó una vez una contraseña en claro y hubo que **enmendar el commit**; otra vez
fue un documento nuevo que reproducía la clave de demo mientras explicaba en qué archivos
estaba. **Si aparece algo, DETENERSE y reportarlo ANTES de commitear** — no commitear y
arreglar después: lo commiteado ya está en la historia. Ojo con el falso positivo honesto:
`jwt.secret` es el NOMBRE de una propiedad, no un valor.

**3. Commits agrupados POR TEMA, no por archivo y nunca uno solo con todo.** Un commit = un
asunto que se pueda revertir sin arrastrar nada más (p. ej. «documentación» y «corrección de
cifras» van separados aunque toquen el mismo archivo). Mensajes **en español**, en imperativo
o sustantivo, describiendo el QUÉ y el PORQUÉ, no los archivos.

**4. El push es MANUAL y por decisión explícita. NUNCA automático.** Cerrar la sesión deja los
commits **locales**, y `git status` dice cuántos van por delante del remoto. El motivo: el
barrido del paso 2 es la última red antes de que algo salga del equipo — una vez empujado a un
remoto compartido, un secreto se considera comprometido aunque se borre después (queda en el
reflog, en los forks y en la caché del servidor), y limpiarlo obliga a reescribir la historia.
Local es reversible; remoto no.

**5. Reconstruir al terminar**, si se tocó código Java o Angular:

```bash
docker compose up -d --build     # sin --build la imagen sigue horneada con lo viejo
```

**Nunca `docker compose down -v`.** El `-v` **destruye los volúmenes**, y con ellos la base
`retailmind` entera y el `fact_eventos` de ClickHouse (2.823.245 filas irreproducibles). Para
parar sin perder nada: `docker compose stop` o, como mucho, `docker compose down` **a secas**.

## Credenciales de desarrollo

- Admin: `admin@retailmind.com` / `Admin2026!`
- Resto de roles (`gerente@`, `vendedor@`, `compras@`, `bodega@`, `despacho@`,
  `analista@retailmind.com`): `Retail2026!` (script 27); `soporte@retailmind.com`:
  `Retail2026!` (script 37)
- Clientes demo (`maria.lopez@demo.com`, `carlos.vera@demo.com`): `Cliente2026!` (script 26)
- **Cliente VACÍO para pruebas** (`cliente.nuevo@demo.com`): `Cliente2026!`. Creado el
  2026-08-21 por `POST /api/auth/register` —no por SQL—, o sea por el mismo camino que la
  pantalla `/admin-usuarios`, que es lo que destapó **D-16**. Es `usuario` 98 / `cliente` 79 y
  no tiene NADA: 0 pedidos, 0 direcciones, carrito y lista de deseos vacíos. Sirve para ver la
  tienda como la ve alguien que acaba de registrarse —los estados vacíos, el checkout sin
  dirección guardada, la primera compra—, que con `maria.lopez` (80 pedidos) no se puede.
  **Si le compras algo deja de servir para eso**: crea otro con el mismo alta.

> **Estas credenciales de LOGIN siguen intactas** tras la rotación del 2026-08-03 (verificado:
> los 10 usuarios entran). Lo que se rotó fueron **cuatro secretos internos que nadie teclea** —
> el superusuario `postgres` del contenedor, los roles `retailmind_app` y `retailmind_etl`, y el
> `jwt.secret`—, porque estaban en claro y versionados. **Ya no aparecen en ningún archivo
> rastreado por git**, así que NO los escribas aquí: viven en `.env` (`PG_APP_PASSWORD`,
> `PG_ETL_PASSWORD`, `JWT_SECRET`), `retailmind/.env` (`ETL_PG_PASSWORD`) y
> `deploy/secrets/pg_superuser.txt`. El **superusuario del PostgreSQL local (5433) NO se rotó**:
> esa contraseña la comparten los MCP de otras materias.

- **Rol de motor del ETL** (no es un usuario de la app, no tiene login web): `retailmind_etl`
  (script 85), LOGIN + BYPASSRLS + solo lectura por cuatro capas. Contraseña en `retailmind/.env`
  como `ETL_PG_*` / `ETL_CH_*`.

## LA CARGA MASIVA: 3.000.000 DE PEDIDOS EN UNA DÉCADA (2026-08-10/11, fases 0-3)

**Las cifras del sistema HOY** (y son las que mandan sobre cualquier número
anterior de este archivo, que describe el estado previo a la carga).
**Re-medidas todas el 2026-08-17**, porque las pruebas manuales del 12 al 16 de
agosto movieron los conteos —poco, pero los movieron— y porque dos cifras estaban
mal de antes:

| | antes de la carga | HOY (2026-08-17) |
|---|---|---|
| pedidos | 4.083 | **2.999.995** |
| líneas de pedido | 10.384 | **7.622.438** |
| hitos de historial | 24.610 | **20.215.662** |
| movimientos de kardex | **13.310** (ver nota) | **8.008.403** |
| posiciones de inventario | 1.406 | **11.407** (todas cuadradas) |
| facturas de venta | 3.887 | **2.855.380** |
| clientes · variantes | 72 · 1.221 | **50.072 · 6.224** |
| base `retailmind` | ~250 MB | **17 GB** |
| modelo del DWH | 66.082 filas | **26.971.498 filas / 1,47 GiB** |
| ventana temporal | 2025-01 → 2026-07 | **2025-01 → 2034-12** |
| ticket medio | $1.400,06 | **$182,09** |

Tres advertencias sobre esta tabla, porque las tres son trampas para la próxima
sesión:

- **El kardex «antes de la carga» NO eran 23.289**, como decía esta tabla hasta
  el 2026-08-19: eran **13.310**. La cifra vieja incluía los **10.000
  movimientos de la Fase 0** —o sea, la primera fase de la carga masiva se
  estaba contando en la columna del ANTES: 13.310 + 10.000 = 23.310—. Lo
  destapó montar el estado E2 del plan de pruebas desde el volcado del
  2026-08-03 (anterior a la carga) y contrastarlo contra esta tabla: seis
  medidas cuadraron exactas y ésta salió al 57 %. Y el propio archivo ya se
  contradecía: el bloque de la **Fase 3B** dice
  `fact_movimiento_inventario` (**13.287**) para ese mismo período.
  **Al medir el «antes» de una carga, filtra por el tramo de ids reservado**
  (`id < 900.000.000`), que es el criterio de procedencia que esta misma
  sección declara — no por la fecha ni de memoria.

- **El «modelo del DWH» NO son 32,60 M filas ni 1,92 GiB**, como decía esta tabla
  hasta hoy. El modelo publicado son **26.971.498 filas en 21 tablas / 1,47 GiB**,
  medidas con `system.tables` excluyendo `etl_ejecucion` y las `%_new`. Y no es que
  se haya perdido nada: la corrida ANTERIOR a la de hoy ya tenía **26.971.368**
  filas publicadas (comprobado en la bitácora), así que la cifra vieja nunca
  describió el modelo. Lo más probable es que saliera de una suma que incluía las
  tablas de staging a mitad de corrida o los `filas_escritas` duplicados de los
  marcadores — la trampa que este mismo archivo documenta en el bloque de Airflow.
  **Al medir el almacén: `system.tables`, excluyendo `etl_ejecucion` y `%_new`.**
- **El ticket medio son $182,09** con la definición explícita `AVG(pedido.total)`
  sobre los **2.883.688** pedidos NO cancelados (unidos a `estado_pedido` por
  `estado_pedido_id`: en `pedido` **no hay** columna `estado` ni `estado_pedido`).
  El $182,16 anterior no venía con su definición, así que no se puede saber si
  difiere por las pruebas o por medirse de otro modo.

**Los 49 controles del ETL cuadran EXACTAMENTE** (verificado el 2026-08-17 tras la
reparación del bloque final de este archivo).

### Las cuatro fases, su método y sus tramos

| fase | qué cargó | ventana | tramo de ids | scripts |
|---|---|---|---|---|
| **0** maestros | 5.000 variantes, 50.000 clientes, 100 vendedores, 19 proveedores, stock inicial | — | `900.000.000` (y `60.000` en `categoria`/`proveedor`) | 92-96, `99_revert_fase0` |
| **1** piloto | 10.000 pedidos | 2025 | `1.000.000.000` | 97, `99_revert_fase1` |
| **2** volumen | 300.000 pedidos | 2026-09 → 2027-08 | `1.100.000.000` | 98, `99_revert_fase2` |
| **3** la década | 2.685.908 pedidos en **10 bloques** (redensifica 2025 y 2026-01/08, y llena 2027-09 → 2034-12) | 2025-2034 | `1.2e9` … `2.1e9`, 100 M por bloque | 100, `99_revert_fase3` (por bloque) |

**PROCEDENCIA POR TRAMO DE IDs RESERVADO**, no por columna ni por marcador de
texto: `DELETE ... WHERE id >= base AND id < base + 100.000.000` toca un bloque
y ninguno más. El techo real NO es el `bigint` de PostgreSQL: es el **UInt32**
del almacén (4.294.967.295), que reciben `pedido_id`, `cliente_id`,
`producto_variante_id`, `documento_id` (=`pago.id`), `contraparte_id`,
`orden_compra_id` y `envio_id`. El id más alto escrito queda al **49 %** del
techo. Y el tramo de claves primarias **NO reserva las claves ÚNICAS de
negocio**: los números de documento van en su propia banda (`90`=Fase 0,
`91`=Fase 1, `92`=Fase 2, `9BB`=Fase 3 por bloque) con secuencia POR DÍA.

**EL MÉTODO DE STOCK, que es lo que hace que todo esto escale**: reposición
previa por posición con **neto CERO**. Cada unidad vendida se compra antes en
la misma posición, con orden, recepción, factura y CxP detrás. Consecuencias:
`inventario.stock_actual` NO se escribe nunca (426.722 unidades antes y
después), ninguna fila preexistente cambia, y la reversión es un DELETE y no
una migración. Es O(1) por línea, sin reencadenado global.

El grupo de reposición es **(posición, bimestre, último eslabón ajeno
anterior)**. Ese tercer componente es el que permite REDENSIFICAR: al insertar
en mitad de una cadena viva, lo insertado solo es inocuo si suma cero **entre
dos eslabones consecutivos**; sin ese corte, la entrada queda delante de un
movimiento existente y sus ventas detrás, dejando obsoleto el saldo guardado de
todo lo que venga después.

Si vas a tocar esto: (1) `fecha_creacion` SIEMPRE explícita en el kardex —el
trigger valida la FILA, no el ENLACE (C-2)—; (2) la cadena se verifica
**FUSIONADA** (lo existente MÁS lo planeado) leyendo por `(fecha_creacion, id)`,
y comprobar solo las filas nuevas marca roturas que no lo son; (3) el generador
determinista lleva **sal por bloque**, porque `i` reempieza en cada bloque y al
redensificar una ventana ya ocupada dos pedidos con el mismo `i` caían en el
mismo microsegundo; (4) el sorteo por índice solo escala si el índice se puede
USAR: con el total leído como COLUMNA dentro del LATERAL el paso pasa de 12 s a
más de 3:24 sin terminar.

**Topes de infraestructura que solo aparecen a esta escala** (los tres
corregidos): `max_partitions_per_insert_block` de ClickHouse (la década son 120
particiones mensuales contra un tope de 100); `/dev/shm` de 64 MB por defecto en
Docker, que agota la memoria compartida de las consultas paralelas y da un error
que NOMBRA a PostgreSQL pero no tiene que ver con el disco (`shm_size: 2gb` en
el compose); y el `execution_timeout` de 5 min del DAG, que se quedó corto para
`fact_movimiento_inventario` y mataba la tarea por reloj, no por error.

**LIMITACIÓN DECLARADA — la serie es demasiado regular.** El CV interanual del
total mensual es de **0,19 % a 0,68 %**: cada enero de los diez años difiere
menos del 1 % del siguiente. Por eso el ingenuo estacional saca un MAPE del
0,40 % y el modelo de previsión (0,83 %) no lo supera, y publica en
`modo=linea_base`. Falta variación interanual —tendencia, choques, ruido de
mes—; se corrige regenerando bloques, no parcheando el modelo.


## Qué está hecho / qué falta

**Hecho**: catálogo maestro; ciclo de compra con compuertas ENFORZADAS en backend
(orden→aprobación de GERENTE/ADMIN→recepción completa→factura→pago; sin aprobar no se recibe ni
factura, sin recibir completo no se factura, sin factura no hay CxP ni pago); inventario
(transferencias, ajustes, kardex); ciclo de venta completo con compuertas (pedido confirmado→
PAGO del cliente [tabla pago+transaccion_pago, abonos parciales]→pagado→facturado→
en_preparacion→preparado→despachado→entregado→devolución solo tras entrega) con PDF, listado
de facturas de venta con búsqueda/paginación, timeline (historial_estado_pedido) y acciones
encadenadas en el detalle del pedido; **TRAMO DE SALIDA robustecido (2026-07-16, script 39)**:
estados nuevos `facturado` y `preparado`; la factura del pedido ONLINE se emite AUTOMÁTICAMENTE
al pagar el checkout (misma transacción, bajo grp_cliente: INSERT + pol_cliente_emision +
`fn_recalcular_total_factura_venta` SECURITY DEFINER) y el cliente la ve/descarga en la
confirmación y en Mis Pedidos; la factura de pedidos INTERNOS sigue MANUAL
(POST /pedidos/{id}/factura = ADMIN/VENDEDOR; en ambos casos exige estado 'pagado' exacto y
deja el pedido 'facturado'); transportista ASIGNADO AUTOMÁTICAMENTE por zona al crear el pedido
(`pedido.transportista_id` + metodo_envio_id; dirección→zona_envio por ciudad>provincia>país→
tarifa activa más barata→metodo_envio.transportista_id; seeds Quevedo local/Los Ríos/Ecuador,
el cliente solo lo VE con su tiempo estimado); BODEGA prepara en
`/operativo/ventas/preparacion` (cola GET /api/ventas/preparacion = pedidos
facturado/en_preparacion con detalle de picking dedicado — NO usa obtenerPedido porque bodega
no lee pago — y transiciones POST /pedidos/{id}/preparacion y /preparado con guardias);
DESPACHO solo despacha pedidos 'preparado' (detalle GET /api/ventas/despacho/{id} con ítems,
cliente, dirección y transportista asignado; en el POST transportista/método son OPCIONALES:
por defecto van los asignados y mandarlos distintos es un override que queda registrado en
historial y seguimiento, actualizando el pedido);
el checkout del cliente entra al MISMO flujo y el cliente ve estado/guía/seguimiento/factura PDF
en Mis Pedidos (RLS, script 35); **checkout ONLINE completo tipo Amazon (2026-07-15, script 36)**:
`/shop/checkout` con dirección de envío (o alta inline), campo de cupón (solo UI; la validación
llega con la fase de descuentos — enganche en `CarritoService.checkout`), método de pago
tarjeta/transferencia SIMULADO (validación de formato + Luhn + MM/AA + CVV; se persiste SOLO
marca + últimos 4 en `pago.referencia_externa` y `transaccion_pago.respuesta_pasarela`, NUNCA
PAN/CVV) — el pedido online nace **PAGADO** en una sola transacción (`pagarCheckoutOnline`).
`pedido.canal` discrimina origen: 'web' = online (el back-office NO muestra "registrar pago";
`registrarPago` lo rechaza con 409) vs 'tienda'/'telefono' = interno (cobro manual intacto;
POST /api/ventas/pedidos rechaza canal 'web');
horarios de acceso; marketing (cupones, promociones+productos, campañas, banners, newsletter —
solo gestión); tienda online 100% PostgreSQL (catálogo real con búsqueda con debounce, filtro por
marca/categoría y paginación server-side, carrito,
wishlist, checkout → pedido del ciclo de venta, perfil + CRUD de direcciones, un solo
"Mis Pedidos" en `/operativo/ventas/mis-pedidos`; recomendaciones con señal ClickHouse y
productos PG, degradan a destacados); analítica
ClickHouse (dashboard, funnel, sesiones, región/dispositivo/tráfico, reportes); **soporte
profesional con rol SOPORTE (2026-07-15, script 37)**: usuario `soporte@retailmind.com` /
`Retail2026!`, bandeja con filtros (sin asignar/míos, estado, categoría, prioridad), 7 categorías
reales con `prioridad_defecto` → prioridad AUTOMÁTICA al crear (el cliente no la elige;
solo SOPORTE/ADMIN la cambia con PATCH /prioridad), número `TICK-AAAA-NNNN`, SLA calculado
(urgente 2h/alta 4h/media 24h/baja 72h, indicador vence en/VENCIDO), tomar ticket
(auto-asignación, abierto→en_proceso), transiciones con guardias, reapertura si el cliente
responde un 'resuelto' (grant de columna UPDATE(estado) a grp_cliente), RLS de cliente y
"Equipo de soporte" como autor anónimo; notas de pedido (`nota_pedido`, bitácora con nota
interna vs. visible al cliente); anulación de ajuste de inventario por contramovimiento de
kardex; **RMA / logística inversa (2026-07-16, script 38, paquete `devoluciones/`)**: la
devolución NACE DEL CLIENTE en Mis Pedidos (pedido entregado/devuelto con plazo de 30 días
desde la entrega — constante `PLAZO_DIAS_DEVOLUCION` — o despachado = rechazo en puerta) y
crea/engancha un ticket categoría "Devolución" (`devolucion.ticket_soporte_id`); ciclo
solicitada→en_revision→aprobada|rechazada(terminal)→en_transito→recibida→inspeccionada→
reembolsada→cerrada con UN rol por transición (SOPORTE valida y genera guía de retorno
RET-… + transportista + bodega, PDF vía DocumentoPdfService; DESPACHO tránsito/recepción;
BODEGA inspección POR ÍTEM: solo `apto_reventa` reingresa stock vía StockService con kardex
`entrada_devolucion_cliente` y el pedido pasa a 'devuelto' — `defectuoso` = merma pendiente
proveedor, `rechazado` = sin reembolso; GERENTE/ADMIN reembolso SIMULADO apto+defectuoso;
SOPORTE cierra y el ticket queda resuelto), historial en `historial_estado_devolucion`
(autor usuario O cliente), RLS pol_cliente_propio/pol_soporte/pol_horario en las 3 tablas,
`devolucion.monto_total` lo mantiene el trigger `fn_recalcular_total_devolucion`
(SECURITY DEFINER, NUNCA escribirlo) y el endpoint viejo de devolución en un paso se
eliminó (`/api/devoluciones`, tablero multi-rol en `/operativo/ventas/devoluciones`).
**DESCUENTOS REALES (2026-07-17, script 40, `marketing/DescuentosService`)**: promociones
vigentes se aplican AUTOMÁTICAMENTE por línea en `crearPedido`
(`pedido_detalle.monto_descuento`, IVA sobre la base rebajada; gana la de mayor prioridad y
solo las `acumulable` se suman; historial las registra) y el CUPÓN del checkout online se
valida/recalcula SIEMPRE en backend (existe+activo, vigencia, `usos_maximos` vía
`usos_actuales`, `usos_por_cliente` contra `uso_cupon`, monto mínimo sobre el subtotal neto
de promos sin IVA; el front solo envía el CÓDIGO) y se aplica en `pedido.monto_descuento`
— los triggers de total ya restaban ambas capas, CERO columnas nuevas — registrando
`uso_cupon` en la misma transacción; trigger `fn_registrar_uso_cupon` SECURITY DEFINER
(lock FOR UPDATE del cupón) mantiene `usos_actuales` y es el backstop de concurrencia de
límites; un solo cupón por pedido (UNIQUE `uso_cupon.pedido_id`); promoción + cupón SÍ
combinan (promo a la línea, cupón sobre el subtotal ya rebajado); la factura PRORRATEA el
cupón entre líneas (`factura_venta_detalle.monto_descuento`, ajuste de redondeo en la
última) porque su trigger recalcula solo desde el detalle, y el PDF muestra
Subtotal/Descuento/IVA/Total + meta "Cupón"; endpoint POST `/api/carrito/cupon/validar`
(motivos claros), carrito/checkout muestran precio promocional tachado y desglose, y el
back-office/Mis Pedidos muestran cupón y descuentos.
**RESEÑAS con compra verificada (script 32 + fase de pulido 2026-07-17)**: módulo completo
en `resenas/` (reseñas, votos de utilidad, reportes de abuso, preguntas/respuestas;
moderación ADMIN/GERENTE en `/operativo/resenas`); crear reseña EXIGE compra
(pedido pagado→entregado, incluye 'devuelto', del propio cliente con ese producto;
si no → 409 "Solo puedes reseñar productos que has comprado"); el selector del
formulario ofrece SOLO productos comprados (GET `/api/resenas/productos-comprados`,
CLIENTE) y Mis Pedidos tiene botón "Reseñar" por ítem que navega con
`?productoId=` preseleccionado.
**SEGREGACIÓN FINANCIERA (2026-07-17, script 41)**: BODEGA y DESPACHO no leen montos —
grants POR COLUMNA sin dinero sobre pedido/pedido_detalle/factura_venta/factura_compra/
orden_compra/orden_compra_detalle/devolucion y `pago` revocado a despacho; consultas
role-aware en `VentasService` (colaPreparacion/detalleLogistico/listarPedidos/entregar),
`ComprasService` (listarOrdenes/obtenerOrden) y `DevolucionService` (listar/obtener);
UI sin columnas de monto para esos roles y BODEGA fuera de Facturas de Compra
(ruta + nav `facturasCompra`). EXCEPCIÓN documentada: grp_bodega conserva SELECT de
`precio_unitario` en los DETALLES porque valoriza el kardex bajo su rol (recepción y
reingreso RMA); la UI no lo muestra. OJO: `entregar` devuelve respuesta ligera para
DESPACHO (sin pagos) y el pedido completo para el resto.
**TRAZABILIDAD DE AUTOR (2026-07-17, script 42, `auditoria/AuditoriaService`)**: columnas
directas de autor (FK a usuario, del JWT SIEMPRE) — `pedido.vendedor_id` (NULL si canal
'web': el autor del checkout es el CLIENTE, trazado por cliente_id+historial),
`envio.despachado_por`, `factura_compra.registrado_por`, `resena.moderado_por` +
`fecha_moderacion` y `pregunta_producto.moderado_por`+`fecha_moderacion`
(`respuesta_pregunta` ya tenía `usuario_id`); `AuditoriaService.registrar()` generaliza el
log de la aprobación de OC y escribe `log_auditoria` (jsonb antes/después, CHECK de
acciones) en: crear pedido interno, despachar, registrar factura de compra y moderar
reseña/pregunta; INSERT de log_auditoria otorgado a grp_vendedor/despacho/compras (script
42; el checkout online NO loguea: grp_cliente sin INSERT a propósito). Trazabilidad futura
(producto, marketing, historial de ticket) documentada en deuda.
**SANEAMIENTO TIPO 1 (2026-07-18, script 43)**: los 10 bugs del inventario consolidado
quedaron cerrados por causa raíz — cupón ahora SÍ recalcula el IVA (prorrateo por línea en
`aplicarCupon`, reescala `pedido_detalle.monto_impuesto` antes del pago; `envio_gratis` no
toca base); RLS habilitado en `pago`/`transaccion_pago`/`cupon`/`uso_cupon` (cliente aislado
a lo suyo, cupones solo activos o usados por él; helper `fn_pago_del_cliente` SECURITY
DEFINER); numeración de documentos por secuencia global `seq_numero_documento` (desde
100000; los 3 `siguienteNumero`) y tickets por `fn_siguiente_numero_ticket()` (correlativo
por año bajo lock); `resena` con grants de cliente POR COLUMNA (sin UPDATE); soporte sin
escritura en `categoria_ticket`; RMA 'despachado' con plazo de 30 días desde
`fecha_despacho`; pedidos legacy saneados (factura del 24662 anulada — `emitirFactura`
ignora 'anulada' —, el 87538 facturado). La deuda técnica acumulada vive en
`DEUDA_TECNICA.md` (raíz).
**NOVEDADES DE ENVÍO (2026-07-18, script 44)**: incidencias sobre el envío en tránsito
(tabla `novedad_envio`: cliente_ausente/direccion_incorrecta/cliente_rechazo/
zona_dificil_acceso/dano_en_transito, autor del JWT, RLS pol_horario+pol_cliente_propio).
DESPACHO registra la novedad (POST `/api/ventas/envios/{id}/novedades`, el envío pasa a
'fallido' — CHECK existente) y la resuelve: REPROGRAMAR (`/novedades/{id}/reprogramar`,
máx. 3 intentos, envío vuelve a 'en_transito' + nueva fecha estimada) o DEVOLVER AL ALMACÉN
(`/novedades/{id}/devolver-almacen`: envío 'devuelto', pedido → estado NUEVO
'no_entregado' terminal, SIN reingreso de stock — el kardex solo se mueve tras inspección
de bodega, criterio RMA; reembolso/reingreso por soporte, deuda Fase 6). Guardias: solo
envíos en tránsito, UNA novedad abierta a la vez, `entregar` bloqueado con novedad abierta,
intentos = 1 + reprogramaciones (calculado, sin columna). Consulta compartida GET
`/pedidos/{id}/novedades` (role-aware: el cliente no une usuario). Rastro triple:
seguimiento_envio + historial_estado_pedido + log_auditoria. UI: tarjeta "Novedades de
entrega" en `/operativo/ventas/despachos` y mensaje amable + timeline en Mis Pedidos.

**DEVOLUCIÓN A PROVEEDOR (2026-07-18, script 45, `compras/DevolucionProveedorService`)**:
espejo del RMA hacia el proveedor. Pool `item_defectuoso` (pendiente→en_devolucion→resuelto)
con DOS orígenes: (1) inspección RMA 'defectuoso' lo crea AUTOMÁTICO (proveedor rastreado por
última OC de la variante o NULL → COMPRAS lo asigna con PATCH; SIN stock: nunca reingresó) y
(2) recepción de compra — rechazo EN PUERTA (`cantidad_rechazada`, automático, jamás entró a
stock; UI en Recepciones) o marcado POSTERIOR de BODEGA (POST
`/api/compras/recepciones/detalles/{id}/defectuoso`: ahí SÍ sale del stock vendible con kardex
`salida_devolucion_proveedor`, tope recibidas−ya marcadas). COMPRAS agrupa pendientes de UN
proveedor en `devolucion_proveedor` (numero DP-… por seq global; registrada→enviada [sin
movimiento de stock]→resuelta→cerrada, guardias 409) y registra la resolución: `nota_credito`
(crédito SIMULADO = Σ cantidad·costo, sin stock) o `reposicion` (reingreso APTO vía
StockService, kardex NUEVO `entrada_reposicion_proveedor`, en la bodega de origen de cada
ítem, bajo grp_compras — grants de stock dados en el 45). Historial propio
(`historial_devolucion_proveedor`) + AuditoriaService en cada acción (grp_bodega ganó INSERT
de log_auditoria); RLS pol_horario en las 4 tablas nuevas. Pantalla multi-rol
`/operativo/compras/devoluciones-proveedor` (BODEGA marca/ve, COMPRAS gestiona con timeline;
GERENTE lee). Deuda de la fase en `DEUDA_TECNICA.md` (Fase 7): sin cuarentena física, nota de
crédito sin asiento en CxP, reposición en un paso, rechazo total en puerta imposible
(CHECK recibida>0).

**RE-AUDITORÍA DE DEUDA + HEALTH FAIL-FAST (2026-07-18, solo backend, sin script)**: auditoría
completa de deuda post scripts 43-45 verificada contra el sistema real; el único Tipo 1
detectado quedó CERRADO el mismo día — `/api/health` se colgaba con ClickHouse apagado
(Hikari con `connectionTimeout` por defecto de 30s y driver sin timeout). `ClickHouseConfig`
ahora arma el pool con `connectionTimeout=3s`, `validationTimeout=1.5s`,
`initializationFailTimeout=-1` y propiedades del driver `connect_timeout=2.5s` /
`socket_timeout=30s`; `checkPythonRuntime` con `waitFor(3s)`. Verificado en vivo:
`/api/health` responde en ~3.1s acotados con `status: UP, analytics: DEGRADED` — YA SIRVE
como healthcheck de contenedores. **Tipo 1 vigentes = 0 A ESA FECHA** (2026-07-18). Después
entró **A-3** (la pantalla de gestión de datos editaba y borraba `fact_eventos` por `event_pk`,
que **no es una clave**: 50.000 valores distintos sobre 2.823.245 filas, así que un borrado se
llevaba entre 52 y 139 eventos) y **se cerró el mismo 2026-08-07** retirando la escritura, así
que **hoy la cuenta es A = 0** (esta línea decía «A = 1» hasta el 2026-08-17, contradiciendo al
propio `DEUDA_TECNICA.md`, que ya daba A-3 por resuelto en su sección D). Lo que quedó vivo de
ahí es la fragilidad **C-15**: la tabla sigue sin identificador único. La foto completa de deuda
vive en `DEUDA_TECNICA.md` (raíz) + `docs/INVENTARIO_DEUDA_CONSOLIDADO.md` (re-verificado), y el
recuento que manda es el de la cabecera de `DEUDA_TECNICA.md`: **A = 0 · B = 28 · C = 20**.

**CONTRASTE DE CATÁLOGO Y DEMANDA (2026-07-24, scripts 67 y 68-70)**: corrige los hallazgos
A2/A3/A5/A6/B4 de `docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` (§11 lleva el estado).
El **67** reasigna solo `producto_variante.costo` con banda por categoría (mayorista:
Electrónica ~9 % … Belleza ~36 %): NO hay COGS almacenado, el margen se computa en vivo contra
`producto_variante.costo`, así que no altera ninguna transacción. El **Bloque D (68 respaldo →
69 curva/mapeo → 70 aplicación**, reversión `99_revert_bloque_d_demanda.sql`) **redistribuye**
la demanda ya sembrada sin crear ni borrar ventas: reasigna *qué variante* se vendió en 9.167 de
10.384 líneas dejando `cantidad` y `precio_unitario` INTACTOS, por lo que el impacto monetario
es **$0,00 exacto** (16 agregados y 19 meses sin mover un centavo). top 20 % de variantes
45,9 % → 62,2 %; Abarrotes líder con 3,69× Accesorios en venta. Si vas a tocar esto:
(1) el destino de una línea debe cumplir `precio_unitario/precio ∈ [0,90;1,00]`, invariante del
seed; (2) `factura_venta_detalle` tiene su PROPIO `producto_variante_id` y hay que moverlo en
paralelo o la factura queda apuntando a otro producto; (3) `uq_pedido_detalle` NO es deferrable
⇒ UPDATE en pasadas iterativas; (4) para el kardex el balance final NO basta: hay que respetar
la cronología (el 70 lo deja encadenado por fecha, no por id). Respaldos en el esquema
`seed_backup`, fuera de `public`.

**DESCUENTOS SEMBRADOS QUE SÍ MUEVEN EL DINERO (2026-07-25, scripts 71-73)**: cierra A8/A9 de
`docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md`. Los cupones y promociones del Bloque C ahora
descuentan de verdad: **71** respalda en `seed_backup.dsc71_*` con huella md5 por tabla
(reversión `99_revert_descuentos.sql`, probada aplicar→revertir→bit-idéntico), **72** aplica el
cupón a 535 pedidos y **73** la promoción a 120 líneas de 119 pedidos (+ recálculo del cupón en
los 24 con solape). Se aplicó por el camino del sistema real: se escriben SOLO
`pedido.monto_descuento`, `pedido_detalle.monto_descuento/monto_impuesto` y
`factura_venta_detalle.monto_descuento/monto_impuesto` — los totales de pedido y factura los
rehacen sus triggers y `pago.monto` es el ÚNICO ajuste manual (no tiene trigger de recálculo).
Si vas a tocar esto: (1) el invariante real es **`factura_venta.total = pedido.total −
pedido.costo_envio`** (la factura no factura el flete), no `= pedido.total`; (2) un cupón
`envio_gratis` NO reescala IVA pero SÍ se prorratea en `factura_venta_detalle.monto_descuento`,
porque `emitirFactura` lee `pedido.monto_descuento` sin mirar el tipo — omitirlo desalinea la
factura; (3) el descuento arrastra su IVA: el total cae **1,15×** el descuento (ingreso
$5.780.474,00 → $5.716.436,55 = −$64.037,45 = cupón $50.537,34 + promo $5.205,94 + IVA liberado
$8.294,17); (4) alcance = solo pedidos con pago `completado`; los 176 pagos fallidos, el kardex
(12.396 movs) y el inventario quedan intactos. Excepción declarada: pedidos **20 y 21** (legacy,
con factura pero sin fila en `pago`) siguen con su cupón sin reflejar.

**REBALANCEO DEL ABASTECIMIENTO (2026-07-25, scripts 74-78)**: cierra A1/B2 de
`docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` (§11 lleva el estado). El 78,5 % de las unidades
que entraban al almacén lo hacían como una apertura ficticia de un solo día (2025-01-01,
1.216 movs, 120.160 uds); ahora la apertura baja a **34.210 uds (22,4 %)** y las compras a
proveedores suben a **118.473 uds (77,4 %)** repartidas en los 19 meses. **74** respalda
(`seed_backup.reb74_*`, filas completas de `movimiento_inventario` + 16 huellas md5; reversión
`99_revert_abastecimiento.sql`, probada aplicar→revertir→bit-idéntico 3 veces), **75** calcula
el plan y demuestra su factibilidad temporal SIN escribir en public, **76** crea 529 OC +
recepciones (+566 `producto_proveedor`), **77** sus facturas/CxP/pagos y **78** recompone el
kardex. Si vas a tocar esto: (1) el principio es que **`inventario.stock_actual` NO se escribe** —
solo cambia el ORIGEN de las entradas (menos `inventario_inicial`, más `entrada_compra`), así que
por cada unidad retirada de la apertura hay una `entrada_compra` anterior a la primera salida de
esa variante; (2) la factibilidad se prueba por tramos, no por balance final: un segundo lote más
tarde solo puede llevarse el **mínimo saldo del tramo** `[T1,T2)` (columna `max_seguro` de
`reb75_factibilidad`); (3) el kardex se encadena por **`(fecha_creacion, id)`** y toda cadena
arranca en 0 — insertar en el pasado obliga a reencadenar `stock_anterior/stock_nuevo` de la
cadena completa (2.234 filas reescritas, 1.006 de ellas salidas de venta: cambia su saldo
corrido, NUNCA su cantidad ni su fecha); (4) las variantes con primera salida antes del
2025-03-01 (343) CONSERVAN su apertura a propósito — no hay espacio para una compra previa
creíble — y esa apertura se reparte del 2 al 11 de enero de 2025. Consecuencia declarada:
facturas de compra $3,82 M → **$22,47 M**, pagos → **$16,08 M**, saldo CxP → **$6,38 M**, cuadre
exacto ($0,00 de descuadre); la compra supera con mucho la venta porque el stock sembrado ya era
de ~6,8 años de rotación (M2, fuera de alcance). Ventas intactas al centavo (huellas md5 de
`pedido`/`pedido_detalle`/`factura_venta`/`pago` idénticas).

**CIERRE DE LOS OBJETIVOS SIN DATOS (2026-07-25, scripts 79-84)**: cierra 6 de los 7 objetivos
que la §8 de `docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` dejaba vacíos (§12 lleva el estado).
**79** respalda (`seed_backup.op79_*` con filas completas de `movimiento_inventario`/`inventario`
+ 11 huellas md5; reversión `99_revert_objetivos_pendientes.sql`, probada
aplicar→revertir→bit-idéntico→re-aplicar con conteos idénticos). **80** es el ÚNICO que mueve
stock: 61 transferencias (`recibida`/`en_transito`/`pendiente`/`cancelada`) + 50 ajustes
(7 motivos, `aplicado`/`anulado` con contramovimiento) repartidos en 19 meses. **81** siembra
49 preguntas de producto con 29 respuestas (+ `log_auditoria` de la moderación, que es lo ÚNICO
que el sistema audita de este bloque). **82** 1.400 accesos históricos con los 4 motivos de
`LoginFallidoException`. **83** el marketing VIGENTE de hoy (5 promos + 4 campañas + 6 banners +
4 cupones, con % por debajo del margen real de cada categoría). **84** las metas de los 5
departamentos faltantes × 19 meses. Si vas a tocar esto: (1) `transferencia_bodega` y
`ajuste_inventario` son **solo cabecera** — variante y cantidad viven en el kardex y en el texto
`[SKU xN] …`; (2) para insertar una SALIDA en el pasado hay que verificar el **suffix-min** de la
cadena pristina de esa `(variante, bodega)` (el balance final NO basta) y usar **una sola salida
por par**, o el `max_seguro` deja de ser válido; (3) el kardex se reencadena por
`(fecha_creacion, id)` — 150 cadenas reescritas; (4) aquí `inventario.stock_actual` SÍ cambia
(−159 uds: merma −48 + 111 en tránsito), a diferencia del 78. Ventas/compras/dinero intactos
(9 huellas md5 idénticas). `en_transito` = solo la salida en origen; `pendiente`/`cancelada` sin
kardex: son dato histórico, no un flujo vivo (el sistema real crea la transferencia ya
`recibida`). **OTD-GER-07** (efecto de promociones, 123 líneas) queda documentado como
limitación aceptada: densificarlo exigiría reasignar ventas.

**INFORMES TÁCTICOS — VENTAS + PATRÓN REUTILIZABLE (2026-07-25, solo código, sin script)**:
primer módulo del nivel táctico. Se implementaron los CINCO objetivos SIMPLES de Ventas del
`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` — OTD-VEN-01 (cartera de pedidos por estado),
02 (ventas por vendedor), 08 (carritos abandonados), 10 (cola de moderación) y 15 (venta contra
la meta del mes) — bajo la convención `/api/informes/{departamento}/{informe}`, todos GET de
solo lectura y todos devolviendo el MISMO sobre `{items, total, page, size, resumen[]}`.
Backend: `informes/InformeServiceBase` (molde: lista blanca de filtros → 400, paginación
server-side, KPIs, `rolActual()/usuarioActualId()`) + `InformesVentasService/Controller`.
Frontend: UNA pantalla genérica `features/operativo/informes/informes-departamento.component`
parametrizada por `data.departamento` de la ruta, que se pinta desde el archivo declarativo
`definiciones/ventas.informes.ts`; un departamento nuevo = 2 clases Java + 1 archivo TS + 5
líneas de enganche, sin componentes ni servicios Angular nuevos. Si vas a tocar esto: (1) TODO
método de informe va en `@Transactional(readOnly = true)` o corre sin `SET LOCAL ROLE`; (2) los
filtros usan guarda NULL por parámetro `(?::tipo IS NULL OR col = ?::tipo)` con el valor pasado
DOS veces — jamás se concatena texto del usuario; (3) la antigüedad del carrito se mide con
`COALESCE(fecha_actualizacion, fecha_creacion)` porque el trigger touch no dispara en los
abandonados; (4) VEN-02 recorta al VENDEDOR a lo suyo desde el JWT y devuelve
`alcance: "propio"`; (5) NADA de PDF: el nivel táctico se consulta por pantalla. Segregación
financiera verificada: BODEGA y DESPACHO reciben 403 en los cinco informes. El patrón está
documentado en `docs/tactico/PATRON_INFORMES.md`.

**INFORMES TÁCTICOS — COMPRAS Y LOGÍSTICA (2026-07-26, solo código, sin script)**: cuarto y
quinto módulo del nivel táctico, con el mismo coste que el patrón promete (2 clases Java +
1 archivo TS + enganche por departamento; cero componentes, servicios o estilos nuevos).
**Compras**: OTD-COM-01 (`ordenes`, 865), 02 (`cuentas-por-pagar`, 839), 08 (`defectuosos`, 38)
y 10 (`catalogo-proveedor`, 1.106). **Logística**: OTD-LOG-01 (`cola-despacho`, 48),
02 (`envios`, 2.872), 06 (`devoluciones`, 196) y 11 (`costo-envio`, 9 filas agregadas).
Si vas a tocar esto: (1) en COM-01 el estado sintético `pendiente_aprobacion` agrupa
'borrador'+'enviada' — NO existe un estado 'aprobada', aprobar deja la orden en 'confirmada';
(2) en COM-02 conviven DOS clasificaciones: `estado` (columna real) y `situacion` (recalculada
hoy contra `fecha_vencimiento`), y lo pagado es `monto_original − saldo_pendiente`;
(3) **COM-08 estrena el tercer lugar donde vive el corte financiero: la CONSULTA** — BODEGA
entra al informe y el motor NO lo impide (script 45 le dio SELECT sobre
`item_defectuoso.costo_unitario` y `devolucion_proveedor.monto_credito`), así que la barrera es
que el SQL no selecciona monto alguno; (4) la ZONA de LOG-11 no es una columna del envío: se
resuelve desde la dirección del pedido por ciudad > provincia > país, la MISMA cadena de
`VentasService.asignarEnvioPorZona` — agrupar por país miente; (5) LOG-11 lo cierra la RUTA y no
el motor (grp_despacho lee `envio.costo` porque lo escribe al despachar), igual que INV-07;
(6) los informes con agregado por fila usan el triple `tabla + lateral + filtro` — el LATERAL va
ANTES del WHERE y el conteo reutiliza solo `tabla + filtro`. Matriz verificada por API
(8 endpoints × 8 roles): DESPACHO 200 en LOG-01/02/06 y 403 en `/costo-envio`; BODEGA solo en
`/defectuosos` y `/devoluciones`; SOPORTE solo en `/devoluciones`; VENDEDOR y ANALISTA 403 en
los ocho. Detalle en `docs/tactico/PATRON_INFORMES.md` §10.

**INFORMES TÁCTICOS — SOPORTE Y GERENCIA: NIVEL TÁCTICO COMPLETO (2026-07-26, solo código,
sin script)**: sexto y último módulo, mismo coste del patrón (2 clases Java + 1 archivo TS +
enganche por departamento). **Soporte**: OTD-SOP-01 (`bandeja`, 248 tickets / 128 vivos),
04 (`por-categoria`, 8 filas) y 05 (`por-agente`, 7 filas). **Gerencia**: OTD-GER-01
(`foto-dia`, 20 filas agregadas), 04 (`cupones`, 33 / 7 vigentes), 06 (`marketing`, 65 / 20
vigentes), 08 (`auditoria`, 7.073) y 09 (`accesos`, ~1.500). Con esto los SEIS departamentos
tienen sus informes SIMPLES. Si vas a tocar esto: (1) **GER-08 y GER-09 son DATOS SENSIBLES DE
SEGURIDAD** (solo ADMIN/GERENTE, el corte más estricto) y cada uno se apoya en una capa
distinta — en `/accesos` motor + ruta coinciden (solo grp_administrador y grp_gerente leen
`log_acceso`), pero en `/auditoria` **grp_analista SÍ lee `log_auditoria`** (script 19), así que
ahí el corte lo hace la RUTA, como en INV-07 y LOG-11; por eso van en su propia línea de
`SecurityConfig`; (2) el valor por DEFECTO de un filtro es diseño: SOP-01 arranca en el estado
sintético `pendientes` (= `estado NOT IN ('resuelto','cerrado')`, traducido en el servicio, nunca
concatenado), **VEN-01 en el sintético `en_curso`** (ver el bloque «LA CARTERA ABRÍA CON LOS TRES
INDICADORES SIN CALCULAR» al final), GER-04 en `situacion=vigente` y GER-06 en
`vigencia=vigente`; (3) la `situacion`
del cupón replica las TRES condiciones de canje de `DescuentosService` (activo + ventana +
`usos_maximos`), no `activo`; (4) GER-01 emite una fila explícita «Día sin movimiento» y un KPI
«Último día con pedidos» porque el seed llega al 2026-07-22 (pedidos) / 07-23 (cobros) y
consultar hoy sale vacío — limitación temporal declarada, no un fallo; su bloque de
**pendientes es AL MOMENTO**, no del día consultado; (5) un `date` puro se serializa
«AAAA-MM-DD» y el formateador de la pantalla lo lee como UTC restando un día: las fechas-día del
resumen viajan ya formateadas con `to_char` y tipo `texto` (las columnas de los demás informes
son timestamptz y no sufren esto). Detalle en `docs/tactico/PATRON_INFORMES.md` §11. De paso se
corrigió en la pantalla genérica la barra «avance sobre la meta», que aparecía en CUALQUIER
informe con un KPI de porcentaje (afectaba ya a INV-08): ahora es opt-in con `barraAvance: true`
y solo OTD-VEN-15 la declara.

**ETL AL DWH — FASE 0, PRERREQUISITOS (2026-07-30, script 85 + paquete `etl/dwh/`)**: arranca
el pipeline PostgreSQL → ClickHouse que alimentará los 39 objetivos tácticos COMPUESTOS, según
`docs/estrategico/DISENO_ETL_CLICKHOUSE.md` (§9.1). **No se carga ni una fila de datos**:
`registro.TAREAS` está vacío a propósito y las 19 tablas de destino entran por fases. Tres piezas:
(1) **script 85** = rol `retailmind_etl` (LOGIN + **BYPASSRLS** + SELECT sobre las 54 tablas de
origen, `usuario` POR COLUMNA para no exponer `password_hash`). BYPASSRLS es el punto entero del
script: `pol_horario` está declarada con `cmd = ALL`, y **ALL incluye SELECT**, así que un ETL
nocturno con cualquier rol `grp_*` no recibe un 403 — RLS filtra **en silencio** y devuelve CERO
FILAS, publicando 19 tablas vacías sin un error en ningún log (verificado: bajo `grp_cliente` el
mismo `SELECT count(*) FROM pedido` da **0**; bajo `retailmind_etl`, **4.083**). (2) base
**`retailmind_dwh`** (ENGINE `Atomic`, obligatorio para `EXCHANGE TABLES`) + bitácora
`etl_ejecucion`; la base `retailmind` **legada de ClickHouse no se toca** y quedó verificada
bit-idéntica (14 tablas, `fact_eventos` 2.823.245). (3) esqueleto `retailmind/etl/dwh/` con el
patrón de carga atómica del §6.2 (staging `_new` → validar contra el origen → `EXCHANGE TABLES`;
si la validación falla se ABORTA y la tabla publicada no se toca). Si vas a tocar esto: (1) el rol
es de **SOLO LECTURA en cuatro capas** — sin atributos de escritura, solo GRANT SELECT, REVOKE
explícito de escritura, y `default_transaction_read_only = on` a nivel de ROL; las capas 2 y 4 se
probaron **por separado** (con la sesión en READ WRITE el motor sigue negando por privilegio);
(2) el `default_transaction_read_only` implica que **una sesión de este rol no puede escribir ni
temporales** — si alguna fase necesitara una tabla temporal, va en ClickHouse, no en PostgreSQL;
(3) el CONNECT a otras bases del clúster lo concede el `PUBLIC` por defecto de PostgreSQL
(`pg_database.datacl IS NULL` en las 14 bases): cerrarlo exigiría `REVOKE ... FROM PUBLIC`, que es
modificar privilegios existentes y afectaría a las otras apps del clúster — queda declarado como
limitación, y el riesgo residual es nulo porque el rol no tiene privilegio alguno sobre sus
objetos; (4) `EXCHANGE TABLES` exige que AMBAS tablas existan: la primera carga de cada tabla usa
`RENAME TABLE` (ya contemplado en `carga_atomica`); (5) `conexiones._validar_destino` lanza
`BaseProhibida` si alguien apunta el pipeline a la base legada. Punto de entrada:
`python -m etl.dwh.cargar --tabla X` (además de `--init`, `--verificar`, `--listar`, `--bitacora`),
ejecutado desde `retailmind/`. Cada tabla es un comando autónomo: el DAG de Airflow de §7.1 sería
un `BashOperator` de una línea por tarea, sin lógica de negocio dentro.

**ETL AL DWH — FASE 1, EL PILOTO DE PUNTA A PUNTA (2026-07-30, solo código, sin script)**:
`dim_fecha` (730 días, GENERADA con `numbers()` dentro de ClickHouse, no consulta PostgreSQL),
`dim_producto` (1.221 variantes) y `fact_venta_linea` (10.384 líneas) cargadas y validadas contra
PostgreSQL **al centavo**, más el primer informe COMPUESTO **OTD-VEN-06**
(`GET /api/informes/ventas/evolucion-mensual`, ADMIN/GERENTE/ANALISTA). Las 7 cifras de control de
§9.2 cuadran exactas (20.687 uds · venta neta de línea $4.991.078,85 · costo $3.844.509,33 · 0
líneas sin costo · 19 meses) y también el control mes a mes en los 19 meses (3.924 pedidos no
cancelados / $5.498.570,35). Entregable extra: `python -m etl.dwh.validar_dwh` (4 controles,
`--fase`, `--control`, `--detalle`; exit 1 si algo difiere). Si vas a tocar esto:
(1) **`factura_venta` NO es 1:1 con el pedido** — el pedido 2 tiene DOS facturas 'emitida'
(duplicado legacy), así que filtrar solo por `estado <> 'anulada'` da 10.386 filas donde hay
10.384; hay que tomar UNA **factura canónica** por pedido (`DISTINCT ON`, la no anulada más
reciente por `fecha_emision, id`); (2) las **6 excepciones de descuento NO son las que el diseño
suponía** (no son los pedidos 20/21/24662): son los pedidos **40, 4031, 4078, 4106, 4161 y 4176**,
todos con descuento y en estado 'pagado' sin llegar a 'facturado', o sea SIN factura de la que
prorratear el cupón — se cargan con `descuento_cupon_prorrateado = 0`, se MARCAN con la columna
`excepcion_descuento` (consultables en el almacén, no solo contadas) y se registran en
`etl_ejecucion.excepciones`; (3) el `mes` se calcula **en PostgreSQL**
(`date_trunc('month', fecha_pedido AT TIME ZONE 'America/Guayaquil')`) y viaja resuelto: no se
deriva en ClickHouse; (4) `fact_venta_linea.pedido_total` es un **atributo degenerado de cabecera**
— solo se lee tras `GROUP BY pedido_id`; sumarlo a grano de línea lo contaría 2,5 veces; (5) OJO
con ClickHouse: ningún alias de agregado puede llamarse como una columna (`ILLEGAL_AGGREGATION`),
dividir dos `Decimal` trunca a la escala del operando izquierdo (los porcentajes van en
`toFloat64`, el dinero NO), y el minuto de `formatDateTime` es `%i` — `%M` es el nombre del mes.
La degradación se afinó: **solo un fallo de CONEXIÓN degrada**; una consulta mal formada se propaga
como 500, porque capturar todo `DataAccessException` disfrazaba bugs de SQL de «analítica no
disponible» y dejaba la prueba por API en verde. Detalle en `docs/tactico/PATRON_INFORMES.md` §13.

**ETL AL DWH — FASE 2, EL NÚCLEO DE LA VENTA Y EL DINERO (2026-07-30, solo código, sin script)**:
`dim_cliente` (72), `fact_pedido` (4.083) y `fact_flujo_caja` (4.981 = 4.079 cobros + 902 pagos)
cargadas y validadas al centavo, más OCHO informes compuestos nuevos — OTD-VEN-05
(`/clientes`), VEN-07 (`/ticket-promedio`), VEN-09 (`/formas-cobro`), VEN-12 (`/cobros-fallidos`),
VEN-13 (`/evolucion-canal`), LOG-12 (`/logistica/tiempos-ciclo`), GER-02 (`/gerencia/balanza`) y
GER-05 (`/gerencia/descuento-cupones`). Los 10 controles de `validar_dwh.py` (4 de Fase 1 + 6
nuevos) cuadran EXACTOS: 3.924 pedidos no cancelados / $5.498.570,35, canal web 2.132 / tienda 990
/ teléfono 802, $5.467.791,59 cobrados y $16.084.462,74 pagados a proveedor, y el control mes a mes
en los 19 meses. `pedido_total` se RETIRÓ de `fact_venta_linea` (era un atributo degenerado de
cabecera que solo existía para poder validar la Fase 1 sin `fact_pedido`); ningún informe lo leía y
los controles de la Fase 1 siguen cuadrando. Si vas a tocar esto: (1) los **176 cobros fallidos no
tienen `pedido_id` NI `fecha_pago`** —el intento se registra antes de que exista pedido y un cobro
rechazado nunca se liquida—, así que la fecha es `COALESCE(fecha_pago, fecha_creacion)` marcada con
`fecha_es_intento = 1`; tomar `fecha_pago` a secas habría dejado a OTD-VEN-12 sin período y el
informe habría salido vacío sin un solo error; (2) `movimiento_id` **no es único**: `pago.id` y
`pago_proveedor.id` son secuencias independientes que se solapan, la clave es el par
`(sentido, movimiento_id)`; (3) `factura_venta` sigue sin ser 1:1 con el pedido (3.886 no anuladas
→ **3.885** pedidos), así que la factura canónica se aplica también aquí o `fact_pedido` sale con
4.084 filas; (4) `uso_cupon.monto_descontado` ($50.727,89 en 564 canjes) y `pedido.monto_descuento`
($50.590,25 en 562) NO son la misma cifra —los pedidos legacy 20 y 21— y viajan en columnas
separadas a propósito; (5) el pivote de hitos usa `min` y no `max` porque hay hitos repetidos (19
'confirmado', 8 'pagado', 5 'despachado'), y las cuatro etapas de LOG-12 se miden sobre
poblaciones DISTINTAS (2.868 / 2.856 / 2.727 / 3.696: hay 828 entregados sin registro de despacho),
por lo que cada fila declara su `pedidos_medidos`; (6) los tramos van en `Decimal(12,2)` y no en el
`Float32` del diseño, porque `validar_dwh.py` rechaza floats por construcción; (7) `motivo_fallo` se
normaliza en `transformar()` (Python, no SQL) para poder aplicar la regla de escape: 6 valores
crudos → **5** motivos, y cualquier valor no previsto cae en `'otro'` y se registra en la bitácora.
La matriz rol × endpoint (8 × 8) está verificada por API: BODEGA fuera de los ocho y DESPACHO solo
en LOG-12, cuyo corte financiero lo hace **la CONSULTA** (la tabla sí tiene `total`; ClickHouse no
tiene GRANT por columna). Detalle en `docs/tactico/PATRON_INFORMES.md` §14.

**ETL AL DWH — FASE 3A, EL CICLO DE COMPRAS + BITÁCORA DE CORRECCIONES (2026-07-30, solo código,
sin script)**: `dim_proveedor` (11), `fact_orden_compra` (865) y `fact_compra_linea` (2.949)
cargadas y validadas al centavo; **6 controles nuevos en `validar_dwh.py` (16 en total, todos en
verde)**, entre ellos el CUADRE CONTABLE que cruza dos tablas de hechos de fases distintas —
facturas de compra $22.467.387,27 − pagos a proveedor $16.084.462,74 = saldo CxP $6.382.924,53,
**descuadre $0,00**. Entregable de la fase: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`, la
bitácora de los **17 supuestos del diseño que NO se sostuvieron** (2 de la Fase 1, 7 de la 2,
7 de la 3A + apéndice de los que sí), con el formato «qué decía · qué dice la base · cómo se
resolvió · qué informe habría roto». **A partir de aquí, cada supuesto que falle se registra ahí
antes de cerrar la tarea.** Si vas a tocar esto: (1) **la cadena OC→recepción→factura→CxP NO es
1:1 completa** aunque §5.4 lo afirme — 839 OC con recepción y 839 con factura, pero solo **838 con
ambas** (la OC 8 tiene factura sin recepción, la 20 recepción sin factura): dos conteos idénticos
sobre conjuntos distintos, así que se parte SIEMPRE de `orden_compra` con LEFT JOIN encadenado y se
validan los tres por separado; (2) el gasto de compras es **`factura_total` y nunca el total de la
orden** — difieren en 119 órdenes por $226.070,31 porque el proveedor factura lo que entregó
(72 OC en `recibida_parcial`), y sumar la orden inventaría un +2,4 %; (3) `pct_rechazo` se calcula
sobre **lo que LLEGÓ** (`recibida + rechazada`) y no sobre lo pedido, porque el rechazo no siempre
se descuenta de lo recibido (49 líneas descontado, 37 aditivo, 6 mixto): sobre lo pedido, una línea
real da 42,9 % donde la verdad es 30,0 %, y el sesgo cae solo en unos proveedores; (4)
`fecha_recepcion` es `timestamptz` y `fecha_emision` es `date`: la resta exige `AT TIME ZONE`
explícito o 5 de 839 órdenes ganan un día — invisible en el promedio de COM-06, pero COM-05
clasifica con el corte en `desvio <= 0` y ahí un día cambia de lado; (5) `motivo_rechazo` es TEXTO
LIBRE (6 valores crudos, 5 del negocio) y se normaliza en `transformar()` con regla de escape a
`'Otro'`, pero a **frase canónica y no a slug** como en la Fase 2, porque aquí el origen ya guarda
frases legibles; (6) `fact_compra_linea` es la ÚNICA tabla con el `ORDER BY` invertido
(`producto_variante_id, proveedor_id, fecha_emision`) — respétalo: COM-12 es una serie por producto.

**ETL AL DWH — FASE 3B, EL KARDEX Y LA RECONSTRUCCIÓN DEL INVENTARIO MENSUAL (2026-07-31, solo
código, sin script)**: `fact_movimiento_inventario` (13.287) y `fact_stock_mensual` (21.122), la
única `TareaDerivada` del modelo — se calcula DENTRO de ClickHouse y no vuelve a consultar
PostgreSQL. **8 controles nuevos en `validar_dwh.py` (24 en total, todos en verde)**, entre ellos
**LA PRUEBA DEFINITIVA**: el `stock_cierre` del último mes contra `inventario.stock_actual`
**posición por posición — 1.406 de 1.406, 0 diferencias**; corre también DENTRO de la tarea y si una
sola posición difiere la tabla NO se publica. Se conectaron los tres informes compuestos de
Inventario: OTD-INV-04 (`/rotacion`, 10 categorías), OTD-INV-09 (`/capital-inmovilizado`, 19 meses,
$22.024.063,50 al cierre) y OTD-INV-10 (`/mermas`, 11 motivos, 137 uds perdidas / 90 sobrantes).
Si vas a tocar esto: (1) **INV-10 filtra por `es_ajuste_real` y JAMÁS por `naturaleza='ajuste'`** —
la apertura del almacén se registró como `entrada_ajuste` (343 movs / 34.210 uds), así que el filtro
«obvio» multiplica el sobrante real por **380×** sin que falle ninguna suma; el ETL precalcula la
columna para que el error no esté al alcance de un descuido; (2) `ajuste_inventario.motivo` es TEXTO
LIBRE con el SKU incrustado (`[SKU-P1340 x4] Merma…`): en crudo hay **53 valores distintos sobre 53
ajustes** y tras limpiar prefijo y sufijo quedan **11** — aquí se LIMPIA pero NO se remapean
sinónimos, al revés que en C3.3, porque quitar decoración de máquina es una limpieza y fusionar dos
frases que escribió una persona es una opinión; (3) `fact_stock_mensual` son **21.122 filas y no las
~26.700 del diseño**: la malla arranca en el PRIMER movimiento de cada par y no en el primer mes del
período, o se fabrican 5.592 ceros que aplanan la curva de INV-09 al principio; (4) la
reconstrucción LEE `argMax(stock_nuevo, (fecha, movimiento_id))` en vez de recalcular, y eso es
lícito solo porque la cadena está íntegra (verificado: ecuación, enlaces, arranque en 0 y
Σ(cantidad×factor) = stock_actual en los 1.406 pares); (5) **en ClickHouse «no hay dato» y «el dato
es cero» se parecen demasiado** — un LEFT JOIN rellena con el DEFECTO del tipo y no con NULL (por eso
el NULL del arrastre se fabrica desde `movimientos_mes`), y `any(x) OVER (… 1 PRECEDING)` hacía que
el primer mes de INV-09 mostrara su capital entero como «variación»; (6) INV-09 valoriza a **costo
VIGENTE** (no hay histórico) y lo DECLARA en pantalla con el campo nuevo `salvedad` del sobre — es
volumen a moneda constante, no el valor histórico de la bodega; (7) los 56 movimientos de ajuste son
justo los que NO traen `costo_unitario` (177 en total, con las transferencias), así que INV-10
valoriza con `dim_producto.costo` y arrastra la misma salvedad. Matriz 8 roles × 3 endpoints
verificada por API (BODEGA 200 en rotación y mermas, **403 en capital-inmovilizado**; INV-10 es
MIXTO y a BODEGA no le llegan las columnas de valor: no se seleccionan). Detalle en
`docs/tactico/PATRON_INFORMES.md` §15.

**ETL AL DWH — FASE 3C, LA ÚLTIMA MILLA Y LAS INCIDENCIAS DE ENTREGA (2026-07-31, solo código,
sin script)**: `fact_envio` (2.872) y `fact_novedad_envio` (176) cargadas y validadas, **7
controles nuevos en `validar_dwh.py` (31 en total, todos en verde)**, y los CUATRO informes
compuestos de Logística que las tablas dejan servidos: OTD-LOG-03 (`/cumplimiento-promesa`, 5
transportistas), LOG-04 (`/dias-transito`, con filtro `agrupar` ∈ transportista|mes|zona),
LOG-05 (`/novedades`, 14 filas tipo × desenlace) y la **SERIE mensual del costo de envío**
(`/costo-envio-mensual`, 19 meses) — la evolución que OTD-LOG-11 dejó pendiente para ClickHouse
al reclasificarse a SIMPLE; el simple da la FOTO por zona, éste la SERIE. Coste del patrón: **0
clases Java nuevas** (entran en el servicio/controlador de Logística que ya existían por LOG-12)
+ 1 bloque por informe en las definiciones. LOG-09 NO se implementó: necesita `fact_devolucion`
(Fase 4). Si vas a tocar esto: (1) **la zona NO es una columna de `envio`** — se resuelve por
ciudad > provincia > país con precedencia por especificidad (181/596/2.078/17, exactos contra
§5.8), y como las TRES zonas cuelgan del mismo país, agrupar por país manda **2.855 de 2.872 a
UNA fila** sin dar error: por eso la tabla trae `zona_nivel` y los cuatro conteos quedan
auditables con un `GROUP BY`; (2) **C3C.1 — la zona horaria decide el día y con él los tres
plazos**: 569 de 2.727 envíos (20,9 %) cambian de `dias_transito` entre UTC y America/Guayaquil
y el promedio se mueve de 3,98 a 3,77 días — el error es ASIMÉTRICO (el despacho es de tarde, la
entrega de mañana) y acorta el tránsito sistemáticamente; la expresión del día vive en UNA
constante porque aplicarla en dos restas y olvidarla en la tercera da un informe coherente
consigo mismo y equivocado; (3) **C3C.3 — `accion` NO vale lo que dice §5.9**: `reprogramar`/
`devolver_almacen` son los verbos del API y lo guardado es el participio (`reprogramada` 49 /
`devuelto_almacen` 120 / NULL 7), así que el filtro del diseño casa con CERO filas y ocultaría
el 68 % de las novedades — la lista blanca sale de los DATOS, no del documento; (4) **C3C.2 —
los 24 envíos con `costo=0` y peso nulo NO son envíos gratis**, son envíos sin tarifar (ids 1-24,
anteriores al script 54) y caen todos en julio de 2026: promediarlos deja ese mes en $7,59 en vez
de $9,74 (**−22 %**), o sea el último punto de la serie parece una bajada de tarifas; se marcan
con `sin_tarifa`, se excluyen del promedio y el informe DICE cuántos excluyó (campo `salvedad`);
(5) cada informe declara su denominador porque hay TRES distintos (2.872 despachados / 2.727
entregados / 2.723 con promesa medible: los 145 restantes no llegaron tarde, no llegaron) y
`entregado_a_tiempo` viaja NULL —nunca 0— cuando falta una fecha; (6) **es la primera fase donde
dos informes de la MISMA tabla se separan por dinero**: LOG-03/04 y la serie de costo salen los
tres de `fact_envio`, y lo único que deja a DESPACHO fuera del tercero es que su consulta SÍ
selecciona importes (más la línea de `SecurityConfig`) — el motor no los distingue, ClickHouse no
tiene GRANT por columna; las dos rutas de dinero se enumeran POR NOMBRE y no con comodín para que
un endpoint futuro no herede el permiso. Matriz 8 roles × 4 endpoints verificada por API (32
celdas, 0 discrepancias): DESPACHO 200 en LOG-03/04/05 y **403 en `/costo-envio-mensual`**,
SOPORTE solo en `/novedades`, ANALISTA en todos menos `/novedades` y el costo. Degradación
probada con `docker stop`: los 4 informes dan 200 con `analiticaDisponible=false` en ~4,1 s, los
informes SIMPLES de PostgreSQL siguen intactos, y al levantar el contenedor se recuperan sin
reiniciar el backend. Detalle en `docs/tactico/PATRON_INFORMES.md` §16.

**ETL AL DWH — FASE 4, LA POSVENTA Y EL CIERRE DEL MODELO (2026-07-31, solo código, sin
script)**: última fase de carga. `dim_promocion_producto` (232), `fact_devolucion` (196),
`fact_devolucion_linea` (274), `fact_ticket` (248), `fact_resena` (344) y
`fact_devolucion_proveedor` (38) cargadas y validadas al centavo. **13 controles nuevos en
`validar_dwh.py` (44 en total, todos en verde)**, tres de ellos CRUZADOS dentro del almacén
(devoluciones ↔ `fact_pedido`, líneas/tickets/reseñas ↔ `dim_producto`, y la base mensual de
LOG-09 cruzando `fact_envio` de la Fase 3C con `fact_devolucion`): **0 huérfanos en todas las
direcciones**. Con esto el modelo está COMPLETO —19 de 19 tablas— y se conectaron los 16
informes compuestos de POSVENTA: VEN-11, VEN-14, LOG-07/08/09/10, SOP-02/03/06/07/08,
GER-03/07/10/11 y COM-09 — **32 endpoints compuestos en producción**, de los **39 OBJETIVOS
compuestos que declara el catálogo** (endpoints y objetivos NO son la misma cuenta: ver el
recuento reconciliado al final de este bloque y la ficha **C-14** de `DEUDA_TECNICA.md`);
los 7 que faltan son de Compras (COM-03/04/05/06/07/11/12), con sus tablas cargadas desde la
Fase 3A y pendientes solo de conectar. Coste: 2 clases Java nuevas (Soporte y Compras) + 1
bloque por informe en las definiciones; GER-03/10/11 salen de `fact_venta_linea` sin tabla
nueva. Si vas a tocar esto: (1) **el reembolso tiene DOS registros que no coinciden** —
`devolucion.monto_reembolsado` (86 / $44.695,33) y la tabla `reembolso` (85 / $44.525,63), la
devolución 8 no tiene asiento— y solo la cabecera guarda la VÍA, que es media pregunta de
LOG-10: viajan sin reconciliar y el informe declara cuál usa; (2) **el ciclo completo del RMA
solo existe en 35 de 196** (las cerradas), así que se carga además `dias_hasta_desenlace`, que
suma las 18 rechazadas —terminales y las más rápidas— y mide 53; (3) `ticket_soporte`
.`categoria_ticket_id` es NULLABLE y el JOIN interno de §5.12 tira 1 de 248 sin avisar; (4)
**unir `fact_resena` a `dim_producto` por `producto_id` MULTIPLICA** (344 → 347): la dimensión
es por variante y la reseña por producto padre, así que la tabla denormaliza y jamás une; (5)
«resuelto» (44) NO escribe `fecha_cierre` — los tiempos miden sobre los 76 cerrados y no sobre
los 120 «atendidos»; (6) `item_defectuoso.origen` vale `rma`/`recepcion` y NO
`inspeccion_rma`/`recepcion_compra` como dice §5.14: el filtro del diseño vacía COM-09 entero
sin dar error (segunda reincidencia exacta de C3C.3); (7) **19 de 28 ítems defectuosos se
detectaron DESPUÉS de la devolución que los agrupa**, así que `dias_hasta_resolucion` mide el
ciclo de la devolución (registro → resolución) y no la espera del ítem — la resta ingenua sale
negativa en 18 y la carga abortó por ello; (8) los 4 informes con `now()` en su clasificación
(SOP-02 y los vencidos de SOP-07/08) NO precalculan nada: el veredicto de un abierto depende de
la hora en que se mira. **SOP-02 parte la base en cuatro** (12 a tiempo / 64 tarde / 0 abiertos
en plazo / 172 abiertos y vencidos) porque una tasa sobre 248 daría 4,8 % y sería falsa; los
DOS informes de muestra débil la declaran en pantalla (**COM-09**: 6 resoluciones,
$5.220,94 recuperados sobre un pool de $9.349,93; **GER-07**: 184 líneas en ventana y 123 con
descuento frente a 3.217 de base, ordenado por VOLUMEN y nunca por la variación). Matriz 16
endpoints × 8 roles verificada por API (128 celdas, 0 discrepancias): los seis con dinero
—VEN-14, LOG-10, GER-03, GER-10, GER-11, COM-09— dejan fuera a BODEGA y DESPACHO por RUTA;
BODEGA solo entra en LOG-08 y DESPACHO solo en LOG-09. Degradación probada con `docker stop`:
200 con `analiticaDisponible=false` en ~4,1 s y recuperación sin reiniciar. Detalle en
`docs/tactico/PATRON_INFORMES.md` §17.

**LOS SIETE DE COMPRAS — CATÁLOGO TÁCTICO COMPLETO (2026-07-31, solo código, sin script, NO
carga ni una fila)**: se conectan los objetivos que quedaban, todos sobre tablas ya validadas
en las Fases 2 y 3A. **Compuestos** (ClickHouse): OTD-COM-03 (`/puntualidad-pago`, 902 pagos /
$16.084.462,74 / 564 a tiempo), COM-04 (`/gasto-mensual`, 839 facturas / $22.467.387,27),
COM-05 (`/cumplimiento-plazo`, 825 pares / 449 cumplidas), COM-06 (`/ciclo-compra`, 839 órdenes
/ 10,81 días de media) y COM-12 (`/evolucion-costo`, 1.041 pares producto-proveedor; 768 subieron,
150 bajaron). **Mixtos** (BODEGA entra «en cantidades, sin montos»): COM-07 (`/rechazos`, 185 uds
/ 5 motivos / $27.557,63) y **COM-11** (`/entregas-incompletas`, 165 líneas cortas / 1.514 uds),
que es **SIMPLE y va contra PostgreSQL** —era el último objetivo simple del catálogo sin
construir— porque agrega sobre la foto presente sin comparar períodos. Coste: **0 clases Java
nuevas** + 1 bloque de definición por informe + 2 líneas de `SecurityConfig`. Si vas a tocar esto:
(1) **el mes del GASTO es el de la FACTURA y no el de la orden** (corrección C5.1): 360 de las 839
facturas caen en un mes distinto al de su OC, y agrupar por `fact_orden_compra.mes` desplaza
**$4.628.932,62** entre meses —enero 2025 +52,6 %, julio 2026 −46,8 %— **sin que el total deje de
cuadrar al centavo**; la columna `mes` sí es la correcta para COM-05/06, que hablan de la orden;
(2) las «259 líneas incompletas» del catálogo son **tres cosas distintas** (C5.2): solo 165 son
incumplimiento del proveedor, 41 vienen de camino y 53 son de órdenes canceladas — con las 259,
Comercial El Costeno pasa de mejor proveedor (99,71 %) a PEOR (91,77 %) por 4 órdenes que canceló
Compras, así que el filtro `alcance` arranca en `entregadas`; (3) **COM-05 y COM-06 miden sobre
poblaciones distintas a propósito** (825 pares con promesa y llegada vs 839 con llegada) y cada
fila declara su base; (4) en COM-12 **`lagInFrame` rellena la primera fila de cada partición con
el DEFECTO del tipo y no con NULL**, así que la frontera de la serie se marca con
`row_number() > 1` y jamás con `precio_previo != 0` —comparar contra ese 0,00 daría «subida del
100 %» en los 1.041 primeros precios—, y el desempate del mismo día (16 pares) es
`orden_compra_id`; (5) dos trampas NUEVAS y las dos de Java, no de ClickHouse: un bloque de texto
**recorta el espacio final de cada línea** (`"""SELECT """ + col` → `SELECTpr.razon_social`) y
`String.formatted()` **interpreta el bloque entero, comentarios incluidos** (el patrón de fecha
va con el por-ciento duplicado… y el comentario que lo explicaba tumbó la consulta por llevar un
especificador suelto dentro). Verificación: **41 controles contra PostgreSQL tomando la cifra de
la RESPUESTA HTTP**, todos con Δ = 0; matriz 7 endpoints × 8 roles (56 celdas, 0 discrepancias):
ANALISTA entra en COM-03/04/06/12 y queda fuera de COM-05 —que no lleva ni un importe, pero el
catálogo lo reserva a Compras y Gerencia—, BODEGA solo en COM-07 y COM-11. Detalle en
`docs/tactico/PATRON_INFORMES.md` §18.

**TABLEROS DE DIRECCIÓN — FASE E1-A (2026-08-01, solo código, sin script, NO carga ni una
fila)**: arranca el nivel ESTRATÉGICO con los tres primeros tableros de
`docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md` §4 — **T-1 Omnicanal** (OE-06), **T-2 Rentabilidad
y Rotación** (OE-07) y **T-3 Cliente y Posventa** (OE-08) — que cubren 10 de las 19 decisiones de
dashboard. **0 tablas nuevas**: las 19 del almacén bastan. Paquete nuevo `tableros/`
(`TableroServiceBase` + 3 servicios + 1 controlador) y pantalla genérica
`features/operativo/tableros/` parametrizada por `definiciones/tableros.ts`; un tablero nuevo =
1 clase Java + 1 bloque de definición.
**ENFOQUE: una respuesta por tablero, no una por elemento** (`GET /api/tableros/{tablero}` →
`{tablero, kpis[], bloques[], salvedades[], datosAl, analiticaDisponible}`). Los seis elementos
comparten filtros, llevan la MISMA marca de agua y degradan a la vez; con seis peticiones,
ClickHouse cayéndose a mitad de carga dejaría medio tablero pintado. **Excepción declarada**: los
dos elementos que NO salen del almacén —carrito abandonado (T-1) y sobre-stock del presente
(T-2)— los pide la PANTALLA con una segunda llamada a los informes simples OTD-VEN-08 y
OTD-INV-08, y por eso **siguen vivos con ClickHouse apagado**.
Si vas a tocar esto: (1) **el embudo de T-1 cuenta «alcanzó este hito O uno posterior», jamás la
marca a secas**: hay **969 pedidos entregados sin registro de despacho**, y con
`countIf(fecha_despachado IS NOT NULL)` el embudo NO es monótono (2.868 despachados contra 3.696
entregados) y pinta una fuga del 26 % en la etapa que no la tiene — justo la decisión que D-06.2
toma; (2) la **tasa de rechazo del cobro NO es separable por canal**: los 176 intentos fallidos no
tienen `pedido_id` y el canal sale del pedido, así que partirla daría **0 % en los tres canales**
sin un solo error (misma causa que C2.1); (3) **T-3 recorta a SOPORTE por la CONSULTA y no por la
ruta** —entra al endpoint y el servicio NO ejecuta los bloques de valor del cliente ni de
reseñas—, y el sobre declara cuáles omitió en `bloquesOmitidos`; (4) la salvedad de **costo
vigente** (margen) y **moneda constante** (capital) es obligatoria y se pinta ENCIMA de la cifra;
(5) `bloque()` **exige** el campo `denominador` y revienta sin él: en este nivel una cifra sin su
base no produce una pantalla rara, produce una decisión; (6) el alias de agregado con el nombre de
su columna reincidió **seis veces** en una tarde (`sum(monto) AS monto` → `ILLEGAL_AGGREGATION`):
prefija `t_` SIEMPRE y repón el nombre del contrato en el SELECT exterior; (7) la **dispersión se
pinta entera** (834 puntos) aunque su tabla se recorte — la cruz son las MEDIANAS del conjunto y
con las 40 primeras por venta los cuadrantes dejan de corresponder con lo que se ve; (8) los
rótulos del SVG son `<title>` NATIVO y no `matTooltip`: con 1.933 directivas vivas el navegador
dejaba de responder.
Verificación: **71 controles contra PostgreSQL tomando la cifra de la RESPUESTA HTTP**
(`retailmind/validar_tableros.py`, rol `retailmind_etl`), todos con Δ = 0 — venta $5.498.570,35 ·
3.924 pedidos · 64 clientes omnicanales · margen $1.049.320,91 · capital $22.024.063,50 · 387
productos hueso · 834 variantes con venta · 76 tickets cerrados · 344 reseñas sin multiplicar.
Matriz **24 celdas × 0 discrepancias** (`retailmind/matriz_tableros.py`, que ensancha la ventana
horaria y la **restaura verificándola**). Degradación probada con `docker stop`: 200 con
`analiticaDisponible=false` en ~4,1 s, los bloques de PostgreSQL intactos, y recuperación sin
reiniciar. Los 6 supuestos del diseño que no se sostuvieron están en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE1.1 a CE1.6).

**TABLEROS DE DIRECCIÓN — FASE E1-B: NIVEL ESTRATÉGICO DE TABLERO COMPLETO (2026-08-01, solo
código, sin script, NO carga ni una fila)**: los cuatro que faltaban — **T-4 Operación y Última
Milla** (OE-09), **T-5 Costo de la Operación** (OE-09), **T-6 Abastecimiento** (OE-11) y **T-7
Gobierno del Dato** (OE-10) —. Con E1-A son **7 tableros y las 19 decisiones de dashboard, todas
servidas**; **0 tablas nuevas** en el almacén, como el diseño anticipó. Coste: 4 clases Java + 1
bloque de definición por tablero + 4 líneas de `SecurityConfig` (la pantalla genérica no se tocó,
solo ganó dos trazados: caja-y-bigotes y mapa de calor).
Si vas a tocar esto: (1) **T-4 es el ÚNICO tablero SIN dinero y el único que DESPACHO y BODEGA
abren**; lo sostienen DOS cosas a la vez —su línea de `SecurityConfig` y que su consulta no
seleccione un importe—, y como ClickHouse no tiene GRANT por columna la segunda se comprueba
automáticamente: `validar_tableros.py` recorre la respuesta entera buscando nombres con aspecto
monetario y falla si aparece uno, **en los cinco roles**; (2) **`dim_fecha` NO tiene
`fecha_carga`** —es el calendario, generado con `numbers()` dentro de ClickHouse— y pedírsela no
devuelve nulo, revienta con `UNKNOWN_IDENTIFIER`: la frescura se calcula sobre las **18** tablas
con sello y el calendario se publica aparte, marcado; (3) en `etl_ejecucion` **`corrida` y
`validar_dwh` NO son tablas**: la primera escribe DOS filas (`en_curso` al empezar, `exito` al
acabar) y su `filas_escritas` repite el total de todas las tablas, así que sumar sin excluirlas da
**128.214 donde hay 64.085** —el doble exacto— y lista una tarea eternamente «en curso»; se colapsa
con `argMax(…, inicio)` y se excluyen de todo conteo; (4) **«preparación» en `horas_pago_a_
preparacion` es el hito `preparado` (picking TERMINADO), no `en_preparacion`** —2.868 pedidos
frente a 2.883—, y las etiquetas del tablero ya no usan esa palabra; (5) el embudo del retorno al
almacén **termina en CERO y se publica igual**: los 120 pedidos devueltos no tienen ninguna
devolución registrada después, la mercancía volvió y no consta en ningún sitio — es la brecha que
D-09.4 tiene que ver, no un fallo del dato; (6) en `argMax(resultado, inicio)` el segundo argumento
es la **columna cruda**, jamás el alias del `min(inicio)` del mismo nivel: renombrar el alias no
basta, hay que dejar de usarlo ahí dentro; (7) T-6 arranca en `alcance=entregadas` porque con
«todas» el mejor proveedor (99,71 %) pasa a ser el peor (91,77 %), y su GASTO se agrupa por el mes
de la **FACTURA**, no por el de la orden.
Verificación: **132 controles contra PostgreSQL tomando la cifra de la RESPUESTA HTTP**
(`retailmind/validar_tableros.py`, rol `retailmind_etl`), todos con Δ = 0 — 2.872 envíos · 2.723
con promesa medible · 1.704 a tiempo · tramos 2.868/2.856/2.727/3.696 · 176 incidencias con 169
resueltas · merma 137 perdidas/90 sobrantes (con el filtro por naturaleza serían 34.300: **381×**) ·
$32.723,25 de flete con 24 envíos sin tarifar excluidos · 86 reembolsos con 1 sin asiento y $169,70
de diferencia · $22.467.387,27 facturado · $6.382.924,53 de saldo · 902 pagos por $16.084.462,74 ·
38 ítems defectuosos en 9 devoluciones. Matriz **56 celdas × 0 discrepancias**
(`retailmind/matriz_tableros.py`, que ensancha la ventana horaria y la **restaura verificándola**):
ANALISTA entra en SEIS tableros y queda fuera de T-7; COMPRAS solo en T-6; BODEGA y DESPACHO solo
en T-4. Degradación probada con `docker stop`: 200 con `analiticaDisponible=false` en ~4,08 s, los
bloques servidos desde PostgreSQL intactos (auditoría 7.073, accesos 219), y recuperación sin
reiniciar. Los 4 supuestos que no se sostuvieron están en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE2.1 a CE2.4). **Pendiente del nivel estratégico**:
solo el modelo E3 (alerta de abandono); E2 está hecho — ver el bloque siguiente.

**PREVISIÓN DE DEMANDA — FASE E2, EL PRIMER MODELO (2026-08-01, solo código, sin script)**:
`fact_prevision_demanda`, la **tabla 20** del almacén y la primera con filas de **fecha futura**
(510 = 3 meses × [1 total + 10 categorías + 159 variantes]). Descomposición multiplicativa con
factores estacionales ENCOGIDOS, ajustada a total y categoría, desagregada a la variante por
cuota; entra como `TareaModelo` (sabor nuevo de `TareaDerivada`: se calcula desde el DWH pero su
transformación es Python y no un `INSERT … SELECT`). El modelo vive en
`retailmind/etl/dwh/modelos/prevision_demanda.py` y **no abre ninguna conexión**: entran dos
vectores, sale una previsión con banda. **Veredicto: se publica el MODELO** — MAPE total
**8,78 %** contra **12,22 %** del ingenuo estacional y 33,00 % del ingenuo (con el mes truncado
anualizado, 17,39 % contra 20,07 %), cobertura de la banda del 80 % en **87,6 % (772/881 puntos)**,
y 41 de 168 series publican su línea base por no superarla (`es_linea_base = 1`). Dos endpoints
—`/api/informes/{gerencia|compras}/prevision-demanda`— sobre UNA sola clase
(`InformesPrevisionService`) que los dos controladores existentes inyectan. Si vas a tocar esto:
(1) **el `k ≈ 2` del diseño encoge DEMASIADO y hace que el modelo se rechace a sí mismo**: deja
diciembre en 1,075 donde el generador del seed lo escribió en 1,48, infla σ al 0,214 y la banda al
±41 %, con lo que la cobertura sale **100 %** y suspende el criterio de §5.1.6; k se ESTIMA de los
datos (Stein / Bayes empírico, `k = σ²/τ²` → 0,175, recortado al suelo declarado de 0,25);
(2) **el nivel con el que se calculan las razones tiene que ser ESTACIONALMENTE NEUTRO** — el nivel
filtrado por suavizado exponencial persigue la subida de mayo, la varianza dentro del mes sale mayor
que la de entre meses y los doce factores acaban entre 0,98 y 1,02; se usa el nivel GLOBAL de la
serie ya normalizada al año base; (3) **la cobertura NO se juzga sobre los 6 puntos del total** —
una banda perfecta al 80 % da 6/6 el 26 % de las veces—: se mide sobre los 881 puntos agrupados;
(4) **el mes truncado se DETECTA** (día máximo del último mes contra la mediana de los anteriores;
julio 2026 cubre 1/1,227) y su exclusión se publica en `horizonte_efectivo`, porque la tabla sale
IDÉNTICA se haya excluido o no y la pantalla no tendría cómo marcarlo; (5) **la regla «la banda se
ensancha con el horizonte» NO es exigible fila a fila** —en unidades falla en 15 series y en
relativo en 16, todas con razón—: se exige serie a serie sobre `descomposicion` y en media sobre el
resto; (6) `String.formatted()` interpreta el bloque entero y `formatDateTime(mes,'%Y-%m')` lo
revienta con un 400 «Conversion = 'Y'» (reincidencia de §18). Verificación: **46 controles** de
`validar_dwh.py` en verde (44 + universo + ancla, este último comprobando desde PostgreSQL que el
mes truncado se excluyó) y **16 celdas × 0 discrepancias** en `retailmind/matriz_prevision.py`, que
además contrasta contra PostgreSQL **la serie del gráfico mes a mes** y exige las cinco
limitaciones de §5.1.10 en la salvedad. Degradación probada con `docker stop`: 200 con
`analiticaDisponible=false` en ~4,1 s y recuperación sin reiniciar. Detalle en
`docs/tactico/PATRON_INFORMES.md` §20; los 6 supuestos que no se sostuvieron, en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE3.1 a CE3.6).

**ALERTA DE ABANDONO — FASE E3, EL ÚLTIMO MODELO (2026-08-02, solo código, sin script)**:
`fact_alerta_cliente`, la **tabla 21** del almacén, y con ella el nivel estratégico COMPLETO.
Es un modelo del **PROCESO** y no uno aprendido, y entra sabiendo que §5.2.2 lo declaró **NO
VIABLE como modelo entrenado**: no hay etiqueta de abandono, el generador del seed sortea al
cliente con peso constante —**nadie abandona nunca**— y la correlación entre el mejor predictor
y el resultado real es **0,039**. Se implementa supervivencia exponencial con la tasa propia de
cada cliente (λᵢ = pedidos/días observados; P = e^(−λᵢ·t); alerta si P < α = 0,05), sin
etiquetas y con la tasa de falsa alarma conocida de antemano. **Se publica CON SU LIFT A LA
VISTA**: las tres primeras tarjetas de la cabecera son el lift medido (**1,99×**), su muestra
(**14 casos positivos de 167 evaluaciones**) y el dictamen **«¿Supera al azar? NO · p = 0,1019»**
—el valor p es la pieza que el diseño no pidió y sin la cual un 1,99 sobre 14 positivos se lee
como un éxito—. Endpoint `GET /api/informes/ventas/clientes-en-riesgo` (ADMIN/GERENTE/VENDEDOR,
lleva monto: Bodega y Despacho fuera por RUTA), **0 clases Java nuevas** y **0 componentes
Angular nuevos**. Si vas a tocar esto: (1) **la ventana estable NO es una fecha** — son los
últimos `MESES_VENTANA = 7` meses contados desde el ancla, y un mes escrito en el código
funcionaría exactamente una vez; el guardia de **concentración ABORTA la publicación** si algún
mes de la ventana tiene un cliente por encima del 25 % (hoy 10,9 %; con los 19 meses, **100 %**,
y ahí el 2.º cliente de la cartera —$399.425— sale como la alerta más fuerte del sistema con
P = 4·10⁻¹⁷: la inversión exacta de la verdad); (2) **la recencia se ancla a `max(fecha_pedido)`
del almacén y JAMÁS al reloj** —si el ETL se para, los 69 clientes cruzarían el umbral a la vez—
y la fecha ancla va EN EL TÍTULO; (3) **los clientes sin muestra son los candidatos más fuertes
y el modelo los expulsa**: los dos silencios más largos de la cartera (179 y 94 días) tienen por
eso mismo menos de 3 pedidos en la ventana, así que se publican los **69** con nivel
`sin_muestra` y su silencio REAL en vez de los 61 evaluables; (4) el lift se divide por la tasa
base **de su propio origen** (5,8 % · 7,0 % · 12,1 %, no el 9,4 % del diseño) y un origen sin
positivos da lift **inexistente**, no cero; (5) **la ventana de entrenamiento del backtest RUEDA
con el origen**, o se mide un estimador que nunca se publica (1,34 fija vs 1,99 rodante); (6) el
recorte del VENDEDOR **no puede usar `vendedor_id`** —el almacén guarda el NOMBRE—: casa contra
`vendedores Array(String)` y el ETL valida que los nombres sean únicos; medido, deja **50 de 69**
clientes, porque 54 fueron atendidos por 3 o más vendedores. Verificación: **49 controles** de
`validar_dwh.py` en verde (46 + 3 nuevos, donde PostgreSQL **recalcula el modelo entero**,
exponencial incluida, y contrasta λ **cliente por cliente**) y **8 celdas × 0 discrepancias** en
`retailmind/matriz_alerta_cliente.py`, que además verifica el reparto por nivel contra
PostgreSQL (69/3/6/8, Δ = 0), el recorte del vendedor cliente por cliente y que el **lift esté en
las tres primeras tarjetas**. Degradación probada con `docker stop`: 200 con
`analiticaDisponible=false` en ~4,1 s y recuperación sin reiniciar. Detalle en
`docs/tactico/PATRON_INFORMES.md` §21; los 8 supuestos que no se sostuvieron, en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` (CE4.1 a CE4.8).

**CONTENERIZACIÓN COMPLETA (2026-08-03, sin script — bitácora en `docs/DESPLIEGUE_EJECUTADO.md`)**:
PostgreSQL migrado al contenedor y **cortado** (el contenedor sirve en el **5432**, el local pasó al
**5433** y queda como marcha atrás de un minuto). Las **11 verificaciones** de §9.5 del diseño
pasaron —95 políticas RLS, 109 columnas con ACL, 13 funciones SECURITY DEFINER, 1.354 GRANT, las 4
sumas de columnas GENERATED al centavo— con `diff` de salidas contra el local, no leyendo números.
El `docker-compose.yml` objetivo está escrito y probado de punta a punta: los 4 servicios `healthy`
en **28 s**, los 10 usuarios entran, 4 pantallas operativas + informe simple + compuesto + **los 7
tableros** + **los 2 modelos** responden 200, el invariante de ClickHouse apagado se cumple
(`status: UP` / `analytics: DEGRADED` en 5 s acotados, y recuperación **sin reiniciar el backend**)
y el **ETL corrió DENTRO de Docker por primera vez**: 21/21 tablas, 66.079 filas, **49/49
controles**. Credenciales internas rotadas. Si vas a tocar esto: (1) la imagen **`postgres:18` monta
en `/var/lib/postgresql`**, NO en `/var/lib/postgresql/data` —desde la 18 el directorio lleva la
versión (`PGDATA=/var/lib/postgresql/18/docker`)— y con la ruta antigua la imagen **se niega a
arrancar** con un mensaje que habla de `pg_upgrade` y despista; (2) **un ACL que `pg_dump` no emite
NO es un privilegio perdido**: omite el GRANT cuando coincide con el que el objeto tendría por
defecto, así que se compara el privilegio EFECTIVO con `has_function_privilege`, no el texto del
ACL; (3) comparar catálogos entre dos motores exige **`COLLATE "C"` en todo `ORDER BY` de texto**
(origen `Spanish_Ecuador.1252`, destino ICU `es-EC`) o el diff acusa una diferencia inexistente;
(4) un **healthcheck contra `localhost` dentro de un contenedor** resuelve a `::1` primero y nginx
solo escucha IPv4 → `unhealthy` eterno **con la página sirviéndose bien**: siempre `127.0.0.1`;
(5) al interpretar un 403 de bodega/despacho/compras, **mira el reloj antes que la migración**
(`fuera_horario` bloquea el LOGIN entero). El DAG de Airflow que esta bitácora dejaba pendiente
YA ESTÁ (2026-08-06, ver el bloque «ORQUESTACIÓN DEL ETL CON AIRFLOW» al final), y con él
**`DWH_CRON=-`** ya está puesto. El MODELO DE
DATOS está **COMPLETO**: las **19 tablas de hechos** del DWH más `fact_prevision_demanda` y
`fact_alerta_cliente` — **21 tablas**, hoy con **26.971.498 filas / 1,47 GiB** (re-medidas el
2026-08-17; las «66.082 filas» eran de ANTES de la carga masiva y se quedaron aquí de la
redacción original), cargadas y validadas (**49 controles en verde**). Esa es la cifra del
MODELO; la base `retailmind_dwh` tiene **22 objetos** porque además está la bitácora
`etl_ejecucion`, que NO es del modelo y por eso no se suma — y que además CRECE con cada corrida
del DAG, así que sumarla da un número distinto cada día (862 filas el 2026-08-07). El **CATÁLOGO TÁCTICO**: 30 informes simples y
**43 rutas de informe compuesto**, que NO son 43 objetivos — el desglose reconciliado es
**39 objetivos OTD con ruta propia + 2 modelos estratégicos + `costo-envio-mensual` (declarado
fuera del catálogo) + `prevision-demanda` servida en dos departamentos**. Desde el 2026-08-07
el catálogo compuesto está **COMPLETO, 39 de 39**: entraron **OTD-VEN-03**
(`/api/informes/ventas/top-productos`) y **OTD-VEN-04** (`/productos-hueso`), los dos que
faltaban. La lección de **C-14** sigue en pie y por eso se escribe el desglose: **rutas y
objetivos no son la misma cuenta**. Y el **NIVEL
ESTRATÉGICO está CERRADO**: 7 tableros, las 19 decisiones de dashboard y **los 2 modelos**
(previsión de demanda y alerta de abandono). El diseño del pipeline vive en
`docs/estrategico/DISENO_ETL_CLICKHOUSE.md` y el del nivel estratégico en
`docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`; **los dos están corregidos en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md` — 57 supuestos que no se sostuvieron; léelo antes
de tocar cualquier tabla**.

**PERMISOS DEL MOTOR — LA PANTALLA QUE ENSEÑA LA SEGURIDAD (2026-08-05/06, scripts 86 y 87)**:
`/operativo/seguridad/permisos`, **solo ADMIN** (6+3 endpoints ENUMERADOS uno a uno en
`SecurityConfig`, nunca por comodín). Seis pestañas: **Editor de rol** (interruptores),
Roles, Usuarios por rol, Permisos (grilla plana auditable), Políticas RLS y Restricción
horaria. Todo sale de `pg_catalog`: las tablas `permiso`/`rol_permiso` están VACÍAS y son
vestigiales. Cifras contrastadas contra el motor: 9 roles · 95 políticas · 50 tablas con RLS ·
**109 columnas con ACL en 14 tablas** · **1.355 GRANT** (era 1.354; +1 por la tabla nueva
`rol_personalizado`) + 113 MAINTAIN aparte.
Si vas a tocar esto: (1) **un GRANT ejecutado por quien NO es propietario NO FALLA** — emite
`WARNING: no privileges were granted` y no hace nada, así que sin `fn_admin_cambiar_permiso`
(script 86, SECURITY DEFINER) la pantalla respondería 200 a cada clic sin cambiar el motor; la
función **verifica el privilegio efectivo antes y después** y devuelve `aplicado`; (2)
**`information_schema` filtra por `pg_has_role` y miente por debajo EN SILENCIO**:
`role_table_grants` da **1.354** como superusuario y **738** bajo `grp_administrador`, que es el
rol con el que corre la pantalla — todo se lee con `aclexplode()` sobre `pg_catalog`, y los de
COLUMNA desde `pg_attribute.attacl`, nunca desde `column_privileges` (expande los heredados);
(3) **los privilegios de columna solo SUMAN**: revocar una columna a un rol que tiene el
privilegio de TABLA no cambia nada — para restringir hay que revocar la tabla y conceder las
columnas, que es exactamente como está hecha la segregación financiera (bodega: 90 ACL de
columna y CERO SELECT de tabla sobre `pedido`); (4) LECTURA no necesita SECURITY DEFINER
(`grp_administrador` lee `pg_catalog` entero), solo la ESCRITURA. **Cuatro protecciones**: R1
`grp_administrador` no se toca; R2 identidad (`usuario`/`usuario_rol`/`rol`/`permiso`/
`rol_permiso`) cerrada en AMBAS direcciones —ahí lo peligroso es CONCEDER, por `password_hash`—
y rastro/compuerta (`log_auditoria`/`log_acceso`/`grupo_horario`) solo prohíbe REVOKE; R3 solo
los `grp_*` son destinatarios (deja fuera a `retailmind_app`/`retailmind_etl`/PUBLIC **por
construcción**); R4 solo tabla/columna y SELECT/INSERT/UPDATE/DELETE (USAGE ON SCHEMA y las
membresías quedan fuera porque no hay parámetro que las exprese). Todo cambio va a
`log_auditoria` como `pg_privilegio` con el autor del JWT.

**ROLES PROPIOS (script 87)**: `fn_admin_crear_rol` / `fn_admin_eliminar_rol` + tabla
`rol_personalizado`. Un `CREATE ROLE` a secas da un rol **INSERVIBLE y falla en silencio**:
hacen falta **SEIS piezas** —NOLOGIN · `GRANT USAGE ON SCHEMA public` (el 19 se lo revocó a
PUBLIC) · `GRANT <rol> TO retailmind_app` (sin ella `SET LOCAL ROLE` falla y la app entera da
403) · 7 ventanas en `grupo_horario` (sin ellas el script 53 BLOQUEA el login) · **una política
RLS por cada una de las 50 tablas con RLS** (el defecto de RLS es DENEGAR: con SELECT y sin
política lee **CERO FILAS sin un solo error**) · fila en `rol` con `es_sistema = false`—. El rol
nace SIN privilegios; se encienden con los interruptores. **`rol_base` decide PANTALLAS, no
datos**: `SecurityConfig` es código compilado y no conoce roles creados en caliente, así que la
authority del JWT es la del rol base mientras que contra el motor se asume el rol PROPIO — dos
usuarios en la misma pantalla viendo datos distintos. Probado de punta a punta: el usuario del
rol nuevo entra a `/api/ventas/pedidos` y recibe **403 del motor** hasta que se le encienden los
privilegios. Solo se elimina lo que tenga marca de catálogo **y** fila propia **y** cero
usuarios. Trampas de PL/pgSQL que costaron tiempo: un alias de tabla `r` choca con la variable
de bucle `r record` («record is not assigned yet»), y una columna de `RETURNS TABLE` con el
mismo nombre que una columna real da «ambiguous».

**VENTANAS HORARIAS EN 24/7 — EL MECANISMO INTACTO (2026-08-06, scripts 88, 89 y 90)**: el 88
puso las ventanas de `grupo_horario` en `[00:00:00, 24:00:00)` los 7 días. **La restricción por
horario NO se eliminó ni se debilitó: solo cambiaron FILAS DE DATOS.** Verificado hoy contra el
motor: siguen los **34 triggers `trg_horario_*`**, las **50 políticas `pol_horario`** con
`cmd = ALL`, las 50 tablas con RLS y los md5 de `esta_en_horario()`, `fn_grupo_actual()` y
`fn_bloquear_fuera_horario()` sin cambio; ni un GRANT tocado. El motivo es de PRESENTACIÓN: las
ventanas viejas dejaban a los seis roles de personal bloqueados **1.986-1.988 minutos de cada
10.080 (19,7 % de la semana)**, y como `pol_horario` es `cmd = ALL` —y ALL incluye SELECT— un
fallo dentro de esa franja no da error: **RLS filtra y devuelve CERO FILAS en silencio**.
Respaldo del estado previo en `seed_backup.hor88_grupo_horario_20260806`.
La frontera es `24:00:00` y no `23:59`: `esta_en_horario()` compara con el intervalo SEMIABIERTO
`[hora_inicio, hora_fin)` y un cast desde un instante real nunca puede valer `24:00:00`, así que
es la única frontera que no deja un microsegundo fuera.
Para DEMOSTRAR la restricción en vivo están los hermanos, que el 88 no ejecuta: **89**
(`-v rol=grp_x`, estrecha la ventana de UN rol a un rango que excluye el momento actual y
ABORTA si `esta_en_horario()` sigue en true) y **90** (restaura el 24/7 y ABORTA si algún rol
queda con un solo minuto bloqueado). **OJO**: `grupo_horario` NO está congelado — la pantalla de
admin (`HorariosAdminService:50/61`, INSERT/UPDATE para `grp_administrador`) puede reescribir
esas ventanas, y eso ya pasó una vez: `grp_analista` domingo (`grupo_horario` id 54) apareció en
`00:00-23:30` —30 minutos bloqueados de 10.080, la única de las 56—, escrita por esa pantalla y
no por ningún script (el 90 aborta si eso pasa). **RESTAURADA el 2026-08-07** ejecutando el 90:
hoy los 8 roles están en 0 minutos bloqueados de los 10.080, verificado con la misma condición
que evalúa `esta_en_horario()`. La ficha vive en `DEUDA_TECNICA.md` (C-11, la causa; y la
entrada A-1 en el histórico de resueltas).

**ORQUESTACIÓN DEL ETL CON AIRFLOW (2026-08-06, perfil `airflow`)**: Apache Airflow 2.10.5 con el
DAG **`retailmind_dwh`** (`retailmind/airflow/dags/retailmind_dwh.py`) = **21 tareas de carga, una
por tabla, más `validar_dwh`**; cada tarea es un `BashOperator` que invoca `run_etl.py`, sin lógica
de negocio dentro. Sus metadatos viven en una base **separada** llamada `airflow` del MISMO
contenedor PostgreSQL: la base `retailmind` no se toca. Tres servicios (`airflow-init`,
`airflow-webserver` en el **8081**, `airflow-scheduler`) bajo el perfil `airflow`, que **NO
arranca** con un `up -d` a secas porque `.env` fija `COMPOSE_PROFILES=demo`:

```bash
docker compose --profile airflow up -d      # web en http://localhost:8081
docker compose exec airflow-scheduler airflow dags unpause retailmind_dwh
```

Estado verificado hoy: DAG **ACTIVO** (`is_paused = False`) con schedule **`0 2 * * *`**. Como
Airflow tomó el relevo, el `@Scheduled` del backend está APAGADO con **`DWH_CRON=-`** (en `.env` y
confirmado en el entorno del contenedor vivo); si se reactivara, a las 02:00 dispararían los dos y
competirían por el `EXCHANGE TABLES`. Trampas: (1) **un DAG PAUSADO encola los disparos manuales
sin ejecutarlos** — se quedan en `queued` y arrancan de golpe al despausarlo, así que despausa
antes de disparar; (2) **una corrida escribe 22 pares de marcadores `corrida` en `etl_ejecucion`,
no uno** (verificado: 22 `en_curso` + 22 cierres en la corrida de las 10:30), porque cada tarea es
un proceso `run_etl.py` independiente que abre y cierra su propio marcador — los datos son
correctos y el backend ya lee de forma defensiva (`DwhActualizacionService` colapsa con
`argMax`); (3) `airflow-init` necesita el mismo bloque de entorno que los otros dos, de ahí los
anclas YAML `x-airflow-env` / `x-airflow-volumes`.

**BENCHMARK COLUMNAR — SQL RELACIONAL vs. CLICKHOUSE (2026-08-07, sin script, sin tocar código)**:
mide cuánto gana una base columnar en las consultas de agregación que sirven los informes, **a dos
escalas** para enseñar la CURVA y no un número favorable. Documento:
`docs/BENCHMARK_COLUMNAR.md`; guiones: `retailmind/benchmark/` (7 pasos numerados). Los datos se
copian de ClickHouse a una base PostgreSQL **nueva y aislada, `retailmind_benchmark`, que queda
PERMANENTE en el mismo contenedor `retailmind-postgres-1`** (~807 MB, esquemas `dwh` con las 21
tablas del modelo y `web` con `fact_eventos`). **La base `retailmind` no se toca**: un guardia
(`comun.guardia_base`) aborta si el destino de escritura no es `retailmind_benchmark`.
**Resultado, con el matiz incómodo incluido**: a **66.082 filas PostgreSQL GANA** (0,74× y 0,69×
en OTD-VEN-06 y OTD-INV-04), y a **2.823.245 filas ClickHouse gana 16,96×** — la relación mejora
**24×** entre las dos escalas. (Esas 66.082 filas son la escala PEQUEÑA del benchmark, que era el
tamaño del modelo el 2026-08-07 y no lo es ya: hoy son 26.971.498. Rehacer la medición a la escala
de hoy movería el punto pequeño de la curva, no la conclusión.) Espacio: PostgreSQL ocupa **6,8×** más con los mismos datos. Si vas
a tocar esto: (1) PostgreSQL corre con **índices cubrientes hechos a medida** para cada consulta y
`VACUUM ANALYZE` —sin el vacuum el mapa de visibilidad está vacío y NO hay *index only scan*
aunque el índice exista—, mientras que ClickHouse va **a pelo**: la ventaja medida es un SUELO;
(2) la consulta de control **Q4 separa las dos causas** — de los 16,96×, un factor 3,62× es el
modelo columnar y el 4,7× restante es que `COUNT(DISTINCT)` en PostgreSQL se resuelve **ordenando
y volcando a disco**; (3) el arnés tenía DOS sesgos a favor de PostgreSQL que hubo que corregir y
quedan declarados: conexión no reutilizada y **falta de `TCP_NODELAY`** (libpq lo activa,
`http.client` no), que cargaba **43 ms fijos** a cada consulta de ClickHouse por el ACK diferido;
(4) `uniqExact()` y nunca `uniq()` —es HyperLogLog, aproximado, y no sería la misma pregunta—, y
`COLLATE "C"` en todo `ORDER BY` de texto en PostgreSQL, o las filas no se pueden contrastar en
paralelo contra el orden por bytes de ClickHouse. Reproducción **~3 min desde cero**, o **113 s**
solo el paso de medición si la base ya está cargada:

```bash
cd retailmind/benchmark && export PYTHONIOENCODING=utf-8
py -3 00_crear_base.py --recrear && py -3 01_cargar.py    # 1 s + 21 s
py -3 02_verificar_copia.py                               # 27 s — md5 idéntico, fila por fila
py -3 03_indexar.py && py -3 04_medir.py --reps 11         # 8 s + 113 s  <- el paso que se enseña
```

**CIERRE DEL CATÁLOGO TÁCTICO + `fact_eventos` EN SOLO LECTURA (2026-08-07, solo código, sin
script)**: tres cosas de coste bajo en zonas distintas. **(1) A-3 cerrado**: la pantalla
`/gestion-datos` ya NO edita ni borra `fact_eventos`. Se retiraron los tres endpoints
(`GET/PUT/DELETE /api/gestion/fact-eventos/{eventPk}` → **404**), sus tres métodos de
`GestionDatosService`, y en Angular el panel de edición y la columna «Acciones»; en su lugar
hay un aviso que explica por qué. El motivo: **`event_pk` no identifica una fila** —50.000
valores para 2.823.245 filas, 52-139 filas por valor—, así que un clic de «borrar este evento»
se llevaba un centenar de eventos de otras tantas sesiones e informaba de éxito. **NO se tocó
la tabla** (cero UPDATE/DELETE/ALTER) y sigue con sus 2.823.245 filas: la reconstrucción con un
identificador de verdad queda como fragilidad **C-15**, con respaldo y sin prisa. De regalo
desapareció la única concatenación de un NOMBRE DE COLUMNA en SQL del archivo (`updateFactEvento`
la tomaba de las claves del cuerpo de la petición). **(2) OTD-VEN-03**
(`/api/informes/ventas/top-productos`, producto estrella) y **(3) OTD-VEN-04**
(`/productos-hueso`), los dos objetivos que faltaban: **el catálogo compuesto queda en 39 de
39**. Si vas a tocar esto: (1) **VEN-03 NO comparte SQL con OTD-GER-10** aunque agreguen la
misma tabla por el mismo grano — difieren en `ORDER BY`, columnas, KPI y DESTINATARIOS, y
compartir acoplaría dos departamentos: añadir una columna a GER-10 (dirección) se la añadiría
en silencio a VEN-03, que ven VENDEDOR y COMPRAS; (2) **VEN-03 no devuelve margen ni costo y
VEN-04 no devuelve ni un importe**, y eso lo garantiza LA CONSULTA, no la ruta — ClickHouse no
tiene GRANT por columna, así que lo que no debe salir no se selecciona (mismo mecanismo que
COM-08); (3) **VEN-04 no comparte código con el bloque `productoHueso` del tablero T-2** ni le
abre T-2 a COMPRAS: T-2 ordena por capital retenido, no limita y lleva margen, así que darle
entrada habría roto la segregación financiera por la puerta de atrás — COMPRAS entra al informe
y sigue recibiendo **403** en `/api/tableros/rentabilidad`; (4) **`alcance` ∈ {nunca, periodo}
son DOS listas y dos decisiones**: «sin venta nunca» (387) y «sin venta en el período» (491 en
el primer semestre de 2026), y el sobre DICE en pantalla cuál se está viendo — con `nunca` los
filtros de período y canal no se aplican al criterio, porque «nunca vendida en marzo» no es
«nunca vendida»; (5) los días sin venta se anclan a la última salida del ALMACÉN y no a
`now()`, como T-2; (6) **el LEFT JOIN de ClickHouse rellena con el DEFECTO DEL TIPO**, así que
una variante sin salidas saldría con `dias = 0` y, ordenando descendente, se iría al FINAL — el
NULL se fabrica desde un `tiene_venta = 1` explícito; (7) **un endpoint nuevo no basta**: el
`roleGuard` de `/operativo/informes/ventas` y el `nav-model` son la UNIÓN de quien ve al menos
un informe del área, y sin añadir COMPRAS ahí los dos endpoints respondían 200 a una pantalla
que ese rol no podía abrir (lo detectó la prueba en navegador, no la de API). Verificación:
`retailmind/verificar_ven0304.py` (**41 comprobaciones**, cifras tomadas de la RESPUESTA HTTP y
contrastadas contra ClickHouse; matriz 24 celdas × 0 discrepancias) y
`retailmind/verificar_pantallas.js` (**Chrome headless**: las 3 pantallas cargan sin errores de
aplicación). DAG completo tras el cambio: **22/22 tareas y los 49 controles exactos**.
Los dos piden las claves POR ENTORNO y **sin valor por defecto** —a diferencia de
`validar_tableros.py`, que las trae escritas— para no engordar la lista de la deuda C-4; si
falta la variable se plantan y dicen cuál:

```bash
export RETAILMIND_ADMIN_PASS='…'   # la del admin      (ver «Credenciales de desarrollo»)
export RETAILMIND_STAFF_PASS='…'   # la del resto de roles
py -3 retailmind/verificar_ven0304.py        # ~2 min: V2, V3 y la matriz de roles
node retailmind/verificar_pantallas.js       # ~1 min: consola del navegador, necesita
                                             # `npm i --no-save puppeteer` en el frontend
```

**EL USO REAL DE LA APP INVALIDÓ TRES SUPUESTOS DEL ETL (2026-08-17, solo código, sin script,
sin tocar un solo dato de negocio)**: sesión de diagnóstico y reparación. La pantalla de informes
mostraba **fallo parcial** desde el 2026-08-16: `dim_producto`, `fact_orden_compra` y
`fact_compra_linea` NO se publicaban. **No hubo corrupción ni descuadre en PostgreSQL**: el dato
operativo era impecable y lo que estaba mal eran las CONSULTAS del ETL. Las tablas conservaron el
dato de la corrida anterior y los informes siguieron sirviendo cifras coherentes — el patrón de
carga atómica y los 49 controles funcionando como se diseñaron. Bitácora completa en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md`, **Fase 6, correcciones C6.1 a C6.5** (que es una
clase NUEVA de supuesto fallido: no falló el diseño en papel, falló una medición que **era cierta**
y dejó de serlo cuando alguien usó el sistema).

**La causa, que es UNA y se manifestó en tres sitios**: el 2026-08-16 se registró una compra real
—**OC 920**— recibida en **DOS ACTOS** (11 unidades aceptadas + 1 rechazada por «Caja dañada» a las
20:16; la que faltaba a las 20:19). Todo correcto: 1 factura, 1 CxP, y
`orden_compra_detalle.cantidad_recibida = 12 = 11 + 1`. Pero **`recepcion_mercancia` NO tiene UNIQUE
sobre `orden_compra_id`** y el backend permite recibir en varios actos, así que el «máx.
recepciones por OC = 1» que el ETL tenía medido y documentado era una **casualidad del seed**, no una
garantía del motor. Aparte, tres variantes creadas desde la pantalla (2427-2429) nacieron **sin
`peso_kg`**, porque el formulario no capturaba ese campo.

Las huellas que permitieron localizarlo en minutos: **+$2.415,00** de descuadre = el total EXACTO de
la OC 920 contado dos veces, y **+$1.020,00** = el subtotal EXACTO de la línea 2.957 duplicada.

Si vas a tocar esto:
1. **`fact_orden_compra` usa la RECEPCIÓN CANÓNICA** (`_RECEPCION_CANONICA`): la ÚLTIMA por
   `(fecha_recepcion, id)`. La última y no la primera porque las unidades de la fila son el TOTAL
   recibido, y emparejar ese total con la fecha de la primera afirmaría que las 12 estaban en bodega
   cuando solo había 11. Consecuencia declarada para COM-05/COM-06: **la promesa se juzga contra la
   orden COMPLETA**. `estado` NO se filtra (las 134.563 recepciones son 'confirmada'); si aparece una
   'anulada', el filtro entra **en la extracción Y en los controles a la vez** o dejan de hablar del
   mismo conjunto.
2. **El fan-out puede estar DENTRO de un LATERAL y el control de grano no lo ve**: la subconsulta
   sigue devolviendo una fila por orden, así que `count(*)` vs `countDistinct(id)` pasa en verde. Lo
   cazó la igualdad exacta de `lineas` contra `count(*) FROM orden_compra_detalle`. Ahora lo pedido y
   lo recibido salen del detalle de la ORDEN y el rechazo de `recepcion_detalle`, en LATERAL
   separados.
3. **`fact_compra_linea` AGREGA la recepción por línea** (`_RECEPCION_AGREGADA`), no la elige: una
   línea puede recibirse en varios actos y lo recibido es la SUMA. Tres detalles no obvios:
   `lineas_recepcion` (un `count`) sustituye a `rd.id IS NOT NULL` porque **un `SUM` sobre cero filas
   es NULL y no distingue «recibí 0» de «no hubo recepción»**; el MOTIVO no se puede sumar y se toma
   el de la recepción que rechazó (y entre varias, la última por `id`); y **«completa» es propiedad
   de la LÍNEA** medida sobre el total —por fila de recepción, la 2.957 no era completa en NINGUNA de
   sus dos, cuando se sirvió entera—.
4. **SEIS consultas DE LOS PROPIOS CONTROLES llevaban el supuesto dentro** (3 en las tareas, 3 en
   `validar_dwh.py`): con un `JOIN recepcion_mercancia` a secas, el control **acusaba a la tabla de un
   descuadre que estaba en el control**. Se movieron al grano correcto. **Eso NO es relajar un
   control** —siguen exigiendo igualdad exacta al centavo y siguen detectando fan-out—: se corrigió
   su GRANO, no su umbral. Distingue siempre las dos cosas.
5. **`dim_producto.peso_kg` es ahora `Nullable(Decimal(10,3))`**. `InvalidOperation:
   [ConversionSyntax]` en una carga = **NULL contra una columna Decimal no-Nullable**:
   `clickhouse_connect` hace `int(Decimal(str(x)) * mult)` y con `x = None` eso es `Decimal('None')`.
   El mensaje **no nombra columna, ni fila, ni tabla** — se localiza reproduciéndolo en aislamiento.
   Se arregla con `Nullable`, **nunca** con `COALESCE(...,0)` (C3B.5: cero y «no hay dato» no son lo
   mismo). **Y una columna más allá estaba la misma trampa, hoy CERRADA en sus dos mitades**
   (2026-08-17): `margen_catalogo_pct` sale de `NULLIF(precio, 0)` y rompía igual con `precio = 0`.
   Como el CHECK del motor es `precio >= 0` —**el cero es LEGAL para PostgreSQL**— la guarda va en la
   aplicación: `CatalogoAdminService.precioValidado()` exige precio **estrictamente positivo y no
   nulo** al crear Y al editar (la CAUSA); y la columna es ahora
   **`Nullable(Decimal(6,2))`** (el SÍNTOMA), para que el vector que no pasa por la app —un script de
   siembra— publique un margen NULL en vez de abortar la carga. Probado con el caso real: sembrando
   `precio = 0` directo en PostgreSQL, `dim_producto` publica con `margen = NULL` donde antes moría.
   **Las DOS columnas Nullable de `dim_producto` son la misma lección: una columna no-Nullable en el
   almacén es una apuesta sobre datos que la aplicación no garantiza.** Ficha **C-19** en
   `DEUDA_TECNICA.md`, ya en la sección de resueltas.
6. **Una regla de escape hay que reimplementarla COMPLETA en el control.** El de motivos traducía el
   sinónimo pero no el escape a `'Otro'`, y con «Caja dañada» (motivo nuevo, el campo es TEXTO LIBRE)
   PostgreSQL decía «Caja dañada» donde el almacén dice «Otro». Además cuadraba **por coincidencia**:
   7 crudos − 1 sinónimo = 6, y 5 canónicos + 'Otro' = 6; con dos motivos nuevos habría fallado.
   «Caja dañada» **se deja en `'Otro'` a propósito**: fusionar dos frases humanas es criterio de
   Compras, no del ETL (C3.3).

Verificación: DAG completo **22/22 tareas `success` en 12 min 02 s**, 0 abortadas, **49/49 controles
exactos** (confirmado en la bitácora del DAG y re-ejecutando `validar_dwh` aparte); kardex con **0
enlaces rotos** y 0 cadenas mal arrancadas leyendo por `(fecha_creacion, id)`, cierre vs
`inventario` **11.407/11.407 posiciones, 0 descuadradas**; 3 pantallas de informes en Chrome
headless sin aviso de fallo y consola limpia; defensas intactas (**34** `trg_horario_*`, **95**
políticas RLS, **3** `trg_kardex_*`). Conteos operativos **idénticos antes y después**.

**`peso_kg` YA SE CAPTURA EN EL ALTA DE VARIANTE (2026-08-17, `CatalogoAdminService`)**: la causa
raíz del punto 5 no era del ETL sino de la aplicación — **`crearVariante` y `editarVariante` no
tenían el campo**, ni `VarianteReq`, así que TODA variante creada desde la pantalla nacía sin peso y
no había forma de arreglarla. El daño de verdad no era el ETL: **`VentasService.pesoTotalPedido` es
TODO-O-NADA por diseño** (un total parcial distorsionaría el costo por kg en silencio), así que UNA
línea sin peso deja el peso del pedido ENTERO en NULL y el flete se cobra **solo con `costo_base`,
sin el cargo por kilo**. Una variante mal dada de alta subfactura el envío de todos los pedidos que
la incluyan, sin un error en ningún log.

La regla es **asimétrica a propósito**: en el **ALTA** el peso es **OBLIGATORIO** (400 con el motivo,
vía `IllegalArgumentException`) y en la **EDICIÓN** es **OPCIONAL y omitirlo CONSERVA** el que
hubiera (`COALESCE(?, peso_kg)`) — porque ese método ya era una actualización parcial y porque así
la pantalla puede RELLENAR las variantes que hoy lo tienen vacío **sin script de migración**. Lo que
se rechaza siempre es `0` o negativo: para «no lo sé» ya está el NULL que la columna admite, y un `0`
reintroduciría el fallo por la puerta de atrás porque `pesoTotalPedido` filtra por **`peso_kg <= 0`**,
no por `IS NULL`. La exigencia es de la APLICACIÓN, no del motor: la columna sigue NULLABLE y tiene
que seguirlo (1.221 variantes históricas se poblaron con el script 54). UI: campo *Peso* en
`variante-dialog`, columna **PESO** en la grilla que marca `sin peso` en rojo, y aviso dentro del
diálogo solo en las variantes que YA venían vacías (`pesoOriginal` se captura al abrir, o el aviso
desaparecería al teclear el primer dígito); precarga a `null` y no a `0`, para que el campo salga
vacío en vez de con un peso afirmado. Probado end-to-end contra el sistema corriendo: alta sin peso /
con `0` / con `-1,5` → **400**; con `1.234` → **201**; editar omitiendo → peso **conservado** mientras
`precio` sí cambia; editar con `0` → 400 sin modificar nada; y en navegador **«Aceptar» deshabilitado
con el peso vacío**. **Quedan 3 variantes sin peso a propósito** (2427, 2428, 2429): no se inventaron
los valores —meter un número plausible cobraría el flete mal *y* sin aviso— y ahora se corrigen desde
la pantalla. OJO: el javadoc de `pesoTotalPedido` **está desactualizado** (afirma que `peso_kg` está
NULL en las 1.221 variantes, falso desde el script 54) — ficha **C-20** en `DEUDA_TECNICA.md`.

**LA CARTERA ABRÍA CON LOS TRES INDICADORES SIN CALCULAR (2026-08-17, solo código, sin script)**:
OTD-VEN-01 (`/api/informes/ventas/cartera-pedidos`) mostraba **«No calculado»** en los tres KPI
—«Pedidos en el filtro», «Monto total» y «Monto aún en proceso»— al abrir la pantalla. **No era un
fallo, era el diseño mordiendo a la escala nueva**: por encima del tope de conteo los importes no se
calculan a propósito, y con el filtro vacío el conjunto son los **2.999.995** pedidos. La cifra que
justifica ese tope **se re-midió y sigue viva**: sumar bajo RLS cuesta **4,58 s** (4.577 / 4.565 /
4.598 ms; como superusuario son 190 ms, o sea el coste ES la RLS).

**El arreglo es un filtro por defecto, no calcular más**: estado sintético **`en_curso`** = la
negación de los cuatro terminales, que es lo que significa «cartera» — de los 3 M de pedidos
**2.641.189 están entregados** y son historia. En curso son **75.139**, muy por debajo del tope, así
que los tres indicadores salen EXACTOS. Mismo patrón que el `pendientes` de SOP-01: el defecto lo
declara `valorInicial` en la definición del frontend y el servicio solo lo acepta y lo traduce, para
que un `GET` sin `estado` siga significando «sin filtro» y no mienta. La opción «Todos los estados»
sigue ahí y ahora **se llama «Todos los estados (sin importes)»**, que es la advertencia en el sitio
donde se toma la decisión.

Si vas a tocar esto: (1) **la traducción a SQL NO es la forma obvia**. `ep.codigo NOT IN <terminales>`
es correcto y tarda **4,6 s**, porque va contra la tabla UNIDA y el planificador no puede empujarlo a
`idx_pedido_estado`: Parallel Seq Scan de los 3 M. Filtrando por la columna indexada del propio
pedido con **`p.estado_pedido_id = ANY(?::bigint[])`** el plan pasa a Bitmap Index Scan y baja a
**~200 ms, unas 20×** (la pantalla completa: **10,5 s → 0,48 s**). La subconsulta
`estado_pedido_id IN (SELECT id … WHERE codigo NOT IN …)` **no sirve**: se resuelve como semi-join y
vuelve a los 4,58 s. (2) Va con **un solo parámetro LIGADO** (el array como texto `{1,2,3}`) y no con
un `IN (?,?,?)` cuyos placeholders habría que construir — así no se arma SQL por concatenación
(regla 2) y el cast explícito cumple la 8. (3) **Los ids se RESUELVEN desde los códigos en cada
consulta, jamás se escriben**: durante el desarrollo se probó con `(2,3,4,5,6,7)` deducida del
`orden` de la tabla y el **id 6 es `entregado`** (2.641.189 pedidos), así que el «filtro de cartera»
devolvía la tabla entera y volvía a los 4,2 s **sin dar error**. Los ids reales no siguen el orden
del proceso: `facturado` es 9 y `preparado` 10, posteriores a `entregado`. (4) La lista de terminales
vive en **UNA** constante (`TERMINALES_SQL`) que usan el filtro Y el KPI «Monto aún en proceso»: si
se separan, la pantalla enseña un total de cartera que no cuadra con el importe de al lado. (5) Con
`en_curso` puesto, «Monto total» y «Monto aún en proceso» dan **lo mismo a propósito** —todos los
pedidos del conjunto están en proceso—, no es un bug.
Verificado: **0,48-0,76 s** con el defecto y con `canal=web`, cifras contrastadas al centavo contra
PostgreSQL (**75.139 / $13.486.538,26** y **40.921 / $7.481.996,72**, Δ = 0); en navegador la
pantalla abre con los tres calculados y al elegir «Todos los estados» vuelven a «No calculado» **con
la explicación visible**, que es el comportamiento correcto; los 10 endpoints de Ventas siguen en 200
y un `estado=inventado` sigue dando **400** por lista blanca.

**META DE VENTAS DEL MES VIGENTE (2026-08-17, dos filas de datos, sin script)**: OTD-VEN-15 daba
**409** («No hay meta de ventas vigente para 8/2026») porque `meta_venta` llegaba solo hasta
2026-07. Se fijó la meta de agosto de 2026 en **$3.650.000** para `ventas` y `general` —base: el
facturado de agosto de 2025, $3.645.915,16, redondeado— **por `POST /api/gerencia/metas`** y no con
un INSERT, para que `fijada_por` saliera del JWT y pasara por las validaciones del servicio (ids 212
y 213; `meta_venta` 133 → 135). El informe responde 200 con avance **92,0 %** (real $3.357.913,35,
falta $292.086,65), verificado en navegador con la barra pintada. **Dos avisos que quedaron como
deuda**: las **133 metas históricas siguen a la escala PREVIA** a la carga masiva y dan avances del
**874 % al 1.756 %** (media 1.469,7 %; 19 de 19 por encima del 500 %) — ficha **C-21**, se decidió
NO reescribirlas porque una meta es una decisión con fecha; y la columna «días corridos» del informe
**engaña** (17/31 junto a un 92 % que ya es de mes cerrado, porque la carga escribió agosto entero
hasta el día 31) — ficha **C-22**. Si vas a fijar más metas: el `venta_real` del informe es el
facturado TOTAL del mes y **no distingue departamento**, así que dos departamentos con metas
distintas son solo dos denominadores sobre el mismo numerador.


**PLAN DE PRUEBAS Y SUS SEIS DEFECTOS GRAVES (2026-08-18/19, script 111 + código)**: el sistema
tenía **cero pruebas automatizadas** —`src/test/` vacío, 0 `.spec.ts`— y todo lo verificado hasta
entonces eran guiones de una sesión que comprobaban CIFRAS, no COMPORTAMIENTO. El plan vive en
`docs/PLAN_DE_PRUEBAS.md` (13 suites) y el arnés ejecutable en `pruebas/` (ver su README); los
defectos, en `docs/pruebas/DEFECTOS.md`. **El eje que lo estructura son los CUATRO ESTADOS DE
DATOS** —E0 vacía · E1 mínima · E2 sembrada · E3 masiva—, porque el sistema falla de formas
distintas y excluyentes según cuántas filas tenga: E0 destapa división por cero, 404 donde debería
haber lista vacía y controles que cuadran comparando 0 con 0; E3 destapa conteos bajo RLS y filtros
que no llegan al índice. **Las 13 suites implementadas y los CUATRO estados ejecutados.**

**E2 se monta desde `deploy/postgres/initdb/01_retailmind.dump`** (2026-08-03 20:25) con
`pruebas/estados/montar_e2.sh`: la contenerización fue el 3 de agosto y la carga masiva el 10/11,
así que ese volcado es una FOTO REAL del seed y no hace falta revertir fases. Dos avisos: (1) el
volcado trae el ESQUEMA de esa fecha y el código ha avanzado — el script **87** creó
`rol_personalizado` el día 6 y la consulta de login la une, así que sin aplicarlo **nadie entra**,
con un `bad SQL grammar` que no parece un problema de esquema; el montador aplica el DDL posterior
(86, 87, 88, 91, 106, 110, 111) y deja fuera los de DATOS (92-105), que convertirían E2 en E3; (2)
**E2 es el único estado con ORÁCULO** —sus cifras están publicadas aquí— y por eso destapó que la
del kardex estaba mal (ver la tercera advertencia de la tabla de la carga masiva).

**LOS DOCE DEFECTOS ESTÁN CERRADOS Y VERIFICADOS.** Los seis S2 y seis S3. Dos de los S3
merecen mención porque su corrección es una regla reutilizable: **D-06** cambió el 409 de
«no hay meta» por un 200 con salvedad y los KPI en **null y no en cero** —un 0 % se lee como
«vendimos nada» cuando lo cierto es «no hay contra qué medir»—, porque **un 409 es un guardia
de ACCIÓN y aquí el usuario está MIRANDO**; y **D-07** cerró el hueco de que nada comprobaba
que el almacén correspondiera a la base operativa: el ETL sella ahora el `system_identifier`
del clúster de origen en `etl_ejecucion` (único por `initdb`; el nombre de la base NO basta,
porque dos clústeres tienen su propia `retailmind` y ése es el escenario peligroso) y
`InformeCompuestoServiceBase` lo contrasta contra su propia conexión, publicando
`origenCoherente: false` con una salvedad que nombra las dos bases. Avisa y no falla —un
almacén ajeno es una condición de despliegue, no un error—, y calla ante lo desconocido, para
que la advertencia siga significando algo el día que salte de verdad.

Los **seis defectos S2**, todos corregidos y verificados:
1. **Una familia entera de errores de cliente salía como 500.** `GlobalExceptionHandler` no extiende
   `ResponseEntityExceptionHandler`, así que toda excepción de Spring Web no declarada caía en el
   manejador genérico. Faltaban SEIS: parámetro obligatorio ausente, cuerpo ilegible, parte
   multipart ausente, Content-Type no soportado, `@Valid` fallido y archivo demasiado grande.
   `/api/gerencia/metas/vigente` daba **500 a sus cuatro roles**.
2. **El login no dejaba rastro de un fallo interno**: un solo `catch (Exception)` devolvía
   «Credenciales incorrectas» sin registrar nada, así que una instalación mal configurada era
   indistinguible de una contraseña mal tecleada. Ahora el rechazo esperado y el fallo inesperado
   van por `catch` separados; **la respuesta al cliente no cambia**.
3. **La restricción horaria se había vuelto a estrechar** (C-11 reincidiendo): `grp_analista` los
   LUNES 07:00-17:30 y `grp_bodega` los domingos hasta `23:59` — la trampa del intervalo
   semiabierto. **Invisible hasta que llega el día**, y `fuera_horario` bloquea el LOGIN entero.
   Restaurado con el script 90.
4. **`docker compose down -v` estaba ofrecido como comando de rutina en el README**, con un
   «(CUIDADO)» entre paréntesis. Un paréntesis no es una barrera.
5. **D-11 · el predicado de RLS se evaluaba una vez POR FILA** — ver el bloque siguiente.
6. **D-09 · la red logística no se podía dar de alta** — ver el bloque siguiente.

**D-11 — EL PREDICADO DE RLS SE EVALUABA POR FILA (script 111)**: la misma consulta, el mismo
índice y los mismos datos costaban **5 ms como superusuario y 4.056 ms bajo rol** — 810×, con
**2.936.358 buffers frente a 107**. La causa NO era la RLS ni un índice ausente: era que
`esta_en_horario(fn_grupo_actual())` —que no depende de la fila: es función del ROL y del RELOJ— se
evaluaba una vez por cada fila examinada, y cada llamada lee `grupo_horario`. El script **111**
envuelve esas llamadas en un subselect escalar (`(SELECT esta_en_horario(...))`), lo que las
convierte en un **InitPlan** evaluado UNA vez por consulta. **La seguridad no cambia: el predicado
es el mismo, solo cambia cuántas veces se calcula.** Se reescribieron las 95 políticas con tres
guardias (número de políticas, conservación de rol/comando/predicado, y que la compuerta siga
discriminando). Resultado: la consulta patrón **4.056 → 180 ms**, y los ocho informes lentos de E3
bajo el umbral **sin tocar una sola consulta de informe** (costo-envío 17,1 → 2,07 s; avance-meta
8,4 → 0,32 s; kardex 49,7 → 0,10 s, éste además con `paginarConTope`). Si vas a tocar esto: (1)
**no faltaba un índice** —`idx_factura_venta_fecha_cubriente` existe y es el correcto—; (2) **no es
la RLS en sí**: reproducido en una tabla sintética, con `USING (true)` el plan usa el índice y lee
241 buffers, con la llamada a función 7.556; (3) **`LEAKPROOF` NO lo arregla** — se probó marcando
las dos funciones y el plan no se movió ni un buffer; se revirtieron a `NOT LEAKPROOF`, porque no
se deja tocado un atributo de seguridad que no aporta nada.

**D-09 — LA RED LOGÍSTICA NO SE PODÍA DAR DE ALTA (pantalla `/operativo/red`, solo ADMIN)**:
`bodega`, `transportista`, `metodo_envio`, `zona_envio` y `tarifa_envio` **no tenían ni un solo
`INSERT INTO` en todo `src/main`** — únicamente lecturas. Sostienen el ciclo de venta entero (sin
bodega no se puede crear un pedido; sin zona ni tarifa el checkout no asigna transportista), así
que una instalación nueva no podía tomar un pedido hasta que alguien ejecutara SQL, y una en marcha
no podía abrir una segunda bodega ni contratar un transportista sin un DBA — mientras la pantalla
de transferencias entre bodegas funcionaba con un operando que no se podía crear. **No hizo falta
ni un GRANT ni un script**: el motor ya los concedía a `grp_administrador`; el hueco era solo de
aplicación. `SecurityConfig` tampoco se tocó (`/api/admin/**` ya reserva la rama). Paquete
`admin/red/` + una pantalla de cinco pestañas —son tablas de CONFIGURACIÓN que solo tienen sentido
juntas— enganchada en los cuatro puntos de la regla 6. **Baja lógica, nunca borrado**: lo
referencian pedidos, envíos y kardex históricos. Validaciones que el motor no impone: costos no
negativos, peso máximo mayor que el mínimo, plazo coherente, y **una zona de ciudad debe declarar
su provincia** o nunca llega a aplicarse. Verificado en navegador (14 comprobaciones, incluido que
GERENTE no ve el enlace y el guard lo rechaza) y, sobre todo, con
`pruebas/p05_puesta_en_marcha.py` (**13/13**): desde una base con CERO bodegas se puso el sistema
en marcha **sin una línea de SQL**, hasta crear un pedido real.

**LA TIENDA DEL CLIENTE, REHECHA (2026-08-20, SOLO FRONTEND — cero cambios de
backend, de SQL y de seguridad)**: las cinco pantallas de la tienda (`/shop`,
ficha, carrito, checkout y `/wishlist`) más `/recomendaciones` pasan a una
interfaz de comercio: **panel de filtros a la izquierda**, franja de
departamentos, y en la **barra superior** la **dirección de envío** junto al
logotipo, un **buscador compacto a la derecha** y los accesos a
Pedidos/Devoluciones y Ayuda/Soporte; tarjetas con cinta de stock y corazón,
vista en cuadrícula o lista, esqueletos de carga y estados vacíos explícitos. Piezas nuevas: `features/shop/shop-shared.scss` (tokens y tarjeta
comunes a las seis pantallas), `catalogo-visual.ts` (icono y color por
departamento) y `shop-ui.service.ts` (contadores de carrito y wishlist, que
viven en la barra y los mueve el catálogo). El arnés es
**`pruebas/p14_tienda.js`** (59 casos, Chrome headless): cada filtro se
contrasta contra `/api/catalogo/productos` con los mismos parámetros, no contra
sí mismo.

**LO QUE SE SURTIÓ Y NO SE VEÍA**: el rango de **precio** ya existía en el
endpoint (`min_price` / `max_price`) y ninguna pantalla lo ofrecía; ahora está
con seis tramos y con rango escrito a mano. También asoman el SKU, el stock por
producto, el enlace a reseñas con `?productoId=`, el de preguntas, el de FAQ y
el de abrir un ticket. Los **banners y campañas de marketing NO se pintan a
propósito**: `SecurityConfig` reserva `GET /api/marketing/**` a ADMIN/GERENTE,
así que enseñarlos al cliente exigiría tocar el backend — queda declarado, no
intentado.

Si vas a tocar esto:
1. **TODO EL FILTRO VIVE EN LA URL** (`q`, `cat`, `marca`, `min`, `max`, `page`,
   `size`) y el catálogo reacciona a `queryParamMap`. No es cosmético: el campo
   de búsqueda está en `app.component` —fuera del catálogo— y se comunican por
   ahí; además el botón «atrás» deshace un filtro y un resultado se puede
   compartir por enlace. Un filtro nuevo se escribe en la URL, nunca en un
   campo del componente.
2. **El ORDEN y «solo con stock» son de PÁGINA, no de catálogo**, porque el
   endpoint no los ofrece — y la pantalla lo DICE junto a cada control. Si
   algún día el backend ordena, hay que mover el control a la URL y quitar el
   aviso: dejar el aviso puesto sería tan falso como no haberlo puesto nunca.
3. **La identidad visual del departamento va por NOMBRE y no por id.** Los ids
   no son una serie: conviven 1-12 con 60001-60006 (Fase 0 de la carga masiva),
   y el mapa por id que había dejaba SEIS departamentos —900 productos el mayor—
   con el icono genérico de caja. Carrito y wishlist devuelven solo
   `categoriaId`, así que `ShopUiService` resuelve el nombre pidiendo
   `/api/catalogo/categorias` UNA vez; lo desconocido cae en un hash
   determinista y tiene color propio, nunca gris.
4. **El globo de la barra cuenta LÍNEAS.** Sumarle uno tras agregar es un error:
   agregar algo que YA estaba sube la cantidad y no crea línea, así que el
   contador se releía mal hasta la siguiente recarga. Se relee del servidor.
5. **`flex: 1` aplasta un botón dentro de un contenedor en COLUMNA**: el atajo
   pone `flex-basis: 0%` y la base sustituye a la altura (pasó en la caja de
   compra de la ficha y en los estados vacíos). En `shop-shared.scss` el botón
   principal va con `flex: 1 1 auto`.
6. **Nada de `[style.--variable]`**: el enlace de propiedades CSS
   personalizadas de Angular no es de fiar entre versiones; el color de cada
   departamento se aplica con `[style.color]` y `[style.background]` normales.
7. **El filtro de disponibilidad hay que probarlo donde HAYA agotados.** En este
   catálogo solo 3 de 6.214 variantes están a cero, y sobre la primera página
   la prueba pasaría en verde sin haber escondido nada; `p14_tienda.js` usa una
   página elegida (`?marca=Ikea&size=48`) y EXIGE que hubiera al menos uno.
8. **La dirección de la barra es una PREFERENCIA, no la predeterminada del
   perfil.** Vive en `ShopUiService` y se guarda en `localStorage`
   (`rm_dir_envio`) para que sobreviva a un F5 —volver del refresco con otra
   dirección puesta es justo lo que este bloque venía a evitar—, y se **valida
   contra la lista** en cada carga: si la dirección se dio de baja, o si en ese
   navegador entra otro cliente, la elección se descarta sola y manda otra vez
   `esPredeterminada`. Marcar una predeterminada de verdad es del perfil y
   tiene su pantalla; esto no la toca. El checkout la lee para preseleccionar,
   y al dar de alta una dirección desde el pago avisa a la barra con
   `cargarDirecciones(true)`, o la barra sigue anunciando la vieja.
9. **La barra mete cinco bloques en una fila y el buscador CRECE para llenar
   el hueco**, así que se rompe por desbordamiento y no por error: las piezas
   se montan unas sobre otras y la pantalla «sigue funcionando». Dos reglas:
   en modo tienda el separador **deja de crecer** (`.navbar-tienda
   .navbar-spacer`), o el hueco libre se lo repartían separador y buscador y
   quedaba un vacío a la izquierda del campo; y ocultar el rótulo pequeño de
   un acceso de dos líneas **NO estrecha nada** —es una columna, manda el
   grande—, lo que de verdad recorta son el texto de la dirección, la insignia
   del rol y el nombre del usuario. `p14_tienda.js` mide 10 anchuras × 3
   pantallas y exige `scrollWidth <= clientWidth`.
10. **Por debajo de 1080 px el campo de la barra desaparece y el del catálogo
   toma el relevo en el MISMO corte** — si no coincidieran quedaría una franja
   sin ningún buscador. Y como el del catálogo solo existe EN el catálogo, la
   barra conserva el **atajo de la lupa** (`.acc-buscar`), que lleva a `/shop`
   con `buscar=1` y deja el cursor en el campo; el catálogo borra el parámetro
   con `replaceUrl`. Lo destapó la propia prueba: medía solo en `/shop` y daba
   verde mientras el carrito y la lista de deseos se quedaban sin buscador.
11. **La `panelClass` de `<mat-menu>` NO llega al panel en esta versión** —
   medido en el DOM vivo: ni al `.mat-mdc-menu-panel` ni al
   `.cdk-overlay-pane`—, así que los menús de la barra se ven redondeados por
   la regla general `::ng-deep .mat-mdc-menu-panel`, no por su clase. El ancho
   del menú de direcciones se fija seleccionando por CONTENIDO
   (`:has(.envio-menu-titulo)`); la clase sigue en la plantilla por si vuelve a
   aplicarse.
12. **P14 escribe en el carrito, en la lista de deseos y en la dirección
   elegida del cliente demo, y los deja como estaban** (agrega una línea y la
   elimina desde la pantalla; el corazón se pulsa dos veces; la dirección se
   devuelve a la de partida). Si añades un caso que escriba, deshazlo.

Verificado: **86/86** casos de `p14_tienda.js`, **65/65** de `p11_interfaz.js`
(las 32 pantallas del back-office siguen intactas: la barra nueva solo se pinta
con rol CLIENTE), `ng build` sin errores y consola del navegador limpia en todo
el recorrido.

**EL PERFIL DEL CLIENTE NO DEJABA GUARDAR — D-13 y D-14 (2026-08-20, código,
sin script)**: dos defectos que solo se ven usando la pantalla. **D-13**: el
desplegable de género ofrecía `F`/`M`/`O` y `cliente_genero_check` solo admite
`masculino`, `femenino`, `otro`, `no_indica`, así que guardar daba **400** con
el mensaje genérico de restricción — y al leer, un género ya registrado no
casaba con ninguna opción y el campo salía **en blanco** (los 50.070 clientes
del seed tienen género y ninguno se veía). **D-14, S1 por silencioso**: el
formulario no ofrecía la fecha de nacimiento **ni la enviaba**, y el UPDATE la
escribía igual, así que **cada «Guardar cambios» la ponía a NULL** —la tienen
50.070 de 50.072 clientes—.

Corregido en las dos capas: la pantalla usa los valores canónicos y edita la
fecha; y `PerfilService.actualizarDatos` valida por **lista blanca** con
mensajes que nombran campo y valores (regla 2 del proyecto, que ese servicio no
aplicaba) y trata **un campo ausente como «no tocar»** —solo borra si llega
presente y vacío, con `body.containsKey(...)` decidiendo y un
`CASE WHEN ?::boolean THEN … ELSE <columna> END` en el UPDATE—. Esa última
parte es la que impide que el daño vuelva: sin ella, basta con que cualquier
pantalla futura se olvide de un campo para que se borre solo. Fichas completas
en `docs/pruebas/DEFECTOS.md`; verificación en `pruebas/p14_tienda.js` §15
(guardar, releer, volver a guardar sin perder la fecha, y alta/edición/baja de
una dirección desde la pantalla).

**EL PEDIDO RECIÉN HECHO CAÍA EN LA ÚLTIMA PÁGINA — D-15 (2026-08-21)**: el
listado de pedidos ordenaba `ORDER BY p.id DESC`. **En esta base el id NO es
cronológico**: la carga masiva usó bandas reservadas hasta `2.100.055.830` y la
secuencia real va por `4.343`, así que un pedido creado hoy es el id **más
bajo** de la tabla y aterrizaba detrás de tres millones. Corregido a
`ORDER BY p.fecha_pedido DESC, p.id DESC` (el id queda de desempate: hay
pedidos que comparten fecha al microsegundo; `idx_pedido_fecha` ya existía y la
latencia va de 8-11 ms a 12-19 ms).

**Y el orden por fecha no basta, que es lo interesante**: el catálogo de
demostración llega hasta **2034**, así que «el más reciente» es de verdad uno
de 2034 y una compra de hoy queda en medio (medido: el pedido real de
`maria.lopez` tiene **51 con fecha posterior**). Por eso «Mis Pedidos» se
rehízo: tarjetas en vez de tabla, **buscador por número y filtro por estado
resueltos EN SERVIDOR** —el parámetro `q` del endpoint existía y ninguna
pantalla lo usaba—, los dos reflejados en la URL, y la confirmación del
checkout enlaza a `…/mis-pedidos?q=<número>` para abrir la lista ya filtrada
por la compra que se acaba de hacer. Ficha en `docs/pruebas/DEFECTOS.md`;
verificación en `pruebas/p14_tienda.js` §16, que comprueba el orden sobre la
fecha que llevan los propios números (`PED-AAAAMMDD-…`) y busca un pedido
tomado a propósito de la página 4.

**CINCO MEJORAS PEDIDAS SOBRE EL USO REAL (2026-08-25, código + script 113)**: ninguna
nace de una prueba sino de andar por el sistema, y tres de las cinco resultaron
ser defectos y no mejoras — más un cuarto, ajeno a lo pedido, que destapó la
propia suite al comprobar la regresión del PDF (punto 6). Suites nuevas: `pruebas/p17_mejoras.py` (**31/31**) y
`pruebas/p17_mejoras.js` (**36/36**).

**1 · El alta pública no decía que la cuenta ya estaba creada.** Nace al terminar
el PASO 2 —eso ya estaba así y es deliberado, para que «omitir» signifique algo—,
pero se pasaba de «Crear mi cuenta» a un formulario de dirección sin una sola
señal de que lo suyo ya estaba hecho: quien cerrara el navegador en el paso 3 se
iba creyendo que no se había registrado. Ahora los pasos 3 y 4 llevan un aviso
(`.reg-creada`, `role="status"`) que nombra el correo con el que se inició sesión,
dice que lo que queda es opcional y ofrece irse a la tienda ya.

**2 · TOPE DE CARACTERES en los 179 campos escribibles.** La fase del 2026-08-21
acotó QUÉ admite cada campo y no CUÁNTO. Ahora **todo perfil de
`perfiles-texto.ts` declara `largo`** —el campo pasó de opcional a obligatorio en
la interfaz, así que un perfil nuevo sin tope no compila— y `CampoTextoDirective`
aplica **el MÍNIMO** entre el del perfil y el `maxlength` de la plantilla: el
perfil es el techo de la CLASE de dato y la plantilla el de la COLUMNA, que a
veces es menor (`nombre` admite 150 y `cliente.nombre` es varchar(100)). Se toma
el mínimo y no «el de la plantilla si existe» para que un `maxlength` escrito de
más no afloje el perfil en silencio.

**El caso que lo destapó es el número de tarjeta, y era un DEFECTO con causa
concreta**: su formateo colgaba de un `(input)` de la plantilla, y **un manejador
de plantilla corre ANTES de que `ngModel` escriba en el modelo**. O sea que
`formatearNumero()` leía el valor de la tecla ANTERIOR, recortaba ése a 16
dígitos, y acto seguido `ngModel` machacaba el resultado con lo recién tecleado:
el tope no llegaba a aplicarse nunca y se podían escribir tantos dígitos como
cupieran. Los dos métodos se retiraron del componente y son ahora los perfiles
**`tarjeta`** (agrupa de cuatro en cuatro, 19 caracteres) y **`vencimiento`**
(MM/AA), que trabajan sobre `el.value` —el DOM, no el modelo— y reemiten `input`
con el valor ya limpio. **La directiva no tiene el problema porque no lee el
modelo.**

Los NÚMEROS ganaron un tope de dígitos, que no tenían ninguno:
`DIGITOS_ENTEROS_POR_DEFECTO = 9`, y no es un número redondo elegido a ojo — es
el mayor valor que cabe a la vez en un `integer` de PostgreSQL (999.999.999 <
2.147.483.647) y en un `numeric(12,2)`, que son los dos tipos detrás de todos los
campos numéricos de la aplicación (52 columnas de dinero son `numeric(12,2)`).
Cuando el campo declara un `max`, el tope se deduce de ÉL. Tres detalles:
(1) el aviso de tope **NO invalida** el control —un campo lleno hasta su máximo
es correcto, e invalidarlo bloquearía el «Aceptar» del formulario—, así que
`validate()` y `motivoActual()` dejaron de ser el mismo cálculo; (2) con el campo
lleno el navegador se limita a IGNORAR la tecla, sin decir nada, así que un
`keydown` detecta ese momento y enciende el aviso; (3) el perfil `telefono` bajó
de 30 a 20, que es lo que miden las SIETE columnas `telefono` del esquema.

**3 · El PDF de la factura: columna «Código» vacía, cupón invisible y aspecto de
borrador.** La columna salía vacía **en todas las líneas** porque
`FacturaVentaPdfService` pasaba `null` como código: `factura_venta_detalle`
congela la descripción pero no guarda el SKU. Ahora sale de la VARIANTE con un
LEFT JOIN —una variante dada de baja no puede dejar una factura sin emitir— y no
hizo falta ningún GRANT: `grp_cliente` ya lee `producto_variante`, que es como la
tienda pinta el catálogo. De paso se retira el SKU que la descripción ya traía
entre paréntesis, que ahora salía DOS VECES en la misma fila. El **cupón** pasa a
la letra pequeña de la fila «Descuento» (`Totales.detalleDescuento`), que es donde
se busca un importe restado; si el descuento supera lo que aportó el cupón, lo
dice («+ promociones») en vez de dejar la diferencia sin explicar.
`DocumentoPdfService` se rehízo entero —lo comparten las TRES plantillas: factura
de venta, factura de compra y guía de retorno del RMA—: **logotipo** desde el
classpath (`/pdf/logo-retailmind.png`, cacheado, y su ausencia no rompe el
documento), membrete, caja oscura con la etiqueta de estado en color, rótulos de
sección, tabla con cabecera repetida por página y filas cebra, recuento de
artículos, bloque de totales con el TOTAL destacado y **pie con «Página X de Y»**.
Tres trampas de iText 5 anotadas en la clase: el pie NO puede ir en el flujo (va
en un `PdfPageEventHelper`), «de Y» exige un `PdfTemplate` estampado al cerrar
—en la página 1 aún no se sabe cuántas habrá—, y el evento se registra ANTES de
`open()` o la primera página sale sin pie.

**4 · El catálogo no decía de qué proveedor es cada producto.** El dato existía y
no se enseñaba en ninguna pantalla. Ojo con el grano: `producto_proveedor` es
(proveedor, **VARIANTE**), así que un producto puede tener varios —6.043 de los
6.217 tienen al menos uno—. La grilla agrega los distintos y enseña el primero
con «+N»; el detalle de variantes muestra el **preferido** de cada SKU (o el más
barato) y cuántos la surten, para no dar a entender que es el único.

**5 · Inventario no tenía dónde ver QUÉ hay y CUÁNTO hay.** Tenía Transferencias,
Ajustes y Kardex —los tres MOVIMIENTOS— y ninguna pantalla contestaba «cuánto
tengo»: el stock solo asomaba dentro del formulario de ajuste, y solo de la
variante que se estuviera ajustando. Pantalla nueva
**`/operativo/inventario/existencias`** (ADMIN/GERENTE/BODEGA/ANALISTA, los
mismos que el kardex) sobre `GET /api/inventario/existencias`, con búsqueda,
filtro por bodega, cinco situaciones, cuatro ordenaciones y paginación **todo en
el SERVIDOR**; cuatro indicadores medidos sobre el conjunto FILTRADO entero (no
sobre la página) y reparto por bodega al pulsar una fila. Sin script: los GRANTs
ya estaban.

Si vas a tocar esto:
1. **El grano es la VARIANTE y se parte de ella, no de `inventario`.** Una
   variante sin ninguna posición existe y su stock es CERO, que es justo el caso
   que hay que ver; con un `FROM inventario` desaparece del listado y «no
   aparece» se lee como «no tengo». Son 6.224 variantes contra 11.408 posiciones.
2. **El filtro de bodega va en el JOIN, NO en el WHERE.** En el WHERE convierte
   el LEFT JOIN en interno y vuelve a perder las variantes sin stock en esa
   bodega, que son exactamente las que se buscan al filtrar por una bodega.
3. **NO se une `marca`, y no es un olvido**: `grp_bodega` no tiene SELECT sobre
   esa tabla, así que el JOIN devolvía 42501 y la pantalla respondía **403 a
   BODEGA** —el rol que más la necesita— mientras funcionaba con los otros tres.
   Es la misma trampa que ya dejó `categoria` fuera de OTD-INV-07. **Que la ruta
   esté abierta no basta: el motor manda.**
4. **`HAVING""" + condicion` da `HAVINGTRUE`**: el bloque de texto de Java recorta
   el espacio final de cada línea (reincidencia de §18). El concatenado va con un
   `+ " " +` explícito.
5. `estado` y `orden` son **listas blancas del servicio** y lo que se concatena es
   el texto escrito ahí, nunca el del usuario; un valor no previsto da 400.
6. **Ni un importe**: la pantalla la abren BODEGA y ANALISTA, y el corte lo hace
   la CONSULTA —no la ruta—, como en OTD-COM-08. `p17_mejoras.py` recorre la
   respuesta entera buscando nombres con pinta de dinero y falla si aparece uno.

**6 · EL ADMIN NO PODÍA ABRIR UNA DEVOLUCIÓN (script 113, defecto D-17)**: salió de
rebote, al añadir a la suite una regresión para las otras dos plantillas que
comparten el renderizador de PDF. `grp_administrador` **no tenía ni un GRANT**
sobre `historial_estado_devolucion` —lo tenían los otros siete grupos del
pipeline—, y como el detalle de la devolución lee esa tabla siempre, el síntoma
no era «no veo el historial» sino que **la pantalla entera y la guía PDF daban
403** para el ÚNICO rol que `SecurityConfig` autoriza en las SEIS transiciones
del ciclo. Es una omisión del script 38 y lo prueba el propio script 38: su
bloque de RLS **sí** metió a `grp_administrador` en `pol_horario` sobre esa misma
tabla. Auditadas las 113 tablas de `public`, era la única con ese hueco.

Si vas a tocar esto:
1. **Son DOS GRANT y no uno.** `SELECT, INSERT` sobre la tabla **y USAGE sobre su
   secuencia**: el `id` es un `serial` con `DEFAULT nextval(...)`, y un serial
   exige las dos cosas. Con solo el primero, el admin abre el detalle y **la
   primera transición que intente muere con «permission denied for sequence»** —
   un arreglo a medias que revienta en otro sitio y otro día.
2. **`has_sequence_privilege` NO es el oráculo de esa pregunta.** Devuelve
   `false` en `meta_venta`, `novedad_envio`, `item_defectuoso` y las tres de
   devolución a proveedor, que insertan perfectamente: son columnas **IDENTITY**,
   y ahí PostgreSQL no comprueba privilegios de secuencia. `historial_estado_
   devolucion` es la única `serial` de la familia. El único oráculo fiable es
   EJECUTAR el INSERT, y eso hace la guardia 3 del script: asume el rol con
   `set_config('role', …, true)`, escribe un hito real y lo borra.
3. **No hizo falta política RLS**: `pol_horario` ya enumeraba al administrador.
   Ojo con el orden inverso —GRANT sobre tabla con RLS y sin política deja al rol
   leyendo **cero filas en silencio**, la trampa del script 87—, y por eso la
   prueba compara el historial del ADMIN con el del GERENTE en vez de exigir un
   200 pelado.
4. **Ni UPDATE ni DELETE**: el historial es un rastro, se escribe y no se corrige.
Reversión probada: `99_revert_grant_admin_historial.sql`, ciclo
aplicar→revertir→re-aplicar midiendo por API cada vez (**200 → 403 → 200**), y los
cuatro casos nuevos (P17-035 a P17-038) salen en ROJO con la reversión puesta.
Los GRANT de tabla sobre `grp_*` pasan de 1.369 a **1.371** (medidos con
`aclexplode` sobre `pg_class`, los 7 privilegios estándar; los 113 MAINTAIN van
aparte).


**Deuda técnica conocida** (tablas huérfanas, requieren bloque dedicado):

- `lote` (0 filas): trazabilidad por lote/vencimiento. **DECISIÓN DE ALCANCE (2026-07-18,
  ver `ROADMAP.md`)**: se evaluó FEFO y se pospuso deliberadamente — obliga a tocar
  recepción (capturar lote+vencimiento), inventario (stock por lote), kardex (arrastrar
  `lote_id`) y salida FEFO en despacho, sin aporte al flujo retail general. Las FK
  `movimiento_inventario.lote_id` y `recepcion_detalle.lote_id` quedan listas para esa fase.
- `ajuste_inventario.estado = 'borrador'`: el CHECK lo admite y `'anulado'` ya tiene flujo, pero un
  borrador aplicable exigiría una tabla de detalle de líneas del ajuste, que hoy no existe (el
  ajuste escribe el movimiento de kardex directo al aplicarse).
- `devolucion_proveedor` **ya existe** (script 45): el ítem `defectuoso` de la inspección RMA
  y el de recepción caen al pool `item_defectuoso` y se devuelven al proveedor con resolución
  nota de crédito/reposición (ver bloque DEVOLUCIÓN A PROVEEDOR). `salida_devolucion_proveedor`
  y `entrada_reposicion_proveedor` en uso.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->

**VALIDACIÓN DE LOS CAMPOS ESCRIBIBLES (2026-08-21, solo frontend — cero cambios de
backend, de SQL y de seguridad)**: los **179 campos** de la aplicación en los que se puede
escribir pasan a admitir SOLO lo que les corresponde. Piezas nuevas en
`retailmind-frontend/src/app/core/validacion/`: `perfiles-texto.ts` (la TABLA de qué admite
cada clase de dato), `campo-texto.directive.ts` (`appTexto="perfil"`) y
`campo-numero.directive.ts` (`appNumero="entero|dinero|decimal"`), más el estilo
`.rm-campo-error` de `styles.scss`. Enganchadas en **57 componentes**; ni una regla de negocio
tocada, ni un getter de «puedeAceptar» movido.

**El punto de partida es que `<input type="number">` NO valida nada por sí solo.** El
navegador acepta `e`, `E`, `+` y `-` en cualquier posición —la notación científica es un
número válido para el estándar— y cuando lo tecleado no se puede leer, `el.value` devuelve
**cadena vacía** con `validity.badInput` en true. Con `[(ngModel)]` eso llega al modelo como
`null`: el usuario ve «12e» escrito y el formulario cree que el campo está VACÍO, sin un
error en ninguna de las dos mitades. De ahí las tres capas, que son distintas a propósito:
**`keydown`** descarta la tecla (evita el 99 %), **`input`** es la red para lo que no pasa por
el teclado —pegar, arrastrar, autocompletar— y **`blur`** ajusta a `min`/`max`. `min` y `max`
se leen del PROPIO elemento, así que los que ya estaban escritos en las plantillas —incluidos
los enlazados, como `[max]="saldo_pendiente"`— pasan de declarados a EXIGIDOS.

Si vas a tocar esto:
1. **La condición «restaura salvo que la última tecla fuera parte de un número» es lo que hace
   utilizable el campo de dinero.** Escribir `12.` deja el input en `badInput` —un punto suelto
   al final no es un número—, y una restauración a secas se come el punto EN EL MOMENTO de
   teclearlo: se escribe «12.99» y en la caja aparece «1299». La tecla se recuerda en `keydown`
   y se OLVIDA al terminar cada `input`, para que lo que llega sin teclado siga cayendo del lado
   que restaura. Lo destapó la prueba, no la lectura del código.
2. **La caja va ANTES de retirar lo prohibido.** Al revés, un perfil de minúsculas como el slug
   empieza por BORRAR las mayúsculas —que no están en su juego de caracteres— en vez de bajarlas,
   y «Zapatos De Cuero» sale «apatos-e-uero»: se pierden justo las iniciales.
3. **La coma NO se admite** aunque aquí se escriba con coma decimal: un `type="number"` solo
   entiende el punto, y dejarla pasar deja el campo en `badInput` para siempre.
4. **El perfil `url` se declara con lista BLANCA** y no con lista negra como los demás: enumerar
   lo prohibido en una URL es enumerar casi todo el teclado.
5. **El aviso se inyecta en el `subscript-wrapper` que Material ya reserva**, así que aparecer y
   desaparecer no mueve el formulario; y solo se pinta tras el primer `blur` —un formulario
   recién abierto no debe salir todo en rojo—.
6. **Se corrige lo que se teclea, no lo que se carga.** Un valor precargado no se toca hasta que
   alguien escribe en su campo. OJO con la consecuencia: **7 SKU y 1 slug heredados no cumplen
   el juego de caracteres nuevo** (`zapatos 2 warion`, `Prueba 1 AAAA`… todos dados de alta a
   mano desde la pantalla), y si alguien edita ESE campo se normalizan de golpe. El resto del
   catálogo ya cumplía: 6.217 nombres, 6.217 slugs, 50.182 correos, 50.072 teléfonos, 33 códigos
   de cupón y los 35 RUC, todos con cero excepciones (medido contra la base).
7. **Cuatro campos quedan FUERA a propósito** y no es un olvido: la contraseña del login
   (acotar sus caracteres la debilita) y los tres que ya se sanean en su propio componente
   —`rol-dialog.alEscribirCodigo`, `checkout.formatearNumero` y `formatearVencimiento`—. El CVV
   sí se cubrió: era el único de la tarjeta que no filtraba nada.
8. **Esto es validación de INTERFAZ y no sustituye a la del servidor**, que sigue siendo la
   lista blanca de la regla 2 y los CHECK del motor. Un cliente HTTP directo no pasa por aquí.

Efecto colateral declarado y deliberado: el **código de cupón ahora sube a mayúsculas DE
VERDAD**. La pantalla lo mostraba en mayúsculas con `text-transform` y enviaba lo tecleado en
minúsculas; los 33 cupones de la base están en mayúsculas, así que lo que había era una
discrepancia entre lo que se veía y lo que se mandaba.

Verificación: **`pruebas/p15_validacion_campos.js`, 85/85 casos** (Chrome headless, ADMIN y
CLIENTE, 28 pantallas). El oráculo NO es la tabla de perfiles del frontend —contrastar la
implementación consigo misma daría verde con las dos mitades equivocadas—: es una lista de
formas escrita aparte en la propia suite. Cada campo se prueba por las DOS vías: `page.type`
(eventos de teclado reales, prueba la capa 1) y `keyboard.sendCharacter`, que usa
`Input.insertText` y **se salta el `keydown` a propósito** para que la capa 2 no pueda faltar sin
que se note. Además: la mitad simétrica —un nombre, un importe y una URL legítimos NO se tocan—,
porque una directiva que borrase el campo entero pasaría el barrido con sobresaliente. Sin
regresión: **P11 65/65** y **P14 86/86**, `ng build` limpio y consola del navegador sin un solo
error en todo el recorrido.

**D-16 — UN USUARIO DADO DE ALTA COMO CLIENTE NO ERA UN CLIENTE (2026-08-21, backend, sin
script)**: `POST /api/auth/register` —lo que hay detrás de `/admin-usuarios`— escribía `usuario`
y `usuario_rol` y ahí terminaba. Un cliente de la tienda necesita ADEMÁS su fila en `cliente`:
el login resuelve `cliente_id` uniendo `cliente.usuario_id`, y con eso en null el aspecto
`PgSessionRoleAspect` **no fija `app.cliente_id`**, que es la variable de la que cuelga toda la
RLS de la tienda. El alta devolvía `success: true` y el usuario aparecía en la lista con su rol,
así que el síntoma llegaba mucho después y en otra pantalla: `/api/perfil` responde 200 con
**`esCliente: false`** y `/api/perfil/direcciones` rebota con un 409. Y donde la aplicación no
comprueba `esCliente`, la RLS no da error: **devuelve cero filas**, la misma trampa del script
87. Corregido creando la ficha en la MISMA transacción que el usuario y su rol. **No hizo falta
ni un GRANT ni un script**: `grp_administrador` ya tenía INSERT sobre `cliente` y `pol_horario`
lo cubre — el hueco era solo de aplicación, igual que D-09.

Si vas a tocar esto: (1) **`asignarRolUnico` NO crea ficha y no es un olvido.** Parecía la otra
mitad del agujero, pero al probarlo el PUT devolvió un **409 deliberado**:
`UsuarioAdminService.modificar` prohíbe cruzar la frontera CLIENTE / personal interno en los dos
sentidos, porque «la ficha de cliente y sus pedidos quedarían huérfanos». Añadir el INSERT allí
habría sido código muerto que además sugiere que la conversión es posible. **Probar la segunda
puerta antes de taparla es lo que evitó dejarlo escrito.** (2) El `ON CONFLICT (usuario_id)` no
cubre ningún caso conocido —el UNIQUE y la comprobación de email duplicado del controlador lo
hacen imposible—: está para que un reintento no convierta un choque de clave en un 500.
Verificado: alta VENDEDOR → 0 fichas; alta CLIENTE → ficha creada y `esCliente` pasa a **true**;
perfil, direcciones, carrito, lista de deseos y productos comprados en **200 con lista vacía**;
aislamiento medido con dos cuentas a la vez (**0 pedidos** el nuevo, **80** `maria.lopez`); y
recorrido en Chrome headless por las 8 pantallas del cliente sin un error de consola. Ficha
completa en `docs/pruebas/DEFECTOS.md`.

**LA TIENDA SE ABRE AL PÚBLICO Y LOS CLIENTES SE REGISTRAN SOLOS (2026-08-21, script 112 +
código)**: hasta hoy nadie veía el catálogo sin cuenta y las cuentas solo las creaba un
administrador — el sistema era un back-office con una tienda dentro. Ahora **el escaparate es
público** (`/shop` y la ficha de producto, sin guard; `GET /api/catalogo/**` en `permitAll`) y
**comprar sigue exigiendo cuenta**: al agregar al carrito o pulsar el corazón sale una tarjeta
flotante con el fondo difuminado que ofrece entrar o **crear una cuenta en cuatro pasos**
(`/registro`: datos → acceso → dirección → intereses, los dos últimos OPCIONALES).

**Las dos puertas nuevas chocaban con la MISMA pared, y es lo único no obvio de todo el
bloque**: una petición anónima no trae JWT, así que `PgSessionRoleAspect` no asume rol y la
transacción corre como `retailmind_app` —LOGIN **NOINHERIT**, sin un solo privilegio de
negocio—. Abrir la línea de `SecurityConfig` no habría bastado: el catálogo habría muerto con un
42501 en la primera tabla, y el registro no habría podido escribir nada. La respuesta es la que
el proyecto ya usa: un **rol de motor para leer** y una **función SECURITY DEFINER para
escribir**.

Si vas a tocar esto:
1. **`grp_visitante` es un rol de MOTOR y no de aplicación.** Solo SELECT, y solo sobre las SEIS
   tablas que el catálogo consulta de verdad (producto, producto_variante, producto_categoria,
   categoria, marca, inventario). **No lleva fila en `rol`** —nadie inicia sesión como
   visitante—, y una guardia del script lo comprueba: con esa fila saldría en el desplegable del
   alta de usuarios. De las seis tablas **solo `inventario` tiene RLS**, así que la quinta pieza
   del ritual del script 87 se reduce a una política. **Las ventanas de `grupo_horario` SÍ hacen
   falta aunque no haya login**: esa política llama a `esta_en_horario()`, que devuelve **false**
   para un rol sin filas — el catálogo se quedaría sin stock, en silencio y sin un error.
2. **Reutilizar `grp_cliente` para el visitante sería una línea y es la peor idea posible**: ese
   rol ESCRIBE en carrito, pedido, pago y reseña, y lo único que lo frenaría es `app.cliente_id`,
   o sea la capa de aplicación — justo lo que este proyecto se niega a usar como barrera.
3. **El rol NO es un parámetro en ningún punto del alta pública.** Ni el controlador lo lee, ni
   el servicio lo pasa, ni `fn_registrar_cliente` lo acepta en su firma (hay una guardia que
   revienta el script si algún día aparece). Por eso `POST /api/auth/register` sigue siendo de
   ADMIN y no se abrió: aquel SÍ toma el rol del cuerpo, y en abierto sería un
   `{"rol":"ADMIN"}` de escalada en una línea. Probado: mandando `rol: ADMIN` al alta pública, la
   cuenta sale CLIENTE y su token recibe 403 en la gestión de usuarios.
4. **La cuenta se crea al terminar el PASO 2, no al final.** Es lo que hace que «omitir» signifique
   algo: si se creara al final, saltarse la dirección sería mandarla vacía. Y como los pasos 3 y 4
   ya van con la sesión recién abierta, **ni la dirección ni los intereses viajan por una ruta
   anónima** — no hubo que abrir un solo permiso más. Efecto secundario buscado: cerrar el
   navegador en el paso 3 deja una cuenta usable, no un registro a medias.
5. **`uq_cliente_identificacion` es UNIQUE sobre (tipo, número)** y el choque salía como el texto
   genérico del motor («referencia inexistente, duplicado o valor fuera de rango»), que no dice
   que lo repetido es tu cédula. La función levanta ahora `REGISTRO_IDENT_DUPLICADA` y el
   servicio lo traduce a un 409 que la nombra. Lo descubrió la suite al correr por SEGUNDA vez:
   la primera pasó porque la cédula aún no existía.
6. **El escaparate público destapó cinco llamadas de CLIENTE que el catálogo hacía siempre** —
   carrito, lista de deseos, direcciones, recomendaciones y la señal de eventos, más los
   «similares» de la ficha—. Todas llevaban `error: () => {}`, así que los 403 **no se veían en
   pantalla** mientras ensuciaban la consola y el registro del servidor en cada visita. Se cortan
   en el origen (`ShopUiService.hayCliente` y dos guardas en la tienda): si no hay cliente, no se
   pregunta. La suite lo vigila mirando las RESPUESTAS HTTP, no lo pintado.
7. **El muro no es una barrera de seguridad y no debe leerse como tal.** El login que usa es el
   de siempre —tiene que serlo: el personal también entra por ahí—; lo que hace es mirar el ROL
   de la sesión recién abierta y, si no es CLIENTE, llevar a esa persona al sistema interno en
   vez de devolverla al carrito. Un GERENTE recibe 403 en `/api/carrito` con muro o sin él.
   El parámetro `?volver=` **solo admite rutas internas**, o sería un redirector abierto.
8. **El censo de RLS subió y está declarado**: **51 tablas y 98 políticas** (eran 50 y 95). Las
   nuevas son `cliente_categoria_interes` con sus dos políticas y `pol_visitante_catalogo` sobre
   `inventario`. P03 exige esos números: al cambiarlos hay que decir de dónde sale cada fila, o la
   comprobación se vuelve decorativa.

Reversión: `retailmind/sql/postgres/99_revert_tienda_publica.sql` (probado). Verificación:
**`pruebas/p16_tienda_publica.js`, 47/47** — escaparate sin cuenta, muro con su fondo difuminado
y su motivo, el desvío del GERENTE, el alta paso a paso, y que lo guardado en los pasos
opcionales esté de verdad ahí. Sin regresión: **P03 43/43 · P04 126/126 · P11 65/65 · P14 86/86 ·
P15 85/85**.

**LOS CUATRO BORDES DE LA TIENDA PÚBLICA (2026-08-21, mismo día, solo frontend)**: auditoría de
los caminos que la implementación inicial no recorría. Los cuatro se corrigieron antes de que
nadie los usara, y los cuatro son la misma clase de fallo —**el flujo funcionaba por el camino
principal y se comportaba de otra manera por los laterales**—:

1. **`authGuard` no recordaba a dónde ibas.** El muro y la barra del visitante ya pasaban
   `?volver=`, pero quien escribía `/shop/carrito` o abría un enlace a `/wishlist` iniciaba
   sesión y aterrizaba en `/inicio`. El guard era el ÚNICO camino de entrada que lo perdía, y eso
   hacía que el sistema se comportara de dos maneras según por dónde se llegara.
2. **El personal podía abrir `/registro` y su sesión quedaba PISADA.** `guardarSesion` escribe
   sobre las mismas claves de `localStorage`, así que un gerente que completara el alta perdía su
   sesión sin aviso y sin forma de deshacerlo salvo volviendo a entrar. Ahora se le pregunta, y
   `AuthService.limpiarSesion()` existe aparte de `logout()` porque aquí cerrar sesión **no
   significa irse**: hay que soltar la suya y QUEDARSE en el registro.
3. **La ficha pública decía «No encontramos productos similares en el catálogo», y era falso.**
   No es que no haya: es que no se piden, porque las recomendaciones salen de lo que esa persona
   ha visto. Una afirmación sobre el catálogo donde lo cierto era una condición de la sesión.
   Medido: al visitante le sale «Inicia sesión para ver productos parecidos»; al cliente, 6
   similares reales.
4. **El número de identificación se validaba solo en el servidor.** El alta ocurre al final del
   paso 2, así que quien escribía tres dígitos en el paso 1 se enteraba una pantalla después y
   con un mensaje sobre un campo que ya no veía.

**Y el círculo se probó entero**: una cuenta que no existía llegó a `/shop` sin sesión, se
registró con dirección, agregó al carrito sin muro —ya tenía sesión—, pasó por el checkout con
tarjeta simulada y **generó el pedido `PED-20260821-114043`, estado `facturado`** (el pedido
online nace pagado y se factura solo, script 39). 10/10 y consola limpia. La cuenta
`compra.7349124665@demo.com` se deja ACTIVA a propósito: es la evidencia, y sirve para mirar el
pedido desde el back-office. Los invariantes de datos siguen intactos tras esa compra (**P06
17/17**: kardex, cuadre contable y huérfanos).

Cobertura de campos: **P15 pasa a 89 casos** — ahora barre también los dos primeros pasos del
alta. La pestaña del registro **suelta la sesión antes de mirar**: el `localStorage` es por origen
y lo comparten todas las pestañas, así que heredaba la del admin y `/registro` le enseñaba el
aviso de «ya hay una sesión abierta» en vez del formulario. Lo detectó el propio barrido al decir
«no se pintó ninguno» en vez de dar verde por no haber mirado.

**EL CONTROL QUE LLEVABA EL SUPUESTO DENTRO — SEGUNDA REINCIDENCIA DE C6.4 (2026-08-21, solo
código del ETL)**: tras abrir el alta pública, el DAG empezó a abortar en `dim_cliente` con
«Tipos de identificación: origen 2 vs destino 3». **No había nada mal en el dato**: el descuadre
estaba en el CONTROL. La extracción convierte el NULL en `'sin_dato'` con un `COALESCE`, pero el
control del origen medía `count(DISTINCT c.tipo_identificacion)` — y **en SQL `COUNT DISTINCT`
ignora los NULL**. Comparaba dos expresiones que no son la misma, y funcionaba solo mientras la
columna estuviera SIEMPRE llena: cierto **por casualidad del seed**, no por el motor, porque
`cliente.tipo_identificacion` lleva siendo NULLABLE desde el principio. El registro de la tienda
la deja opcional y con los primeros diez clientes sin ella el control se cayó.

Si vas a tocar esto:
1. **Arreglarlo es corregir la EXPRESIÓN, no el umbral.** El control sigue exigiendo igualdad
   exacta; lo que se le añade es el mismo `COALESCE` que ya hacía la extracción. Distinguir esas
   dos cosas es lo que separa una corrección de una relajación.
2. **Hay que arreglarlo DOS VECES**, y ésa es la otra mitad de la lección: el control vive
   duplicado en `tablas/dim_cliente.py` y en `validar_dwh.py`. Con solo el primero, las 21 tablas
   publican y es `validar_dwh` la que sigue en rojo — se ve como si el arreglo no hubiera
   funcionado, cuando lo que pasa es que falta la otra copia.
3. **El patrón de carga atómica hizo exactamente lo suyo**: validó contra el origen y abortó SIN
   publicar, así que las pantallas siguieron sirviendo el dato de la corrida anterior mientras
   tanto. Un DAG en rojo con informes coherentes es el comportamiento diseñado, no una
   contradicción.
4. **La cabecera de `dim_cliente.py` describía la cartera de 72 clientes anterior a la carga
   masiva.** Se anotó el estado de hoy sin borrar la medición que sostuvo el veredicto de
   población homogénea — el veredicto no cambia: 4.166 RUC repartidos en la misma distribución de
   compra siguen sin dibujar dos poblaciones.

**Estado tras la reparación (2026-08-21 21:51)**: DAG `verificacion_final_213700` con **22/22
tareas en éxito** y los **49 controles cuadrando exactamente**. Las dos bases contrastadas:
pedidos **2.999.997**, clientes **50.087**, líneas **7.622.440**, variantes **6.224** — idénticos
en PostgreSQL y en el almacén. Modelo publicado: **21 tablas / 26.971.522 filas / 1,44 GiB**.

**OJO con el calendario de Airflow**: el `0 2 * * *` vive DENTRO del scheduler, así que con el
contenedor apagado no ocurre nada a las 2 de la mañana. Estuvo apagado del 17 al 21 de agosto y
el almacén se quedó en la foto del **2026-08-17 10:01** sin que nada lo avisara.

**`catchup=False` NO quiere decir que al encender no pase nada** —esta frase afirmaba que «no
recupera las corridas perdidas» hasta el 2026-08-22, y es falsa—: lo que evita es que se reponga
el atraso ENTERO, no que se dispare nada. Medido en `dag_run` al volver a encender Airflow la
noche del 21: el scheduler lanzó **por su cuenta, sin que nadie lo pidiera, DOS programadas
atrasadas** (`scheduled__2026-08-17T07:00` a las 20:33 y `scheduled__2026-08-20T07:00` a las
20:55), no las cuatro o cinco del hueco. Las dos fallaron, pero por el bug de `dim_cliente` de esa
noche y no por el calendario.

La consecuencia práctica es la que importa: **a los pocos minutos de encender el equipo puede
arrancar sola una corrida de ~12 minutos**. Si en ese rato se cierra Docker, esa corrida muere a
media carga y sale ROJA — no falló nada, se le quitó el suelo. Y el rojo NO se va: Airflow guarda
todas las corridas para siempre, así que **el estado del sistema es la fila de ARRIBA y jamás el
color de la parrilla**. El 2026-08-22 hay cuatro rojas seguidas justo debajo del 22/22 verde
—tres por `dim_cliente` y una por `validar_dwh`, todas anteriores al arreglo de esa noche— y son
historia, no un problema pendiente.

Los dos servicios llevan `restart: unless-stopped`, así que vuelven solos tras reiniciar el
equipo; lo que **no** vuelve solo es lo que se pare a mano, y ahí entra tanto `docker compose
stop` como el botón de parada de Docker Desktop —los dos cuentan como parada explícita—. Desde
fuera **no se distingue** una cosa de la otra: el código de salida no lo dice (`137` y `143` salen
en ambos casos, según si el proceso atendió el SIGTERM a tiempo), así que no se adivina, se
comprueba. Al encender el equipo:

```bash
docker ps --format "{{.Names}} {{.Status}}"      # ¿están arriba los de airflow?
docker compose --profile airflow up -d           # si no, esto los repone (y es idempotente)
```
