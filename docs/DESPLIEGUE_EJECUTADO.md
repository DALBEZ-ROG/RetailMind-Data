# Despliegue de RetailMind — lo EJECUTADO el 2026-08-03

> **Naturaleza**: bitácora de ejecución. Este documento cuenta lo que se **hizo**, no lo que se
> planeó. El plan vive en `docs/DESPLIEGUE_DISENO.md` (ya corregido con los hallazgos de aquí).
>
> **Estado**: la contenerización está **COMPLETA**. PostgreSQL migrado, verificado y **cortado**
> (el contenedor sirve en el 5432; el local pasó al 5433). El compose objetivo está escrito,
> construido y probado de punta a punta. Credenciales rotadas.
>
> **NO se ha hecho `git commit`.** Todo está en el directorio de trabajo.
>
> **Este archivo NO contiene ninguna contraseña.** Se dice dónde vive cada una.

---

## 1. Cómo queda la máquina

| Puerto | Quién | Contenido |
|---|---|---|
| **5432** | Contenedor Docker (PostgreSQL **18.4** Debian) | `retailmind` — **la base VIVA** |
| **5433** | PostgreSQL local (**18.3** Windows, servicio `postgresql-x64-18`) | 12 bases de otras materias + copia congelada de `retailmind` |
| 8080 | `retailmind-backend-1` | API |
| 4200 | `retailmind-frontend-1` | Angular por nginx |
| 8123 / 9000 | `retailmind-clickhouse-1` | Analítica (`retailmind` legada + `retailmind_dwh`) |

```powershell
docker compose up -d           # FERIA: los 4 servicios, LISTO EN 28 s
docker compose up -d --build   # tras cambiar código Java o Angular
docker compose up -d postgres clickhouse   # DESARROLLO: solo base y analítica
docker compose stop backend frontend       # libera 8080 y 4200 para el dev local
```

`.env` lleva `COMPOSE_PROFILES=demo`, por eso `up -d` a secas levanta los cuatro.

**Marcha atrás** (un minuto, sigue disponible): `docker compose down`, devolver
`postgresql.conf` a `port = 5432` (hay copia en `postgresql.conf.bak_antes_del_corte`),
`Restart-Service postgresql-x64-18`, y arrancar el backend a mano.

---

## 2. Lo que se ejecutó, en orden

1. **Migración de PostgreSQL al contenedor** siguiendo §9 del diseño: `00_roles.sql` escrito
   explícitamente (11 roles + 9 membresías + los dos `ALTER ROLE … SET` del ETL),
   `pg_dump --format=custom` **sin `--create`** y **sin `--no-owner`**, restauración como
   `postgres` con `--exit-on-error`. **Las 11 verificaciones (V1–V11) pasaron.**
2. **`docker-compose.yml` definitivo**: 6 servicios, `pocketbase` eliminado, perfiles
   `demo`/`tools`, red `retailmind_net` fija, secreto de Docker para el superusuario.
3. **Re-migración limpia** desde un volcado fresco antes del corte, para eliminar la divergencia
   acumulada durante la convivencia.
4. **EL CORTE**: servicio de Windows detenido, contenedor remapeado a 5432, V10 y V11 repetidas
   contra el puerto nuevo.
5. **Reparto de puertos**: el PostgreSQL local se movió al **5433** (`postgresql.conf`) para que
   convivan. Las 12 bases de otras materias siguen vivas ahí.
6. **Corrección de `application.properties`**: fuera la IP de WSL y fuera los secretos por defecto.
7. **Rotación de credenciales** (las 4 internas; **NO** las de los usuarios).
8. **Limpieza de MCP**: 3 servidores muertos eliminados, 14 repuntados al 5433.

---

## 3. Credenciales — LO MÁS IMPORTANTE DE ESTE DOCUMENTO

### Lo que NO cambió: las claves de los usuarios del sistema

Los **10 usuarios entran exactamente igual que siempre** (verificado 10/10 tras la rotación):

- `admin@retailmind.com` / `Admin2026!`
- `gerente@`, `vendedor@`, `compras@`, `bodega@`, `despacho@`, `analista@`,
  `soporte@retailmind.com` / `Retail2026!`
- `maria.lopez@demo.com`, `carlos.vera@demo.com` / `Cliente2026!`

### Lo que SÍ se rotó: 4 secretos internos que nadie teclea

