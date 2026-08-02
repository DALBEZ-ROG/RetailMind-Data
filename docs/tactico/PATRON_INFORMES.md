# Patrón de informes tácticos — RetailMind

**Estado**: establecido el 2026-07-25 con el módulo de **Ventas** (OTD-VEN-01, 02, 08, 10, 15) y
confirmado el mismo día con **Inventario** (OTD-INV-01, 02, 03, 05, 06, 07, 08), que se construyó
con 2 clases Java + 1 archivo TS + el enganche, sin tocar la pantalla, el servicio ni los estilos.
El 2026-07-26 se sumaron **Compras** (OTD-COM-01, 02, 08, 10) y **Logística** (OTD-LOG-01, 02,
06, 11) con el mismo coste: 4 clases Java + 2 archivos TS + el enganche, cero componentes,
servicios o estilos nuevos. Ese mismo día cerraron el nivel táctico **Soporte** (OTD-SOP-01, 04,
05) y **Gerencia** (OTD-GER-01, 04, 06, 08, 09), otra vez con 4 clases Java + 2 archivos TS + el
enganche. **Los SEIS departamentos están cubiertos**: lo que queda del nivel táctico son los
objetivos COMPUESTOS, que son trabajo de ETL → ClickHouse y no de este patrón.

**Fuente de verdad de QUÉ informe existe**: `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md`.
Este documento explica CÓMO se construye.

---

## 1. Reglas de alcance (no se replantean)

- Solo se implementan aquí los objetivos **SIMPLES** (estado actual, consulta directa a
  PostgreSQL). Los **COMPUESTOS** (tendencias, comparación entre períodos, agregación del
  histórico) pertenecen a la fase ETL → ClickHouse y **no** entran en este patrón.
- Los informes tácticos se consultan **POR PANTALLA**, con filtros y registros visibles.
  **NO hay exportación a PDF.** Los PDF siguen siendo solo para documentos operativos
  (facturas, guías de retorno, comprobantes).
- **Segregación financiera**: ninguna columna de dinero llega a Bodega ni a Despacho. Se declara
  en `SecurityConfig` y en `nav-model.ts`, y el motor la respalda (esos grupos no tienen SELECT
  sobre `pedido.total`, `carrito` ni `meta_venta`).

## 2. Anatomía: qué se reutiliza y qué se crea

| Pieza | Ruta | ¿Se reutiliza? |
|---|---|---|
| Molde del servicio (validación, paginación, KPIs, identidad) | `informes/InformeServiceBase.java` | **Se reutiliza tal cual** |
| Servicio del departamento | `informes/Informes<Depto>Service.java` | Se crea (1 por departamento) |
| Controlador del departamento | `informes/Informes<Depto>Controller.java` | Se crea (1 por departamento) |
| Autorización de la ruta | `security/SecurityConfig.java` | Se añaden 1-2 líneas |
| Contrato de tipos del frontend | `core/models/informe.model.ts` | **Se reutiliza tal cual** |
| Cliente HTTP de informes | `core/services/informes.service.ts` | **Se reutiliza tal cual** (sirve a los 6) |
| Pantalla genérica | `features/operativo/informes/informes-departamento.component.*` | **Se reutiliza tal cual** |
| Estilos de la pantalla | `features/operativo/informes/informes.scss` | **Se reutiliza tal cual** |
| Definiciones del departamento | `features/operativo/informes/definiciones/<depto>.informes.ts` | Se crea (1 por departamento) |
| Registro de departamentos | `features/operativo/informes/definiciones/catalogo-informes.ts` | Se añade 1 línea |
| Permiso, ruta, sidebar, breadcrumb | `nav-model.ts`, `app.routes.ts`, `app.component.*` | Se añade 1 entrada en cada uno |

**Resultado**: un departamento nuevo son **2 archivos Java + 1 archivo TypeScript + 5 líneas de
enganche**. Ningún componente Angular nuevo, ningún servicio Angular nuevo, ningún estilo nuevo.

## 3. Contrato: un solo sobre para todos los informes

Todos los endpoints devuelven la misma forma, y por eso una sola pantalla los pinta todos:

```jsonc
{
  "items":  [ { /* fila, claves snake_case tal como salen del SQL */ } ],
  "total":  4083,          // filas que cumplen el filtro (no las de la página)
  "page":   0,
  "size":   25,
  "resumen": [             // tarjetas de cabecera; opcional
    { "etiqueta": "Monto total", "valor": 5716436.55, "tipo": "moneda" }
  ],
  "alcance": "propio"      // opcional: el backend recortó el informe a quien pregunta
}
```

`tipo` de un KPI o de una columna: `texto | numero | moneda | porcentaje | fecha | fechaHora |
dias | estrellas | booleano | chip`. Es lo único que la pantalla necesita saber para formatear.

## 4. Convención de rutas

```
GET /api/informes/{departamento}/{informe}
```

Ejemplos vigentes: `/api/informes/ventas/cartera-pedidos`, `/api/informes/ventas/por-vendedor`,
`/api/informes/ventas/carritos-abandonados`, `/api/informes/ventas/moderacion`,
`/api/informes/ventas/avance-meta`.

Todos son **GET**. `SecurityConfig` cierra `/api/informes/**` a cualquier otro método con
`denyAll()`: un informe nunca escribe.

En la app: `/operativo/informes/{departamento}`.

---

## 5. Cómo se añade un informe NUEVO (los 5 pasos)

### Paso 0 — Verificar el dato ANTES de codificar

Antes de escribir una línea, se consulta la BD real (MCP `retailmind` o psycopg2) y se comprueba
que la consulta **devuelve filas coherentes**. El catálogo dice qué columnas soportan el objetivo,
pero el estado real manda: por ejemplo, en OTD-VEN-08 los carritos abandonados tienen
`fecha_actualizacion` **NULL** (el trigger touch solo dispara si se les mueve algo), así que la
antigüedad hay que medirla sobre `COALESCE(fecha_actualizacion, fecha_creacion)` o el informe sale
vacío. Ese tipo de detalle solo aparece mirando los datos.

### Paso 1 — Backend: método en el servicio del departamento

```java
@Transactional(readOnly = true)   // OBLIGATORIO: sin tx no hay SET LOCAL ROLE
public Map<String, Object> miInforme(String filtro, int page, int size) {
    String f = opcion(filtro, VALORES_PERMITIDOS, "filtro");   // lista blanca → 400 si no

    final String from = """
            FROM tabla t
            WHERE (?::varchar IS NULL OR t.columna = ?::varchar)
            """;
    Object[] args = { f, f };

    Map<String, Object> res = paginar(
            "SELECT ... " + from + " ORDER BY ...",
            "SELECT count(*) " + from, args, page, size);

    return conResumen(res, List.of(kpi("Etiqueta", valor, "moneda")));
}
```

Reglas del SQL, sin excepción:

1. **Las cadenas SQL son constantes del código.** Lo del usuario viaja SOLO en `args`.
2. **Guarda NULL por parámetro**: `(?::tipo IS NULL OR col = ?::tipo)` — el mismo valor se pasa
   dos veces. Así un filtro ausente no exige otra consulta ni concatenar un `AND`.
3. **Castear siempre** los parámetros hacia PostgreSQL (`?::date`, `?::varchar`, `?::bigint`):
   en contexto no tipado un `null` sin cast falla.
4. `paginar()` recibe el `SELECT` sin `LIMIT/OFFSET` — los añade él y duplica los argumentos.
5. Informes de pocas filas por naturaleza (metas, ranking del equipo): `sobre(items)` en vez de
   `paginar()`, y `sinPaginar: true` en la definición del frontend.

### Paso 2 — Backend: endpoint en el controlador del departamento

Un `@GetMapping("/mi-informe")` que recibe los filtros como `@RequestParam(required = false)` y
delega. Sin lógica.

### Paso 3 — Autorización

En `SecurityConfig`, dentro del bloque de informes tácticos, **del más específico al más general**:

```java
.requestMatchers(HttpMethod.GET, "/api/informes/<depto>/<informe-restringido>")
    .hasAnyAuthority("ADMIN", "GERENTE")
.requestMatchers(HttpMethod.GET, "/api/informes/<depto>/**")
    .hasAnyAuthority(/* roles destinatarios del catálogo */)
```

Los roles salen de la columna «Dashboard y rol destinatario» del catálogo. **Si el informe lleva
monto, Bodega y Despacho no van en la lista.**

### Paso 4 — Frontend: declarar el informe

En `definiciones/<depto>.informes.ts`, un objeto `DefinicionInforme`:

```ts
{
  id: 'OTD-XXX-NN',
  endpoint: 'mi-informe',        // coincide con el @GetMapping
  titulo: '…', descripcion: '…', icono: 'material_icon',
  roles: ['ADMIN', 'GERENTE'],   // ESPEJA SecurityConfig (no lo sustituye)
  vacio: 'Mensaje claro cuando el filtro no devuelve nada.',
  filtros: [ { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [...] } ],
  columnas: [ { campo: 'total', titulo: 'Total', tipo: 'moneda', monto: true } ]
}
```

Tipos de filtro disponibles: `select`, `fecha`, `periodo` (mes, se envía como `anio`+`mes`),
`numero`, `texto` (con `debounce: true` para buscar mientras se escribe).

### Paso 5 — Enganche de navegación (departamento nuevo, no informe nuevo)

1. `catalogo-informes.ts`: `<depto>: INFORMES_<DEPTO>`.
2. `nav-model.ts`: permiso `informes<Depto>` en `PermisoNav` + `ROLES_POR_PERMISO` + una acción
   en el área `informes` de `DASHBOARD_AREAS`.
3. `app.routes.ts`: ruta `operativo/informes/<depto>` con `data: { departamento: '<depto>' }` y
   `roleGuard([...])` **con los mismos roles que SecurityConfig**.
4. `app.component.ts`: entrada en `routeMap` (breadcrumb) + getter `canInformes<Depto>`.
5. `app.component.html`: enlace en la sección «Informes Tácticos» del sidebar.

---

## 6. Seguridad: las tres capas que hay que respetar

1. **`SecurityConfig`** decide quién llama al endpoint (por rol del JWT).
2. **`PgSessionRoleAspect`** asume `SET LOCAL ROLE grp_*` dentro de la transacción: los GRANTs por
   columna, la RLS y la restricción horaria las aplica **el motor**. Por eso todo método de
   informe va en `@Transactional(readOnly = true)` — sin transacción la consulta corre como
   `retailmind_app`, sin privilegios, y se saltaría la seguridad de motor.
3. **`nav-model.ts` + `roles` de la definición** solo evitan mostrar y disparar lo que igual se
   negaría; **no son la autorización**, son cortesía de UI.

**Recorte por identidad** (el vendedor solo ve lo suyo en OTD-VEN-02): se hace en el servicio con
`rolActual()` / `usuarioActualId()` de `InformeServiceBase`, forzando el filtro y devolviendo
`alcance: "propio"` en el sobre para que la pantalla lo explique al usuario. Nunca se confía en un
id que venga del cliente.

**Horario**: un rol fuera de su ventana ni siquiera puede iniciar sesión (script 53, motivo
`fuera_horario`). Si la sesión ya estaba abierta, la RLS `pol_horario` devuelve 0 filas o la BD
lanza SQLState 42501, que `GlobalExceptionHandler` traduce a **403** con mensaje legible.

## 7. Errores

| Situación | Excepción en el servicio | HTTP |
|---|---|---|
| Filtro fuera de la lista blanca, fecha mal formada, rango invertido | `IllegalArgumentException` | 400 |
| El informe no puede calcularse (p. ej. no hay meta fijada para el período) | `IllegalStateException` | 409 |
| Registro pedido que no existe | `NoSuchElementException` | 404 |
| Privilegio o ventana horaria negados por el motor | (SQLState 42501) | 403 |

En el frontend, **siempre** `mensajeError()` de `api-error.util.ts` en un snackbar; y estado vacío
con el texto `vacio` de la definición — nunca una tabla rota.

## 8. El módulo de Ventas como referencia

