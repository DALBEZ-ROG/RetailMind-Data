# Guion de rodaje — Tarea 15 · los tres videos de RetailMind

**Fecha de verificación: 2026-08-16.** Todas las rutas, roles, permisos y cifras de
este guion se comprobaron contra el código (`app.routes.ts`, `SecurityConfig`,
`nav-model.ts`) y contra el sistema en marcha. Los tiempos de carga están **medidos
por HTTP**, no estimados.

> Este archivo **no forma parte del sistema**. Es el papel que se tiene abierto en la
> otra pantalla mientras se graba. No se enseña en cámara: el profesor pidió
> **nada de documentación**, y un guion en pantalla es documentación.

---

## 0. ANTES DE GRABAR

### 0.1 Qué levantar, y en qué orden

```bash
cd "C:/Users/ASUS/Documents/UTEQ/6 SEMESTRE/Construccion de software/1M6DatosCS"

# 1 · Los cuatro servicios del perfil demo (postgres, clickhouse, backend, frontend)
docker compose up -d
#    Listo en ~30 s. Espera a que los cuatro digan (healthy):
docker compose ps

# 2 · Solo para el VIDEO 3 (estratégico), y solo si vas a enseñar Airflow:
docker compose --profile airflow up -d
#    Tarda más (arranca 3 servicios y su base de metadatos). Web: http://localhost:8081
```

**Nunca** `docker compose down -v`. El `-v` destruye los volúmenes y con ellos la base
`retailmind` entera y el `fact_eventos` de ClickHouse, que no es reproducible. Para
parar: `docker compose stop`.

### 0.2 Comprobaciones antes de pulsar «grabar»

```bash
# a · Los cuatro servicios sanos y la salud del backend
curl -s http://localhost:8080/api/health
#   Debe decir: "postgres":"UP", "clickhouse":"UP", "status":"UP", "analytics":"UP"

# b · El almacén analítico: 21 tablas y de cuándo es el dato
docker compose exec -T clickhouse clickhouse-client -q "
  SELECT count(), sum(total_rows) FROM system.tables
  WHERE database='retailmind_dwh' AND name<>'etl_ejecucion';
  SELECT max(fin) FROM retailmind_dwh.etl_ejecucion WHERE resultado='exito';"
#   Hoy: 21 tablas · 26.971.352 filas · última corrida buena 2026-08-12 17:24

# c · Que las ventanas horarias estén en 24/7 (si no, un rol se te queda ciego en vivo)
docker compose exec -T postgres psql -U postgres -d retailmind -c "
  SELECT count(*) FILTER (WHERE hora_inicio='00:00' AND hora_fin='24:00') AS abiertas,
         count(*) AS total FROM grupo_horario WHERE activo;"
#   Debe salir 56 / 56. Si no, ejecuta el script 90 (ver 0.6).
```

**c es la comprobación que más veces salva una grabación.** Una sola ventana desviada
deja a ese rol viendo **cero filas sin ningún mensaje de error** — `pol_horario` está
declarada `cmd = ALL`, y ALL incluye SELECT: la RLS no rechaza, **filtra**. Ya pasó una
vez (`grp_analista`, domingo, 30 minutos de 10.080).

### 0.3 Pre-calentado obligatorio

La **primera** apertura de cada pantalla es notablemente más lenta que las siguientes
(compilación JIT del backend, cachés frías, plan de consulta). Antes de grabar, abre una
vez y cierra: `/inicio`, `/operativo/productos`, `/operativo/ventas/pedidos`,
`/operativo/informes/ventas`, `/operativo/tableros/omnicanal`, `/operativo/panorama`.
Cuesta dos minutos y evita que el primer plano del video sea una pantalla en blanco.

### 0.4 Usuarios y contraseñas

Salen de los scripts de siembra y están anotadas en `CLAUDE.md` §«Credenciales de
desarrollo». Verificado hoy: los 10 existen y están activos.

| Rol | Usuario | Contraseña | De dónde sale |
|---|---|---|---|
| ADMIN | `admin@retailmind.com` | `Admin2026!` | script 26 |
| GERENTE | `gerente@retailmind.com` | `Retail2026!` | script 27 |
| VENDEDOR | `vendedor@retailmind.com` | `Retail2026!` | script 27 |
| COMPRAS | `compras@retailmind.com` | `Retail2026!` | script 27 |
| BODEGA | `bodega@retailmind.com` | `Retail2026!` | script 27 |
| DESPACHO | `despacho@retailmind.com` | `Retail2026!` | script 27 |
| ANALISTA | `analista@retailmind.com` | `Retail2026!` | script 27 |
| SOPORTE | `soporte@retailmind.com` | `Retail2026!` | script 37 |
| CLIENTE | `maria.lopez@demo.com` | `Cliente2026!` | script 26 |
| CLIENTE | `carlos.vera@demo.com` | `Cliente2026!` | script 26 |

**Truco de rodaje**: usa **dos navegadores distintos** (o uno normal y otro en incógnito)
para tener dos roles abiertos a la vez. Lo vas a necesitar en el bloque de seguridad del
video 1 y en el de segregación del video 2. Cambiar de rol cerrando sesión cuesta 20
segundos de video muerto cada vez.

### 0.5 Cuánto tarda cada pantalla — medido hoy por HTTP

Lo que está por encima de 2 s hay que **rellenarlo con voz**. Aquí está qué decir
mientras carga, en cada bloque.

| Pantalla / informe | Tiempo | Qué hacer |
|---|---|---|
| `informes/inventario/kardex` (OTD-INV-03) | **25,6 s** | **No abrir en vivo.** Ver 0.8 |
| `informes/logistica/envios` (OTD-LOG-02) | **22,1 s** | **No abrir en vivo.** |
| `informes/logistica/costo-envio` (OTD-LOG-11) | **18,8 s** | **No abrir en vivo.** |
| `informes/compras/entregas-incompletas` | **8,8 s** | Solo si narras la espera |
| `informes/ventas/participacion-canal` | **7,2 s** | Evitar (además, ver 0.8) |
| Tablero Rentabilidad (T-2) | 2,2 s | Una frase de entrada |
| Tablero Cliente y Posventa (T-3) | 2,1 s | Una frase de entrada |
| Tablero Operación (T-4) | 1,4 s | Fluido |
| `informes/soporte/bandeja` | 1,5 s | Fluido |
| `informes/compras/ordenes` | 1,3 s | Fluido |
| Tablero Omnicanal (T-1) | 0,8 s | Fluido |
| `informes/ventas/evolucion-mensual` (compuesto) | **0,76 s** | Fluido — y es el argumento |
| `informes/gerencia/foto-dia` | 0,69 s | Fluido |
| `informes/logistica/cola-despacho` | 0,70 s | Fluido |
| Tableros T-5, T-6, T-7 | 0,3–0,6 s | Fluido |
| `informes/inventario/rotacion` (compuesto) | **0,38 s** | Fluido — y es el argumento |
| `informes/ventas/cartera-pedidos` | 0,37 s | Fluido |
| Previsión de demanda / Clientes en riesgo | **0,10 s** | Instantáneo |
| `informes/gerencia/auditoria` | 0,04 s | Instantáneo |