| Secreto | Dónde vive ahora |
|---|---|
| Superusuario `postgres` **del contenedor** | `deploy/secrets/pg_superuser.txt` (Docker secret) + MCP `retailmind` |
| Rol `retailmind_app` | `.env` → `PG_APP_PASSWORD` + `retailmind-backend/application-local.properties` |
| Rol `retailmind_etl` | `.env` → `PG_ETL_PASSWORD` + `retailmind/.env` → `ETL_PG_PASSWORD` |
| `jwt.secret` | `.env` → `JWT_SECRET` + `application-local.properties` |

Contraseñas de **28 caracteres alfanuméricos** (sin símbolos a propósito: un `@` o un `/` rompe las
cadenas `postgresql://usuario:clave@host`); el `jwt.secret`, 88.

**Excepción declarada**: el superusuario del **PostgreSQL local (5433) NO se rotó**, porque esa
contraseña la comparten los 12 MCP de otras materias. `retailmind_app` y `retailmind_etl` **sí** se
rotaron en las dos instancias, para que la marcha atrás siga funcionando con el mismo `.env`.

Los cuatro archivos con secretos están en `.gitignore` y **verificados como ignorados**:
`.env`, `retailmind/.env`, `deploy/secrets/`, `application-local.properties`.
`.env` y `retailmind/.env` salieron del índice con `git rm --cached` (siguen en disco).

---

## 4. Correcciones al diseño descubiertas EJECUTANDO

Estas son la parte con más valor: cosas que el diseño daba por otras y que solo aparecen al hacerlo.

1. **`postgres:18` cambió el punto de montaje.** El volumen va en **`/var/lib/postgresql`**, NO en
   `/var/lib/postgresql/data`. Desde la 18, `PGDATA=/var/lib/postgresql/18/docker`. Con la ruta
   antigua la imagen **detecta el volumen huérfano y se niega a arrancar** — fallo ruidoso y
   correcto, pero **el mensaje habla de `pg_upgrade` y no de la ruta**, así que despista.
2. **Un ACL que `pg_dump` no emite NO es un privilegio perdido.** Tres funciones SECURITY DEFINER
   (`fn_liberar_uso_cupon`, `fn_registrar_uso_cupon`, `fn_usuario_actual`) salen con `proacl`
   materializado en el origen y NULL en el destino: `pg_dump` omite el GRANT cuando el ACL coincide
   con el que la función tendría por defecto. Se probó con **`has_function_privilege`** (privilegio
   EFECTIVO, 13 funciones × 11 roles = 143 filas, diff vacío), no comparando el texto del ACL.
3. **Comparar catálogos entre dos motores exige `COLLATE "C"` en todo `ORDER BY` de texto.** El
   origen ordena con `Spanish_Ecuador.1252` (libc de Windows) y el destino con ICU `es-EC`: sin
   fijar la colación, el listado sale en otro orden y el diff acusa una diferencia inexistente.
   (Y `ORDER BY 1 COLLATE "C"` no compila: con un `COLLATE` detrás, el `1` se lee como el literal
   entero. En un `UNION`, el `ORDER BY` solo admite nombres de columna pelados.)
4. **Un healthcheck contra `localhost` DENTRO de un contenedor es una trampa.** `localhost` resuelve
   a `127.0.0.1` **y a `::1`**, el `wget` de busybox prueba IPv6 primero, y nginx con `listen 80;`
   solo escucha IPv4 → «Connection refused» eterno **aunque la página se sirva perfectamente**.
   El frontend quedaba `unhealthy` de forma permanente mientras funcionaba. **Siempre `127.0.0.1`**
   (los tres healthchecks se cambiaron; el del backend funcionaba con `localhost` solo porque
   Tomcat abre doble pila — suerte, no diseño).
5. **`log_acceso` es append-only y crece con las propias pruebas.** Un diff de conteos sobre esa
   tabla no prueba nada por sí solo: hay que separar lo histórico de lo que escribió la
   verificación.
6. **El volumen de ClickHouse va con `external: true`; el de PostgreSQL NO.** Exigencias opuestas:
   el de ClickHouse guarda un dato irreproducible (2.823.245 eventos) y debe fallar si falta; el de
   PostgreSQL es reproducible (dump + `initdb/`) y **tiene que crearse solo** en una máquina nueva,
   o un clon del repositorio no arrancaría jamás.
