# Despliegue de RetailMind — diagnóstico y diseño del compose objetivo

> **Fecha**: 2026-08-03 · **Naturaleza**: análisis y diseño. **No se modificó ningún archivo de
> configuración, no se levantó ni se detuvo ningún contenedor, no se migró nada.**
> Todas las cifras de este documento están verificadas contra los archivos reales del repositorio
> y contra la base `retailmind` en vivo (consultas de solo lectura). Lo que no se pudo verificar
> se declara como tal.
>
> **Nota de entorno**: durante la elaboración de este documento el demonio de Docker **no estaba
> corriendo** (`failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`),
> así que el inventario de contenedores, imágenes y volúmenes **no pudo leerse en vivo**. Las
> afirmaciones sobre volúmenes provienen del `docker-compose.yml` y del diagnóstico de ClickHouse
> del 2026-07-30. Están marcadas donde corresponde.
>
> ---
>
> ### Addendum del 2026-08-03 — verificado en vivo y **migración ejecutada**
>
> Los pasos 0, 1 y 2 de la §8 **están hechos**. El demonio se levantó y todo lo que quedaba
> pendiente de verificar se leyó en vivo. Correcciones aplicadas al documento:
>
> | Dónde | Decía | Dice ahora |
> |---|---|---|
> | §2.2 | `fact_eventos` con **2.832.605** filas | **2.823.245** (dígitos transpuestos) |
> | §2.2 / §5.2 | versión de ClickHouse sin leer | **`26.4.2.10`**, fijada en el compose |
> | §5.1 | montaje en `/var/lib/postgresql/data` | **`/var/lib/postgresql`** — la imagen 18+ cambió la convención |
>
> El nombre del volumen de ClickHouse **se confirmó**: `1m6datoscs_clickhouse_data`, tal como
> asumía la §5.0. La migración pasó **las once verificaciones** de la §9.5 con el PostgreSQL
> local intacto; el detalle vive en `deploy/verificar_migracion.sql` (V1–V9, diffable) y
> `deploy/verificar_v11.sh`. Lo aprendido durante la ejecución está marcado en el texto con
> **«verificado 2026-08-03»**.

---

## 1. Resumen ejecutivo

**Estado actual, en tres líneas.** El `docker-compose.yml` tiene **5 servicios de los cuales 2 son
legado puro** (`pocketbase` y `etl`, ambos de la etapa en que la tienda vivía sobre
PocketBase → ClickHouse) y un tercero, `backend`, **no puede funcionar tal como está**: no recibe
ninguna variable `POSTGRES_DATASOURCE_*`, así que dentro del contenedor cae en el valor por defecto
`jdbc:postgresql://localhost:5432/retailmind` — que apunta al propio contenedor, no a la base. Los
únicos servicios sanos son `clickhouse` (con la base equivocada declarada) y `frontend`. La
configuración arrastra credenciales en claro en dos `.env` **versionados en git** y una IP de WSL
(`172.29.94.38`) incrustada como valor por defecto de ClickHouse en `application.properties`.

**Decisión sobre PostgreSQL: ya está tomada — se conteneriza.** Este documento no la vuelve a
plantear; diseña **cómo hacerla sin perder nada**. El inventario verificado contra la base real dice
que la migración es pequeña en datos (**110 tablas, 89 con filas, 119.730 filas, 95 MB**) y delicada
en seguridad de motor: **11 roles que un `pg_dump` de base NO incluye**, 95 políticas RLS sobre 50
tablas, **109 columnas con ACL propia** (la segregación financiera), 13 funciones `SECURITY DEFINER`
cuyo comportamiento depende de su propietario, y un **locale `Spanish_Ecuador.1252` que no existe en
la imagen de Debian** y haría fallar un `pg_dump --create` sin avisar de la causa real. La sección 9
lleva el plan con red de seguridad: el PostgreSQL local se queda intacto y sirviendo hasta que el
contenedor pase **once verificaciones**, incluidas tres pruebas funcionales con roles distintos.

---

## 2. Diagnóstico del compose actual, servicio por servicio

Archivo: `docker-compose.yml` (raíz, 96 líneas, `version: "3.8"`). Cinco servicios, dos volúmenes
nombrados, **sin red declarada** (usa la `default` implícita del proyecto).

### 2.1 `pocketbase` — **LEGADO**

```yaml
image: ghcr.io/muchobien/pocketbase:latest   ports: 8091:8090   volume: pocketbase_data:/pb_data
```

Era el origen del ETL de la primera etapa (PocketBase → Parquet → ClickHouse). **Ninguna pieza del
núcleo operativo lo toca**: el backend solo lo nombra en `admin/etl/InicializacionController.java`.

**Consecuencia honesta de eliminarlo**, que hay que decidir a conciencia: existe una pantalla viva
de administrador, **`/inicializacion` («Inicialización del Sistema»**, en `app.routes.ts:107` y en el
menú `nav-model.ts:449`), cuyos botones llaman a `etl/extraccion/08_extract_pocketbase.py`,
`etl/carga/09_load_clickhouse.py`, `10_verify_clickhouse.py`, `11_reset_clickhouse.py`,
`12_generate_synthetic.py` y `13_create_shop_tables.py`. Esos scripts **sí existen**. Si `pocketbase`
sale del compose, los tres primeros dejan de funcionar (los de ClickHouse siguen). Alimentan la base
**legada** `retailmind` de ClickHouse, que está congelada como archivo. Opciones, en orden de
preferencia:

1. **Retirar el servicio y la pantalla** del menú de la feria (es la etapa anterior del proyecto y
   regenerar el relleno sintético no aporta nada a la demostración). Cambio de una línea en
   `nav-model.ts`; no se toca el backend.
2. Retirar el servicio y dejar la pantalla, aceptando que 3 de sus 6 botones devuelvan error.
3. Conservar `pocketbase` bajo un perfil `legacy` que no arranque por defecto.

> **Aviso relacionado, no eliminable a la ligera**: `admin/etl/EtlService.java` invoca
> `etl/load_csv_staging.py` y `etl/05_load_incremental.py`. **Ninguno de los dos existe** en el
> repositorio (verificado con `find`). Son endpoints muertos desde antes de esta tarea; no dependen
> de PocketBase, pero explican por qué la imagen del backend instala paquetes que ya nadie usa.

### 2.2 `clickhouse` — **VIGENTE, con la base equivocada declarada**

```yaml
image: clickhouse/clickhouse-server:latest   user: "101:101"   ports: 8123, 9000
environment: CLICKHOUSE_DB=${CH_DATABASE}  → .env dice CH_DATABASE=retailmind
volume: clickhouse_data:/var/lib/clickhouse   healthcheck: wget /ping
```

El servicio es necesario y su healthcheck es correcto. Tres problemas:

- **`CLICKHOUSE_DB=retailmind` es la base LEGADA.** El almacén vivo es **`retailmind_dwh`** (21
  tablas, 64.664 filas). No es un fallo funcional —el backend cualifica siempre
  `retailmind_dwh.tabla` en los informes compuestos y `retailmind.fact_eventos` en `analytics/`—
  pero la declaración del compose sugiere lo contrario a quien lo lea.
- **La base legada NO es solo archivo: se lee en vivo.** Las pantallas de `analytics/` (funnel,
  sesiones, región, dispositivo, tráfico, dashboard) consultan `retailmind.fact_eventos`
  (**2.823.245 filas**, verificado 2026-08-03) con nombre cualificado. Ese dato **solo existe en
  el volumen** y no es
  reproducible: el 96,2 % lo generó `12_generate_synthetic.py` con semilla no fijada y el 3,8 %
  restante es un dataset externo. **Perder el volumen = perder las pantallas de analítica para
  siempre.**
- **El volumen está atado al nombre del directorio.** Al ser un volumen nombrado sin `name:`
  explícito, Docker lo llama `<proyecto>_clickhouse_data` → `1m6datoscs_clickhouse_data`. Renombrar
  la carpeta del proyecto, clonarla en otra ruta o pasar `-p` crea un volumen **nuevo y vacío** sin
  un solo error: ClickHouse arranca, el DWH aparece vacío, los 39 informes compuestos y los 7
  tableros degradan en bloque y la causa parece "ClickHouse caído". El compose objetivo lo fija con
  `name:` explícito (§5.2).