| Objetivo | Endpoint | Filtros | Notas de implementación |
|---|---|---|---|
| OTD-VEN-01 Cartera de pedidos | `cartera-pedidos` | estado, canal, desde, hasta, buscar | Paginado (4.083 pedidos). KPI de monto aún en proceso |
| OTD-VEN-02 Ventas por vendedor | `por-vendedor` | desde, hasta | Sin paginar. Cancelados aparte; el online (vendedor NULL) sale como fila propia; VENDEDOR recortado a lo suyo |
| OTD-VEN-08 Carritos abandonados | `carritos-abandonados` | estado, diasMinimos | Antigüedad con `COALESCE(fecha_actualizacion, fecha_creacion)` |
| OTD-VEN-10 Cola de moderación | `moderacion` | tipo, diasMinimos | UNION de reseñas pendientes + preguntas pendientes o publicadas sin respuesta. Solo ADMIN/GERENTE |
| OTD-VEN-15 Venta contra la meta | `avance-meta` | periodo (mes) | Sin paginar. Venta real = facturas no anuladas del mes (misma definición que `MetasVentaService`, script 48) |

## 9. El módulo de Inventario: dónde el molde se estira

Inventario es el caso INVERSO a Ventas y por eso vale como segunda referencia: seis de sus siete
informes son de **cantidades** y **BODEGA es la destinataria principal**, no la excluida.

| Objetivo | Endpoint | Filtros | Notas de implementación |
|---|---|---|---|
| OTD-INV-01 Bajo mínimo | `bajo-minimo` | bodega, buscar | Paginado (162 de 1.406). Exige `stock_minimo > 0`: el default 0 significa «sin mínimo definido» |
| OTD-INV-02 Stock por bodega | `stock-bodega` | bodega, situacion, buscar | Paginado (1.406). `situacion` resuelve con/sin existencias y con reserva en un solo parámetro |
| OTD-INV-03 Kardex | `kardex` | buscar, bodega, naturaleza, tipo, desde, hasta | Paginado (13.287). El signo lo pone `tipo_movimiento.factor`; el saldo corrido solo se lee acotando a una variante |
| OTD-INV-05 Ajustes | `ajustes` | tipo, estado, motivo, bodega, desde, hasta | Paginado (53). Unidades desde el KARDEX; anulado sale en neto 0 |
| OTD-INV-06 Transferencias | `transferencias` | estado, bodega, desde, hasta | Paginado (71). Unidades solo de la SALIDA; 'pendiente'/'cancelada' con 0 es el estado real, no un hueco |
| OTD-INV-07 Valor del inventario | `valor-inventario` | bodega, categoria | Sin paginar (19 filas). **CON MONTO**: ADMIN/GERENTE/ANALISTA |
| OTD-INV-08 Sobre-stock | `sobre-stock` | bodega, buscar | Paginado (184). Exige `stock_maximo > 0`, por el mismo motivo que INV-01 |

Tres lecciones que este módulo añade al patrón:

1. **La segregación financiera no siempre la respalda el motor.** En Ventas sí (grp_bodega no
   tiene SELECT sobre `pedido.total`). En Inventario **no**: grp_bodega conserva SELECT sobre
   `producto_variante.costo` por la excepción declarada del script 41 (valoriza su kardex al
   recibir y al reingresar un RMA). Por eso el informe con dinero va en **su propio endpoint**
   —`/valor-inventario`, cerrado en `SecurityConfig`— en vez de esconder columnas: cuando el
   motor no puede ser la última línea, la RUTA es el control.
2. **Los GRANTs del destinatario condicionan los JOIN.** grp_bodega **no** tiene SELECT sobre
   `categoria` ni `producto_categoria`: unirlas en los seis informes de cantidades los rompería
   con 42501 → 403 justo para quien más los usa. La categoría solo aparece en INV-07, cuyos roles
   sí la leen. Antes de escribir un JOIN hay que mirar la matriz de privilegios del rol
   destinatario, no solo la del que prueba.
3. **Cabecera sin detalle: la cantidad vive en el kardex.** `ajuste_inventario` y
   `transferencia_bodega` son SOLO cabecera (deuda técnica declarada). Las unidades se agregan
   desde `movimiento_inventario` por `referencia_tipo`/`referencia_id`, con `LEFT JOIN LATERAL`,
   y ahí hay que decidir qué se cuenta: en el ajuste, `sum(cantidad * factor)` (así un anulado sale
   en 0, que es la verdad); en la transferencia, solo `salida_transferencia` (o el par
   salida+entrada de una recibida contaría doble).

Verificación de que las tres capas funcionan (2026-07-25): BODEGA recibe 200 en los seis informes
de cantidades y **403** en `/valor-inventario`; Gerencia y Administración 200 en los siete;
Despacho 403 en los siete. Con la ventana horaria de bodega cerrada, un JWT todavía válido recibe
**200 con 0 filas** — la RLS `pol_horario` actuando en el motor, que es la prueba de que
`SET LOCAL ROLE` se está asumiendo de verdad.

## 10. Compras y Logística: cuándo el corte financiero es la CONSULTA

Estos dos módulos (2026-07-26) añaden el tercer caso de segregación. Hasta aquí había dos: el
motor la respalda (Ventas) o la respalda la RUTA porque el motor no puede (OTD-INV-07). Compras
aporta el tercero: **el corte lo hace el SQL**.

| Objetivo | Endpoint | Filtros | Notas de implementación |
|---|---|---|---|
| OTD-COM-01 Órdenes de compra | `ordenes` | estado, proveedor, desde, hasta | Paginado (865). El estado sintético `pendiente_aprobacion` agrupa 'borrador'+'enviada': NO existe un estado 'aprobada' — aprobar deja la orden en 'confirmada'. Avance de recepción desde el DETALLE |
| OTD-COM-02 Cuentas por pagar | `cuentas-por-pagar` | estado, situacion, proveedor | Paginado (839). `estado` es la columna; `situacion` se recalcula HOY contra `fecha_vencimiento` (vencida / por_vencer ≤7d / vigente / saldada). Lo pagado = `monto_original − saldo_pendiente` |
| OTD-COM-08 Defectuosos | `defectuosos` | estado, origen, proveedor, buscar | Paginado (38). **SIN dinero** ⇒ BODEGA entra. Proveedor NULL = «(por asignar)», el cuello de botella real |
| OTD-COM-10 Catálogo proveedor–producto | `catalogo-proveedor` | buscar, proveedor, oferta | Paginado (1.106). `es_mas_barato` sale de un LATERAL sobre TODAS las ofertas activas, no de una ventana sobre la página filtrada |
| OTD-LOG-01 Cola de despacho | `cola-despacho` | estado, canal, transportista, buscar | Paginado (48). El universo son los TRES estados del tramo de salida, no solo 'preparado' |
| OTD-LOG-02 Envíos | `envios` | estado, transportista, desde, hasta, buscar | Paginado (2.872). `atrasado` solo para lo que sigue en tránsito; novedades abiertas aparte porque BLOQUEAN la entrega |
| OTD-LOG-06 Devoluciones de cliente | `devoluciones` | estado, motivo, desde, hasta, buscar | Paginado (196). Apto/defectuoso/rechazado en columnas separadas: cada uno tiene destino distinto |
| OTD-LOG-11 Costo de envío | `costo-envio` | zona, transportista, desde, hasta | Sin paginar (9 filas). **CON MONTO**: ADMIN/GERENTE |

Tres lecciones que estos módulos añaden:

1. **Un tercer lugar donde puede vivir el corte financiero: la CONSULTA.** OTD-COM-08 incluye a
   BODEGA «en cantidades, sin montos», pero el motor no lo impone — el script 45 dio a grp_bodega
   y grp_compras SELECT sobre `item_defectuoso.costo_unitario` y
   `devolucion_proveedor.monto_credito` porque el flujo operativo de la devolución al proveedor los
   necesita. Como el informe comparte endpoint entre roles con y sin dinero, la barrera no puede
   ser la ruta: es que el SQL **no selecciona** ninguna columna de monto. Queda declarado en el
   javadoc para que nadie la añada «porque la BD lo deja».
2. **La ZONA de un envío no es una columna: es una resolución.** `envio` no guarda zona; se deriva
   de la dirección del pedido por especificidad **ciudad > provincia > país**, la misma cadena de
   `VentasService.asignarEnvioPorZona`. Agrupar por país daría un promedio sin sentido, porque una
   zona nacional y una local cubren a la vez la misma dirección y solo la más específica aplicó la
   tarifa. Los envíos sin zona configurada salen como fila propia con costo 0.00 en vez de
   esconderse.
3. **El LATERAL va ANTES del WHERE.** Los informes con agregado por fila (líneas de la orden,
   novedades del envío, inspección de la devolución) se arman con el triple
   `tabla + lateral + filtro` de Inventario, no con un `from` monolítico: el conteo de la
   paginación reutiliza `tabla + filtro` sin pagar el agregado, y concatenar el LATERAL después
   del WHERE es un error de sintaxis que solo aparece en ejecución.

Verificación de las tres capas (2026-07-26, ocho endpoints × ocho roles): COMPRAS 200 en sus
cuatro; DESPACHO 200 en LOG-01/02/06 y **403 en `/costo-envio`**; BODEGA 200 solo en
`/defectuosos` y `/devoluciones`; SOPORTE 200 solo en `/devoluciones`; ADMIN y GERENTE 200 en los
ocho; VENDEDOR y ANALISTA 403 en los ocho. Un valor fuera de lista blanca devuelve **400** con el
listado de permitidos, y un POST sobre cualquier ruta de informes **403** por el `denyAll()`.

## 11. Soporte y Gerencia: el dato sensible de seguridad

Estos dos módulos (2026-07-26) cierran el nivel táctico relacional y añaden el caso que faltaba:
un informe cuyo dato no es dinero sino **seguridad**.

| Objetivo | Endpoint | Filtros | Notas de implementación |
|---|---|---|---|
| OTD-SOP-01 Bandeja de tickets | `soporte/bandeja` | estado, prioridad, categoria, agente, buscar | Paginado (248; 128 vivos). El estado sintético `pendientes` —los tres estados no terminales— es el valor por DEFECTO: la bandeja se abre por lo que sigue vivo. Orden por vencimiento, no por fecha |
| OTD-SOP-04 Tickets por categoría | `soporte/por-categoria` | desde, hasta | Sin paginar (8). Parte de `categoria_ticket` con LEFT JOIN: una categoría en cero es información. El `%` se calcula sobre el total DEL PERÍODO con `sum(count(*)) OVER ()` |
| OTD-SOP-05 Carga por agente | `soporte/por-agente` | desde, hasta | Sin paginar (7). Parte de los TICKETS, no de los usuarios: la fila «(sin asignar)» es el dato más accionable y va primera |
| OTD-GER-01 Foto del día | `gerencia/foto-dia` | fecha | Sin paginar (20). CUATRO bloques de agregados en una tabla; el bloque de pendientes es AL MOMENTO, no del día |
| OTD-GER-04 Cupones | `gerencia/cupones` | situacion, tipo, buscar | Paginado (33; 7 vigentes). `situacion` replica las tres condiciones de canje de `DescuentosService`, no `activo` |
| OTD-GER-06 Marketing vigente | `gerencia/marketing` | tipo, vigencia, buscar | Paginado (65; 20 vigentes = 6 promos + 6 campañas + 8 banners). UNION de tres tablas bajo una misma definición de vigencia |
| OTD-GER-08 Auditoría | `gerencia/auditoria` | usuario, tabla, accion, desde, hasta | Paginado (7.073). **SENSIBLE**. jsonb devuelto como texto, sin aplanar |
| OTD-GER-09 Intentos de acceso | `gerencia/accesos` | resultado, correo, desde, hasta | Paginado (~1.500). **SENSIBLE**. Un solo filtro `resultado` cubre desenlace y motivo |

Tres lecciones que estos módulos añaden:

1. **El dato sensible de seguridad se corta con las DOS capas, aunque una baste.** GER-08 y
   GER-09 son ADMIN/GERENTE, el corte más estricto del sistema, y cada uno se apoya en una capa
   distinta: en `/accesos` la RUTA y el MOTOR dicen lo mismo (solo grp_administrador y grp_gerente
   tienen SELECT sobre `log_acceso`, script 53), pero en `/auditoria` **el motor no alcanza** —
   grp_analista también lee `log_auditoria` (script 19), así que ahí el corte lo hace la RUTA,
   igual que en INV-07 y LOG-11. Por eso los dos van en su **propia línea** de `SecurityConfig`
   aunque hoy coincida con la del departamento: ampliar Gerencia a otro rol no debe arrastrarlos.
2. **El valor por defecto de un filtro es una decisión de diseño, no un detalle.** SOP-01 arranca
   en el estado sintético `pendientes`, GER-04 en `situacion=vigente` y GER-06 en
   `vigencia=vigente`, porque el informe debe abrirse respondiendo la pregunta que lo motiva y no
   volcando el histórico. Se declara con `valorInicial` en el frontend y la lista blanca del
   backend lo acepta como un valor más — el sintético se traduce a la condición real en el
   servicio (`pendientes` → `estado NOT IN ('resuelto','cerrado')`), nunca concatenando SQL.