7. **Existe un volumen huérfano `1m6datoscs_postgres_data` con datos de PostgreSQL 15.** Es la razón
   de fondo del `name: retailmind_pg_data` explícito. No se tocó.
8. **Versión de ClickHouse fijada: `26.4.2.10`** (leída en vivo). Nunca `:latest` sobre un motor con
   formato en disco propio.

---

## 5. Archivos nuevos y modificados (sin commit)

**Nuevos**
```
deploy/postgres/docker-compose.migracion.yml   compose de la fase de convivencia (histórico)
deploy/postgres/initdb/00_roles.sql            11 roles + 9 membresías + ALTER ROLE del ETL
deploy/postgres/initdb/02_restaurar.sh         exige las claves por entorno y restaura
deploy/postgres/initdb/01_retailmind.dump      volcado (gitignored)
deploy/postgres/.env                           credenciales del compose de migración (gitignored)
deploy/secrets/pg_superuser.txt                secreto de Docker (gitignored)
deploy/verificar_migracion.sql                 V1–V9, diffable entre dos servidores
deploy/verificar_v11.sh                        V11, prueba funcional por API con 3 roles
.env.example                                   plantilla con las claves sin valores
docs/DESPLIEGUE_EJECUTADO.md                   este archivo
retailmind-backend/application-local.properties  secretos del modo dev (gitignored)
```

**Modificados**
```
docker-compose.yml                  reescrito: 6 servicios, perfiles, sin pocketbase
application.properties              fuera la IP de WSL y los secretos por defecto
.gitignore                          .env, secretos, __pycache__, application-local.properties
docs/DESPLIEGUE_DISENO.md           corregido con los 3 puntos + addendum fechado
```