- Menor: `:latest` es una bomba de relojería para un motor con formato en disco. Los datos del
  volumen los escribió la versión que corría en mayo de 2026; un `docker compose pull` puede traer
  una versión que los migre de forma irreversible. **Verificado 2026-08-03: la versión es
  `26.4.2.10`** y ya está fijada en el compose (§5.2). La imagen `:latest` de esta máquina se
  descargó el **2026-05-07** y el volumen se creó el **2026-05-15**: nunca se ha vuelto a
  descargar, así que ésa *es* la versión que escribió el volumen y no una posterior que ya lo
  hubiera migrado.

> **Ruido a limpiar**: existe además `clickhouse-data/` en la raíz (59 MB, con una base `retailmind`
> dentro), resto de una iteración anterior con *bind mount*. No es el dato vivo —ese está en el
> volumen, ~830 MB— y confunde. Está en `.gitignore`; conviene archivarlo o borrarlo, nunca
> montarlo.

### 2.3 `backend` — **DESACTUALIZADO: no puede servir lo operativo**

```yaml
build: ./retailmind-backend   ports: 8080:8080   volumes: ./retailmind:/etl
environment: CLICKHOUSE_*, CH_*, INIT_SCRIPTS_PATH, ETL_SCRIPTS_PATH, ETL_PYTHON_PATH, PB_*
depends_on: clickhouse (healthy)
```

Cuatro defectos, el primero terminal:

1. **No recibe ninguna variable `POSTGRES_DATASOURCE_*`.** `application.properties` cae entonces en
   `${POSTGRES_DATASOURCE_URL:jdbc:postgresql://localhost:5432/retailmind}`, y dentro del contenedor
   `localhost` es el contenedor. **Todo lo operativo falla, empezando por el login** (`AuthService`
   lee la tabla `usuario` de PostgreSQL). Este servicio corresponde a la etapa en que el login y la
   tienda vivían en ClickHouse; hoy es inservible tal cual.
2. **La actualización automática del DWH no puede correr en el contenedor.**
   `dwh.python.path=${DWH_PYTHON_PATH:py}` y el compose **no define `DWH_PYTHON_PATH`**: dentro de
   la imagen el intérprete es `python`, no `py`. El `@Scheduled` de `DwhProgramacion` dispararía a
   las 02:00 y `DwhActualizacionService` fallaría al lanzar el proceso. (`ETL_SCRIPTS_PATH=/etl` sí
   está bien y casa con el montaje.)
3. **Tampoco recibe `ETL_PG_*` ni `ETL_CH_*`.** El orquestador leería `retailmind/.env` desde el
   montaje, donde `ETL_PG_HOST=localhost` y `ETL_CH_HOST=localhost` — otra vez el propio contenedor.
   Es exactamente la fricción que `DwhProgramacion` documenta en su javadoc como razón para no haber
   usado el contenedor `etl`.
4. **Sin `healthcheck`**, aunque `/api/health` está en `permitAll` (`SecurityConfig:42`) y devuelve
   `status: UP` **si y solo si PostgreSQL responde** (`HealthCheckService.checkAll`) — es decir, el
   sistema ya tiene el probe ideal y el compose no lo usa. Tampoco declara `depends_on` de su base.

### 2.4 `frontend` — **VIGENTE**

```yaml
build: ./retailmind-frontend   ports: 4200:80   depends_on: backend
```

Correcto y verificado: `environment.production.ts` usa `apiUrl: ''` (rutas relativas) y `nginx.conf`
hace `proxy_pass http://backend:8080` para `/api/`, con `try_files … /index.html` para el ruteo de
Angular y cabeceras `X-Forwarded-*`. La única mejora es la condición del `depends_on` (§5.5).

### 2.5 `etl` — **LEGADO Y OCIOSO**

```yaml
build: ./retailmind   command: tail -f /dev/null
environment: CH_*, PB_*    depends_on: clickhouse + pocketbase (healthy)
```

No ejecuta nada (`tail -f /dev/null`, igual que el `CMD` de su Dockerfile), apunta a PocketBase y
**no recibe una sola variable `ETL_PG_*`**, que es justo lo que el pipeline vigente
(`retailmind/etl/dwh/`) necesita. Es el contenedor que `DwhProgramacion` descartó por escrito.

### 2.6 Recuento

| Servicio | Veredicto |
|---|---|
| `pocketbase` | **Legado** |
| `etl` | **Legado** (en su forma actual; el servicio se reutiliza con otro propósito en §5.6) |
| `backend` | **Desactualizado / inservible** (sin conexión a PostgreSQL) |
| `clickhouse` | Vigente, mal parametrizado (base legada declarada, volumen frágil, `:latest`) |
| `frontend` | **Vigente** |

**5 servicios · 2 legado · 1 inservible · 1 vigente con reservas · 1 vigente.**

---

## 3. Dockerfiles y configuración de conexión

### 3.1 Dockerfiles

| Archivo | Estado | Detalle verificado |
|---|---|---|
| `retailmind-backend/Dockerfile` | **Funcional pero pesado y con lastre** | Multi-stage correcto (`eclipse-temurin:17-jdk-alpine` → `17-jre-alpine`), `dependency:go-offline` antes de copiar `src` (buen cacheo). En la imagen final instala `python3 + gcc + musl-dev` y **6 paquetes pip** (`clickhouse-connect`, **`pocketbase`**, `pyarrow`, `pandas`, `python-dotenv`, `psycopg2-binary`). Sobre Alpine (musl) `pyarrow`/`pandas` no tienen rueda oficial y compilan: es la mayor parte del tiempo de build. `pocketbase` ya no hace falta; `pyarrow` tampoco lo usa `etl/dwh/` (verificado: sus únicas dependencias externas son `psycopg2`, `clickhouse_connect`, `python-dotenv` y `numpy`, este último a través de `pandas`). Sin `HEALTHCHECK`, sin `TZ`, sin límite de heap. |
| `retailmind-frontend/Dockerfile` | **Correcto y verificado hoy** | `node:20-alpine` + `npm ci` + `ng build --configuration=production` → `nginx:alpine`. La ruta `dist/retailmind-frontend/browser` **casa** con el builder `application` de Angular 17 declarado en `angular.json` (`outputPath: dist/retailmind-frontend`). Hay un `dist/` generado el 2026-08-02 con *hashing* de producción, o sea la build de producción **pasa hoy** (bundle inicial `main` 131 KB + `styles` 97 KB, holgado bajo el presupuesto de 1 MB). `package-lock.json` presente, así que `npm ci` es reproducible. |
| `retailmind/Dockerfile` | **Desactualizado en su propósito** | `python:3.12-slim` + `requirements.txt` + `CMD tail -f /dev/null`. La imagen es válida y **suficiente para el pipeline vigente** (`requirements.txt` trae psycopg2-binary, pandas, clickhouse-connect, python-dotenv; `numpy` entra con pandas). Lo que está mal es el `CMD` y que nunca se le pasan las variables del pipeline. `sqlalchemy` y `pocketbase` sobran. |

### 3.2 Valores de conexión problemáticos

| Dónde | Valor | Problema |
|---|---|---|
| `retailmind-backend/src/main/resources/application.properties` | `clickhouse.datasource.url` por defecto **`jdbc:ch://172.29.94.38:8123/retailmind`** y `CH_HOST` por defecto **`172.29.94.38`** | Una **IP de WSL incrustada como valor por defecto**. No es portable: depende de la máquina y cambia al reiniciar WSL. En Docker el compose la pisa; **fuera de Docker no la pisa nadie**, así que el modo desarrollo depende de que esa IP siga resolviendo. Debería ser `localhost`. |
| Ídem | `postgres.datasource.password` por defecto **con la contraseña real incrustada** (valor redactado; rotado el 2026-08-03) | Contraseña de producción de `retailmind_app` en claro **y versionada** dentro del código fuente. |
| Ídem | `jwt.secret` por defecto **con el secreto real incrustado** (valor redactado; rotado el 2026-08-03) | Secreto de firma de tokens en claro y versionado. |
| Ídem | `clickhouse.datasource.url` apunta a la base **`retailmind`** (legada) | Correcto en la práctica —`analytics/` la necesita como base por defecto y los informes cualifican `retailmind_dwh`—, pero no es evidente y merece un comentario. |
| `.env` (raíz) | `CH_PASSWORD`, `PB_PASSWORD`, `JWT_SECRET` en claro | **Versionado en git** (`git ls-files` lo confirma). `.gitignore` no excluye `.env`. |
| `retailmind/.env` | `DB_NAME=CDRetail_IntelligenceViejo2`, `DB_PASSWORD=<redactado>` (superusuario `postgres`, **sigue vigente en el local del 5433**), `CH_HOST=172.29.94.38`, `ETL_PG_PASSWORD=<redactado>` (rotado el 2026-08-03), `ETL_CH_PASSWORD` | **Versionado en git.** Mezcla la configuración vigente (`ETL_*`) con la de otro proyecto (`DB_NAME` apunta a una base ajena) y repite la IP de WSL. Contiene la **contraseña del superusuario del clúster**. |
| `docker-compose.yml`, servicio `backend` | ausencia de `POSTGRES_DATASOURCE_*`, `DWH_PYTHON_PATH`, `ETL_PG_*` | Ya tratado en §2.3. |