3. **Un informe «del día» tiene que decir cuándo el día está vacío.** Los datos del sistema
   llegan al 2026-07-22 en pedidos y al 23 en cobros; consultar hoy devuelve los bloques del día
   en cero, que es la verdad, pero una tabla con solo pendientes se lee como una avería. GER-01
   emite una fila explícita («Sin pedidos, cobros ni facturas en la fecha consultada») cuando los
   tres bloques del día están vacíos, y el resumen incluye SIEMPRE **«Último día con pedidos»**
   para saber a dónde mover el filtro. El estado vacío se resuelve con datos, no con un texto fijo.

Verificación de las tres capas (2026-07-26, ocho endpoints × ocho roles): SOPORTE 200 en sus tres
informes y **403 en los cinco de Gerencia**; ADMIN y GERENTE 200 en los ocho; VENDEDOR, COMPRAS,
BODEGA, DESPACHO y ANALISTA **403 en los ocho**. En particular, `/gerencia/auditoria` y
`/gerencia/accesos` responden 200 **solo** a ADMIN y GERENTE, y 403 a los otros seis roles y al
anónimo. Un valor fuera de lista blanca devuelve **400** con el listado de permitidos en los ocho
informes, y POST/PUT/DELETE sobre cualquier ruta de informes **403** por el `denyAll()`.

## 12. Criterio canónico: `canal` es el MEDIO, nunca el tipo de cliente

Regla vinculante para todo informe presente y futuro:

> **`pedido.canal` es el MEDIO por el que entró el pedido (`web` / `tienda` / `telefono`) y NO
> debe usarse para inferir el tipo de cliente ni para etiquetar B2B/B2C.** Un negocio puede
> comprar por la tienda en línea y un consumidor final por mostrador: agrupar por `canal` y
> rotularlo «B2B vs. B2C» es una lectura falsa del dato.

**Estado del corte B2B/B2C: NO ES DERIVABLE — descartado el 2026-07-30.** La clasificación
B2B/B2C **no está capturada en ninguna columna** y —esto es lo que cambió— **tampoco puede
derivarse del comportamiento de compra**. El diagnóstico
`docs/estrategico/DIAGNOSTICO_SEGMENTO_CLIENTE.md` (2026-07-30, solo lectura, 4.083 pedidos ·
10.384 líneas · 3.887 facturas · 72 clientes) cerró con veredicto **(c) POBLACIÓN HOMOGÉNEA**: no
hay dos poblaciones de compra en los datos. Las comprobaciones, en dos bloques:

*Lo que no existe en el esquema:*

| Comprobación | Resultado |
|---|---|
| `pedido.tipo_venta` | **NO EXISTE** — `pedido` tiene 19 columnas y ninguna es `tipo_venta`; tampoco existe en ninguna de las ~110 tablas |
| Valores «mayoreo» / «menudeo» | **no existen** en el modelo; el único CHECK de clasificación de pedido es `canal ∈ ('web','tienda','telefono')` |
| `grupo_cliente` / `segmento_cliente` / `cliente_segmento` | **0, 0 y 0 filas** |
| `cliente.grupo_cliente_id` poblado | **0 de 72 clientes** |
| `cliente.tipo_identificacion` | **'cedula' en los 72** — ningún RUC que permita inferir «negocio» |
| `factura_venta.identificacion` | **10 dígitos en las 3.887 facturas** (el RUC ecuatoriano tiene 13) |
| Lista de precios por grupo · crédito a cliente | **no existe ninguna tabla**: el 100 % de los pedidos se cobra al contado |

*Lo que el comportamiento tampoco permite derivar:*

| Dimensión | ¿Separa dos poblaciones? |
|---|---|
| Unidades por línea | **No — la prueba decisiva**: 10.378 de 10.384 líneas (**99,94 %**) piden 1–4 unidades; techo histórico 12 por línea y 24 por pedido. **No existe ni una sola compra de volumen** |
| Ticket | **No** — unimodal log-normal, una sola cresta, sin hombro a la derecha |
| Líneas por pedido | **No** — máximo 5 para todos los clientes y los tres canales |
| Mezcla de categorías | **No** — Δ máx. **0,62 pp** entre el cliente grande y el pequeño |
| Método de pago | **No** — Δ máx. **1,44 pp** |
| Canal | **No** — dispersión del ticket medio entre canales: 3,3 % |
| Regularidad de compra | **No** — CV 1,34–1,82 en los tres grupos (el más frecuente es el *más* irregular) |
| Frecuencia / facturación acumulada | **Solo en escala** — top 10 % = 49,34 % del ingreso, pero con el mismo ticket ($1.415,96 vs. $1.258,97) y la misma canasta: es **valor de cliente**, no **tipo de cliente** |

**Qué hacer con esto.** No se inventa la clasificación **ni se deja como pendiente**. El patrón
vigente es el de **OTD-VEN-16**: agrupar por la dimensión que SÍ existe (el canal, rotulado como
canal) y exponer la medida honesta de la ausencia — la columna y el KPI, hoy rotulados
**«Clientes con segmento registrado»**
(`count(DISTINCT cliente_id) FILTER (WHERE c.grupo_cliente_id IS NOT NULL)`), que valen **0** en
los tres canales. *Nota de rótulo (2026-07-30)*: antes se llamaban «Clientes negocio (B2B)»; se
cambió porque ese nombre sugería un segmento pendiente de llenarse, cuando lo que la cifra dice es
que **no hay segmentación registrada de ninguna clase**.

**Cuándo cambiaría el criterio.** No con un cambio de sistema, sino con un cambio de **negocio**.
Poblar `grupo_cliente` hoy no capturaría un dato: pondría una etiqueta arbitraria sobre una
población que se comporta como una sola. El criterio canónico solo pasaría a ser el segmento del
cliente si la operación cambiara de verdad —clientes con RUC, líneas de decenas o cientos de
unidades, precio por grupo, crédito comercial—, y en ese escenario el informe se rediseñaría con su
propia justificación. Por eso el propuesto **OTD-VEN-17** quedó **DESCARTADO** y no pospuesto
(`CATALOGO_OBJETIVOS_TACTICOS.md` §12, `BASE_ESTRATEGICA.md` §6.1.b).

**Si aparece un requerimiento que afirme que la clasificación vive en `pedido.tipo_venta`,
está equivocado**: esa columna **no existe** —nunca existió, en ninguna de las ~110 tablas— y un
informe que la consulte falla en tiempo de ejecución. Aplíquese el Paso 0 (§5) —verificar el dato
antes de codificar— antes de aceptar cualquier cifra de reparto B2B/B2C: hoy, cualquier cifra de
ese reparto es inventada.

**Lo que sí es un hecho medido sobre el cliente, y se puede usar:** la **omnicanalidad**. 64 de los
69 clientes con pedidos compran tanto por la tienda en línea como por un canal interno (mostrador o
teléfono); solo 2 son exclusivamente web y 3 exclusivamente internos. Un informe puede afirmar eso
—se deriva del cruce `pedido.cliente_id` × `pedido.canal`— sin salirse de la regla: sigue hablando
de **medio**, no de tipo de comprador.

---

## 13. Informes COMPUESTOS: el mismo molde, con ClickHouse debajo

*(2026-07-30, Fase 1 del pipeline ETL — primer informe: **OTD-VEN-06**, evolución de la venta mes a
mes y por categoría, `GET /api/informes/ventas/evolucion-mensual`.)*

La noticia es lo poco que cambia. El **sobre es el mismo**, la **pantalla genérica es la misma** y
las **definiciones declarativas son las mismas**: añadir OTD-VEN-06 al frontend fue un objeto más en
`ventas.informes.ts`. Lo que cambia son cuatro cosas, y conviene tenerlas juntas.

**1. Clases nuevas, no un patrón nuevo.** `InformeCompuestoServiceBase` extiende
`InformeServiceBase` y añade solo lo propio de la capa analítica; los servicios compuestos van en
un controlador APARTE (`InformesVentasCompuestosController`) que comparte la ruta base. Los 29
informes simples no se tocaron.

**2. Estos métodos NO llevan `@Transactional`.** La regla de oro del proyecto («todo acceso a
Postgres va dentro de una transacción o corre sin `SET LOCAL ROLE`») **no aplica**, porque no se
toca Postgres. Anotarlos abriría una transacción inútil y haría que `PgSessionRoleAspect` asumiera
un rol de grupo para nada.

**3. El corte financiero lo hace ÍNTEGRAMENTE la RUTA.** ClickHouse no tiene RLS por fila ni GRANT
por columna: el ETL escribe todas las columnas de dinero en tablas planas. Es el mismo caso ya
declarado de OTD-INV-07, OTD-LOG-11 y OTD-GER-08, pero aquí **siempre**, no como excepción. Cada
endpoint compuesto necesita su línea propia en `SecurityConfig`, antes del comodín del
departamento. Verificado por API en los 8 roles: ADMIN/GERENTE/ANALISTA 200; VENDEDOR, COMPRAS,
BODEGA, DESPACHO y SOPORTE 403. El VENDEDOR es el caso que importa: pasa el comodín
`/api/informes/ventas/**` y solo la línea específica lo detiene.

**4. Marca de agua obligatoria.** Todo sobre compuesto viaja con `datosAl` (el `max(fecha_carga)`
de su tabla) y la pantalla lo pinta. Un informe analítico puede llevar hasta 24 h de desfase; uno
que no dice de cuándo es su dato miente por omisión.

### Cuatro trampas de ClickHouse que costaron una corrida cada una

Son específicas del motor y no se deducen de saber SQL. Las cuatro se detectaron **probando por
API**, no leyendo el código.

| Trampa | Síntoma | Regla |
|---|---|---|
| **Alias que se llama como una columna** | `ILLEGAL_AGGREGATION: Aggregate function sum(margen) is found inside another aggregate function`, y su variante `... is found in WHERE` | ClickHouse resuelve los alias hacia atrás y sustituye la expresión. **Ningún alias de agregado puede llamarse igual que una columna de la tabla.** Convención adoptada: `t_` para totales, `n_` para conteos, `etiqueta_` para textos; los nombres del contrato de la API se reponen en el SELECT más externo |
| **División de `Decimal`** | El margen sale «15,00» donde debía decir «15,08» | Dividir dos `Decimal(14,2)` devuelve un `Decimal` con la escala del operando **izquierdo**, y trunca. Los **porcentajes** se calculan con `toFloat64(...)`; el **dinero** sigue siendo `Decimal` y esa regla no se toca |
| **`%M` en `formatDateTime`** | La marca de agua decía `30/07/2026 18:July` | En ClickHouse el minuto es **`%i`**; `%M` es el NOMBRE DEL MES, al revés que en `strftime` de C y que en Java |
| **`%` dentro de un bloque de texto que va a `.formatted()`** | 400 con `Conversion = '»'` | Es Java, no ClickHouse: un `%` en un comentario SQL dentro de un text block se lee como especificador de formato. Escríbase `%%` o redáctese sin el signo |

### La trampa de diseño, que es la peor de las cinco

La primera versión capturaba **todo** `DataAccessException` y devolvía «la analítica no está
disponible». Con eso, un **error de SQL** se presentaba como una caída de ClickHouse: el informe
salía vacío con el motor perfectamente vivo, y —lo grave— **la prueba por API pasaba en verde**,
porque el endpoint devolvía 200. El primer bug de alias estuvo a punto de colarse así.

La regla que quedó: **se degrada un fallo de CONEXIÓN; una consulta mal formada se propaga y sale
como 500.** La clasificación mira la causa raíz (`ConnectException`, `SocketTimeoutException`,
`SQLTransientConnectionException`…) y no solo el tipo de Spring, porque el driver envuelve el error
de red dentro de excepciones genéricas — y equivocarse en ese sentido sería el error simétrico:
devolver 500 con Docker apagado, justo lo que el invariante del sistema prohíbe.

Degradación verificada en vivo con el contenedor detenido: `/api/health` responde en ~5 s con
`status: UP, analytics: DEGRADED`; OTD-VEN-06 devuelve **200** en ~4 s con el sobre vacío,
`analiticaDisponible: false` y el aviso; los informes simples y la operación siguen intactos. Al
reencender ClickHouse el informe se recupera **sin reiniciar el backend**.