**La lectura que hay que sacar de esta tabla, y que es media sustentación**: los informes
lentos son **todos** simples (PostgreSQL, transaccional) y los rápidos son **todos**
compuestos (ClickHouse, columnar), sobre los mismos volúmenes. No es que unos estén mal
programados: es que **8 millones de movimientos de kardex agregados fila a fila cuestan 25
segundos en una base de filas y 0,4 en una de columnas**.

### 0.6 Preparación de datos por bloque

| Bloque | Qué hay que preparar | Cuándo | Cuánto tarda |
|---|---|---|---|
| V1-B8 seguridad | Nada. Los scripts 89 y 90 ya existen | — | 30 s cada uno |
| V1-B3 compras | Hay **7 órdenes de compra por aprobar** ahora mismo; no hace falta crear ninguna | — | — |
| V1-B5 ventas | **26.551 pedidos en cola de preparación** y **8.846 listos para despacho** | — | — |
| V3-B3 Airflow | Arrancar el perfil `airflow` y **despausar** el DAG antes de disparar | 10 min antes | ver abajo |
| V3-B3 (opcional) | Una corrida completa para que la marca de agua diga «hoy» | **la noche antes** | **~23,5 min** |

**La corrida completa del DAG tarda 23 minutos y medio**, no 12: la última medida
(2026-08-12) fue de 1.409 s de reloj, con `fact_movimiento_inventario` en 498 s,
`fact_venta_linea` en 291 s, `fact_envio` en 246 s y `validar_dwh` en 208 s. **No cabe en
un video de 15 minutos.** Dispárala la noche anterior y graba el DAG **ya verde**, o
dispárala al empezar el bloque y vuelve a ella al final.

Si no la corres, la marca de agua dirá **«Datos al 12/08/2026 17:14»**. Eso **no es un
fallo y conviene decirlo así**: un informe analítico puede llevar horas de desfase y el
sistema lo declara en pantalla en vez de fingir que es de este segundo.

Restaurar el horario si algo quedó torcido:

```bash
docker exec -i retailmind-postgres-1 psql -U postgres -d retailmind -v ON_ERROR_STOP=1 \
  < retailmind/sql/postgres/90_horario_demo_restaurar.sql
# Debe imprimir: «24/7 restaurado, 0 minutos bloqueados de 10080»
```

### 0.7 LAS TRAMPAS — lo que NO hay que hacer

Están todas registradas en el repositorio; aquí van con lo que significan en cámara.

1. **No intentes iniciar sesión con un rol que tenga el horario restringido.** El script 53
   hizo que `fuera_horario` **bloquee el login**, y el mensaje que sale es
   **401 «Credenciales incorrectas»** — genérico. En vivo se lee como una contraseña mal
   tecleada y descarrila la demostración. **La sesión se abre ANTES de restringir**; el
   JWT ya emitido sigue valiendo y por eso la recarga funciona. (Paso 0 del script 89.)

2. **No enseñes el 403 pulsando un botón de la pantalla.** Por la vía de la aplicación
   **la RLS muerde antes que el trigger**: `VentasService` lee el pedido antes de
   escribirlo, esa lectura ya viene filtrada a cero filas, y el servicio concluye que el
   pedido no existe → sale **400 «No existe el pedido N»**, no 403. Si quieres el 403,
   hazlo contra el motor:
   ```bash
   docker exec -i retailmind-postgres-1 psql -U postgres -d retailmind -c \
     "BEGIN; SET LOCAL ROLE grp_bodega;
      UPDATE inventario SET stock_actual = stock_actual WHERE id = 1; ROLLBACK;"
   # ERROR 42501: «fuera del horario permitido para el rol grp_bodega…»
   ```

3. **No dispares el DAG con el DAG pausado.** Un DAG pausado **encola los disparos
   manuales sin ejecutarlos**: se quedan en `queued` y arrancan todos de golpe al
   despausarlo. Despausa primero:
   ```bash
   docker compose exec airflow-scheduler airflow dags unpause retailmind_dwh
   ```

4. **No cuentes las corridas de `etl_ejecucion` como una.** Cada tarea es un proceso
   independiente y abre su propio marcador: **una corrida escribe 22 pares**, no uno.

5. **Si grabas el DAG a principios de mes, anticipa dos cuadros rojos.** El guardia de
   concentración de `fact_alerta_cliente` aborta si un cliente supera el 25 % de los
   pedidos de un mes de la ventana, y el primer pedido de un mes nuevo hace 1 de 1 = 100 %.
   Arrastra `validar_dwh` a `upstream_failed`: **2 rojos sobre 22**. **Las otras 20 tablas
   se publican igual** y todas las pantallas siguen sirviendo. Hoy el ancla del almacén es
   **2034-12** y el riesgo no aplica, pero si sale, la historia es buena: **es el guardia
   funcionando**, no un fallo. No bajes el umbral para la ocasión.

6. **No toques el catálogo maestro en cámara si no vas a revertirlo.** El patrón CRUD tiene
   baja lógica (`activo = false`), así que un «Eliminar» se ve bien pero deja rastro.
   Para el video, usa **Ver** (Modo Consulta) y **Modificar → Cancelar**.

7. **El primer clic de cada pantalla es el lento.** Ver 0.3.

8. **No cambies de rol a mitad de un plano.** Cerrar sesión, volver a entrar y navegar
   cuesta ~20 s de video muerto. Ten los dos navegadores preparados (0.4).

9. **No afirmes de memoria las cifras del recuento de permisos.** La pantalla de Permisos
   del Motor calcula sus números en vivo desde `pg_catalog`: **léelos de la pantalla**.
   (Además, `information_schema` miente por debajo: da 738 donde el catálogo da 1.355,
   porque filtra por `pg_has_role`. Ese contraste, dicho, suma.)

### 0.8 Qué NO enseñar, y por qué