**Resumen de higiene**: dos archivos `.env` versionados, siete secretos distintos en claro (entre
ellos el superusuario de PostgreSQL y el secreto de firma del JWT) y tres valores por defecto en el
código que solo funcionan en una máquina concreta.

---

## 4. La decisión sobre PostgreSQL

**Tomada por el usuario: PostgreSQL se conteneriza.** No se replantea. Lo que sigue es el encuadre
técnico de la decisión; el *cómo* está en la §9.

### 4.1 Qué gana el proyecto

- **El «un solo comando» se vuelve real.** Hoy `docker compose up` levanta un backend que no puede
  hablar con su base. Con la base dentro, `docker compose up -d` es el sistema entero.
- **Desaparece la fricción documentada del ETL.** El contenedor alcanza `postgres:5432` por DNS de
  la red del compose. No hace falta `host.docker.internal`, ni `extra_hosts`, ni tocar
  `listen_addresses`, ni añadir una línea a `pg_hba.conf`. Es exactamente el escenario que
  `DwhProgramacion` describió como «cuando la contenerización se complete, esta opción pasa a ser la
  buena», y el mismo obstáculo que el diseño del ETL (§7.2) señaló como «el punto donde más gente se
  queda atascada» para Airflow.
- **Aísla RetailMind de un clúster compartido.** El PostgreSQL local **no es solo de RetailMind**:
  tiene **12 bases** (`AdventureWorks2017`, `bdventas`, `presusDb`, `tienda`, `mod_venta_inve`…) y
  **11 roles con LOGIN**, de los cuales 8 son de otras materias (`ElToke`, `Darinxxo`,
  `usuario_ventas`, `jefe_ventas`, `jefe_tthh`, `aux_tthh`, `jefe_sistemas`, `usuario_consultas`).
  Un contenedor dedicado deja RetailMind fuera del alcance de cualquier otro trabajo.
- **El diagrama de despliegue deja de tener una excepción.** Todo nodo de ejecución es un
  contenedor de la misma red.

### 4.2 Qué hay que hacer bien para no perder nada

Lo que se migra **no son 110 tablas**: son 110 tablas **más una capa de seguridad de motor** que
vive fuera de ellas y, en parte, fuera de la base. El inventario verificado está en la §9.1. Los
tres puntos donde una migración descuidada rompe en silencio:

1. **Los roles son objetos de clúster.** `pg_dump` de la base **no los incluye**. Si se restaura sin
   crearlos antes, fallan las políticas RLS, los GRANT y los GRANT por columna — 1.354 + 109
   sentencias — y el resultado es una base que arranca, responde a `SELECT` como `postgres` y
   **deniega todo** en cuanto la aplicación hace `SET LOCAL ROLE`.
2. **El `ALTER ROLE retailmind_etl SET default_transaction_read_only=on`** vive en
   `pg_db_role_setting`, no en la base. Es una de las cuatro capas de solo-lectura del rol del ETL.
   Un dump de base lo pierde sin decir nada, y el pipeline seguiría funcionando: la pérdida solo se
   nota el día que alguien escriba.
3. **El locale `Spanish_Ecuador.1252` no existe en la imagen Debian.** Un `pg_dump --create` emite
   un `CREATE DATABASE … LC_COLLATE='Spanish_Ecuador.1252'` que **falla en el contenedor**. Se
   resuelve creando la base con ICU `es-EC` y restaurando **sin** `--create` (§9.3).

### 4.3 El riesgo, dicho sin adornos

El riesgo real **no es el volumen de datos** (95 MB, 119.730 filas, ~10 s de restauración) sino la
**verificación**: es fácil terminar con un contenedor que arranca, sirve el catálogo y el login de
admin, y tiene rota la segregación financiera o el aislamiento del cliente — fallos que solo
aparecen al entrar con un rol concreto. Por eso el plan (§9) es **aditivo y reversible**: el
PostgreSQL local **no se toca ni se apaga** hasta que el contenedor pase las once verificaciones,
tres de ellas funcionales con roles distintos. Si algo falla, se apaga el contenedor y el sistema
sigue exactamente como hoy — coste de dar marcha atrás: **cero**.

Recomendación de calendario: **hacer la migración con margen** (no la víspera), y dejar el
PostgreSQL local instalado —aunque detenido— durante la feria, como plan B de un minuto.

---

## 5. El compose objetivo

**6 servicios**: `postgres`, `clickhouse`, `backend`, `frontend`, `etl` (redefinido) y `pgadmin`
opcional bajo perfil. Se elimina `pocketbase`.

### 5.0 Forma general

```yaml
name: retailmind                    # fija el prefijo de volúmenes y red: ya no depende
                                    # del nombre de la carpeta (ver §2.2)

networks:
  retailmind:
    name: retailmind_net            # nombre fijo: Airflow se enganchará como red externa (§7)

volumes:
  pg_data:
    name: retailmind_pg_data
  clickhouse_data:
    name: 1m6datoscs_clickhouse_data   # ← EL VOLUMEN EXISTENTE, por nombre literal
    external: true                     #   NO lo crea: si no existe, falla en vez de arrancar vacío
```

> `external: true` sobre el volumen de ClickHouse es la pieza que evita el peor fallo posible del
> despliegue: arrancar con un almacén vacío y creer que el sistema funciona. Si el volumen no está,
> `docker compose up` se niega a arrancar y lo dice. **Verificado 2026-08-03 con `docker volume ls`:
> el volumen se llama `1m6datoscs_clickhouse_data`**, exactamente como suponía este documento.
>
> **`pg_data` NO lleva `external: true`, y es deliberado.** Los dos volúmenes tienen exigencias
> opuestas: el de ClickHouse guarda un dato **irreproducible** y por eso debe fallar si falta; el de
> PostgreSQL sí es reproducible (dump + `initdb/`) y en una máquina nueva **tiene que crearse solo**
> para que el `docker-entrypoint-initdb.d` restaure la base. Con `external: true` en `pg_data`, un
> clon del repositorio no arrancaría nunca.
>
> **Aviso de esta máquina** (verificado 2026-08-03): existe un volumen huérfano
> **`1m6datoscs_postgres_data` con un directorio de datos de PostgreSQL 15** (`PG_VERSION` = 15),
> resto de una iteración anterior. Es la razón de fondo por la que `pg_data` lleva `name:` explícito:
> si Docker lo nombrara por convención bajo el proyecto `1m6datoscs`, caería sobre ese directorio y
> la imagen 18 se negaría a arrancar con «database files are incompatible with server». No hay que
> reutilizarlo ni borrarlo con prisa; simplemente no se toca.

### 5.1 `postgres` — **NUEVO**

```yaml
postgres:
  image: postgres:18                      # coincide con el local: PostgreSQL 18.3 (verificado)
  environment:
    POSTGRES_DB: retailmind
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD_FILE: /run/secrets/pg_superuser
    POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale-provider=icu --icu-locale=es-EC --locale=C.UTF-8"
    TZ: America/Guayaquil
  secrets: [pg_superuser]
  volumes:
    - pg_data:/var/lib/postgresql          # ← ¡NO /var/lib/postgresql/data! (ver abajo)
    - ./deploy/postgres/initdb:/docker-entrypoint-initdb.d:ro
  ports:
    - "5433:5432"      # ← 5433 mientras el PostgreSQL local siga vivo; 5432 tras el corte (§9.6)
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U postgres -d retailmind"]
    interval: 10s   timeout: 5s   retries: 10   start_period: 30s
  networks: [retailmind]
  restart: unless-stopped
```

Justificación de cada elección:

- **`postgres:18` fijo, no `:latest`.** El local es 18.3; un dump de 18 no restaura en 17. (La
  imagen sirve hoy **18.4**: `postgres:18` sigue la última 18.x. La diferencia de versión menor es
  compatible hacia delante y la restauración pasó sin incidencias — verificado 2026-08-03.)