### Lo que `dim_fecha` compra, y que un `GROUP BY` no puede

OTD-VEN-06 emite una fila explícita **«(sin ventas)»** por cada mes del rango consultado que no
tuvo ninguna venta. Es la corrección de una debilidad conocida: OTD-GER-01 tuvo que fabricar a mano
su fila «Día sin movimiento», porque un `GROUP BY` sobre los hechos solo puede devolver los períodos
que existen en los hechos. Dos matices que son diseño y no descuido: las filas vacías se emiten solo
si el usuario **acotó el rango** (si no, se rellenarían cinco meses futuros de ruido), y el
anti-join respeta **todos** los filtros, de modo que «sin ventas» signifique «sin ventas de lo que
preguntaste».

---

## 14. Fase 2 del pipeline: ocho informes compuestos sobre el núcleo de la venta

Con `dim_cliente`, `fact_pedido` y `fact_flujo_caja` cargadas (2026-07-30), el nivel COMPUESTO pasa
de 1 a 9 informes. El coste por informe se mantuvo en lo que el patrón promete: **cero componentes,
cero servicios y cero estilos nuevos en Angular**; solo dos clases Java por departamento nuevo y
una entrada declarativa por informe.

| Objetivo | Endpoint | Tabla del DWH | Filas |
|---|---|---|---:|
| OTD-VEN-05 | `GET /api/informes/ventas/clientes` | `fact_pedido` ⋈ `dim_cliente` | 69 |
| OTD-VEN-07 | `GET /api/informes/ventas/ticket-promedio` | `fact_pedido` | 57 |
| OTD-VEN-09 | `GET /api/informes/ventas/formas-cobro` | `fact_flujo_caja` | 57 |
| OTD-VEN-12 | `GET /api/informes/ventas/cobros-fallidos` | `fact_flujo_caja` | 80 |
| OTD-VEN-13 | `GET /api/informes/ventas/evolucion-canal` | `fact_pedido` | 57 |
| OTD-LOG-12 | `GET /api/informes/logistica/tiempos-ciclo` | `fact_pedido` (hitos) | 4 |
| OTD-GER-02 | `GET /api/informes/gerencia/balanza` | `fact_flujo_caja` | 19 |
| OTD-GER-05 | `GET /api/informes/gerencia/descuento-cupones` | `fact_pedido` | 78 |

### 14.1 Lo que hay que saber antes de tocar estos ocho

**(1) El filtro que reparte NO puede aplicarse antes del reparto.** En VEN-13, VEN-09 y GER-05 hay
una columna de participación calculada con una ventana `PARTITION BY mes`. Si el filtro de canal o
de forma de pago entra en el `WHERE` del agregado, la única categoría visible se lleva el **100 %**
en todos los meses — un número perfectamente formateado y perfectamente falso. En esos tres el
filtro se aplica **después**, sobre el resultado ya repartido, y por eso su SQL lleva dos cláusulas
`WHERE` y los parámetros viajan concatenados (`base.argsCon(vista.args())`).

**(2) El rango por DÍA se compara sobre `toDate(columna)`, no sobre el `DateTime` crudo.** Dos
motivos en uno: `toDate` de una columna `DateTime('America/Guayaquil')` resuelve el día en la zona
del NEGOCIO —comparar contra un `toDateTime(toDate(?))` lo resuelve en la del servidor—, y de paso
el último día del rango entra COMPLETO. Con la comparación cruda, todo lo ocurrido después de la
medianoche del día final desaparece del informe sin aviso. Los informes MENSUALES siguen filtrando
sobre `mes`, que el ETL ya calculó resuelto.

**(3) Un promedio sin su denominador es una trampa, y LOG-12 es el caso.** Las cuatro etapas del
ciclo se miden sobre poblaciones DISTINTAS: 2.868 pedidos el tramo pago→preparación, 2.856 el de
preparación→despacho, 2.727 el de despacho→entrega y 3.696 el ciclo completo. La causa es del dato
—828 pedidos llegaron a 'entregado' sin registro de 'despachado'— y no del informe, pero presentar
88 h junto a 13 h sin decir sobre cuántos pedidos se calculó cada una señala el cuello de botella
en el sitio equivocado. Por eso cada fila trae `pedidos_medidos` y `cobertura_pct`, y el KPI «cuello
de botella» **excluye el ciclo completo**, que por construcción gana siempre.

**(4) La dimensión se lee con `FINAL`.** `dim_cliente` y `dim_producto` son `ReplacingMergeTree`.
Hoy la carga atómica publica una tabla recién creada y nunca conviven dos versiones de una clave,
pero el helper `dimension(tabla)` del molde envuelve la lectura en `SELECT * FROM … FINAL` porque es
lo que el motor de tabla promete: sobre 72 filas el coste es nulo y un cliente duplicado en un JOIN
duplicaría su fila del informe sin avisar. Nota de sintaxis: `JOIN tabla FINAL` **no** es válido en
el lado derecho de un JOIN; va como subconsulta.

**(5) Las fechas de día viajan formateadas como texto.** Misma regla del §11: `primera_compra` y
`ultima_compra` de VEN-05 salen ya como `%d/%m/%Y` desde ClickHouse y se declaran `tipo: 'texto'`.
Una fecha serializada la interpreta el formateador del frontend como UTC y puede mostrarla un día
antes — en una columna rotulada «última compra», eso es mentir.

**(6) Un KPI es texto plano y no pasa por el mapa de etiquetas del frontend.** El `etiqueta:` de una
columna traduce el código a lenguaje de negocio; una tarjeta de resumen no. El KPI «Motivo más
frecuente» de VEN-12 y los de canal en VEN-07/VEN-13 traducen en el SERVICIO, o la dirección lee
`fondos_insuficientes` en una tarjeta.

### 14.2 La matriz rol × endpoint, verificada por API

Ocho endpoints × ocho roles, con sesión real de cada rol (ninguna ventana horaria hubo que
ensanchar: la del jueves ya cubría la hora de la prueba, y se comprobó al terminar que
`grupo_horario` seguía idéntico en sus 56 filas).

| Objetivo | ADMIN | GERENTE | VENDEDOR | COMPRAS | BODEGA | DESPACHO | ANALISTA | SOPORTE |
|---|---|---|---|---|---|---|---|---|
| OTD-VEN-05 | 200 | 200 | **200** | 403 | 403 | 403 | 200 | 403 |
| OTD-VEN-07 | 200 | 200 | 403 | 403 | 403 | 403 | 200 | 403 |
| OTD-VEN-09 | 200 | 200 | 403 | 403 | 403 | 403 | 200 | 403 |
| OTD-VEN-12 | 200 | 200 | 403 | 403 | 403 | 403 | **403** | 403 |
| OTD-VEN-13 | 200 | 200 | **200** | 403 | 403 | 403 | 200 | 403 |
| OTD-LOG-12 | 200 | 200 | 403 | 403 | 403 | **200** | 200 | 403 |
| OTD-GER-02 | 200 | 200 | 403 | 403 | 403 | 403 | 200 | 403 |
| OTD-GER-05 | 200 | 200 | 403 | 403 | 403 | 403 | 200 | 403 |

**BODEGA queda fuera de los ocho** y **DESPACHO solo entra en LOG-12**, que es el único sin una sola
columna de monto. Ese 200 de DESPACHO es el caso que conviene mirar con atención: sale de
`fact_pedido`, que **sí** tiene `total`, `subtotal` y `monto_cupon`. ClickHouse no tiene GRANT por
columna ni RLS, así que aquí el corte financiero no lo puede hacer el motor y tampoco basta la ruta:
**lo hace la CONSULTA**, que no selecciona ningún importe. Es el tercer lugar donde vive el corte,
ya declarado en OTD-COM-08 — y en los compuestos es el único disponible cuando un rol sin acceso al
dinero necesita leer la misma tabla que lo contiene.

### 14.3 Degradación, verificada otra vez con el contenedor detenido

`/api/health` → `status: UP, analytics: DEGRADED`. Los cuatro compuestos probados (VEN-05, VEN-12,
LOG-12, GER-02) → **HTTP 200** con sobre vacío, `analiticaDisponible: false` y el aviso legible.
En la misma corrida, OTD-VEN-01 (simple, PostgreSQL) devolvió sus filas y el catálogo de la tienda
respondió 200 con productos: con ClickHouse apagado se cae la analítica y **nada más**.

---

## 15. Fase 3B: el inventario reconstruido, y la salvedad que va en pantalla

Los TRES compuestos de Inventario, servidos por `fact_movimiento_inventario` (13.287) y
`fact_stock_mensual` (21.122):

| Objetivo | Endpoint | Filas | Tabla |
|---|---|---|---|
| OTD-INV-04 | `/api/informes/inventario/rotacion` | 10 categorías | stock mensual + kardex |
| OTD-INV-09 | `/api/informes/inventario/capital-inmovilizado` | 19 meses | `fact_stock_mensual` |
| OTD-INV-10 | `/api/informes/inventario/mermas` | 11 motivos | `fact_movimiento_inventario` |

Coste del patrón, otra vez cumplido: **2 clases Java + 1 bloque en el archivo de definiciones**. Lo
único nuevo del molde es `salvedad`, y sirve a cualquier informe futuro.

### 15.1 `salvedad`: la tercera cosa que un informe compuesto puede decir de sí mismo

El sobre ya llevaba `datosAl` («de cuándo es este dato») y `avisoAnalitica` («ahora mismo no hay
dato»). La Fase 3B añade la tercera: **«así está calculado este dato»**.

OTD-INV-09 valoriza el inventario de meses pasados con el **costo VIGENTE**, porque el sistema no
guarda costo histórico (§8.3 del diseño; el margen se computa siempre en vivo contra
`producto_variante.costo`). La serie responde entonces *«cuántas unidades había cada mes, valoradas a
precio de hoy»* — volumen a moneda constante, que para la pregunta del objetivo («¿la bodega se llena
o se vacía de capital?») es incluso **más limpio**, porque aísla el efecto volumen del efecto precio.
Pero **no** es «cuánto valía la bodega aquel mes», y presentarlo como tal sería falso.

Por eso la salvedad se pinta **encima de la tabla y del resumen**, en azul y no en ámbar: no es un
fallo ni una degradación, es parte del dato. Una advertencia sobre cómo interpretar un número llega
tarde si se lee después del número. Y viaja además como KPI («Base de valoración: costo vigente») para
quien solo mire las tarjetas. Documentarla únicamente en el diseño no habría contado: el que lee la
pantalla no lee el diseño.

OTD-INV-10 arrastra la MISMA salvedad, pero solo en su modo valorizado — y por un motivo que no es
elegante sino forzado: los 56 movimientos de ajuste son exactamente los que **no traen
`costo_unitario`** en el origen (corrección C3B.3). Ajustar no valoriza, así que la merma solo se
puede valorar con el costo de hoy.

### 15.2 El corte financiero, en sus tres sabores a la vez

Este trío es el mejor ejemplo del asunto porque los tres mecanismos conviven:

| Informe | Quién corta | Cómo |
|---|---|---|
| OTD-INV-04 | la **CONSULTA** | no selecciona ni un importe, aunque `fact_stock_mensual` tiene `valor_cierre` |
| OTD-INV-09 | la **RUTA** | `SecurityConfig` deja fuera a BODEGA: es dinero de principio a fin |
| OTD-INV-10 | el **SERVICIO** | MIXTO: `puedeVerDinero()` sobre el rol del JWT decide si las columnas de valor **se seleccionan siquiera** |

El tercero es nuevo en los compuestos y es lo que el catálogo pedía («Bodega en cantidades;
valorizado solo Gerente, Administrador»). Verificado por API: con token de BODEGA la respuesta no
trae `valor_merma`, `valor_sobrante` ni `valor_neto` —no llegan vacías, **no existen**—, ni el KPI
«Valor perdido», ni la salvedad. Es el mismo criterio role-aware de `VentasService.colaPreparacion`,
y aquí es la única barrera posible: ClickHouse no tiene GRANT por columna (§8.2).

### 15.3 Matriz rol × endpoint, verificada por API (8 × 3)

| Informe | ADMIN | GERENTE | ANALISTA | BODEGA | VENDEDOR | COMPRAS | DESPACHO | SOPORTE |
|---|---|---|---|---|---|---|---|---|
| OTD-INV-04 | 200 | 200 | 200 | **200** | 403 | 403 | 403 | 403 |
| OTD-INV-09 | 200 | 200 | 200 | **403** | 403 | 403 | 403 | 403 |
| OTD-INV-10 | 200 | 200 | 200 | **200** (sin importes) | 403 | 403 | 403 | 403 |