| Pantalla | Motivo |
|---|---|
| `/inicializacion` | Es una pantalla **explicativa**: cuenta la historia del pipeline PocketBase → Parquet → ClickHouse, que ya no existe. Es documentación en pantalla, justo lo prohibido |
| `/admin-etl` | Se llama «Administración ETL» pero **no es el ETL de Airflow**: es el estado por semana de la capa **legada** (`fact_eventos`). Enseñarla justo antes o después del DAG confunde las dos cosas |
| `/gestion-datos` | Desde la corrección A-3 la tabla de eventos **ya no se edita**, y en su lugar hay un aviso que explica por qué. El aviso es texto explicativo |
| Analytics (dashboard, sesiones, conversiones, funnel, región, dispositivo, tráfico) | Leen el ClickHouse **legado**. El diagnóstico interno concluyó que **el 96,2 % de `fact_eventos` es relleno huérfano** y el resto es un dataset ajeno. Si quieres enseñarlas, 20 segundos y **sin afirmar ninguna cifra de negocio** |
| OTD-INV-03 kardex, OTD-LOG-02 envíos, OTD-LOG-11 costo de envío | 25,6 · 22,1 · 18,8 segundos. En video son eternos. **Usa la pantalla operativa de Kardex** (`/operativo/inventario/kardex`, con SKU: responde en ~1 s) |
| OTD-VEN-16 participación por canal | Tarda 7,2 s **y** mide algo que el sistema declara no medible: `pedido.canal` es el MEDIO, no el segmento; `grupo_cliente` está vacío y hay 0 facturas con RUC, así que «clientes de negocio» sale 0. Solo enséñalo si vas a narrar *por qué* sale 0 |
| OTD-GER-07 efecto de promociones | Muestra insuficiente declarada (184 líneas en ventana frente a 3.217 de base) |
| Crear un rol personalizado en vivo | Un `CREATE ROLE` necesita **seis piezas** para servir; hoy hay **0 roles personalizados** creados. No improvises: enseña el editor de permisos, no la creación |
| `/admin/reportes` | Descargas de Excel/PDF. No aporta al relato y una descarga fallida en cámara es cara |

### 0.9 Cómo grabar

**Por bloques de 3 a 5 minutos, nunca del tirado.** Cada bloque de este guion es una toma.
Si un bloque sale mal, se repite ese bloque y no los 20 minutos. Deja **2 segundos de
silencio** al principio y al final de cada toma para poder cortar limpio.

Antes de cada toma: **una sola pestaña visible**, sin notificaciones, y la ventana
maximizada. Si tienes dos navegadores para dos roles, ponlos en escritorios distintos y
alterna con el gesto del sistema, no arrastrando ventanas.

### 0.10 El reparto de los tres videos, y por qué

- **OPERATIVO (20 min) — el negocio ocurriendo.** Todo lo que escribe en PostgreSQL: el
  ciclo de compra, el de venta, el inventario y la posventa, cada uno con el rol que de
  verdad lo ejecuta. Y **la seguridad de motor**, que va aquí y no en el estratégico
  porque es una propiedad del sistema **operando**: se demuestra bloqueando a un usuario
  real en una pantalla real, no explicando un diagrama.
- **TÁCTICO (15 min) — la misma verdad, consultada.** Los 73 informes departamentales y,
  sobre todo, **la distinción entre simple y compuesto**: qué pregunta se responde contra
  el transaccional y cuál contra el almacén, y qué cuesta cada una. Aquí también va la
  **segregación financiera por rol**, porque es en los informes donde se ve que a Bodega
  le faltan las columnas de dinero.
- **ESTRATÉGICO (15 min) — la decisión.** Los 7 tableros, el panorama, el pipeline que los
  alimenta y los dos modelos. Y el momento más honesto del sistema: **los dos modelos
  publican su propio veredicto**, incluido el que dice que no supera al azar.

**Dos movimientos deliberados respecto de la orientación del enunciado:**

1. **La seguridad de motor (RLS, horarios, permisos) se queda en el OPERATIVO**, no en el
   estratégico. Es donde se puede *demostrar*: dos sesiones abiertas y una que se queda
   ciega. En el estratégico sería una lámina.
2. **El benchmark columnar (PostgreSQL vs ClickHouse) se cuenta en el TÁCTICO**, no en el
   estratégico. El contraste se ve solo cuando tienes el informe lento y el rápido uno al
   lado del otro, y eso pasa en la pantalla de informes.

---

# VIDEO 1 · SISTEMA OPERATIVO (20:00)

## Bloque 1 — Arranque y escala (1:30)

**Rol**: ADMIN (`admin@retailmind.com` / `Admin2026!`). Se entra con admin porque es el
único que ve las catorce áreas y sirve para encuadrar el sistema entero antes de bajar a
cada rol.

**Ruta**: navegador en `http://localhost:4200` → formulario de login → **Entrar** →
aterriza en `/inicio`.

**Qué se ve**: la cabecera «Hola, Administrador Sistema» con la insignia ADMIN, y catorce
tarjetas de área — Catálogo, Compras, Inventario, Ventas, Logística, Devoluciones,
Marketing, Gerencia, Informes Tácticos, Panorama, Tableros, Soporte, Opiniones, Seguridad,
Administración, Analytics — cada una con su recuento de módulos.

**Qué decir**:
- Qué es RetailMind: el back-office completo de un comercio minorista multicanal, con su
  tienda de cliente incluida.
- La escala, y conviene decirla bien porque es lo que sostiene todo lo demás:
  **«tres millones de pedidos repartidos en una década, de 2025 a 2034; 7,6 millones de
  líneas de pedido; 8 millones de movimientos de kardex; 2,1 millones de envíos; 50.072
  clientes; una base de 17 gigabytes»**.
- Que **todo lo transaccional vive en PostgreSQL** y que ClickHouse es solo analítica —
  con ClickHouse apagado el sistema entero sigue funcionando.
- Que la navegación está organizada por **áreas de negocio**, no por tablas.

**Carga**: instantánea si pre-calentaste. Si no, el primer login puede tardar 2-3 s.

---

## Bloque 2 — El catálogo maestro (2:30)

**Rol**: ADMIN. El catálogo está reservado a ADMIN en `roleGuard(['ADMIN'])` — verificado:
ni Gerente ni Compras entran aquí.

**Ruta exacta**: desde `/inicio` → tarjeta **Catálogo** → **Productos y Variantes**.
(URL directa: `/operativo/productos`.)

**Qué se ve**: la tarjeta «Criterios de búsqueda» con tres filtros (Buscar, Marca,
Categoría), y debajo «Catálogo (6.215)» con la barra de cuatro acciones —Nuevo, Modificar,
Eliminar, Ver— y la grilla.

**Qué hacer y decir**:
1. Escribe `Fideo` en Buscar. La grilla se recorta sola con *debounce*, sin pulsar nada.
   Di que **la búsqueda es del lado del servidor**: no se traen 6.215 productos al
   navegador para filtrarlos ahí.
2. Selecciona una fila. Se activan Modificar / Eliminar / Ver y aparece a la derecha
   «Seleccionado: …». Di que **el patrón es el mismo en las quince pantallas de
   mantenimiento**: seleccionar primero, actuar después, sin una columna de botones por
   fila.
3. Pulsa **Ver**. Se abre el diálogo con el chip **MODO CONSULTA** y todos los campos
   deshabilitados. Di que el modo es explícito y que Aceptar en consulta solo cierra.