- **El montaje va en `/var/lib/postgresql`, NO en `/var/lib/postgresql/data`** — corregido
  2026-08-03, era el error que más caro salía de este documento. **A partir de la imagen 18 el
  directorio de datos lleva la versión en el nombre** (`PGDATA=/var/lib/postgresql/18/docker`),
  para que `pg_upgrade --link` no tropiece con el límite del punto de montaje. Montar en la ruta
  antigua **no arranca vacío en silencio**, que sería lo peor: la imagen detecta el volumen
  huérfano y **se niega a arrancar**
  («there appears to be PostgreSQL data in: /var/lib/postgresql/data (unused mount/volume)»).
  Es un fallo ruidoso y correcto, pero **el mensaje habla de `pg_upgrade` y no de la ruta**, así
  que se lee como un problema de versiones cuando en realidad sobra un `/data`.
- **`POSTGRES_INITDB_ARGS` con ICU `es-EC`**: la alternativa `Spanish_Ecuador.1252` no existe en
  Debian (§9.3). ICU sí trae `es-EC` y ordena el texto acentuado como se espera en la demostración.
- **`/docker-entrypoint-initdb.d` con un directorio PROPIO, nunca `retailmind/sql/postgres/`.** Ese
  directorio tiene **93 scripts**, y entre ellos **8 `99_revert_*.sql`** que deshacen bloques
  enteros del seed. El entrypoint los ejecutaría **en orden lexicográfico**, así que los `99_revert`
  correrían al final y **desharían la siembra**. `deploy/postgres/initdb/` contendrá exactamente
  tres archivos (§9.4).
- **Puerto 5433 durante la convivencia.** El local tiene tomado el 5432. Dentro de la red del
  compose los demás servicios usan `postgres:5432`, así que el mapeo externo es irrelevante para
  ellos y solo sirve para inspeccionar desde el anfitrión.
- **`TZ`** por higiene: el `esta_en_horario()` está anclado a `America/Guayaquil` dentro de la
  función (verificado en `pg_get_functiondef`), así que la restricción horaria **no depende** de esta
  variable — pero los registros del motor sí.

### 5.2 `clickhouse` — vigente, corregido

```yaml
clickhouse:
  image: clickhouse/clickhouse-server:26.4.2.10   # ← la versión que escribió el volumen
  user: "101:101"
  environment:
    CLICKHOUSE_DB: retailmind_dwh            # el almacén vivo, no la base legada
    CLICKHOUSE_USER: ${CH_USER}
    CLICKHOUSE_PASSWORD: ${CH_PASSWORD}
    TZ: America/Guayaquil
  volumes: [clickhouse_data:/var/lib/clickhouse]
  ulimits: { nofile: { soft: 262144, hard: 262144 } }
  healthcheck: wget --spider -q http://localhost:8123/ping   # el actual, sirve tal cual
  ports: ["8123:8123", "9000:9000"]
  networks: [retailmind]
```

- **Versión fijada: `26.4.2.10`** (leída en vivo el 2026-08-03 con
  `docker exec … clickhouse-client -q "SELECT version()"`). `:latest` sobre un volumen con formato
  en disco propio es la vía rápida a una migración irreversible. Que la etiqueta `:latest` de esta
  máquina no se haya vuelto a descargar desde el 2026-05-07 —anterior a la creación del volumen— es
  lo que permite afirmar que **ésta es la versión que escribió el dato** y no una posterior que ya
  lo hubiese migrado sin avisar.
- `CLICKHOUSE_DB` pasa a `retailmind_dwh` porque es la base viva; `retailmind` sigue existiendo en el
  volumen y `analytics/` la nombra cualificada, así que no se pierde nada.
- El puerto 8123 se sigue publicando: el ETL corriendo **en el anfitrión** (modo desarrollo) lo
  necesita.

### 5.3 `backend`

```yaml
backend:
  build: ./retailmind-backend
  environment:
    POSTGRES_DATASOURCE_URL: jdbc:postgresql://postgres:5432/retailmind
    POSTGRES_DATASOURCE_USERNAME: retailmind_app
    POSTGRES_DATASOURCE_PASSWORD: ${PG_APP_PASSWORD}
    CLICKHOUSE_DATASOURCE_URL: jdbc:ch://clickhouse:8123/retailmind?compress=0
    CLICKHOUSE_DATASOURCE_USERNAME: ${CH_USER}
    CLICKHOUSE_DATASOURCE_PASSWORD: ${CH_PASSWORD}
    CH_HOST: clickhouse
    CH_PORT: "8123"
    CH_DATABASE: retailmind
    CH_USER: ${CH_USER}
    CH_PASSWORD: ${CH_PASSWORD}
    JWT_SECRET: ${JWT_SECRET}
    # --- ETL invocado desde el backend (botón y @Scheduled) ---
    ETL_SCRIPTS_PATH: /etl
    DWH_ETL_PATH: /etl
    DWH_PYTHON_PATH: python          # ← lo que faltaba: en la imagen NO existe `py`
    ETL_PYTHON_PATH: python
    ETL_PG_HOST: postgres            # ← ahora sí alcanzable
    ETL_PG_PORT: "5432"
    ETL_PG_DATABASE: retailmind
    ETL_PG_USER: retailmind_etl
    ETL_PG_PASSWORD: ${PG_ETL_PASSWORD}
    ETL_CH_HOST: clickhouse
    ETL_CH_PORT: "8123"
    ETL_CH_DATABASE: retailmind_dwh
    ETL_CH_USER: ${CH_USER}
    ETL_CH_PASSWORD: ${CH_PASSWORD}
    DWH_CRON: ${DWH_CRON:-0 0 2 * * *}   # `-` lo desactiva (cuando mande Airflow, §7)
    DWH_ZONA: America/Guayaquil
    TZ: America/Guayaquil
  volumes: [./retailmind:/etl]
  ports: ["8080:8080"]
  depends_on:
    postgres:   { condition: service_healthy }
    clickhouse: { condition: service_started }   # ← a propósito NO service_healthy
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:8080/api/health | grep -q '\"status\":\"UP\"'"]
    interval: 15s   timeout: 10s   retries: 5   start_period: 90s
  networks: [retailmind]
```

Las decisiones que importan:

- **`depends_on: postgres: service_healthy`** — el requisito explícito: el backend no arranca antes
  que su base. Con `start_period: 90s` en su propio healthcheck para no marcarse enfermo mientras
  Spring levanta.
- **`clickhouse: service_started`, deliberadamente no `service_healthy`.** El invariante del sistema
  es que **con ClickHouse apagado todo funciona** y solo la analítica degrada. Condicionar el
  arranque a la salud de ClickHouse lo convertiría en dependencia dura y contradiría el diseño (y el
  trabajo de `ClickHouseConfig` con `initializationFailTimeout=-1` y timeouts de 3 s).
- **El healthcheck usa `/api/health` con `grep '"status":"UP"'`**, no el código HTTP: el endpoint
  responde 200 también cuando PostgreSQL está caído, con `status: DOWN`. Comprobar el cuerpo es lo
  que hace útil el probe. `wget` y `grep` existen en la imagen (busybox de Alpine); es `permitAll`,
  así que no necesita token.
- **El montaje `./retailmind:/etl` se conserva**: es lo que permite que el botón «Actualizar
  almacén» y el `@Scheduled` funcionen dentro del contenedor. Con `DWH_PYTHON_PATH=python` y las
  `ETL_PG_*` apuntando a `postgres`, **por primera vez el pipeline completo corre dentro de Docker**.
- **Las `ETL_*` se pasan por entorno aunque `retailmind/.env` esté montado**: las variables de
  proceso ganan a `load_dotenv()` solo si `load_dotenv` no sobreescribe — y `conexiones.py` llama a
  `load_dotenv(...)` sin `override=True`, así que **el entorno del contenedor manda**. Verificado en
  `etl/dwh/conexiones.py`. Es la razón por la que este esquema funciona sin editar el `.env`.

### 5.4 `frontend`

```yaml
frontend:
  build: ./retailmind-frontend
  ports: ["4200:80"]
  depends_on:
    backend: { condition: service_healthy }
  healthcheck: ["CMD-SHELL", "wget --spider -q http://localhost/ || exit 1"]
  networks: [retailmind]
```

Sin cambios salvo la condición del `depends_on`: nginx resuelve `backend` al arrancar y, si el
contenedor aún no existe, falla el arranque del propio nginx. Esperar a `service_healthy` elimina esa
carrera y garantiza que la primera pantalla que vea el público ya responda.

### 5.5 `etl` — mismo servicio, propósito nuevo