BODEGA entra en dos de los tres y queda fuera del que es dinero, que es exactamente lo que dice el
catálogo. La matriz se probó **sin tocar `grupo_horario`**: se esperó a que abriera la ventana de las
08:00, porque el script 53 hace que `fuera_horario` **bloquee el login** y los ocho roles necesitan
un JWT real. Ensanchar el horario para probar habría sido escribir en la base para validar una
lectura.

### 15.4 Dos trampas de ClickHouse que volvieron a morder

1. **Dividir dos `Decimal` trunca a la escala del izquierdo.** Ya estaba escrito en §13 y volvió a
   pasar: `variacion_pct` salía en múltiplos enteros (21, 12, 2 …) y parecía un dato redondo en vez
   de uno truncado. El ratio va en `toFloat64`; el **dinero sigue en `Decimal`**, que es lo que
   `validar_dwh.py` exige.
2. **`any(x) OVER (… 1 PRECEDING)` devuelve el DEFECTO del tipo, no NULL, cuando no hay fila
   anterior.** El primer mes de la serie mostraba su capital entero ($8,38 M) como si fuera el
   incremento del período. Se guarda con un `count() OVER (… UNBOUNDED PRECEDING AND 1 PRECEDING)`,
   que cuenta FILAS y no valores — así un mes que de verdad cerrara en 0 se sigue comparando bien.

La misma familia de trampa —el LEFT JOIN que rellena con el defecto del tipo en vez de con NULL—
es la que obliga a fabricar el NULL desde `movimientos_mes` en la derivación de `fact_stock_mensual`.
Conviene recordarla: **en ClickHouse, «no hay dato» y «el dato es cero» se parecen demasiado.**

---

## 16. Fase 3C: la última milla, y un departamento con el corte financiero partido en dos

Los CUATRO compuestos que sirven `fact_envio` (2.872) y `fact_novedad_envio` (176):

| Objetivo | Endpoint | Filas | Tabla |
|---|---|---|---|
| OTD-LOG-03 | `/api/informes/logistica/cumplimiento-promesa` | 5 transportistas | `fact_envio` |
| OTD-LOG-04 | `/api/informes/logistica/dias-transito` | 5 / 19 / 3 según el corte | `fact_envio` |
| OTD-LOG-05 | `/api/informes/logistica/novedades` | 14 (tipo × desenlace) | `fact_novedad_envio` |
| OTD-LOG-11 · serie | `/api/informes/logistica/costo-envio-mensual` | 19 meses | `fact_envio` |

Coste del patrón, cumplido de nuevo: **0 clases Java nuevas** —los cuatro entran en el servicio y el
controlador de Logística que ya existían por OTD-LOG-12— y **1 bloque por informe** en el archivo de
definiciones. Ni un componente, ni un servicio Angular, ni un estilo.

La última fila no es un objetivo del catálogo: es la **serie temporal** que OTD-LOG-11 dejó pendiente
para ClickHouse al reclasificarse a SIMPLE (§6 del catálogo). El simple da la FOTO («¿dónde nos cuesta
más caro?»), éste da la SERIE («¿se está encareciendo?»). Son preguntas distintas y conviven: la ruta
se llama `costo-envio-mensual` y no una variante de `costo-envio` precisamente para que quede claro
que el informe simple sigue vivo.

### 16.1 La transformación cara vive en el ETL, y esa es la razón de que el ETL exista

`envio` **no tiene columna de zona**. Se resuelve por la cadena ciudad > provincia > país contra
`zona_envio`, con precedencia por especificidad — la misma lógica de
`VentasService.asignarEnvioPorZona`. Los cuatro conteos, verificados contra el diseño:

```
ciudad     Quevedo (local)        181        provincia  Los Rios (provincial)   596
pais       Ecuador (nacional)   2.078        (ninguno)  sin_zona                 17
```

**El modo de fallo que acecha**: las tres zonas configuradas cuelgan del mismo país, así que agrupar
por país —la simplificación que sale sola mirando el esquema— manda **2.855 de 2.872 envíos a UNA
FILA**. No falla ningún JOIN ni salta ninguna excepción: sale una tabla de una línea con el 99,4 %
del volumen, y el negocio parece operar en un solo sitio.

Por eso la tabla trae además `zona_nivel`, y los cuatro conteos quedan **auditables desde el propio
almacén** con un `GROUP BY`. El coste de los tres LEFT JOIN con desempate se paga una vez por
corrida y no una vez por consulta — que es literalmente para lo que sirve un ETL.

### 16.2 Cada informe declara su denominador, y aquí hay TRES distintos

Lección heredada de OTD-LOG-12 y agravada en esta fase. De los 2.872 envíos:

```
con fecha de despacho ......... 2.872     con entrega real .............. 2.727
con fecha estimada ............ 2.856     con real + estimada ........... 2.723
```

Los 145 que faltan son 120 `devuelto` y 25 `en_transito`: **no llegaron tarde — no llegaron**.
Contarlos como incumplimiento miente en una dirección; ignorarlos en silencio, en la otra. Por eso
`entregado_a_tiempo` viaja **NULL** cuando falta cualquiera de las dos fechas (un 0 diría «llegó
tarde», que no es «no se sabe») y cada fila lleva `medidos` + `cobertura_pct`.

### 16.3 El filtro `agrupar`: una lista blanca que SÍ entra en el SQL

El catálogo pide el tránsito «por transportista **y período**». En vez de duplicar el informe,
OTD-LOG-04 expone un filtro `agrupar` ∈ {`transportista`, `mes`, `zona`} y llama `grupo` a la primera
columna en los tres casos, para que la pantalla genérica no sepa cuál está activo.

Es el **único sitio de todo el nivel táctico donde un filtro decide un fragmento de SQL**, así que la
lista blanca deja de ser higiene y pasa a ser la barrera: el usuario elige la CLAVE de un `Map`, y la
expresión (`transportista`, `formatDateTime(mes, '%Y-%m')`, `zona`) es una constante del código. Un
valor no previsto sale como 400 antes de tocar el motor.

El mes se formatea a texto **en el SQL** y no se manda como `Date`: es la lección de §11 (un `date`
puro lo lee el formateador como UTC y muestra un día menos), y en una columna de agrupación eso
rotularía la serie con el mes equivocado.

### 16.4 Un mismo departamento, una misma tabla, y el corte financiero partido

Es la primera fase donde **dos informes de la misma tabla tienen destinatarios distintos por dinero**:

| | LOG-03 · LOG-04 | serie de costo |
|---|---|---|
| Tabla | `fact_envio` | `fact_envio` |
| ¿Selecciona importes? | **No** | **Sí** |
| DESPACHO | 200 | **403** |
| Barrera efectiva | la **CONSULTA** | la **RUTA** |

`fact_envio` tiene `costo`, y ClickHouse no tiene GRANT por columna: el motor no distingue estos dos
informes de ninguna manera. Lo único que separa a DESPACHO del dinero es que **una consulta no lo
selecciona y la otra sí**, más la línea de `SecurityConfig` que cierra la segunda. Mismo mecanismo ya
declarado en OTD-COM-08 y OTD-LOG-12, ahora sobre la misma tabla y a la vez.

Detalle deliberado: las dos rutas de dinero se enumeran **por nombre** (`costo-envio`,
`costo-envio-mensual`) y no con un comodín `costo-envio*`. Un endpoint futuro que empezara igual
heredaría el permiso sin que nadie lo hubiera decidido.

OTD-LOG-05 cambia de reparto otra vez: entra **SOPORTE** —la incidencia de entrega acaba en su
bandeja— y sale el **ANALISTA**, que no participa en la posventa.

### 16.5 Matriz rol × endpoint, verificada por API (8 × 4 = 32 celdas)

| Informe | ADMIN | GERENTE | ANALISTA | DESPACHO | SOPORTE | BODEGA | VENDEDOR | COMPRAS |
|---|---|---|---|---|---|---|---|---|
| OTD-LOG-03 | 200 | 200 | 200 | **200** | 403 | 403 | 403 | 403 |
| OTD-LOG-04 | 200 | 200 | 200 | **200** | 403 | 403 | 403 | 403 |
| OTD-LOG-05 | 200 | 200 | **403** | **200** | **200** | 403 | 403 | 403 |
| serie de costo | 200 | 200 | 403 | **403** | 403 | 403 | 403 | 403 |

**0 discrepancias.** La matriz se probó **sin tocar `grupo_horario`**: la ventana de los ocho roles
estaba abierta a la hora de correrla. Ensanchar el horario para probar sería escribir en la base para
validar una lectura.

### 16.6 Degradación, verificada apagando el contenedor

Con `docker stop` sobre ClickHouse y **sin reiniciar el backend**:

```
/api/health .................... 200 en 4,30 s · status UP · analytics DEGRADED
los 4 informes nuevos .......... 200 en ~4,1 s · analiticaDisponible=false · aviso legible
informes SIMPLES (PostgreSQL) .. 200 · costo-envio 9 filas · envios 2.872   ← intactos
```

Y al volver a levantarlo, los cuatro se recuperan **solos**, sin reinicio. Es el invariante del
sistema por escrito: con Docker apagado todo funciona y solo la analítica se degrada, con aviso.
Recordatorio de §13 que sigue vigente: **solo un fallo de CONEXIÓN degrada**; una consulta mal formada
se propaga como 500, porque disfrazarla de «analítica no disponible» deja la prueba por API en verde
sobre un bug de SQL.

### 16.7 Los tres supuestos del diseño que no se sostuvieron

Registrados en `docs/estrategico/CORRECCIONES_DISENO_ETL.md` (C3C.1 a C3C.3) **antes de cerrar la
fase**, como manda la regla de trabajo. El que más golpea al informe:

> §5.9 dice que `accion` vale `reprogramar` / `devolver_almacen`. Son los **verbos del API**; lo
> guardado es el participio (`reprogramada` / `devuelto_almacen`). Un filtro escrito desde el diseño
> casa con **cero filas sin dar error**, y OTD-LOG-05 diría que las incidencias se resuelven
> reprogramando en el 100 % de los casos — ocultando justo las 120 que acaban en venta perdida.

Por eso la lista blanca de este informe (`ACCIONES_NOVEDAD`) sale **de los datos y no del documento**,
y la carga avisa si aparece un valor no previsto.

---

## 17. Fase 4: la posventa cierra el nivel táctico — 39 de 39

Sexta y **última** fase de CARGA del pipeline (`docs/estrategico/DISENO_ETL_CLICKHOUSE.md` §9.5).
Con ella las **19 tablas** del modelo están cargadas y validadas, y hay **32 informes compuestos
en producción** de los 39 del catálogo. Los 7 restantes —COM-03/04/05/06/07/11/12— tienen sus
tablas cargadas desde la Fase 3A y están pendientes solo de conectar: no necesitan ETL nuevo.

Seis tablas nuevas y dieciséis informes:

| Tabla | Filas | Informes que sirve |
|---|---:|---|
| `dim_promocion_producto` | 232 | GER-07 |
| `fact_devolucion` | 196 | VEN-14 · LOG-07 · LOG-09 · LOG-10 |
| `fact_devolucion_linea` | 274 | LOG-08 · SOP-08 (mitad devoluciones) |
| `fact_ticket` | 248 | SOP-02 · SOP-03 · SOP-06 · SOP-07 · SOP-08 |
| `fact_resena` | 344 | VEN-11 |
| `fact_devolucion_proveedor` | 38 | COM-09 |

Y cuatro informes más **sin tabla nueva** —GER-03, GER-10 y GER-11 salen de `fact_venta_linea`,
de la Fase 1, y GER-07 la cruza con el puente—, que es la prueba de que el criterio de
agrupación del diseño (19 tablas para 39 objetivos) se sostuvo hasta el final.

Coste del patrón, otra vez el prometido: **2 clases Java nuevas** (Soporte y Compras, los dos
departamentos que aún no tenían servicio compuesto) + 1 bloque por informe en las definiciones.
Cero componentes, cero servicios Angular, cero estilos.

### 17.1 Cinco informes sobre una tabla de 248 filas

`fact_ticket` es la mejor relación informes/filas del modelo, y no por casualidad: los cinco
objetivos de Soporte preguntan por el mismo hecho —un ticket— desde ejes distintos, así que
comparten tabla, filtros y definiciones. La consecuencia práctica es que **una definición mal
puesta se equivoca cinco veces a la vez**, y de ahí que las dos decisiones del módulo vivan en
constantes con nombre (`PRIMERA_RESPUESTA`, `SLA_HORAS`) y no repartidas por el SQL.