4. **Cancelar**. Pulsa **Modificar** para enseñar el chip **MODO ACTUALIZAR** con todo
   precargado, y **Cancelar** otra vez. *(No guardes: ver trampa 6.)*
- Frase clave: **«6.215 productos y 6.222 variantes; la variante es la unidad que se
  vende, la que tiene SKU, precio y stock»**.

**Carga**: la grilla, < 1 s.

---

## Bloque 3 — El ciclo de compra, con sus compuertas (3:00)

**Roles**: COMPRAS y GERENTE. Se usan los dos **a propósito**: la aprobación no la puede
dar quien crea la orden, y eso es lo que hay que enseñar.

**Ruta exacta** (sesión COMPRAS): `/inicio` → **Compras** → **Órdenes de Compra**.
(URL: `/operativo/compras/ordenes`.)

**Qué se ve**: el listado de órdenes con su estado. Hay **134.588 órdenes** en total y
**7 esperando aprobación** ahora mismo.

**Qué hacer y decir**:
1. Filtra por estado **«Esperan aprobación»**. Quedan 7. Di que ese estado es
   **sintético**: agrupa `borrador` y `enviada`, porque **no existe un estado «aprobada»**
   — aprobar deja la orden en `confirmada`.
2. Abre una. Enseña las líneas, el proveedor y el total.
3. Intenta **recibir** sin aprobar. **No se puede**, y ese es el punto:
   **«sin aprobar no se recibe, sin recibir completo no se factura, sin factura no hay
   cuenta por pagar ni pago»**. Las compuertas están **enforzadas en el backend**, no son
   un color en la pantalla.
4. Cambia a la sesión GERENTE y aprueba la orden. Vuelve a COMPRAS: ahora sí se puede
   recibir.
5. Enseña **Recepciones** (`/operativo/compras/recepciones`) y di que la recepción
   **escribe el kardex y actualiza el stock en la misma transacción**, y que además
   alimenta el catálogo del proveedor con el costo real.

**Carga**: el listado de órdenes, 1,3 s. Rellena diciendo el volumen.

**Momento fuerte**: el intento de recibir sin aprobar. Que se vea el rechazo.

---

## Bloque 4 — Inventario y trazabilidad (2:00)

**Rol**: BODEGA (`bodega@retailmind.com`). Es quien mueve el stock, y además sirve para
introducir la segregación que se explota en el video 2.

**Ruta exacta**: `/inicio` → **Inventario** → **Kardex**.
(URL: `/operativo/inventario/kardex`. **Ojo: la pantalla operativa, no el informe
OTD-INV-03**, que tarda 25 s.)

**Qué se ve**: el buscador «Variante (SKU)» con autocompletado contra el servidor.

**Qué hacer y decir**:
1. Escribe `Fideo` en el buscador. Salen las sugerencias con SKU y nombre. Elige una.
2. Aparece el historial: entradas, salidas, el tipo de movimiento y **el saldo corrido**.
   Frase clave: **«ocho millones de movimientos, y cada uno lleva el saldo anterior y el
   nuevo; el invariante `stock_nuevo = stock_anterior + factor × cantidad` lo valida un
   trigger dentro del motor, no la aplicación»**.
3. Di que las **11.406 posiciones de inventario cuadran exactamente** con la suma de su
   kardex — se verifica posición por posición, no en agregado.
4. Enseña de pasada **Transferir Stock** y **Ajustes de Inventario**, y di que un ajuste
   anulado no se borra: se compensa con un **contramovimiento**.

**Carga**: el autocompletado, ~1 s. El kardex de una variante, ~1 s.

---

## Bloque 5 — El ciclo de venta completo (3:30)

**Roles**: VENDEDOR → BODEGA → DESPACHO. Tres roles seguidos, porque el pedido **cambia de
manos** y eso es exactamente lo que hay que ver.

**Ruta exacta** (VENDEDOR): `/inicio` → **Ventas** → **Pedidos de Venta**.
(URL: `/operativo/ventas/pedidos`.)

**Qué hacer y decir**:
1. **Nuevo pedido**: elige cliente, añade dos líneas, confirma. Di que **el descuento por
   promoción vigente se aplica solo, por línea**, y que el IVA se calcula sobre la base ya
   rebajada.
2. **Registrar el pago**. Di que el pedido **no se puede facturar sin estar pagado**, y
   que admite abonos parciales.
3. **Emitir la factura** y **descargar el PDF**. Enséñalo abierto: es un documento real
   generado con iText, con subtotal, descuento, IVA y total.
4. Cambia a **BODEGA** → `/inicio` → **Logística** → **Preparación de Pedidos**
   (`/operativo/ventas/preparacion`). Hay **26.551 pedidos en cola**. Frase clave:
   **«esta cola no usa la misma consulta que el detalle del pedido, porque bodega no tiene
   permiso para leer la tabla de pagos; hay una consulta dedicada que no selecciona
   dinero»**.
5. Prepara el pedido → queda `preparado`.
6. Cambia a **DESPACHO** → **Logística** → **Despachos** (`/operativo/ventas/despachos`).
   Hay **8.846 listos**. Despacha: se genera la guía, se asigna el transportista **por
   zona** (ciudad > provincia > país) y el cliente puede seguirlo.
7. Registra la **entrega**.

**Carga**: la cola de preparación, < 1 s. Los despachos, < 1 s.

**Momento fuerte**: el PDF de la factura abierto en pantalla, y la frase de bodega sin
acceso a pagos.

---

## Bloque 6 — La tienda del cliente (2:00)

**Rol**: CLIENTE (`maria.lopez@demo.com` / `Cliente2026!`). Segundo navegador.

**Ruta exacta**: login → aterriza en la tienda. `/inicio` → **Tienda** → **Tienda**
(URL `/shop`).

**Qué hacer y decir**:
1. Busca un producto, entra a la ficha, **añádelo al carrito**.
2. **Mi Carrito** → **Checkout**. Elige dirección, escribe un cupón, elige tarjeta.
   Frase clave: **«el pago está simulado, pero de lo que se teclea se guarda únicamente la
   marca y los cuatro últimos dígitos; el número de tarjeta y el CVV no se persisten en
   ninguna tabla»**.
3. Confirma. El pedido **nace pagado y facturado en una sola transacción**, y la factura
   se emite automáticamente.
4. **Mis Pedidos** (`/operativo/ventas/mis-pedidos`): el pedido recién hecho, con su
   estado, su guía y su **factura en PDF descargable**.
5. Frase clave: **«es el mismo pedido y el mismo ciclo que el del mostrador; lo único que
   cambia es el canal, y por eso el back-office no ofrece "registrar pago" en un pedido
   web»**.
