# Patrón de informes tácticos — RetailMind

**Estado**: establecido el 2026-07-25 con el módulo de **Ventas** (OTD-VEN-01, 02, 08, 10, 15) y
confirmado el mismo día con **Inventario** (OTD-INV-01, 02, 03, 05, 06, 07, 08), que se construyó
con 2 clases Java + 1 archivo TS + el enganche, sin tocar la pantalla, el servicio ni los estilos.
Quedan por construir los otros cuatro departamentos (Compras, Logística, Soporte, Gerencia)
repitiendo este molde.

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