```yaml
etl:
  build: ./retailmind
  profiles: ["tools"]              # ← NO arranca con `up`: se invoca a demanda
  environment: ETL_PG_* → postgres ; ETL_CH_* → clickhouse ; TZ: America/Guayaquil
  volumes: [./retailmind:/app]
  networks: [retailmind]
  # sin `command`: se usa como
  #   docker compose run --rm etl python -m etl.dwh.run_etl
  #   docker compose run --rm etl python -m etl.dwh.validar_dwh
```

Deja de ser un contenedor ocioso con `tail -f /dev/null` y pasa a ser **el ejecutor puntual del
pipeline**, ya conectado a las dos bases. Es además **el ensayo del operador de Airflow**: cada tarea
del DAG será exactamente uno de estos comandos (§7). Bajo el perfil `tools` no consume memoria en la
feria.

### 5.6 `pgadmin` — opcional, perfil `tools`

Solo si en la feria hace falta enseñar el esquema, los roles o las políticas RLS sin abrir una
terminal. `dpage/pgadmin4`, puerto 5050, perfil `tools`. Prescindible.

### 5.7 Servicios eliminados

| Servicio | Por qué se va |
|---|---|
| `pocketbase` | Origen del pipeline de la etapa anterior. Ninguna pieza operativa lo usa; solo lo tocan 3 botones de la pantalla `/inicializacion`, que alimentan la base **legada** de ClickHouse. Ver §2.1 para la decisión sobre esa pantalla. |
| `etl` **en su forma actual** | El contenedor ocioso desaparece; el servicio se reutiliza como ejecutor bajo perfil (§5.5). |

### 5.8 Modo desarrollo vs modo demo

El compose sirve los dos modos **con perfiles**, sin duplicar archivos:

| | Servicios que arrancan | Comando |
|---|---|---|
| **Desarrollo** (backend y frontend en el anfitrión, iteración rápida) | `postgres`, `clickhouse` | `docker compose up -d` |
| **Demo / feria** | los cuatro | `docker compose --profile demo up -d --build` |
| **Herramientas** | `etl`, `pgadmin` a demanda | `docker compose --profile tools run --rm etl …` |

`backend` y `frontend` llevan `profiles: ["demo"]`; `postgres` y `clickhouse` no llevan perfil, así
que arrancan siempre. Para que **la feria sea literalmente un comando**, el `.env` fija
`COMPOSE_PROFILES=demo` y entonces `docker compose up -d` levanta todo; quien desarrolle usa
`docker compose up -d postgres clickhouse`.

En modo desarrollo el backend local se conecta a `localhost:5433` (o 5432 tras el corte) y a
`localhost:8123`, que es justo lo que ya publica el compose. **Requiere corregir el defecto de
`application.properties`**: hoy el valor por defecto de ClickHouse es la IP de WSL `172.29.94.38`
(§3.2); debe ser `localhost`.

### 5.9 Qué debe verse al terminar

```
$ docker compose up -d
[+] Running 6/6
 ✔ Network retailmind_net       Created
 ✔ Volume  retailmind_pg_data   Created
 ✔ Container retailmind-postgres-1    Healthy
 ✔ Container retailmind-clickhouse-1  Healthy
 ✔ Container retailmind-backend-1     Healthy
 ✔ Container retailmind-frontend-1    Started

$ curl -s http://localhost:8080/api/health
{"postgres":"UP (retailmind_app)","clickhouse":"UP","database":"UP","python":"UP",
 "status":"UP","analytics":"UP"}
```

Criterio de aceptación de la feria: **`docker compose ps` con los cuatro en `healthy`/`running`**,
`http://localhost:4200` sirviendo el login, y `/api/health` con `postgres: UP (retailmind_app)` —
ese `retailmind_app` entre paréntesis es la prueba de que la app **no** está conectada como
superusuario.

---

## 6. Gestión de credenciales

**El problema, verificado**: `.env` y `retailmind/.env` **están versionados** (`git ls-files` los
lista) y `.gitignore` no los excluye. Entre los dos hay siete secretos en claro, incluida la
contraseña del **superusuario** del clúster PostgreSQL y el secreto de firma del JWT. Además
`application.properties` lleva como valores por defecto la contraseña de `retailmind_app` y el
propio `jwt.secret`.

**Propuesta en tres capas, ninguna de las cuales rompe nada hoy**:

1. **Sacar los `.env` del control de versiones y dejar plantillas.**
   ```
   git rm --cached .env retailmind/.env        # deja los archivos en disco, los quita del índice
   echo -e ".env\nretailmind/.env" >> .gitignore
   ```
   Y versionar `.env.example` / `retailmind/.env.example` con las **claves sin valores** y un
   comentario por línea. Quien clone copia y rellena. Coste: cero riesgo, cinco minutos.
   *Aviso honesto*: esto **no borra los secretos del historial** — siguen recuperables en los commits
   anteriores. Reescribir el historial de un repositorio académico no compensa; lo que sí compensa es
   el punto 3.

2. **Vaciar los valores por defecto peligrosos de `application.properties`.** Que la aplicación
   **falle al arrancar** si falta la contraseña es mejor que arrancar con la de producción
   incrustada:
   ```properties
   postgres.datasource.password=${POSTGRES_DATASOURCE_PASSWORD}
   jwt.secret=${JWT_SECRET}
   clickhouse.datasource.url=${CLICKHOUSE_DATASOURCE_URL:jdbc:ch://localhost:8123/retailmind?compress=0}
   ```
   (El tercero, de paso, elimina la IP de WSL.) Estos son cambios de código; quedan fuera del alcance
   de solo-lectura de esta tarea y se proponen para la implementación.

3. **Rotar lo expuesto, después de la migración y antes de la feria.** La ventaja del corte a
   contenedor es que **es el momento natural para cambiar contraseñas**: la base nueva se crea con
   las contraseñas nuevas y no hay que tocar la vieja. Tres contraseñas a rotar: `postgres` (nueva y
   solo en el secreto de Docker), `retailmind_app` y `retailmind_etl`. El `jwt.secret` se rota
   generando 64 bytes aleatorios; el único efecto es que los tokens vigentes caducan, lo que en una
   demostración es irrelevante.

4. **Secretos de Docker para lo que la imagen soporta.** La imagen oficial de PostgreSQL admite
   `POSTGRES_PASSWORD_FILE`, así que la contraseña del superusuario **no necesita pasar por una
   variable de entorno** (que es visible en `docker inspect` y en `docker compose config`):
   ```yaml
   secrets:
     pg_superuser: { file: ./deploy/secrets/pg_superuser.txt }   # ./deploy/secrets/ en .gitignore
   ```
   Spring Boot no lee `*_FILE`, así que `PG_APP_PASSWORD`, `JWT_SECRET` y las de ClickHouse siguen
   viajando por `.env` **no versionado**. Es una mejora parcial y conviene decirlo como tal: cierra
   el secreto de mayor privilegio y deja los demás en un archivo que ya no está en git.

---

## 7. Qué deja previsto este compose para Airflow

El diseño del ETL (`docs/estrategico/DISENO_ETL_CLICKHOUSE.md` §7.2) estima **de 5 a 9 contenedores
y ~1,5–2 GB adicionales**, y señala como mayor fricción precisamente «PostgreSQL corre LOCAL, fuera
del compose». **Contenerizar PostgreSQL elimina esa fricción por completo**: se ahorran
`host.docker.internal`/`extra_hosts`, la edición de `listen_addresses` y la línea en `pg_hba.conf`.
Lo que este compose debe dejar servido para que Airflow encaje sin rehacerlo:

| Pieza | Qué se deja previsto | Por qué |
|---|---|---|
| **Red** | `networks.retailmind.name: retailmind_net`, nombre **fijo** e independiente del nombre de la carpeta | El compose de Airflow se escribe aparte (`docker-compose.airflow.yml`) y se engancha con `networks: {retailmind_net: {external: true}}`. Sin nombre fijo habría que adivinar el prefijo del proyecto. |
| **Acceso a PostgreSQL** | Servicio `postgres` en esa red, con `retailmind_etl` ya creado (LOGIN, BYPASSRLS, solo lectura) | La *Airflow Connection* `retailmind_pg` será `postgresql://retailmind_etl@postgres:5432/retailmind`. Nada más que hacer. **BYPASSRLS es obligatorio**: `pol_horario` está declarada con `cmd = ALL` (incluye SELECT), así que un DAG nocturno con cualquier `grp_*` recibiría **cero filas sin error** y publicaría 21 tablas vacías. |
| **Acceso a ClickHouse** | Servicio `clickhouse` en la misma red, puerto 8123 | *Connection* `retailmind_ch` → `clickhouse:8123/retailmind_dwh`. |
| **Metadatos de Airflow** | **Un contenedor `airflow-postgres` propio**, nunca la base `retailmind` | Airflow escribe intensamente su metadata; mezclarla con la base del negocio contamina los respaldos, los conteos de tablas y las verificaciones del §9. El diseño ya lo asume. |
| **El código del pipeline** | El servicio `etl` bajo perfil `tools`, con el montaje `./retailmind:/app` y todas las `ETL_*` ya resueltas | **Cada tarea del DAG es un `BashOperator` de una línea**: `python -m etl.dwh.cargar --tabla fact_pedido`. Si el comando funciona hoy en `docker compose run --rm etl …`, funciona mañana en Airflow. La imagen `retailmind/Dockerfile` es la base natural del *worker*. |
| **Puertos** | 8080 (backend), 4200 (frontend), 8123/9000 (CH), 5432/5433 (PG) ocupados | Airflow debe publicar su interfaz en **8081**, no en 8080. Conviene anotarlo ya. |
| **Zona horaria** | `TZ: America/Guayaquil` en todos los servicios | Airflow por defecto va en UTC: sin `AIRFLOW__CORE__DEFAULT_TIMEZONE=America/Guayaquil` el DAG de las 02:00 corre a las 21:00, el mismo error que `DwhProgramacion` documenta para `@Scheduled`. |
| **Doble programación** | `DWH_CRON` como variable de entorno del backend, con `-` como valor de apagado | **El día que Airflow tome el relevo hay que poner `DWH_CRON=-`.** Si no, a las 02:00 disparan los dos y dos cargas concurrentes compiten por el `EXCHANGE TABLES` del mismo destino. Que sea una variable —y no un valor fijo en `application.properties`— es lo que hace ese apagado un cambio de `.env` y no una recompilación. |

**Coste a presupuestar**: con `LocalExecutor` bastan 4 contenedores (`airflow-postgres`,
`airflow-init`, `airflow-scheduler`, `airflow-apiserver`) — el extremo bajo del rango del diseño.
Sumado a los 4 de RetailMind más PostgreSQL, la máquina tendría **8 contenedores y ~4 GB**. Para la
feria, **Airflow no debería arrancar por defecto**: perfil propio, o compose separado que se levanta
solo para enseñar el grafo del DAG.

---

## 8. Plan de implementación por pasos

La migración de PostgreSQL tiene su propio plan detallado en la §9; aquí va el orden global y **dónde
puede romperse cada paso**.

| # | Paso | Dónde puede romperse |
|---|---|---|
| 0 | **Anotar el estado de partida**: `docker volume ls`, `docker ps -a`, y `SELECT version()` de ClickHouse | Si el volumen `1m6datoscs_clickhouse_data` **no** se llama así, todo el §5.0 apunta a un volumen inexistente. **Verificar antes de escribir el compose.** |
| 1 | Crear `deploy/postgres/initdb/` con los tres archivos de la §9.4 y `deploy/secrets/` (en `.gitignore`) | Nada activo aún. |
| 2 | **Migrar PostgreSQL al contenedor** siguiendo la §9 completa, con el local intacto y el contenedor en el puerto **5433** | Es el paso de riesgo. La §9.5 lleva las once verificaciones. |
| 3 | Escribir el `docker-compose.yml` nuevo (§5) y `.env.example`; sacar los `.env` del índice de git | Si se olvida `external: true` en el volumen de ClickHouse, un despiste crea un volumen vacío y **se pierden las pantallas de analítica**: ese dato no es reproducible. |
| 4 | Construir las imágenes: `docker compose --profile demo build` | La imagen del backend compila `pyarrow`/`pandas` sobre Alpine: **es lenta** (decenas de minutos la primera vez). Hacerlo con antelación, no la víspera. Si se retira `pyarrow` y `pocketbase` del Dockerfile, baja mucho — pero eso **cambia el Dockerfile** y hay que volver a probar `/api/health` (`checkPythonRuntime`) y el botón del DWH. |
| 5 | Levantar en modo demo y comprobar `/api/health`, el login y una pantalla de cada nivel (operativo, informe simple, informe compuesto, tablero) | Si el compuesto degrada con `analiticaDisponible=false` y el simple funciona, el problema es ClickHouse (volumen o versión), no PostgreSQL. El sistema **está diseñado** para distinguir esos dos casos: úsalo como diagnóstico. |
| 6 | Ejecutar el pipeline dentro de Docker: `docker compose --profile tools run --rm etl python -m etl.dwh.run_etl` y después `validar_dwh` (**49 controles**) | Primera vez que el ETL corre dentro de un contenedor. Si `conexiones.py` toma `localhost` en vez de `postgres`, es que `load_dotenv` ganó al entorno — no debería (no usa `override=True`), pero se ve en el primer error de conexión. |
| 7 | Corregir el defecto de la IP de WSL en `application.properties` y probar **el modo desarrollo** (backend y frontend fuera de Docker contra los contenedores) | Es el modo que usa el desarrollo diario; romperlo por arreglar la feria sería un mal negocio. |
| 8 | Apagar el PostgreSQL local, remapear el contenedor a **5432** y repetir la comprobación del paso 5 | Único paso irreversible en el sentido práctico. Solo tras las once verificaciones. |
| 9 | Ensayo completo desde cero: `docker compose down` + `docker compose up -d` cronometrado | Mide el tiempo de arranque real para la feria. Si el backend tarda más que su `start_period`, se marca *unhealthy* y el frontend no arranca: ajustar `start_period`, no quitar el healthcheck. |
| 10 | (Después) Airflow, según la §7 | — |

**Regla de oro del calendario**: los pasos 2 y 4 son los caros. Ninguno de los dos debe hacerse el
día anterior a la feria.

---

## 9. Plan de migración de PostgreSQL a contenedor

### 9.1 Inventario verificado de lo que hay que llevar

Todo lo siguiente está medido contra la base `retailmind` en vivo el 2026-08-03.