**Respaldos fuera del repositorio** en `C:\Users\ASUS\Documents\RetailMind_Respaldos\`:
`retailmind_local_20260803_2025.dump` (antes de rotar) y
`retailmind_contenedor_20260803_2115.dump` (estado actual: 1.739 objetos, 95 políticas RLS,
150 tablas con datos, 35 funciones).

---

## 6. Verificaciones realizadas (todas con evidencia)

- **V1–V9** (catálogo): diff local↔contenedor **vacío** salvo las 3 representaciones de ACL del
  punto 4.2, probadas equivalentes. 11 roles · 9 membresías · `{default_transaction_read_only=on,
  search_path=public}` · 50 tablas con RLS · **95 políticas** · **109 columnas con ACL** en 14
  tablas · 110 tablas y 89 con filas · 4 sumas de columnas GENERATED idénticas al centavo ·
  13 funciones SECURITY DEFINER de `postgres` · `seq_numero_documento = 114021` · 110 secuencias ·
  `pgcrypto 1.4` + `plpgsql 1.0` · 90 triggers · 379 índices · **1.354 GRANT a `grp_*`**.
- **V10** (motor): `42501` para bodega sobre `pedido.total`, **0** para cliente sin
  `app.cliente_id` (RLS), **4.083** para gerente, y las 4 capas de solo-lectura del ETL en pie.
  Repetido **después** del corte y **después** de la rotación.
- **V11** (aplicación, por la interfaz): los **10 usuarios entran**; 4 pantallas operativas
  (ventas, inventario, compras, soporte); informe simple; informe compuesto; **los 7 tableros**;
  **los 2 modelos de IA**. Bodega **sin columnas de dinero** y 403 en `capital-inmovilizado`;
  cliente ve **sus 21 pedidos** (no 4.083) y el catálogo con 1.212 precios.
- **Invariante de ClickHouse**: con `docker compose stop clickhouse`, `/api/health` responde
  `status: UP` / `analytics: DEGRADED` **en 5 s acotados**; informes simples 200; compuestos y
  tableros **200 con `analiticaDisponible: false`**. Recuperación **sin reiniciar el backend**
  (`StartedAt` idéntico, `RestartCount = 0`).
- **ETL dentro de Docker, por primera vez**: `run_etl` publica **21 de 21 tablas / 66.079 filas** y
  `validar_dwh` da **49/49 controles**. Repetido tras la rotación.
- **Modo desarrollo**: sin `application-local.properties` el backend **se niega a arrancar**
  (`Could not resolve placeholder 'JWT_SECRET'`); con él, arranca y conecta.
- **Arranque en frío cronometrado**: `down` + `up -d` → **28 s** hasta los cuatro `healthy`.

---

## 7. QUÉ FALTA

### Importante

1. **`CLAUDE.md` está DESACTUALIZADO y hay que corregirlo.** Su línea 77 dice
   *«PostgreSQL corre **local** (`localhost:5432/retailmind`)»* y la 79 lleva **la contraseña del
   superusuario en claro** apuntando a ese puerto. Hoy el 5432 es **el contenedor**, cuya contraseña
   **está rotada**, y el local vive en el **5433**. Un agente que lea eso partirá de una premisa
   falsa. **Es lo primero que hay que arreglar** — y de paso conviene quitar de ahí la contraseña,
   que sigue siendo válida para el local y `CLAUDE.md` sí está versionado.
2. **Los secretos siguen en el HISTORIAL de git.** La rotación los deja inservibles, que es lo que
   de verdad cierra el asunto, pero los valores viejos siguen siendo recuperables en commits
   anteriores. Reescribir el historial de un repositorio académico no compensa; queda declarado.
3. **Nada está commiteado.** Hay 6 archivos modificados/borrados y 3 sin rastrear.

### Menor

4. `retailmind/logs/etl_dwh.log` está versionado y cambia en cada corrida del ETL. Un log en git es
   ruido: `git rm --cached` cuando se quiera.
5. La pantalla `/inicializacion` se **conserva por decisión del usuario** (se explicará como
   histórica), pero **3 de sus 6 botones dan error** porque el servicio `pocketbase` se eliminó del
   compose. No afecta a nada más.
6. **Adelgazar `retailmind-backend/Dockerfile`**: instala `pyarrow`, `pandas` y `pocketbase` en la
   imagen final; sobre Alpine los dos primeros compilan. `pocketbase` ya no hace falta y `pyarrow`
   no lo usa `etl/dwh/`. Bajaría mucho el tiempo de build **en frío**, pero cambia la imagen y
   obliga a reprobar `/api/health` (`checkPythonRuntime`) y el botón del DWH. Tarea aparte.
7. **Airflow** (§7 del diseño): sigue pendiente y ahora es más fácil — contenerizar PostgreSQL
   eliminó la fricción que el diseño señalaba. Recordatorio: el día que Airflow tome el relevo hay
   que poner **`DWH_CRON=-`** en el `.env`, o a las 02:00 disparan los dos y dos cargas concurrentes
   compiten por el `EXCHANGE TABLES` del mismo destino.
8. **No desinstalar el PostgreSQL local.** Ahora es dos cosas: plan B de RetailMind **y** el hogar
   de las 12 bases de otras materias.
9. Reiniciar Claude Code para que los MCP tomen puertos y contraseña nuevos.

---

## 8. Trampas para quien siga

- **Un cambio de código Java o Angular NO entra solo en Docker**: la imagen está horneada. Hace
  falta `docker compose up -d --build`. En cambio el **Python del ETL sí es inmediato**, porque
  `./retailmind` va montado, no copiado.
- **Los datos NO están en la imagen sino en el volumen**: reconstruir **no** borra los datos.
- **Un script SQL nuevo NO se aplica solo.** `deploy/postgres/initdb/` corre **una única vez**,
  cuando el volumen está vacío. Para aplicar uno nuevo:
  `docker compose exec -T postgres psql -U postgres -d retailmind < ruta/al/script.sql`.
- **Antes de `docker compose up` con el compose raíz, no dejar vivo el proyecto viejo**
  (`docker compose -p 1m6datoscs down`): dos servidores sobre el mismo volumen es corrupción, no un
  choque de puertos. Ningún `down` debe llevar `-v`.
- **Al interpretar un 403 de bodega/despacho/compras, mirar el reloj antes que la migración**:
  `motivo_fallo = 'fuera_horario'` **bloquea el login entero**, así que el rol no llega ni a pedir
  el endpoint. Los scripts `matriz_*.py` ensanchan la ventana del día en curso y **la restauran en
  un `finally`**; si uno se interrumpe, deja los horarios abiertos.
- **`docker compose up -d` levanta los CUATRO** (perfil `demo` en el `.env`). Si se hace por
  costumbre mientras se programa, ocupará 8080 y 4200 y el backend local no arrancará. Se arregla
  con `docker compose stop backend frontend`.