6. Enseña que el cliente **solo ve lo suyo**: eso no lo hace el backend, lo hace una
   **política RLS** en la base contra `app.cliente_id`.

**Carga**: catálogo y carrito, < 1 s.

---

## Bloque 7 — La posventa (2:00)

**Roles**: CLIENTE (para originar) y SOPORTE / BODEGA (para resolver).

**Ruta exacta**: como CLIENTE, en **Mis Pedidos**, sobre un pedido entregado →
**Solicitar devolución**. Luego, como SOPORTE, `/inicio` → **Devoluciones** →
**Devoluciones (RMA)** (`/operativo/ventas/devoluciones`).

**Qué hacer y decir**:
1. Di que **la devolución nace del cliente**, con un plazo de 30 días desde la entrega, y
   que **crea o engancha un ticket de soporte** automáticamente.
2. En el tablero RMA se ve el ciclo: solicitada → en revisión → aprobada → en tránsito →
   recibida → inspeccionada → reembolsada → cerrada, **con un rol distinto por
   transición**. Hay **145.734 devoluciones** y **10.563 solicitadas** ahora.
3. Frase clave: **«la inspección es por ítem: solo lo apto para reventa vuelve al stock,
   con su movimiento de kardex; lo defectuoso va a un pool que se devuelve al proveedor, y
   lo rechazado no genera reembolso»**.
4. Salta a **Soporte** → **Tickets de Soporte**: **179.851 tickets**, **92.426 vivos**, con
   número `TICK-AAAA-NNNN`, prioridad **automática** por categoría y **SLA calculado**.
5. Salta a **Opiniones** → **Reseñas de Productos**: **263.077 reseñas**, y la regla que
   importa: **«para reseñar hay que haber comprado; el selector solo ofrece los productos
   que ese cliente compró, y el backend lo vuelve a comprobar»**.

**Carga**: RMA y tickets, 1-2 s.

---

## Bloque 8 — La seguridad vive en el motor (3:30) ★ EL MOMENTO FUERTE

**Roles**: ADMIN y BODEGA, **los dos abiertos a la vez**. Este bloque no funciona con un
solo navegador.

### 8.1 · La pantalla de Permisos del Motor (1:30)

**Ruta exacta** (ADMIN): `/inicio` → **Seguridad** → **Permisos del Motor**.
(URL: `/operativo/seguridad/permisos`. Solo ADMIN.)

**Qué se ve**: seis pestañas — Editor de rol, Roles, Usuarios por rol, Permisos, Políticas
RLS, Restricción horaria.

**Qué decir** (lee las cifras de la pantalla, no de memoria — trampa 9):
- **«Todo esto sale de `pg_catalog` en vivo. No hay una tabla de permisos de la
  aplicación: las tablas `permiso` y `rol_permiso` existen y están vacías»**.
- Los números de hoy, verificados: **9 roles de grupo · 95 políticas RLS · 50 tablas con
  RLS · 109 columnas con ACL en 14 tablas · 34 triggers de horario · 18 funciones
  SECURITY DEFINER**.
- Enseña la pestaña **Permisos** y filtra por `grp_bodega` sobre `pedido`: **cero permisos
  de tabla y una lista de columnas**. Frase clave: **«bodega no tiene SELECT sobre la
  tabla `pedido`; tiene SELECT sobre las columnas que no son dinero. La segregación
  financiera no es una condición en el código: es un GRANT»**.
- Si quieres el contraste: `information_schema` da **738** donde el catálogo da **1.355**,
  porque filtra por `pg_has_role`. Por eso la pantalla lee `aclexplode()` y no la vista.

### 8.2 · La demostración horaria en vivo (2:00)

**Sigue el orden exacto o no funciona.**

**PASO 0 — antes de tocar nada.** En el segundo navegador, entra como **BODEGA** y abre
`/operativo/ventas/preparacion`. Se ven los 26.551 pedidos en cola. **Deja esa pestaña
abierta.** (Si restringes primero, el login responde 401 genérico — trampa 1.)

**PASO 1 — restringe.** En la terminal:
```bash
docker exec -i retailmind-postgres-1 psql -U postgres -d retailmind -v ON_ERROR_STOP=1 \
  < retailmind/sql/postgres/89_horario_demo_restringir.sql
```
El script imprime la hora de Ecuador, la ventana que deja puesta y el veredicto de
`esta_en_horario()`, que **debe salir `false`**. Enséñalo en cámara: son tres líneas.

**PASO 2 — recarga la pantalla de bodega (F5).** **La cola aparece vacía.**

**Qué decir mientras se ve el vacío** — esto es lo que hay que decir bien:
- **«Y fíjense en que no hay ningún mensaje de error. Eso es lo importante.»**
- **«La política `pol_horario` está declarada `cmd = ALL`, y ALL incluye SELECT. La base no
  rechaza la consulta: la filtra. El backend recibe cero filas y las pinta tal cual.»**
- **«El backend no ha cambiado. La aplicación no sabe nada de esto. La compuerta está en
  el motor.»**
- Señala la otra ventana: **con ADMIN se sigue viendo todo**, porque `esta_en_horario()`
  exime a `grp_administrador` en su primer `IF`. **Las dos sesiones lado a lado son la
  mejor forma de enseñar que el filtro es por rol.**

**PASO 3 — restaura.**
```bash
docker exec -i retailmind-postgres-1 psql -U postgres -d retailmind -v ON_ERROR_STOP=1 \
  < retailmind/sql/postgres/90_horario_demo_restaurar.sql
```
Recarga: la cola vuelve. El script imprime **«24/7 restaurado, 0 minutos bloqueados de
10080»**.

**NO intentes pulsar «preparar» estando restringido** para enseñar un 403: sale un
**400 «No existe el pedido N»** (trampa 2). Si quieres el 403, es el `psql` de la trampa 2.

---

# VIDEO 2 · SISTEMA TÁCTICO (15:00)

## Bloque 1 — Qué es el nivel táctico (1:30)

**Rol**: ADMIN, para ver los seis departamentos de una vez.

**Ruta exacta**: `/inicio` → **Informes Tácticos** → **Informes de Ventas**.
(URL: `/operativo/informes/ventas`.)

**Qué se ve**: a la izquierda, la columna con los 17 informes del departamento; arriba de
ella, el contador **«17 de 17 informes · 6 simples · 11 compuestos»**; a la derecha, la
barra de filtros en una línea, las tarjetas de indicadores y la tabla.

**Qué decir**:
- **«Setenta y tres informes, en seis departamentos: treinta simples y cuarenta y tres
  compuestos.»**
- **«Una sola pantalla los sirve a todos. Un informe nuevo es un archivo de declaraciones,
  no un componente.»**
- Pulsa las pastillas **simples** y **compuestos** para filtrar la lista, y di que
  **el contador se calcula de lo que hay pintado**, no está escrito a mano.