| Elemento | Cantidad real | ¿Viaja en `pg_dump` **de la base**? | Cómo se lleva si no viaja |
|---|---|---|---|
| **Motor** | PostgreSQL **18.3** (Windows) | — | Imagen `postgres:18`. Un dump de 18 **no** restaura en 17. |
| **Encoding / locale** | UTF8, `Spanish_Ecuador.1252`, provider **libc** | El `--create` lo emite y **FALLA** en Debian | Crear la base con `--locale-provider=icu --icu-locale=es-EC` y restaurar **sin** `--create`. |
| **Extensiones** | `plpgsql 1.0`, `pgcrypto 1.4` | **Sí** (`CREATE EXTENSION`) | Ambas vienen en la imagen oficial (`postgresql-contrib`). Nada que hacer. |
| **Tablas** | **110** (89 con filas), **119.730 filas**, **95 MB** | **Sí** | — |
| **Índices** | 379 | **Sí** (se reconstruyen al restaurar) | — |
| **Vistas / vistas materializadas / tipos propios / colaciones propias / *large objects* / *event triggers* / publicaciones / *tablespaces*** | **0 de cada uno** | — | **Nada que migrar.** Simplifica mucho: el dump es esquema + datos y punto. |
| **Columnas GENERATED** | **4** (`subtotal` en `pedido_detalle`, `orden_compra_detalle`, `factura_venta_detalle`, `factura_compra_detalle`) | **Sí**, la definición. Los **valores no se copian: se recalculan** | Es el comportamiento correcto. Se verifica comparando sumas (§9.5-V6). |
| **Triggers (no internos)** | **90** | **Sí** | — |
| **Funciones en `public`** | **57**, de ellas **13 `SECURITY DEFINER`** | **Sí**, cuerpo y `GRANT EXECUTE` | **Pero el propietario decide la elevación**: las 13 pertenecen a `postgres`. Hay que **restaurar como `postgres`** o la elevación cambia de identidad. Ver §9.5-V7. |
| **Secuencias** | **110** (109 ligadas a columnas + **`seq_numero_documento`, independiente, `last_value = 114021`**) | **Sí**, con su `setval` | El `setval` va en el dump de datos: restaurar **esquema y datos juntos**, no solo el esquema. |
| **Tablas con RLS habilitado** | **50** (`FORCE ROW LEVEL SECURITY`: 0) | **Sí** (`ALTER TABLE … ENABLE ROW LEVEL SECURITY`) | — |
| **Políticas RLS** | **95**, sobre esas 50 tablas | **Sí** (`CREATE POLICY`) | **Nombran roles**: si los roles no existen al restaurar, el `CREATE POLICY` **falla**. Roles primero. |
| **GRANTs de tabla a `grp_*`** | **1.354** | **Sí** | Ídem: roles primero. |
| **GRANTs de COLUMNA** | **109 columnas con ACL propia, en 14 tablas** (la segregación financiera del script 41) | **Sí** (`GRANT SELECT(columna) …`) | Ídem. *(Ojo con la métrica: `information_schema.column_privileges` devuelve 9.392 porque expande también los permisos heredados de la tabla; la cifra real de ACL a nivel de columna es **109**, leída de `pg_attribute.attacl`. Verificar con la consulta correcta o se «comprueba» un número que no significa nada.)* |
| **GRANT USAGE ON SCHEMA public** | **11** (los 9 `grp_*` + `retailmind_app` + `retailmind_etl`) | **Sí** | Crítico: el script 19 lo **revocó a PUBLIC**, así que sin estos GRANT ningún rol ve nada. |
| **`DEFAULT PRIVILEGES`** | **0** | — | Nada que migrar. |
| **ROLES** | **11**: 9 `grp_*` (NOLOGIN, INHERIT) + `retailmind_app` (**LOGIN, NOINHERIT**) + `retailmind_etl` (**LOGIN, BYPASSRLS**) | **NO — son objetos de CLÚSTER** | `pg_dumpall --roles-only`, **filtrado** (§9.2). |
| **Membresías de rol** | **9**: `retailmind_app` es miembro de los 9 `grp_*` | **NO** (van con los roles) | En el mismo script de roles. Sin ellas, `SET LOCAL ROLE` falla y **toda** la aplicación devuelve 403. |
| **Configuración por rol** | **`ALTER ROLE retailmind_etl SET default_transaction_read_only=on, search_path=public`** (en `pg_db_role_setting`) | **NO** | En el script de roles. Es una de las cuatro capas de solo-lectura del ETL, y su pérdida **no da ningún error**. |
| **Contraseñas de rol** | 2 roles con contraseña (`retailmind_app`, `retailmind_etl`) | **NO** | Se recrean explícitamente (y es la ocasión de **rotarlas**, §6.3). |
| **Propietario de todo lo de `public`** | `postgres` en las 110 tablas y en las 57 funciones | **Sí** si se restaura como `postgres` | Restaurar **siempre** como `postgres`. |

**Lo que NO viaja en un `pg_dump` de base, en una frase**: los **11 roles**, sus **9 membresías**,
sus **contraseñas** y el **`ALTER ROLE … SET`** del rol del ETL. Todo lo demás —incluidas las 95
políticas, los 1.354 GRANT de tabla y los 109 GRANT de columna— **sí viaja**, pero **solo se aplica
si los roles ya existen**. De ahí que el orden de restauración no sea negociable.

### 9.2 Qué se exporta, exactamente