### 17.2 SOP-02 parte la base en CUATRO, y no publica una tasa

El aviso venía del diseño y los datos lo confirmaron con creces:

```
cerrados a tiempo ..........  12
cerrados tarde .............  64
abiertos dentro de plazo ...   0     ← la categoría vacía se muestra igualmente
abiertos y YA VENCIDOS ..... 172     ← la accionable
                             ───
                             248
```

El porcentaje de cumplimiento se calcula **solo sobre los 76 cerrados** (15,79 %) y la columna
«Cerrados» va a su lado. Sobre 248, como si todos hubieran terminado, daría **4,8 %**: un número
falso que mezcla lo incumplido con lo desconocido.

Que los «abiertos dentro de plazo» sean **0** no es un fallo del informe: el seed llega al
2026-07 y los plazos son de 2 a 72 horas, así que toda la cola viva está vencida. La fila se
pinta con su cero — ocultar una categoría vacía hace creer que no existe.

**Las dos columnas de abiertos se calculan en la CONSULTA, no en la carga.** Dependen de
`now()`, y un veredicto grabado a las 03:00 del ETL estaría equivocado a las 09:00. El almacén
guarda `fecha_limite` y `fecha_cierre`; la partición la hace el informe al mirarlo.

### 17.3 La definición de «primera respuesta» va en la pantalla, con su coste medido

SOP-06 adopta la del catálogo —primer mensaje del equipo **visible** para el cliente— y la manda
al sobre en `salvedad`. Lo que el diseño no había hecho es medir la alternativa:

```
tickets con ALGÚN mensaje del equipo ................ 244
  cuya primera intervención es una NOTA INTERNA .....  32   (+1,35 h de media)
  sin ninguna respuesta visible (solo notas) ........  51
tickets con primera respuesta VISIBLE ............... 193   ← la adoptada
```

Las dos poblaciones viajan en columnas contiguas del informe. Una advertencia sin cifra no se
puede poner en una pantalla; con ella, el lector juzga si la definición le sirve.

### 17.4 Los DOS informes de muestra débil la declaran, y no se maquillan

`SobreInforme.salvedad` ya existía desde INV-09. Aquí hace un trabajo distinto: no advierte de
un método, advierte de que **no hay bastantes casos**.

**OTD-COM-09** (recuperación al proveedor). 38 ítems, 8 devoluciones, **6 resoluciones** en 6
meses y entre unos pocos de los 11 proveedores. El resumen empieza por el tamaño de la muestra y
solo después da el dinero, y cada fila trae la columna `resoluciones`, que es el denominador de
todo lo demás:

```
Resoluciones (la muestra) ... 6      Costo del pool ......... $9.349,93
Devoluciones ................ 8      Recuperado ............. $5.220,94
Meses con casos ............ 15        en nota de crédito ... $4.196,85
Proveedores implicados ...... 8        en reposición ........ $1.024,09
```

**OTD-GER-07** (efecto de las promociones). 184 líneas dentro de ventana, 123 con descuento
aplicado, frente a **3.217 líneas de base**. Tres decisiones para que la debilidad no se lea como
un hallazgo:

1. la tabla se ordena por **volumen durante la promoción**, NUNCA por la variación — ordenar por
   la variación pondría arriba exactamente los pares de una o dos ventas;
2. `lineas_durante` y `lineas_antes` son columnas de la tabla, con semáforo: rojo si son 0,
   ámbar por debajo de 5;
3. las medias son **por día de calendario** de cada tramo. Una ventana de 15 días comparada en
   totales contra los 400 días previos diría que las promociones hunden la venta.

### 17.5 Ocho supuestos del diseño que no se sostuvieron

Registrados en `docs/estrategico/CORRECCIONES_DISENO_ETL.md` (C4.1 a C4.8) **antes de cerrar la
fase**. Los tres que más golpean al informe:

> **C4.2** — §5.10 mide el ciclo del RMA como «cierre − solicitud», y ese cierre existe en
> **35 de 196** devoluciones. Las 18 rechazadas también terminaron —y son las más rápidas, porque
> rechazar no exige recibir la mercancía—, así que LOG-07 muestra las dos medidas con su `n_`.
> Sin declararlo, el informe publica el ciclo del 17,9 % del proceso y lo presenta como el ciclo.

> **C4.4** — §5.13 manda unir `fact_resena` a `dim_producto` por `producto_id`. La dimensión
> tiene grano de VARIANTE: el JOIN da **347 filas donde hay 344**, y la reseña de un producto de
> tres variantes pesa el triple en un ranking de productos. La tabla denormaliza el producto y
> nunca se une.

> **C4.7** — §5.14 dice que `origen` vale `inspeccion_rma` / `recepcion_compra`. El CHECK del
> motor admite `rma` y `recepcion`. Un filtro escrito desde el diseño **vacía el informe entero
> sin dar un error**. Es la segunda reincidencia exacta de C3C.3, y por eso la regla ya no es una
> nota al pie: la lista blanca sale del CHECK del motor, nunca del documento.

### 17.6 La matriz de permisos, 16 endpoints × 8 roles

Verificada por API: **128 celdas, 0 discrepancias** con el reparto del catálogo. El corte
financiero deja fuera a BODEGA y DESPACHO de los **seis informes con dinero** —VEN-14, LOG-10,
GER-03, GER-10, GER-11 y COM-09— y en ninguno lo respalda el motor: ClickHouse no tiene GRANT por
columna, así que la barrera es la RUTA. Las excepciones que sí entran son deliberadas:

```
BODEGA   → solo LOG-08 (motivos y destino de la mercancía, «en cantidades»)
DESPACHO → solo LOG-09 (tasa de devolución, «en conteos»)
COMPRAS  → SOP-08 (ranking de productos problemáticos) y COM-09
SOPORTE  → los 5 de su departamento + LOG-07, LOG-08 y LOG-10
ANALISTA → todo menos LOG-10 y los cuatro de la mesa de ayuda que no son SOP-03
```

En LOG-08 y LOG-09 la ausencia de dinero **la garantiza la consulta y no el motor** —
`fact_devolucion` lleva `monto_total`—, mismo mecanismo ya declarado en OTD-COM-08 y OTD-LOG-12.
Y las dos ampliaciones de Soporte (ANALISTA en SOP-03, COMPRAS en SOP-08) van en línea propia
ANTES del comodín del departamento: ampliar el comodín habría arrastrado a los otros seis.

### 17.7 Degradación, verificada apagando el contenedor

Con `docker stop` sobre ClickHouse y **sin reiniciar el backend**:

```
los informes nuevos ............ 200 en ~4,1 s · analiticaDisponible=false · aviso legible
informes SIMPLES (PostgreSQL) .. 200 · bandeja 248 filas · cartera 4.083   ← intactos
```

Y al volver a levantarlo se recuperan **solos**. Durante la construcción de esta fase la política
de §13 hizo su trabajo: cinco consultas fallaron con `ILLEGAL_AGGREGATION` —un alias de agregado
llamado igual que la columna que agrega— y salieron como **500**, no como «analítica no
disponible». Con la captura amplia habrían quedado en verde y con la tabla vacía.

---

## 18. Fase 5: los siete de Compras, y el catálogo táctico completo

Última tarea de conexión. **No carga ni una fila**: las cuatro tablas que alimentan estos
informes —`dim_proveedor`, `fact_orden_compra`, `fact_compra_linea` y `fact_flujo_caja`— llevaban
validadas desde las Fases 2 y 3A. Con ella el catálogo queda **cerrado**: 30 objetivos SIMPLES y
39 COMPUESTOS, ninguno pendiente.

| Objetivo | Endpoint | Fuente | Filas | Roles |
|---|---|---|---|---|
| OTD-COM-03 | `compras/puntualidad-pago` | `fact_flujo_caja` (egreso) | 11 / 19 / 2 | + ANALISTA |
| OTD-COM-04 | `compras/gasto-mensual` | `fact_orden_compra` | 19 / 11 / 2 | + ANALISTA |
| OTD-COM-05 | `compras/cumplimiento-plazo` | `fact_orden_compra` | 11 / 19 | ADMIN·GERENTE·COMPRAS |
| OTD-COM-06 | `compras/ciclo-compra` | `fact_orden_compra` | 11 / 19 / 2 | + ANALISTA |
| OTD-COM-07 | `compras/rechazos` | `fact_compra_linea` | 11 / 5 / 19 / 10 | + BODEGA (mixto) |
| OTD-COM-11 | `compras/entregas-incompletas` | **PostgreSQL** | 11 (paginado) | + BODEGA (mixto) |
| OTD-COM-12 | `compras/evolucion-costo` | `fact_compra_linea` | 1.041 (paginado) | + ANALISTA |

Coste del patrón, otra vez el prometido: **0 clases Java nuevas** (los servicios y controladores
de Compras ya existían por COM-01/02/08/10 y COM-09) + 1 bloque de definición por informe + 2
líneas de `SecurityConfig`. Ni un componente, servicio o estilo de Angular.

### 18.1 OTD-COM-11 es SIMPLE, y respetarlo tuvo consecuencias

Era **el último objetivo SIMPLE del catálogo sin construir**, y la tentación de meterlo con los
compuestos era fuerte: agrega, compara proveedores y su dato está en el almacén. Pero la regla de
§3 del catálogo es clara —*agregar sobre la foto presente no convierte un informe en compuesto;
comparar un período contra otro, sí*— y COM-11 no compara períodos. Va contra PostgreSQL, con
`@Transactional(readOnly = true)`, y **no tiene eje de mes**: añadirlo lo habría convertido en una
serie temporal, es decir, en otro informe.

Que sea simple trae además la ventaja de que el motor vuelve a estar debajo: la consulta jamás
toca `orden_compra.total` ni `cuenta_por_pagar`, sobre los que grp_bodega no tiene privilegio.

### 18.2 Dos informes MIXTOS más, y por qué el motor no basta en ninguno

El catálogo da COM-07 y COM-11 a Bodega «en cantidades, sin montos». Es el patrón de OTD-INV-10:
la ruta la deja ENTRAR y la **consulta** decide qué columnas se seleccionan, con
`conValorizacion` en el sobre para que la pantalla lo sepa.

Que el corte no pueda apoyarse en el motor tiene una causa distinta en cada uno, y las dos están
documentadas desde antes:

* **COM-07** vive en ClickHouse, que no tiene GRANT por columna. Nada nuevo.
* **COM-11** vive en PostgreSQL y aun así el motor no alcanza: grp_bodega **conserva a propósito**
  `SELECT` sobre `orden_compra_detalle.precio_unitario` (excepción declarada del script 41 — lo
  necesita para valorizar el kardex al recibir). La BD le dejaría calcular el valor faltante.

Es el mismo mecanismo de OTD-COM-08, con una diferencia que conviene no perder: COM-08 no tiene
NINGUNA columna de dinero para nadie; COM-07 y COM-11 sí las tienen, y lo que cambia es a quién se
le envían.

### 18.3 Lo que hace distinto a cada informe (y que no se ve en el SQL)

1. **COM-03 separa el anticipo del retraso.** `dias_desvio_vencimiento` es negativo cuando se
   pagó antes, así que un promedio único los cancela. Distribuidora Deportiva Andina tiene un
   desvío medio de **−9,2 días** —«le pagamos con nueve de adelanto»— y **4 de sus 18 facturas se
   pagaron tarde**, con 44 días de retraso acumulado. Las tres medidas van en columnas separadas y
   el conteo de cada grupo al lado.