- Y la distinción que estructura el video: **«el simple responde sobre la foto del
  presente y va contra PostgreSQL; el compuesto recorre histórico o cruza períodos y va
  contra el almacén analítico. Cada entrada lo dice con tres señales: el color del filo,
  la forma de la marca y la palabra escrita.»**

**Carga**: < 1 s.

---

## Bloque 2 — Un informe SIMPLE de punta a punta (2:30)

**Rol**: ADMIN. **Ruta**: en la columna, **OTD-VEN-01 · Cartera de pedidos por estado**.

**Qué se ve**: los filtros (Estado, Canal, Desde, Hasta, buscador), tres tarjetas de
indicador y la tabla de pedidos.

**Qué hacer y decir**:
1. Señala el título: **«(más de 200.000)»**. Y las tres tarjetas: **«No calculado»**.
   **Este es el momento fuerte del bloque.** Frase clave: **«el servidor podría haber
   puesto un cero ahí. Decidió no hacerlo: por encima del tope de conteo, sumar sobre
   200.000 pedidos arbitrarios de tres millones no es un total aproximado, es un número
   sin significado. Así que devuelve el indicador vacío y adjunta la explicación.»**
2. Filtra por estado **Entregado** y una fecha. **El conteo vuelve a ser exacto y las
   tarjetas se llenan solas.** Di que la bandera se apaga sola en cuanto la pregunta es
   acotable.
3. Cambia el tamaño de página y pagina. Di que **la paginación es del lado del servidor**.
4. Frase clave de cierre: **«esto responde en menos de medio segundo sobre tres millones
   de pedidos porque solo mira la página que pediste. En cuanto un informe tiene que
   AGREGAR sobre millones de filas, PostgreSQL deja de ser el sitio.»** — que enlaza con
   el bloque 4.

**Carga**: 0,37 s.

---

## Bloque 3 — Un informe COMPUESTO (2:30)

**Ruta**: en la misma columna, **OTD-VEN-06 · Evolución de la venta por mes y categoría**
(insignia COMPUESTO).

**Qué se ve**: diez tarjetas de indicador repartidas en filas parejas, la marca de agua
**«Datos al 12/08/2026 17:14 · ClickHouse · retailmind_dwh»** y la tabla por mes y
categoría.

**Qué decir**:
- **«Misma pantalla, mismo sobre de datos, mismos filtros. Lo único que cambia es de dónde
  sale el dato, y la pantalla lo dice.»**
- La marca de agua: **«un informe analítico puede llevar horas de desfase. Uno que no dice
  de cuándo es su dato miente por omisión.»** (Si corriste el DAG anoche, la fecha será de
  hoy y el argumento es el mismo.)
- Frase clave: **«ciento veinte meses agregados por categoría, en menos de un segundo.»**
- Señala el reparto de las tarjetas: **10 indicadores en 4+4+2**, no siete y tres sueltas.

**Carga**: 0,76 s.

---

## Bloque 4 — La misma pregunta, los dos motores (2:30) ★ MOMENTO FUERTE

Este bloque es el argumento técnico del video. Se hace con **dos informes de inventario,
uno detrás del otro y cronometrados en cámara**.

**Ruta**: `/inicio` → **Informes Tácticos** → **Informes de Inventario**.

1. Abre **OTD-INV-04 · Rotación por categoría** (COMPUESTO). Responde en **0,38 s**.
2. Abre **OTD-INV-03 · Kardex** (SIMPLE). Tarda **25,6 segundos**. **Déjalo cargar y
   narra la espera** — esa espera *es* la demostración.

**Qué decir mientras carga el lento**:
- **«Los dos preguntan por movimientos de inventario. Los dos miran los mismos ocho
  millones de filas. Uno tarda cuatro décimas y el otro veinticinco segundos.»**
- **«No es que este esté mal programado. Se midió: no faltaba ni un índice, y el
  planificador acierta con un error del uno por ciento. La consulta pesada, sin la capa
  de seguridad por filas, resuelve en dos segundos.»**
- **«Lo que cuesta es que la política de seguridad se evalúa UNA VEZ POR FILA. Ocho
  millones de veces.»**
- **«Y aun así no se quita, porque quitarla es debilitar la compuerta. Lo que se hace es
  mover la pregunta al almacén columnar, que es donde una agregación sobre millones de
  filas cuesta lo que debe costar.»**
- Cierre: **«medido con el mismo arnés, a 2,8 millones de filas la base columnar gana
  diecisiete veces. A sesenta mil filas pierde. La ventaja no es una propiedad del motor:
  es una función del volumen.»**

**Carga**: 0,38 s / **25,6 s**. Ten preparadas las frases: son ~25 segundos que hay que
llenar sin titubear.

---

## Bloque 5 — Recorrido por los seis departamentos (3:00)

**Rol**: ADMIN. Un informe representativo por departamento, ~30 s cada uno.

| Departamento | Informe | Ruta en la columna | Qué decir | Carga |
|---|---|---|---|---|
| Inventario | **OTD-INV-09 · Capital inmovilizado** | Informes de Inventario | ★ **Lee en voz alta la salvedad que sale en pantalla**: el inventario de meses pasados se valoriza a **costo vigente** porque el sistema no guarda costo histórico. **«Es volumen a moneda constante, no lo que valía la bodega aquel mes. Y lo dice la pantalla, no la documentación.»** | < 1 s |
| Compras | **OTD-COM-03 · Puntualidad de pago** | Informes de Compras | Trece indicadores repartidos 4+3+3+3. Di que **el mes del gasto es el de la FACTURA, no el de la orden**: agrupar por la orden mueve 4,6 millones entre meses **sin que el total deje de cuadrar** | < 1 s |
| Logística | **OTD-LOG-05 · Problemas de entrega** | Informes de Logística | 66.269 incidencias por tipo y desenlace. Di que **los valores del filtro salen de los datos, no del diseño**: el diseño decía `reprogramar` y lo guardado es `reprogramada` — con el valor del diseño el filtro casa con **cero filas sin dar error** | < 1 s |
| Soporte | **OTD-SOP-01 · Bandeja de tickets** | Informes de Soporte | Arranca filtrado en **«pendientes»**, que es un estado sintético. **92.426 vivos de 179.851** | 1,5 s |
| Gerencia | **OTD-GER-09 · Intentos de acceso** | Informes de Gerencia | Dato sensible: **solo ADMIN y GERENTE**. Los cuatro motivos de fallo, incluido `fuera_horario` — que enlaza con el video 1 | < 1 s |
| Ventas | **OTD-VEN-19 · Clientes en riesgo** | Informes de Ventas | Solo si te sobra tiempo; se cuenta entero en el video 3 | 0,10 s |

---

## Bloque 6 — La segregación se ve en los informes (3:00) ★ MOMENTO FUERTE