Desde el anfitrión, con `pg_dump` **18.3** (verificado en `C:\Program Files\PostgreSQL\18\bin\`):

```bash
cd deploy/postgres/initdb

# (1) Roles del CLÚSTER — hay que FILTRAR: el clúster tiene 11 roles con LOGIN y
#     8 son de otras materias (ElToke, Darinxxo, jefe_ventas, aux_tthh, …).
pg_dumpall -h localhost -p 5432 -U postgres --roles-only > /tmp/roles_todos.sql
#     De ese archivo se conservan SOLO los 11 bloques de RetailMind. Recomendación:
#     no editarlo a mano y en su lugar versionar un 00_roles.sql escrito explícitamente
#     (§9.4) — es reproducible, se revisa en un diff y no arrastra nada ajeno.

# (2) La base, SIN --create (el locale de Windows no existe en Debian) y SIN
#     --no-owner (queremos que los objetos queden de `postgres`).
pg_dump -h localhost -p 5432 -U postgres -d retailmind \
        --format=custom --file=01_retailmind.dump
```

**Por qué `--format=custom`**: permite `pg_restore --jobs`, permite listar el contenido
(`pg_restore -l`) antes de aplicarlo y falla ruidosamente en vez de continuar. **Por qué no
`--no-owner`**: las 13 funciones `SECURITY DEFINER` se ejecutan **con los privilegios de su
propietario**; cambiarlo cambia la seguridad del sistema.

**Lo que NO hay que exportar**: `pg_dumpall` completo (arrastraría las otras 11 bases del clúster) y
`pg_dumpall --globals-only` sin filtrar (arrastraría los roles de las otras materias).

### 9.3 La trampa del locale, explicada

```
local:      datcollate = 'Spanish_Ecuador.1252',  datlocprovider = 'c'   (libc de Windows)
contenedor: Debian — ese locale NO EXISTE
```

Un `pg_dump --create` emitiría `CREATE DATABASE retailmind … LC_COLLATE = 'Spanish_Ecuador.1252'` y
`pg_restore` fallaría con «invalid locale name», un mensaje que no sugiere la causa. **Solución
adoptada**: la base la crea el *entrypoint* de la imagen con
`--locale-provider=icu --icu-locale=es-EC --locale=C.UTF-8`, y se restaura **sin** `--create`.

**Consecuencia que hay que aceptar y verificar**: el orden de `ORDER BY` sobre texto cambia
ligeramente (tratamiento de acentos y mayúsculas entre la colación de Windows y ICU `es-EC`). No
afecta a la unicidad —ambas colaciones son deterministas, así que ningún índice único cambia de
comportamiento— ni a ninguna comparación de igualdad. Solo cambia el orden de presentación en
listados alfabéticos. Se comprueba en V9.

### 9.4 Orden de restauración

`deploy/postgres/initdb/` — el *entrypoint* de la imagen ejecuta su contenido **en orden
lexicográfico** y **solo la primera vez** (volumen vacío):

```
deploy/postgres/initdb/
├── 00_roles.sql          los 11 CREATE ROLE + 9 GRANT de membresía + ALTER ROLE … SET
├── 01_retailmind.dump    el dump custom de la base (no lo lee el entrypoint: lo aplica el .sh)
└── 02_restaurar.sh       pg_restore del dump anterior
```

`00_roles.sql`, escrito explícitamente (no generado), con este contenido:

```sql
-- 9 roles de grupo: NOLOGIN, INHERIT (los asume retailmind_app por transacción)
CREATE ROLE grp_administrador NOLOGIN INHERIT;
CREATE ROLE grp_gerente       NOLOGIN INHERIT;
CREATE ROLE grp_vendedor      NOLOGIN INHERIT;
CREATE ROLE grp_compras       NOLOGIN INHERIT;
CREATE ROLE grp_bodega        NOLOGIN INHERIT;
CREATE ROLE grp_despacho      NOLOGIN INHERIT;
CREATE ROLE grp_cliente       NOLOGIN INHERIT;
CREATE ROLE grp_analista      NOLOGIN INHERIT;
CREATE ROLE grp_soporte       NOLOGIN INHERIT;

-- Rol de la aplicación: NOINHERIT es el punto entero del diseño — tiene los roles
-- pero NO sus privilegios hasta que hace SET LOCAL ROLE.
CREATE ROLE retailmind_app LOGIN NOINHERIT PASSWORD '…';
GRANT grp_administrador, grp_gerente, grp_vendedor, grp_compras, grp_bodega,
      grp_despacho, grp_cliente, grp_analista, grp_soporte TO retailmind_app;

-- Rol del ETL: LOGIN + BYPASSRLS + solo lectura por CUATRO capas.
CREATE ROLE retailmind_etl LOGIN BYPASSRLS NOINHERIT PASSWORD '…';
ALTER ROLE retailmind_etl SET default_transaction_read_only = on;   -- ← NO viaja en el dump
ALTER ROLE retailmind_etl SET search_path = public;                 -- ← NO viaja en el dump
```

`02_restaurar.sh`:

```sh
#!/bin/sh
set -e
pg_restore --username "$POSTGRES_USER" --dbname retailmind --no-create --exit-on-error \
           --verbose /docker-entrypoint-initdb.d/01_retailmind.dump
```

> **Atención**: `--exit-on-error` es obligatorio. Sin él, `pg_restore` informa de los errores y
> **continúa**, y el resultado más probable de un fallo de orden es una base con datos pero sin
> políticas o sin GRANT — que arranca, sirve el login de admin y tiene la seguridad rota. El
> `set -e` del script hace que el contenedor muera y el fallo sea visible.

> **Segunda atención**: **jamás** montar `retailmind/sql/postgres/` como `initdb.d`. Son 93 scripts
> y 8 de ellos son `99_revert_*.sql`; el orden lexicográfico los ejecutaría **al final**, deshaciendo
> la siembra.

### 9.5 Las verificaciones que deben pasar ANTES de apagar el local

Todas se ejecutan contra el **contenedor** (puerto 5433) y se comparan con el **local** (5432), que
sigue vivo. Sugerencia: un `deploy/verificar_migracion.sql` que se lanza contra los dos y se
diferencian las salidas — **`diff` de dos salidas es mejor prueba que leer dos números**.

| # | Qué se verifica | Consulta / prueba | Resultado exigido |
|---|---|---|---|
| **V1** | **Roles** | `SELECT rolname, rolcanlogin, rolinherit, rolbypassrls FROM pg_roles WHERE rolname LIKE 'grp\_%' OR rolname LIKE 'retailmind%' ORDER BY 1` | **11 filas idénticas** al local. En particular `retailmind_app` con `rolinherit = false` y `retailmind_etl` con `rolbypassrls = true`. |
| **V2** | **Membresías** | `SELECT roleid::regrole, member::regrole FROM pg_auth_members WHERE member::regrole::text = 'retailmind_app'` | **9 filas.** Si faltan, la aplicación devuelve 403 en todo. |
| **V3** | **Configuración por rol** | `SELECT setconfig FROM pg_db_role_setting s JOIN pg_roles r ON r.oid=s.setrole WHERE r.rolname='retailmind_etl'` | `{default_transaction_read_only=on, search_path=public}`. **Es el elemento que más fácil se pierde y el que menos ruido hace al perderse.** |
| **V4** | **RLS y políticas** | `SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity` y `SELECT count(*) FROM pg_policies WHERE schemaname='public'` | **50** y **95**. Además: `SELECT tablename, policyname FROM pg_policies ORDER BY 1,2` debe dar **diff vacío** contra el local. |
| **V5** | **GRANTs por COLUMNA** | `SELECT c.relname, a.attname, a.attacl FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND a.attacl IS NOT NULL AND NOT a.attisdropped ORDER BY 1,2` | **109 filas en 14 tablas**, y el **diff con el local vacío**. Es la segregación financiera: Bodega y Despacho sin columnas de dinero. |
| **V6** | **Datos, filas y GENERATED** | Conteo por tabla sobre las 110 (`query_to_xml`) + `SELECT sum(subtotal) FROM pedido_detalle` y las otras tres tablas con columna generada | **110 tablas, 89 con filas, 119.730 filas** y las cuatro sumas de `subtotal` **idénticas al centavo**. Las columnas GENERATED se recalculan en la restauración: si la suma coincide, se recalcularon bien. |
| **V7** | **Funciones `SECURITY DEFINER` y su propietario** | `SELECT proname, prosecdef, pg_get_userbyid(proowner), proacl FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND prosecdef ORDER BY 1` | **13 filas**, todas con `proowner = postgres`, y los ACL idénticos: `esta_en_horario` ejecutable por `retailmind_app`, `fn_registrar_intento_acceso` por `retailmind_app` y `grp_administrador`, `fn_registrar_intento_pago_fallido` por `grp_cliente`, `fn_upsert_producto_proveedor` por compras/bodega/gerente/admin, `fn_siguiente_numero_ticket` por soporte/cliente/gerente/admin. |
| **V8** | **Secuencias** | `SELECT last_value FROM seq_numero_documento` + `SELECT count(*) FROM pg_class WHERE relkind='S'` | **`114021` o mayor** (mayor si el local siguió operando entre el dump y la comprobación) y **110** secuencias. Un `last_value = 1` significa que se restauró solo el esquema: los siguientes documentos chocarían con los existentes. |
| **V9** | **Extensiones, esquema y colación** | `SELECT extname, extversion FROM pg_extension`; `SELECT nspacl FROM pg_namespace WHERE nspname='public'`; `SELECT datcollate, datlocprovider FROM pg_database WHERE datname='retailmind'` | `plpgsql`+`pgcrypto`; **11 GRANT USAGE** en el ACL del esquema; provider `i` con `es-EC` (cambio **esperado y aceptado**, §9.3). |
| **V10** | **Prueba funcional del motor con tres roles** (sin pasar por la aplicación) | En el contenedor, como `retailmind_app`: `BEGIN; SET LOCAL ROLE grp_bodega; SELECT total FROM pedido LIMIT 1;` → debe dar **error de privilegio (42501)**. `BEGIN; SET LOCAL ROLE grp_cliente; SELECT count(*) FROM pedido;` sin `app.cliente_id` → debe dar **0** (RLS activa). `BEGIN; SET LOCAL ROLE grp_gerente; SELECT count(*) FROM pedido;` → **4.083**. | Los tres. El segundo es la prueba determinista de RLS **a cualquier hora**, sin depender del reloj. |
| **V11** | **Prueba funcional de la APLICACIÓN con tres roles** | Apuntar el backend (local, en el puerto 8081 para no chocar) al contenedor y entrar por la interfaz con: **(a) `admin@retailmind.com`** → un informe simple y un tablero; **(b) `bodega@retailmind.com`** → la cola de preparación **debe verse sin columnas de importe** y `/informes/inventario/capital-inmovilizado` debe dar **403**; **(c) `maria.lopez@demo.com`** (CLIENTE) → «Mis Pedidos» **solo los suyos**, y el catálogo con precios. | Los tres, con el mismo comportamiento que contra el local. **Ojo con el horario**: bodega, despacho y compras tienen restricción por franja; si la prueba cae fuera de su horario el 403 puede venir de ahí y no de la migración. Comprobar `grupo_horario` antes de concluir. |

**El punto exacto en que la migración se da por buena**: cuando **V1 a V11 pasan las once**, con los
*diffs* de V4, V5 y V7 vacíos y la prueba V11 hecha con **tres roles reales por la interfaz**. Ni
antes, ni «con V10 basta»: V10 prueba el motor, V11 prueba que la aplicación llega a él con la
identidad correcta, y son fallos distintos.

### 9.6 El corte, y la red de seguridad

```
Fase A — CONVIVENCIA (el local manda)
  local :5432  ← backend, ETL, MCP        contenedor :5433  ← solo las verificaciones
  Coste de dar marcha atrás: apagar el contenedor. CERO impacto.

Fase B — CORTE (tras V1…V11 en verde)
  1. Detener el backend.
  2. Detener el servicio «postgresql-x64-18» de Windows  (services.msc — NO desinstalar).
  3. Remapear el contenedor a 5432:5432 y `docker compose up -d`.
  4. Repetir V10 y V11 contra el puerto 5432.
  5. Actualizar el MCP `retailmind` y `retailmind/.env` a la nueva contraseña, si se rotó.

Fase C — RETIRADA (una semana después de la feria, no antes)
  Desinstalar el PostgreSQL local sólo cuando ya no haga falta como plan B.
  Antes: guardar un `pg_dump` fechado fuera del repositorio.
```

**Marcha atrás durante la feria**, si algo falla en vivo: volver a arrancar el servicio de Windows,
apagar el contenedor de `postgres` y lanzar el backend local. Un minuto, siempre que **el
PostgreSQL local siga instalado** — de ahí la Fase C tardía.

**Detalle a no olvidar**: el corte deja **desfasada la copia local** desde el instante en que el
contenedor empieza a recibir escrituras. Si hubiera que volver atrás **después** de haber demostrado
pedidos en la feria, esos pedidos se quedan en el contenedor. Por eso el corte se hace **antes** de
la feria y no durante, y por eso conviene un `pg_dump` del contenedor la mañana del evento.

---

## Apéndice — comandos de verificación de una línea

```bash
# Estado general
docker compose ps
curl -s http://localhost:8080/api/health

# ¿El sistema aguanta sin ClickHouse? (invariante del diseño)
docker compose stop clickhouse && curl -s http://localhost:8080/api/health   # status UP, analytics DEGRADED
docker compose start clickhouse

# Pipeline completo dentro de Docker
docker compose --profile tools run --rm etl python -m etl.dwh.run_etl
docker compose --profile tools run --rm etl python -m etl.dwh.validar_dwh      # 49 controles

# La comprobación que más rápido detecta una migración a medias
docker compose exec postgres psql -U postgres -d retailmind -c \
  "BEGIN; SET LOCAL ROLE grp_cliente; SELECT count(*) FROM pedido;"            # debe dar 0
```