2. **COM-04 agrupa por el mes de la FACTURA.** Ver [C5.1](../estrategico/CORRECCIONES_DISENO_ETL.md#c51-el-mes-del-gasto-de-compras-no-es-el-mes-de-la-orden):
   con el mes de la orden, $4,6 M cambian de mes y el total sigue cuadrando al centavo.
3. **COM-05 y COM-06 NO miden sobre la misma población,** y es deliberado: 825 pares con promesa y
   llegada contra 839 órdenes con llegada. Cada uno declara su base en una columna («Medidas») y
   en su salvedad. Es la lección de C2.7 aplicada antes de tropezar.
4. **COM-07 divide por lo que LLEGÓ.** C3.2, ya conocida; lo que aporta esta fase es que el
   informe la DICE en pantalla en vez de dejarla en el docstring.
5. **COM-11 recorta a las órdenes ya entregadas.** Ver [C5.2](../estrategico/CORRECCIONES_DISENO_ETL.md#c52-las-259-líneas-incompletas-son-tres-cosas-distintas):
   con las 259 líneas del catálogo, el mejor proveedor pasa a ser el peor.
6. **COM-12 usa la ventana para la que se invirtió el `ORDER BY` de la tabla.**
   `fact_compra_linea` es la única ordenada por `(producto_variante_id, proveedor_id,
   fecha_emision)`: `lagInFrame` recorre esa misma partición. Con un detalle que ClickHouse no
   perdona: **`lagInFrame` rellena la primera fila de cada partición con el DEFECTO del tipo, no
   con NULL** — misma familia que el LEFT JOIN de la Fase 3B. Comparar contra ese `0,00` daría
   «subida del 100 %» en los 1.041 primeros precios y el informe entero diría que todo se ha
   disparado. La frontera se marca con `row_number() > 1`, que es un hecho de la partición y no un
   valor que pueda coincidir con un dato real.

### 18.4 Tres trampas de sintaxis, y solo una es de ClickHouse

Las tres reventaron en tiempo de ejecución y ninguna la vio el compilador:

1. **ClickHouse — `ILLEGAL_AGGREGATION`** (tercera fase consecutiva). `countIf(a_tiempo = 1) AS
   a_tiempo` seguido de `sumIf(monto, a_tiempo = 0)` no compila en el motor; con el alias
   renombrado a `n_a_tiempo`, sí.
2. **Java — un bloque de texto RECORTA el espacio final de cada línea.** Concatenar la parte
   variable de un SELECT con un bloque de texto produce `SELECTpr.razon_social`. Va con una cadena
   normal, no con un bloque.
3. **Java — `String.formatted()` lee el bloque ENTERO, comentarios incluidos.** El patrón de fecha
   de ClickHouse hay que escribirlo con el por-ciento duplicado, o el formateador de Java se come
   un argumento antes de que el patrón llegue al motor.

La tercera se cobró una víctima que merece quedar escrita: el comentario que explicaba la trampa
llevaba dentro un especificador suelto, en el mismo bloque de texto, y tumbó la consulta con
`IllegalFormatConversionException`. **La explicación de la trampa fue la trampa.**

Las tres se manifestaron como error del servidor —500 o 400— y no como «analítica no disponible»,
que es exactamente lo que la política de §13 existe para conseguir.

### 18.5 Matriz rol × endpoint, verificada por API

56 celdas, **0 discrepancias**:

```
informe      endpoint                ADMI GERE COMP ANAL BODE DESP VEND SOPO
OTD-COM-03   puntualidad-pago         200  200  200  200  403  403  403  403
OTD-COM-04   gasto-mensual            200  200  200  200  403  403  403  403
OTD-COM-05   cumplimiento-plazo       200  200  200  403  403  403  403  403
OTD-COM-06   ciclo-compra             200  200  200  200  403  403  403  403
OTD-COM-07   rechazos                 200  200  200  403  200  403  403  403
OTD-COM-11   entregas-incompletas     200  200  200  403  200  403  403  403
OTD-COM-12   evolucion-costo          200  200  200  200  403  403  403  403
```

Dos detalles del orden de las líneas de `SecurityConfig`, que va de lo específico a lo general:

* Los cuatro con ANALISTA se enumeran **por nombre** y no con un comodín, para que un endpoint
  futuro de Compras no herede el permiso sin que nadie lo haya decidido. Mismo criterio que las
  dos rutas de costo de Logística (§16).
* **COM-05 no lleva ni un importe y aun así excluye al ANALISTA.** No es un descuido: el catálogo
  lo reserva a Compras y Gerencia porque es material de negociación con el proveedor. Cuando el
  catálogo y la intuición financiera discrepan, manda el catálogo.

### 18.6 Validación: 41 controles contra PostgreSQL, por API

La cifra se toma de la **respuesta HTTP**, no de ClickHouse directamente, de modo que se valida la
cadena entera —consulta, servicio, controlador y serialización— y no solo el SQL. **41 de 41 con
Δ = 0**, incluidos $16.084.462,74 pagados, $22.467.387,27 facturados, 825 pares comparables, 185
unidades rechazadas y los 1.041 pares de COM-12 con sus precios inicial y final.

Una diferencia esperada y verificada como CORRECTA: contando motivos de rechazo, PostgreSQL en
crudo da 6 valores y el almacén 5. Es la normalización de C3.3 haciendo su trabajo —el valor
tecleado a mano se funde con su sinónimo—, y por eso el control se escribe contra los **dos**
valores del origen.

### 18.7 Degradación, verificada apagando el contenedor

Con `docker stop` sobre ClickHouse y **sin reiniciar el backend**:

```
los seis compuestos ............ 200 en ~4,1 s · analiticaDisponible=false · aviso legible
OTD-COM-11 (PostgreSQL) ........ 200 en 0,15 s · 11 filas   ← intacto, como debe ser
```

Al volver a levantar el contenedor se recuperan **solos**, sin reiniciar nada. Y `validar_dwh.py`
sigue en **44 de 44**: esta fase no tocó ninguna tabla, y el control lo demuestra en vez de
suponerlo.

---

# 19. El nivel ESTRATÉGICO no usa este patrón — y por qué

**Fase E1-A, 2026-08-01.** Los tres primeros tableros de dirección (T-1 Omnicanal, T-2
Rentabilidad y Rotación, T-3 Cliente y Posventa) NO extienden `InformeServiceBase` ni reutilizan
`InformesDepartamentoComponent`. Viven en `com.retailmind.tableros` y en
`features/operativo/tableros/`, con su propio molde (`TableroServiceBase`), su propio sobre y su
propia pantalla. Esta sección existe para que nadie intente unificarlos: la separación es una
decisión, no una omisión.

## 19.1 Qué cambia, exactamente

| | Informe táctico | Tablero de dirección |
|---|---|---|
| **Unidad** | una tabla con filtros | varios elementos de naturaleza distinta que se leen JUNTOS |
| **Sobre** | `{items, total, page, size, resumen[]}` | `{kpis[], bloques[], salvedades[], datosAl, …}` |
| **Grano** | uno | uno POR BLOQUE (mes×canal, cliente, producto, paso del embudo…) |
| **Paginación** | server-side, obligatoria | ninguna: un tablero no se pagina, se lee entero |
| **Denominador** | recomendable | **obligatorio**: `bloque()` lanza excepción sin él |
| **Salida** | una tabla Material | serie, embudo, dispersión, Pareto, semáforo, ranking |

Meter seis elementos de grano distinto dentro de un sobre pensado para uno solo obliga a una de
dos cosas —seis peticiones o un `items` heterogéneo con columnas nulas— y las dos empobrecen el
tablero. Y al revés: darle a un informe táctico el sobre del tablero le quitaría la paginación,
que es lo que impide que OTD-VEN-01 descargue 4.083 filas.

## 19.2 Lo que SÍ se reutiliza, y no se duplica

`TableroServiceBase` **extiende** `InformeCompuestoServiceBase`. De ahí salen, sin una línea
repetida: la validación por lista blanca (`opcion`, `fecha`, `exigirRangoValido`), el acumulador
`Filtros` (fragmentos constantes + parámetros, jamás texto del usuario en el SQL), la lectura
`FINAL` de las dimensiones, la marca de agua y —lo más importante— `esFalloDeConexion`, que decide
qué degrada y qué no. Duplicar esa clasificación en dos jerarquías garantizaría que divergieran en
la primera excepción nueva del driver.

Dos cambios menores en el molde compartido, ambos sin efecto sobre los 69 informes: `Filtros`
ganó un constructor público explícito (el implícito de una clase anidada `protected` no alcanza
desde otro paquete) y `esFalloDeConexion` pasó de `private` a `protected`.

## 19.3 Un endpoint por tablero, no uno por elemento

`GET /api/tableros/{tablero}` devuelve el tablero entero. Cuatro razones, en orden de peso:
coherencia de la foto (los seis elementos comparten filtros y se leen juntos), una sola marca de
agua, **una sola decisión de degradación** —con seis llamadas, ClickHouse cayéndose a mitad de
carga dejaría medio tablero pintado y medio vacío— y coste (sobre ~64.000 filas, seis agregados en
una petición cuestan menos que seis peticiones).

**La excepción declarada**: los elementos que no tienen grano en el almacén —carrito abandonado
(T-1) y sobre-stock del presente (T-2)— los pide la PANTALLA con una segunda llamada a los
informes SIMPLES que ya existen, OTD-VEN-08 y OTD-INV-08. Efecto colateral bueno y verificado:
**esos dos bloques siguen vivos con ClickHouse apagado**.

## 19.4 El corte de rol, un escalón más fino

Hasta ahora el corte financiero era por ENDPOINT. T-3 estrena el corte **por bloque dentro del
mismo endpoint**: SOPORTE entra a `/cliente-posventa` y el servicio **no ejecuta** los bloques de
valor del cliente ni de reseñas. No es que se oculten en la pantalla —se declara cuáles se
omitieron y por qué, en `bloquesOmitidos`— y no lo puede hacer `SecurityConfig`, que solo alcanza
a la ruta. Verificado: 3 bloques con alcance `posventa` frente a 6 con alcance `completo`.

## 19.5 Verificación

Dos scripts nuevos en `retailmind/`, los dos tomando la cifra de la **respuesta HTTP** y no de una
consulta a ClickHouse escrita a mano —entre la tabla y la pantalla hay un agregado, un filtro y un
formateo, y es justo ahí donde se pierde una fila—:

- `validar_tableros.py` — **71 controles contra PostgreSQL, todos con Δ = 0**. Además de las
  sumas, comprueba invariantes de forma: que el embudo sea monótono, que el acumulado del Pareto
  cierre en 100 %, que el primer mes de la serie de capital llegue con la variación NULA y que las
  reseñas no se hayan multiplicado por variante.
- `matriz_tableros.py` — **24 celdas (8 roles × 3 tableros), 0 discrepancias**. Ensancha la
  ventana horaria del día en curso para poder probar a GERENTE y ANALISTA fuera de su franja, la
  restaura en un `finally` y **verifica la restauración releyéndola**.

## 19.6 Fase E1-B: los siete tableros, y lo que el patrón aguantó

**2026-08-01.** T-4 (Operación y Última Milla), T-5 (Costo de la Operación), T-6 (Abastecimiento)
y T-7 (Gobierno del Dato) completan los **siete tableros y las 19 decisiones de dashboard**, sin
crear una sola tabla en el almacén.

**Coste real del patrón**: 4 clases Java (una por tablero), 1 bloque de definición por tablero y
4 líneas de `SecurityConfig`. La pantalla genérica **no se tocó**; solo ganó dos trazados nuevos
—caja y bigotes, y mapa de calor— que sirven a cualquier tablero futuro. T-6 tiene ocho elementos
y T-7 tres: el sobre aguanta las dos formas sin cambiar.

### Lo que esta fase añadió al patrón

**Un corte que el motor no puede dar, comprobado por prueba automática.** T-4 es el único tablero
que Despacho y Bodega abren, y lo que lo permite es que su consulta no seleccione un importe.
ClickHouse no tiene GRANT por columna, así que esa regla no la respalda nada… salvo un control
que recorre la respuesta entera —KPI y todas las filas de todos los bloques— buscando nombres con
aspecto monetario, **y que se repite en los cinco roles**. Una regla que solo vive en un comentario
se rompe en el primer bloque nuevo; ésta falla la verificación.

**El tablero que vigila al resto.** T-7 no mide datos: mide la BITÁCORA del pipeline. Es el único
riesgo del sistema que no se detecta mirando una pantalla —una carga fallida a medias produce
cifras perfectas de ayer— y por eso sus dos elementos sensibles (auditoría y accesos) se sirven de
los informes SIMPLES de PostgreSQL que ya existían: **siguen respondiendo con el almacén apagado**,
que es justo lo que hace falta cuando lo que se sospecha es que el almacén falló.

**Los bloques externos ganaron su propio resumen.** El sobre de un informe simple trae `resumen[]`,
y ahora la pantalla lo pinta DENTRO de la tarjeta del bloque externo, nunca arriba con los KPI del
tablero: son de otra fuente y de otra base de datos, y juntarlos invitaría a sumarlos.

### Verificación

- `validar_tableros.py` — **132 controles contra PostgreSQL, todos con Δ = 0** (los 71 de E1-A más
  61 nuevos). Además de las sumas comprueba invariantes de forma: que los dos embudos sean
  monótonos, que la lista de desenlaces publicada sea EXACTAMENTE la del `SELECT DISTINCT` de la
  base, que el KPI de filas del ETL no incluya la pseudo-tarea que duplica el total, y que T-4 no
  devuelva una sola columna de dinero.
- `matriz_tableros.py` — **56 celdas (8 roles × 7 tableros), 0 discrepancias**, con la ventana
  horaria ensanchada, restaurada y **verificada releyéndola**.

---

## 20. Fase E2: la previsión de demanda — el patrón sirve a un informe PREDICTIVO

> Primer modelo del nivel estratégico. **OTD-GER-13** entra por el patrón de siempre —el mismo
> sobre, la misma pantalla genérica, el mismo archivo declarativo— y es el primer informe del
> sistema cuyas filas describen meses que **no han ocurrido**.
>
> Aporta la respuesta a una pregunta que el patrón todavía no había tenido que contestar: qué se
> valida cuando **no hay nada contra qué validar al centavo**.

### 20.1 Lo que NO cambió

Nada de la mecánica. `InformesPrevisionService` extiende `InformeCompuestoServiceBase` como los
otros 39 compuestos: sin `@Transactional` porque no toca PostgreSQL, con `ejecutar()` para degradar
solo ante un fallo de CONEXIÓN, con `paginarCh`, con la marca de agua obligatoria y con la misma
disciplina de parámetros. La pantalla es
`features/operativo/informes/informes-departamento.component`, sin tocar. El informe se declara en
un archivo y se engancha con una línea por departamento.

### 20.2 Las tres cosas que sí son nuevas

**1. El mismo informe en DOS departamentos.** Es el primero. Gerencia lo usa para fijar metas
(D-10.1) y Compras para el plan de compra (D-11.1) y el nivel objetivo de stock (D-07.5). El dato
es idéntico y lo único que cambia es el reparto de roles, que **no es uno subconjunto del otro**:

```
/api/informes/gerencia/prevision-demanda   ADMIN · GERENTE · ANALISTA
/api/informes/compras/prevision-demanda    ADMIN · GERENTE · COMPRAS
```

De ahí las dos consecuencias de diseño: **una sola clase de servicio** que los dos controladores ya
existentes inyectan (repartirlo entre los dos servicios de departamento obligaría a duplicar la
consulta, y dos copias divergen dando **bandas distintas para el mismo mes** sin que ninguna
pantalla parezca rota), y **una definición compartida** —`definiciones/prevision.informe.ts`
exporta una función que recibe los roles— que los dos archivos de departamento importan. Corrección
CE3.6.

**2. Un gráfico en la pantalla genérica, y es opt-in.** La regla 1 de §5.1.9 exige que la serie
histórica y la previsión vayan en el MISMO gráfico. El sobre trae un campo `serie` con los 19 meses
observados y los 3 previstos en una sola lista —cada punto con lo suyo, y el mes truncado
marcado—, y el informe lo pide con `graficoPrevision: true`. Es opt-in por el mismo motivo que
`barraAvance`: un informe cualquiera con una columna mensual **no es una previsión**, y el trazo
discontinuo afirma que esos puntos no han ocurrido.

**3. La validación cambia de naturaleza.** Los 44 controles anteriores comparan hechos al centavo.
Una fila con fecha futura no tiene con qué compararse, así que se validan las dos cosas que sí
puede responder PostgreSQL y que son justo las que fallan:

| Control | Qué detecta |
|---|---|
| **universo** — 10 categorías, 159 variantes con ≥12 meses, 510 filas | que el almacén haya perdido una categoría o un puñado de variantes, y el modelo publique tan tranquilo la previsión de un catálogo más pequeño, coherente consigo mismo y equivocado |
| **ancla** — la previsión arranca donde acaba la venta real | una tabla **RANCIA**, que es el modo de fallo propio de una predicción: si entra un mes de ventas y el modelo no vuelve a correr, la pantalla enseña con toda naturalidad la previsión de un mes que **ya ocurrió**, con su banda intacta y sin dar ningún error |

El segundo control comprueba además, **desde PostgreSQL y por su cuenta**, que el mes truncado se
excluyó del entrenamiento: deriva de la base si el último mes está corto y compara ese veredicto
contra el que la tabla publicó en `horizonte_efectivo`.

### 20.3 Si vas a tocar esto

1. **`String.formatted()` interpreta el bloque ENTERO** y la consulta lleva
   `formatDateTime(mes, '%Y-%m')` dentro: el formateador lee ese `%Y` como suyo y el endpoint
   responde **400 «Conversion = 'Y'»**, un error del usuario por un fallo que no tiene nada que ver
   con la petición. La consulta se arma por concatenación. Ya estaba escrito en §18 y volvió a
   morder.
2. **Las columnas de banda van PEGADAS a la cifra**, no al final de la tabla. La regla 2 es «ningún
   número sin banda», y una previsión que aparece sola en la columna 5 mientras su intervalo vive
   en la 12 se lee como exacta.
3. **El MAPE por fila necesita su vara al lado.** Un 24 % no significa nada: es excelente en
   Abarrotes (el ingenuo saca 24,2 %) y malo en Ropa (el ingenuo saca 10,7 %). Por eso viajan
   `mape_backtest` y `mape_linea_base` juntas y el semáforo de la primera se calcula **contra la
   segunda**, nunca contra un umbral fijo.
4. **`sin_prevision` no es un cero.** Ropa Mujer (9 meses con venta) y Ropa Hombre (1 mes) publican
   fila con banda vacía y la pantalla escribe «sin previsión». Publicar un 0 con banda las pondría
   al lado de Abarrotes con la misma autoridad visual.
5. **La salvedad se calcula en cada petición, no se escribe.** El mes truncado, el número de
   variantes sin previsión propia y el desajuste entre la suma de las categorías y el total salen
   de la base en el momento. Una salvedad con una cifra escrita a mano caduca sin avisar, y es
   exactamente el tipo de mentira que la salvedad existe para evitar.

### 20.4 Verificación

- `matriz_prevision.py` — **16 celdas (8 roles × 2 rutas), 0 discrepancias**, con la ventana
  horaria ensanchada, restaurada y **verificada releyéndola**. Además del código HTTP comprueba
  **sobre la respuesta**: el universo en los tres niveles, el ancla, los 19 meses del gráfico
  contra PostgreSQL **mes a mes**, que ninguna banda tenga anchura cero ni deje fuera a su propia
  cifra, que `meses_historia` y el par de MAPE viajen en la fila, y que las **cinco limitaciones**
  de §5.1.10 estén en la salvedad.
- `validar_dwh.py` — **46 controles** (44 + 2 de la fase), todos con Δ = 0.
- Degradación con `docker stop`: los dos endpoints responden **200 con
  `analiticaDisponible=false` en ~4,1 s**, los informes simples de PostgreSQL siguen en 0,03 s, y
  se recuperan **sin reiniciar el backend**.

---

## 21. Fase E3: la alerta de abandono — el patrón sirve a un modelo que NO funciona

> Segundo y último modelo del nivel estratégico. **OTD-VEN-19** entra por el patrón de siempre y es
> el primer informe del sistema que **publica su propio veredicto negativo en la cabecera**.
>
> La pregunta nueva que contesta: qué forma tiene una pantalla cuando el modelo que la alimenta
> **no supera al azar**, y el negocio ha decidido publicarla igual.

### 21.1 Coste, otra vez el del patrón

**0 clases Java nuevas** y **0 componentes Angular nuevos**. El informe entra en
`InformesVentasCompuestosService` —el departamento ya existía— con un bloque de definición en
`ventas.informes.ts` y **una línea de `SecurityConfig`**. La pantalla genérica ganó dos capacidades
declarativas y ningún caso particular:

- `tipo: 'sparkline'` — micro-gráfico de barras dibujado en la celda desde un array del sobre;
- `sufijoTitulo` — coletilla que se pinta junto al título del informe.

Las dos son **opt-in**, como `barraAvance` y `graficoPrevision`, y por la misma razón: un array
cualquiera no es una serie temporal y un informe cualquiera no tiene ancla que declarar.

### 21.2 Lo que sí es nuevo: el veredicto va ARRIBA

La regla 4 de §5.2.9 del diseño estratégico dice que **el lift y su muestra van en la cabecera, no
en una nota**. En este patrón la cabecera son los KPI del sobre, así que la regla se traduce en algo
comprobable: **las tres primeras tarjetas del resumen son el veredicto del modelo**, y ninguna de
ellas es dinero.

```
Lift sobre el azar              1,99×
Medido sobre                    14 casos positivos de 167 evaluaciones
¿Supera al azar?                NO · p = 0,1019
─────────────────────────────────────────────────────────────
Aciertos en el top 10           16.67 %
Tasa base (dejaron de comprar)  8.38 %
Clientes en alerta              9 de 69
…y después, el dinero
```

`matriz_alerta_cliente.py` lo **exige**: falla si la primera tarjeta no es el lift, si la segunda no
declara los casos positivos, si la tercera no dice si supera al azar, o si hay una cifra de tipo
`moneda` entre las tres. Si alguien las reordena «para que se vea mejor el dinero», la verificación
se pone en rojo. Es el único informe del sistema con esa restricción, y es deliberado: un modelo
que oculta su lift es indistinguible de uno que funciona.

**La tercera tarjeta no la pidió el diseño.** El lift salió **1,99** y no ≈ 1,0 como §5.2.6
anticipaba; publicado solo, se lee como un éxito. Su valor p es **0,102**: no es distinguible del
azar. Corrección CE4.4.

### 21.3 Cinco cosas que aprendió el patrón, y que se repetirán

1. **El recorte de rol puede no tener el mecanismo que el diseño cita.** §5.2.8 pedía el de
   OTD-VEN-02 (`pedido.vendedor_id = <JWT>`); en el almacén **no hay `vendedor_id`**, solo el
   NOMBRE. Se casa por nombre contra `vendedores Array(String)` y el **ETL valida que los nombres
   sean únicos**, porque dos homónimos compartirían cartera y el recorte fallaría **abierto**.
   Medido: el vendedor ve **50 de los 69** clientes, porque 54 de ellos fueron atendidos por 3 o
   más vendedores — la cartera no es una partición. Corrección CE4.6.
2. **El aviso de alcance tiene que poder ser del informe.** El texto de VEN-02 («tus propias
   ventas») describiría mal este filtro, que es por cartera y no por autoría. El sobre puede
   enviar `avisoAlcance` y la pantalla lo prefiere al genérico.
3. **El valor por defecto de un filtro sigue siendo diseño.** Igual que SOP-01 arranca en
   `pendientes` y GER-04 en `vigente`, éste arranca en `alerta`: un informe de alerta que abre
   mostrando los 69 clientes obliga a buscar la alerta dentro de la lista.
4. **Un nivel que significa «no puedo opinar» no es un nivel más.** `sin_muestra` se pinta en gris
   y con su etiqueta explícita, nunca como `normal`, porque los clientes con más silencio de toda
   la cartera están ahí — su silencio es lo que los dejó sin muestra. Corrección CE4.5.
5. **El rótulo de un SVG en tabla es `<title>` nativo, no `matTooltip`.** Ya se pagó en la fase
   E1-A con la dispersión de T-2; con 69 filas × 12 barras la lección se aplica de entrada.

### 21.4 Verificación

- `matriz_alerta_cliente.py` — **8 celdas (8 roles × 1 ruta), 0 discrepancias**, con la ventana
  horaria ensanchada, restaurada y **verificada releyéndola**. Además del código HTTP comprueba
  **sobre la respuesta**: el reparto por nivel contra PostgreSQL (69 / 3 / 6 / 8, Δ = 0), que el
  filtro por defecto devuelva los 9 en alerta, las cinco reglas de presentación, las cinco
  limitaciones en la salvedad, y el **recorte del vendedor cliente por cliente** —ni uno ajeno, ni
  uno de menos—.
- `validar_dwh.py` — **49 controles** (46 + 3 de la fase), todos con Δ = 0. Los tres nuevos son
  inusualmente fuertes para una predicción: PostgreSQL **recalcula el modelo entero**, exponencial
  incluida, y contrasta λ **cliente por cliente**.
- Degradación con `docker stop`: **200 con `analiticaDisponible=false` en ~4,1 s**, los informes
  simples de PostgreSQL en 0,13 s, y recuperación **sin reiniciar el backend**.