**Roles**: BODEGA y DESPACHO. Segundo navegador.

**Ruta exacta** (BODEGA): `/inicio` → **Informes Tácticos**. **Cuenta las tarjetas**: no
están los seis departamentos.

**Qué hacer y decir**:
1. Con BODEGA, abre **Informes de Compras** → **OTD-COM-07 · Mercancía rechazada en
   puerta**. Frase clave: **«bodega entra al informe y ve las cantidades. Las columnas de
   dinero no están. Y no están porque la consulta no las selecciona: ClickHouse no tiene
   permisos por columna, así que ahí la barrera la pone el SQL.»**
2. Con DESPACHO, abre **Informes de Logística**. Ve **OTD-LOG-03** y **OTD-LOG-04**, pero
   **`/costo-envio` le da 403**. Frase clave: **«tres informes de la misma tabla; lo único
   que deja a despacho fuera del tercero es que ese sí selecciona importes. La ruta y la
   consulta, las dos.»**
3. Vuelve a ADMIN y abre el mismo informe: **ahí sí están las columnas de dinero.**
4. Cierre del video: **«el mismo informe, la misma tabla, dos roles, dos respuestas
   distintas. Y en tres sitios a la vez: el GRANT del motor, la ruta del backend y la
   consulta.»**

---

# VIDEO 3 · SISTEMA ESTRATÉGICO (15:00)

## Bloque 1 — El panorama del negocio (1:30)

**Rol**: GERENTE (`gerente@retailmind.com`). Se usa gerente y no admin porque **este video
es el del que decide**, y conviene que se vea que el rol de dirección tiene su propia
entrada.

**Ruta exacta**: `/inicio` → **Panorama del Negocio** → **Panorama del Negocio**.
(URL: `/operativo/panorama`.)

**Qué se ve**: la década completa resumida en seis cifras y seis preguntas.

**Qué decir**:
- **«Este es el punto de entrada de la dirección: seis cifras y seis preguntas, todas
  sobre el almacén analítico.»**
- Di que **el nivel estratégico no consulta PostgreSQL**: consulta el modelo dimensional,
  **21 tablas y casi 27 millones de filas**.

**Carga**: < 1 s.

---

## Bloque 2 — Los siete tableros de dirección (4:00)

**Rol**: GERENTE. **Ruta exacta**: `/inicio` → **Tableros de Dirección** → y desde ahí uno
a uno. Reserva ~35 s por tablero.

| # | Tablero | URL | Qué destacar | Carga |
|---|---|---|---|---|
| T-1 | Omnicanal | `/operativo/tableros/omnicanal` | El embudo. Frase clave: **«cuenta "alcanzó este hito o uno posterior", nunca la marca a secas: hay 969 pedidos entregados sin registro de despacho, y contando la marca el embudo pinta una fuga del 26 % que no existe»** | 0,8 s |
| T-2 | Rentabilidad y Rotación | `/operativo/tableros/rentabilidad` | La **dispersión de 834 puntos**, que se pinta entera porque la cruz son las medianas del conjunto. Y la **salvedad de costo vigente**, encima de la cifra | 2,2 s |
| T-3 | Cliente y Posventa | `/operativo/tableros/cliente-posventa` | Que **SOPORTE entra pero no recibe los bloques de valor del cliente**, y el sobre **dice cuáles omitió** | 2,1 s |
| T-4 | Operación y Última Milla | `/operativo/tableros/operacion` | ★ **El único tablero sin dinero, y el único que abren BODEGA y DESPACHO.** Lo sostienen dos cosas: su línea de permisos y que su consulta no selecciona un importe | 1,4 s |
| T-5 | Costo de la Operación | `/operativo/tableros/costo-operacion` | El flete, con los **24 envíos sin tarifar excluidos** y el informe diciendo cuántos excluyó | 0,3 s |
| T-6 | Abastecimiento | `/operativo/tableros/abastecimiento` | Arranca en «entregadas» porque con «todas» **el mejor proveedor (99,71 %) pasa a ser el peor (91,77 %)** por órdenes que canceló Compras | 0,6 s |
| T-7 | Gobierno del Dato | `/operativo/tableros/gobierno-dato` | ★ **La frescura del almacén, tabla por tabla.** Es el tablero que audita al propio pipeline, y enlaza con el bloque 3 | 0,5 s |

**Qué decir de conjunto**: **«siete tableros, diecinueve decisiones de dirección, y cero
tablas nuevas: las veintiuna del almacén bastaron.»** Y: **«cada tablero responde en una
sola petición. Con seis, ClickHouse cayéndose a mitad de carga dejaría medio tablero
pintado.»**

---

## Bloque 3 — El pipeline que alimenta todo esto (3:00) ★ MOMENTO FUERTE

**Ruta**: `http://localhost:8081` (Airflow) → **DAGs** → **`retailmind_dwh`** → pestaña
**Graph**.

**Antes de grabar**: arranca el perfil `airflow`, **despausa el DAG** (trampa 3) y ten una
corrida **ya terminada y verde** (trampa: la corrida completa son **23,5 minutos**, ver
0.6).

**Qué se ve**: el grafo con **22 tareas** — 21 de carga, una por tabla, más `validar_dwh` —
y sus aristas de dependencia.

**Qué decir**:
- **«Una tarea por tabla. Cada una es una línea de shell que llama al cargador; no hay
  lógica de negocio dentro de Airflow.»**
- **«El patrón de carga es atómico: se escribe en una tabla de staging, se valida contra el
  origen, y solo entonces se intercambian las tablas. Si la validación falla, se aborta y
  la tabla publicada no se toca.»**
- Abre **`validar_dwh`** y sus logs. Frase clave: **«cuarenta y nueve controles cruzados
  contra PostgreSQL, y cuadran exactamente. Entre ellos, el que compara el stock de cierre
  del último mes contra el inventario real, posición por posición: once mil cuatrocientas
  seis de once mil cuatrocientas seis, cero diferencias.»**
- Los tiempos reales, que impresionan y son ciertos: **«la corrida completa son 23 minutos;
  ocho de ellos son los ocho millones de movimientos de kardex.»**
- Si sale algún cuadro rojo, ver trampa 5: **es el guardia funcionando**.

**Carga**: la interfaz de Airflow, 1-2 s.

---

## Bloque 4 — Modelo 1: previsión de demanda (2:30)

**Rol**: GERENTE. **Ruta exacta**: `/inicio` → **Informes Tácticos** → **Informes de
Gerencia** → en la columna, **OTD-GER-13 · Previsión de demanda (3 meses)**.

**Qué se ve**: el gráfico con la serie histórica en trazo continuo, la previsión en trazo
**discontinuo** y la banda del 80 % por debajo; y la tabla con Previstas, Mínimo, Máximo,
Meses hist., MAPE y Vara (ingenuo).

**Qué decir**:
- **«Descomposición multiplicativa con factores estacionales encogidos. El histórico y la
  previsión van en el MISMO gráfico: un número solo, sin la serie que lo produjo, no es
  interpretable.»**
- **«Ningún número sin banda. Y el trazo discontinuo dice que esos puntos no han
  ocurrido.»**
- ★ **La parte honesta, y es la que hay que decir**: mira la columna **Método**. Hoy las
  **18.711 filas publican como `linea_base_estacional`**. Frase clave: **«el modelo se
  compara con un ingenuo estacional antes de publicarse, y cuando no lo supera publica la
  línea base y lo dice en la fila. Aquí no lo supera en ninguna serie, y la razón está en
  los datos: la serie de la década es demasiado regular, cada enero difiere menos del uno
  por ciento del siguiente. Un modelo que en ese escenario dijera que gana, estaría
  mintiendo.»**
- Señala la **salvedad** que la pantalla pinta encima de la tabla: **se prevé la venta, no
  la demanda** — lo que no se pudo vender por falta de stock no está.

**Carga**: 0,10 s.

---

## Bloque 5 — Modelo 2: alerta de abandono (2:30) ★ EL MOMENTO MÁS HONESTO

**Rol**: GERENTE. **Ruta exacta**: **Informes Tácticos** → **Informes de Ventas** → en la
columna, **OTD-VEN-19 · Clientes en riesgo**.

**Qué se ve**: **once tarjetas** repartidas 4+4+3. Las **tres primeras** son el lift
medido, su muestra y el dictamen **«¿Supera al azar?»** con su valor p. Y la tabla con el
**sparkline de compras por mes** en cada fila.

**Qué decir**:
- **«Supervivencia exponencial con la tasa propia de cada cliente. No es un modelo
  entrenado, y eso está declarado: no hay etiqueta de abandono en estos datos.»**
- ★ **Lee las tres primeras tarjetas en voz alta, tal como salgan.** Frase clave:
  **«las tres primeras cifras del informe no son el resultado: son el juicio sobre el
  resultado. El sistema publica el modelo con su lift, su muestra y su valor p a la vista,
  incluido cuando el veredicto es que no supera al azar.»**
- El reparto de hoy, verificado: **200 clientes en alerta crítica, 931 en atención, 32.594
  normales y 16.347 sin muestra suficiente**.
- Frase clave sobre los `sin_muestra`: **«los dos silencios más largos de la cartera caen
  ahí, y no es una casualidad: es que su propio silencio los dejó sin pedidos en la
  ventana. El modelo los expulsa, así que se publican igual, marcados, con su silencio
  real.»**
- Señala el **sufijo del título con la fecha ancla**: **«la recencia se mide contra la
  última compra registrada en el almacén, no contra el reloj. Si el pipeline se para, esta
  pantalla no empieza a inventar clientes perdidos.»**
- Señala un **sparkline**: **«el histograma va en la fila porque es lo que distingue de un
  golpe un hueco de una pendiente.»**

**Carga**: 0,10 s.

---

## Bloque 6 — La degradación declarada (1:30) ★ OPCIONAL PERO MUY BUENO

**Solo si te sientes cómodo con la terminal en cámara.** Demuestra el invariante de diseño
del sistema entero.

```bash
# 1 · Apaga SOLO la analítica
docker compose stop clickhouse
```

**Qué hacer**: recarga un tablero (p. ej. Omnicanal) y un informe compuesto.

**Qué se ve**: responden **200 en unos 4 segundos** con el aviso **«la analítica no está
disponible»**, y los bloques que salen de PostgreSQL **siguen pintados**.

**Qué decir**: **«ClickHouse es solo analítica. Con la analítica apagada, todo el sistema
operativo sigue funcionando: el catálogo, las ventas, el inventario, la posventa. Solo se
degradan los informes compuestos y los tableros, y lo dicen. Por eso el orquestador de
contenedores declara la dependencia como "arrancado" y nunca como "sano".»**

```bash
# 2 · Vuelve a levantarla
docker compose start clickhouse
```

Recarga: **se recupera sin reiniciar el backend**. Dilo: **«y se recupera sola.»**

**Cierre del video** (y de los tres): **«tres millones de pedidos en una década, veintiuna
tablas de almacén con veintisiete millones de filas, cuarenta y nueve controles que
cuadran, y dos modelos que publican su propio veredicto. Lo que se ha visto no es una
maqueta: es el sistema funcionando.»**

---

## Anexo A — Cifras verificadas hoy (2026-08-16)

Úsalas tal cual; están comprobadas contra el sistema en marcha.

**PostgreSQL (base `retailmind`, 17 GB)**
pedidos **2.999.993** · líneas de pedido **7.622.434** · movimientos de kardex
**8.008.396** · posiciones de inventario **11.406** · facturas de venta **2.855.378** ·
envíos **2.110.095** · devoluciones **145.734** · tickets **179.851** · reseñas
**263.077** · clientes **50.072** · productos **6.215** · variantes **6.222** · órdenes de
compra **134.588** · proveedores **30** · usuarios **50.182**

**ClickHouse `retailmind_dwh`**
**21 tablas · 26.971.352 filas · 1,54 GiB** · última corrida buena **2026-08-12 17:24** ·
duración **1.409 s (23,5 min)** · **49 controles** en verde
*(legado `fact_eventos`: 2.931.837 filas)*

**Seguridad de motor**
**9** roles de grupo · **95** políticas RLS · **50** tablas con RLS · **34** triggers de
horario · **109** columnas con ACL en **14** tablas · **18** funciones SECURITY DEFINER ·
**56** ventanas horarias, todas 24/7

**Interfaz**
**73** informes (**30** simples + **43** compuestos) · **7** tableros · **1** panorama ·
**2** modelos

**Colas vivas para las demostraciones**
cola de preparación **26.551** · listos para despacho **8.846** · órdenes por aprobar
**7** · devoluciones solicitadas **10.563** · tickets vivos **92.426** · ítems defectuosos
pendientes **27.803** · posiciones bajo mínimo **146** · reseñas por moderar **40.262**

**Modelos**
previsión **18.711** filas, **todas** en `linea_base_estacional` · alerta **50.072**
clientes: **200** críticos, **931** atención, **32.594** normales, **16.347** sin muestra ·
fecha ancla **2034-12**

## Anexo B — Correcciones respecto del enunciado de la tarea

Cuatro cifras del enunciado no coinciden con el sistema; se corrigieron en este guion:

| Enunciado | Verificado hoy |
|---|---|
| base ~19 GB | **17 GB** |
| 6.221 variantes | **6.222** |
| 126 columnas con ACL | **109**, en 14 tablas |
| DAG ~12 min | **23,5 min** (1.409 s medidos) |
