# Sincronización de objetivos — base de coherencia para la reescritura de TA11

Diagnóstico READ-ONLY. Objetivo: establecer qué objetivos estratégicos/tácticos "de nivel
superior" ya entregados académicamente siguen vigentes, cuáles quedaron obsoletos, y auditar
los 25 objetivos tácticos del documento TA11 vigente contra `docs/tactico/INVENTARIO_DATOS_TACTICO.md`
(fuente de verdad de datos, leído completo y no repetido aquí). No se redactan objetivos tácticos
nuevos: eso es el bloque siguiente. Ningún archivo fue modificado; no se ejecutó DDL/DML.

## 1. Resumen ejecutivo

Se localizaron objetivos de nivel superior en dos universos **desconectados entre sí**: (a) el
catálogo `OE-xx`/`OT-xx`/`OO-xx` heredado de un documento externo **EVF04** (no presente como
archivo propio en el repo, solo citado verbatim dentro de `specs/001-007`) que cubre el nivel
*operativo* de la tienda del cliente, y (b) la **Constitución** (`.specify/memory/constitution.md`,
v1.0.0, ratificada 2026-06-18) con 7 principios de gobernanza de nivel estratégico. Ninguno de los
dos universos tiene relación textual con los 25 objetivos tácticos departamentales del TA11 actual
(`docs/build_t11.py`), que no usan ningún ID `OT-`. En total se catalogaron **14 objetivos/principios
de nivel superior**: 5 VIGENTES, 6 VIGENTE CON AJUSTE, 3 OBSOLETOS. Dentro de esos 3 obsoletos está
la pieza más grave del desfase: el Principio IV de la Constitución declara **"PostgreSQL fue
eliminado"** y ClickHouse como única BD operativa — exactamente al revés del sistema real
documentado en `CLAUDE.md`/`.kiro/steering/`. De los **25 objetivos tácticos del TA11**: **15
FACTIBLES**, **6 con SOPORTE NO VERIFICADO** (soporte descrito con frase genérica en el documento
original) y **4 NO FACTIBLES** (columna 100% NULL o muestra insuficiente confirmada en el
inventario). El documento TA11 se autocontradice en su propia introducción: declara "siete
departamentos" pero solo nombra seis (Marketing absorbido en Gerencia). Se proponen **7
departamentos** definitivos, separando Marketing de Gerencia. Se identificaron **6 módulos
huérfanos** (capacidades reales con tablas pobladas que ningún objetivo táctico actual reclama):
RMA de cliente, devolución a proveedor, novedades de envío, reseñas/preguntas, auditoría central y
puntualidad de pago a proveedor.

## 2. FUENTES ENCONTRADAS

| Archivo | Nivel de objetivos | ¿Legible SÍ/NO? |
|---|---|---|
| `.specify/memory/constitution.md` | Estratégico (7 principios de gobernanza, sin ID `OE-`) | SÍ (texto plano) |
| `specs/001-operativo-autenticacion/spec.md` (§15, líneas 318-353) | OE-04, OT-07, OT-08, OO-12, OO-14 (citando EVF04) | SÍ |
| `specs/002-operativo-catalogo/spec.md` (§15, líneas 310-315) | OE-01, OT-01, OO-01 | SÍ |
| `specs/003-operativo-carrito/spec.md` (§15, líneas 294-299) | OE-01, OT-01, OO-02 | SÍ |
| `specs/004-operativo-wishlist/spec.md` (§15, líneas 265-270) | OE-01, OT-02, OO-03 | SÍ |
| `specs/005-operativo-pedidos/spec.md` (§15, líneas 238-243) | OE-01, OT-02, OO-04 | SÍ |
| `specs/006-operativo-perfil/spec.md` (§15, líneas 277-282) | OE-04, OT-07, sin OO (deuda declarada) | SÍ |
| `specs/007-operativo-recomendaciones/spec.md` (§15, líneas 290-296) | OE-01, **OE-04 (texto distinto)**, OT-02, sin OO | SÍ |
| `specs/README.md` (índice + trazabilidad, líneas 14-54) | Mapa consolidado OE/OT/OO/CU por módulo | SÍ |
| `docs/build_t11.py` | 25 objetivos tácticos departamentales del TA11 (sin ID `OT-`) | SÍ (script generador; el PDF/DOCX no renderizan con las herramientas disponibles, se usó el script como fuente equivalente, igual que hizo el inventario de datos) |
| `docs/RetailMind_T11_Analisis_Tactico.pdf` / `.docx` | Igual que arriba | NO directamente (binario/PDF); se usó `build_t11.py` como equivalente |
| `.kiro/steering/product.md`, `tech.md`, `structure.md` | Descriptivo del sistema actual (no OE/OT formales, pero contradice a la Constitución) | SÍ |
| `docs/build_ev09.py` | Especificación operativa (EV09), sin objetivos OE/OT propios; encabezado dice "102 tablas" | SÍ |
| `.kiro/specs/retailmind-etl-pipeline/requirements.md` | Requisitos de un pipeline ETL genérico (`dataset_temporal`, 10 tablas normalizadas) que **no corresponde al esquema real de 109 tablas de RetailMind**; sin objetivos OE/OT | SÍ, pero fuera de alcance/desactualizado respecto al sistema actual |
| `docs/tactico/INVENTARIO_DATOS_TACTICO.md` | Fuente de verdad de datos (no de objetivos); usado para toda la auditoría de factibilidad | SÍ |
| `DEUDA_TECNICA.md`, `ROADMAP.md`, `docs/INVENTARIO_DEUDA_CONSOLIDADO.md`, `docs/AUDITORIA_USO_TABLAS.md` | Deuda técnica y uso de tablas, sin objetivos OE/OT | SÍ (consultados, sin contenido de objetivos relevante) |

No se encontró ningún archivo llamado `EVF04` ni `TA06` en el repositorio: ambos son documentos
externos previos cuyo contenido sobrevive solo por citación verbatim dentro de `specs/001-007` (los
IDs `OO-12`, `CU-01`, `CU-O08`, etc.).

## 3. OBJETIVOS ESTRATÉGICOS (y de nivel superior: OT heredados de EVF04 + principios de la Constitución)

| ID | Texto verbatim | Fuente | Veredicto | Justificación con evidencia |
|---|---|---|---|---|
| OE-01 | "Maximizar conversiones y ventas en la tienda online." | `specs/002,003,004,005,007/spec.md` (leyenda §15) | VIGENTE | La tienda online sigue activa y se amplió: catálogo 1.214 productos, carrito (19 filas), checkout con cupón/promoción reales (script 40), reseñas de compra verificada, recomendaciones — todo apunta a conversión (`INVENTARIO_DATOS_TACTICO.md` §3.1, §3.3). |
| OE-04 (versión A) | "Garantizar la seguridad mediante control de acceso basado en roles (RBAC)." | `specs/001-operativo-autenticacion/spec.md:348`, `specs/006-operativo-perfil/spec.md:279` | VIGENTE | El objetivo en sí sigue vigente y se profundizó: hoy son 9 roles de grupo en PostgreSQL con RLS, horario y GRANT por columna (`CLAUDE.md` "Seguridad a nivel de BD"), muy por encima del RBAC de aplicación con el que se concibió. |
| OE-04 (versión B) | "Inteligencia de negocio / personalización (y control de acceso)." | `specs/007-operativo-recomendaciones/spec.md:293` | OBSOLETO (conflicto de ID) | Mismo ID `OE-04` con un texto incompatible con la versión A (RBAC vs. personalización/BI). Ver §7 Contradicciones. La capacidad de personalización en sí (recomendaciones) sí está VIGENTE, pero no puede sostener el ID `OE-04` sin colisionar con la definición de seguridad ya usada por 001 y 006. |
| OT-01 | "Mejorar la experiencia de navegación del cliente." | `specs/002-operativo-catalogo/spec.md:313`, `specs/003-operativo-carrito/spec.md:297` | VIGENTE | Búsqueda con debounce, filtro por marca/categoría, paginación server-side (`CLAUDE.md`, módulo Tienda online) siguen sosteniendo este objetivo. |
| OT-02 | "Incrementar el engagement y la retención del cliente." | `specs/004,005,007/spec.md` (leyenda §15) | VIGENTE | Wishlist, recomendaciones, reseñas y newsletter (tablas `wishlist`, `resena`, `newsletter_suscriptor` en `INVENTARIO_DATOS_TACTICO.md` §2) sostienen el objetivo, aunque con volumen bajo (entorno demo). |
| OT-07 | "Gestionar de forma segura identidades y credenciales." | `specs/001-operativo-autenticacion/spec.md:349`, `specs/006-operativo-perfil/spec.md:280` | VIGENTE CON AJUSTE | El objetivo sigue vigente pero su sustento original (`OO-12`: "Autenticar con JWT y credenciales hasheadas en **ClickHouse**") quedó obsoleto: el login se resuelve hoy contra la tabla `usuario` de **PostgreSQL** (BCrypt), `ClickHouseUserRepository` es "el camino viejo" (`.kiro/steering/tech.md` línea 58). Frase exacta a corregir: "credenciales hasheadas en ClickHouse". |
| OT-08 | "Separar y proteger vistas según rol." | `specs/001-operativo-autenticacion/spec.md:350` | VIGENTE CON AJUSTE | Vigente y ampliamente superado en alcance: el objetivo se concibió para 2 roles de aplicación (ADMIN/CLIENTE, ver Constitución Principio V) y hoy son 9 roles con enforcement a nivel de motor (RLS + horario + segregación financiera por columna, script 41), no solo "vistas" de UI. |
| CONST-I | "Plataforma Web Escalable y Distribuible" (backend stateless, cada componente con su Dockerfile) | `.specify/memory/constitution.md` líneas 46-61 | VIGENTE CON AJUSTE | Cierto para backend/frontend/ClickHouse/ETL, pero PostgreSQL — hoy la BD operativa principal — corre **local, fuera de docker-compose** (`CLAUDE.md` "Pendiente: contenerización completa"), lo que incumple "cada componente… MUST poder construirse… vía su propio Dockerfile". |
| CONST-II | "El Negocio Vende: Capa Operativa Primero" | `.specify/memory/constitution.md` líneas 63-74 | VIGENTE | Es, de hecho, el principio que mejor describe el estado actual: `CLAUDE.md` marca el nivel Operativo como "TERMINADO" y prioriza checkout/pedidos sobre analítica. |
| CONST-III | "Arquitectura por Niveles Empresariales" (lista Operativo = `auth, catalogo, carrito, wishlist, pedidos, perfil, recomendaciones`) | `.specify/memory/constitution.md` líneas 76-92 | OBSOLETO | La enumeración de paquetes "Operativo" no incluye ninguno de los ~15 paquetes de back-office que hoy son el grueso del nivel (`compras`, `inventario`, `ventas`, `devoluciones`, `marketing`, `soporte`, `resenas`, `auditoria`, `admin/horarios`, `admin/usuarios`, `devolucionProveedor`…, ver `.kiro/steering/structure.md` líneas 52-81). |
| CONST-IV | "ClickHouse como Única Fuente Operativa… PostgreSQL fue eliminado… su reintroducción está prohibida" | `.specify/memory/constitution.md` líneas 94-113 | OBSOLETO | Contradicción directa y central con el sistema real: `CLAUDE.md` línea 3 dice "PostgreSQL (BD `retailmind`, ~103 tablas) es la ÚNICA base transaccional"; el inventario de datos confirma 109 tablas reales pobladas. Ver §7. |
| CONST-V | "Seguridad por Defecto: JWT + RBAC… dos roles: ADMIN y CLIENTE" | `.specify/memory/constitution.md` líneas 115-132 | VIGENTE CON AJUSTE | El principio general (seguridad por defecto, JWT) sigue vigente; la enumeración "dos roles: ADMIN y CLIENTE" está obsoleta — son 9 roles (`grp_administrador` … `grp_soporte`, `CLAUDE.md` "Seguridad a nivel de BD"). |
| CONST-VI | "Calidad de Código y Convenciones del Repositorio" | `.specify/memory/constitution.md` líneas 134-152 | VIGENTE CON AJUSTE | Las convenciones de nomenclatura y capas siguen aplicándose; la lista de subcarpetas de `retailmind/` no menciona `sql/postgres/` (scripts 01-45+99), que hoy es "el DDL operativo vigente" (`CLAUDE.md`). |
| CONST-VII | "Verificación y Pruebas" | `.specify/memory/constitution.md` líneas 154-166 | VIGENTE CON AJUSTE | El principio (compilar antes de dar por terminado, rutas críticas cubiertas) sigue aplicándose; el comando canónico cambió de `mvn clean install` a `mvn compile && ng build` (`CLAUDE.md` "Cómo correr"), diferencia menor. |

**Conteo de veredictos (14 entradas)**: VIGENTE 5 · VIGENTE CON AJUSTE 6 · OBSOLETO 3.

## 4. AUDITORÍA DE LOS 25 OBJETIVOS TÁCTICOS ACTUALES (TA11 / `docs/build_t11.py`)

| Departamento | Objetivo verbatim | Clasificación actual | FACTIBLE / NO FACTIBLE / SOPORTE NO VERIFICADO | Columnas reales que lo sustentan o qué falta |
|---|---|---|---|---|
| VENTAS | Controlar ventas por vendedor (cumplimiento individual) | Simple (BDR) | FACTIBLE | `pedido.vendedor_id` (FK directa a usuario, 17/34 poblada — coherente, NULL solo si canal='web') + `usuario` (`INVENTARIO` §3.1, §6). |
| VENTAS | Monitorear pedidos por estado (pendientes, despachados, entregados) | Simple (BDR) | FACTIBLE | `pedido`⋈`estado_pedido`; distribución real verificada: entregado 8, facturado 8, devuelto 7, confirmado 5, despachado 3, no_entregado 2, pagado 1 (`INVENTARIO` §3.1). |
| VENTAS | Analizar tendencia de ventas por mes y categoría | Compuesto (BDC) | SOPORTE NO VERIFICADO | El TA11 solo dice "hechos de venta agregados por mes/categoría" (frase genérica, sin tabla/columna). No cita el join real necesario (`pedido.fecha_pedido` + `pedido_detalle.producto_variante_id` + `producto_categoria`). |
| VENTAS | Medir ticket promedio de compra por período | Compuesto (BDC) | SOPORTE NO VERIFICADO | Sustento: "agregación temporal de pedidos pagados" — genérico, sin nombrar `pedido.total`/`pago.fecha_pago`. |
| COMPRAS | Ver órdenes de compra pendientes de aprobación | Simple (BDR) | FACTIBLE | `orden_compra.estado` real y poblado (16 filas: confirmada 1, enviada 1, recibida 12, recibida_parcial 2; `INVENTARIO` §3.4). |
| COMPRAS | Controlar cuentas por pagar vigentes y vencidas | Simple (BDR) | FACTIBLE | `cuenta_por_pagar.estado/fecha_vencimiento/saldo_pendiente`, 14 filas pobladas (`INVENTARIO` §3.4). |
| COMPRAS | Analizar gasto de compra por proveedor por trimestre | Compuesto (BDC) | SOPORTE NO VERIFICADO | Sustento: "facturas de compra agregadas por proveedor/trimestre" — genérico; no cita que `proveedor` solo tiene 2 filas (universo mínimo, `INVENTARIO` §5) que limita la utilidad real del agregado. |
| COMPRAS | Evaluar cumplimiento de tiempos de entrega por proveedor | Compuesto (BDC) | **NO FACTIBLE** | `orden_compra.fecha_entrega_esperada` está **100% NULL (0/16)** — la columna existe pero la aplicación nunca la puebla (`INVENTARIO` §4 "Verificado que NO existe pese a ser candidato natural", §7 hueco ALTA). Sin esa columna no hay "tiempo pactado" que comparar contra `recepcion_mercancia.fecha_recepcion`. |
| INVENTARIO / BODEGA | Identificar productos bajo stock mínimo | Simple (BDR) | FACTIBLE | `inventario.stock_actual` vs `stock_minimo`, 1227 filas pobladas (`INVENTARIO` §3.5). |
| INVENTARIO / BODEGA | Consultar stock actual por bodega | Simple (BDR) | FACTIBLE | `inventario`⋈`bodega` (2 bodegas, `INVENTARIO` §3.5). |
| INVENTARIO / BODEGA | Analizar rotación de inventario por categoría | Compuesto (BDC) | SOPORTE NO VERIFICADO | Sustento cita `movimiento_inventario` (real, 98 filas) pero no el join a `producto_categoria`/`categoria` que exige la dimensión "por categoría"; esa cadena no se verifica en el texto original. |
| INVENTARIO / BODEGA | Medir productos de baja/nula rotación por período | Compuesto (BDC) | SOPORTE NO VERIFICADO | Sustento: "kardex vs. catálogo, ventanas temporales" — genérico, sin columnas. |
| LOGÍSTICA / DESPACHO | Ver pedidos preparados pendientes de despacho | Simple (BDR) | FACTIBLE | `pedido.estado = 'preparado'` (estado real del tramo de salida, script 39; `estado_pedido` tiene 11 valores catalogados, `INVENTARIO` §2). |
| LOGÍSTICA / DESPACHO | Consultar envíos por transportista | Simple (BDR) | FACTIBLE | `envio`⋈`transportista` (24 envíos, 2 transportistas: Tramaco Express, Servientrega; `INVENTARIO` §3.6). |
| LOGÍSTICA / DESPACHO | Analizar tiempo promedio de entrega por transportista/zona | Compuesto (BDC) | SOPORTE NO VERIFICADO | `envio.fecha_despacho`/`fecha_entrega_real` sí existen y están pobladas, pero la dimensión "zona" **no es columna de `envio`**: la zona se deriva vía `dirección→zona_envio` (ciudad>provincia>país), cadena que el sustento del TA11 no menciona ni verifica; `direccion` solo tiene 4 filas. |
| LOGÍSTICA / DESPACHO | Medir tasa de devoluciones sobre despachos por período | Compuesto (BDC) | FACTIBLE | `devolucion` (7 filas) vs `envio` (24 filas), ambas con fechas reales (`INVENTARIO` §3.6); muestra pequeña pero el cálculo es sostenible con las columnas existentes. |
| SOPORTE | Ver tickets abiertos por prioridad y estado | Simple (BDR) | FACTIBLE | `ticket_soporte.prioridad/estado`, 12 filas con distribución real (`INVENTARIO` §3.7). |
| SOPORTE | Consultar tickets asignados por agente | Simple (BDR) | FACTIBLE | `ticket_soporte.asignado_usuario_id`⋈`usuario` (`INVENTARIO` §3.7). |
| SOPORTE | Analizar tiempo promedio de resolución por categoría por mes | Compuesto (BDC) | **NO FACTIBLE** | `ticket_soporte.fecha_cierre` solo está poblada en **2 de 12 tickets** (10/12 NULL), consistente con solo 2 tickets en estado 'cerrado' (`INVENTARIO` §4). Con esa muestra no hay agregado mensual por categoría defendible hoy. |
| SOPORTE | Medir cumplimiento de SLA por período | Compuesto (BDC) | **NO FACTIBLE** | El propio TA11 admite en su nota de verificación que "el SLA de soporte no se guarda como columna: se deriva de la prioridad… y su fecha de creación" (`build_t11.py` líneas 249-252); `INVENTARIO` §3.7 confirma que no existe `fecha_limite`/`sla_vencimiento` en `ticket_soporte`. Se suma la falta de `fecha_cierre` poblada (ver fila anterior). |
| GERENCIA / DIRECCIÓN (incluye Marketing) | Consultar estado consolidado de pedidos del día | Simple (BDR) | FACTIBLE | `pedido`⋈`estado_pedido` filtrado por fecha actual (`INVENTARIO` §3.1). |
| GERENCIA / DIRECCIÓN (incluye Marketing) | Listar cupones activos y promociones vigentes (marketing) | Simple (BDR) | FACTIBLE | `cupon.activo`, `promocion.fecha_inicio/fecha_fin` (6 cupones, 1 promoción; `INVENTARIO` §3.3). |
| GERENCIA / DIRECCIÓN (incluye Marketing) | Analizar tasa de redención de cupones e impacto de promociones por período (marketing) | Compuesto (BDC) | **NO FACTIBLE** | `uso_cupon` tiene solo **3 filas** (2026-07-17 a 18) y el propio inventario lo marca "¿Alimenta informes? NO" (`INVENTARIO` §2, §3.3): muestra insuficiente para una "tasa… por período" con series temporales. |
| GERENCIA / DIRECCIÓN (incluye Marketing) | Analizar rentabilidad por categoría por período | Compuesto (BDC) | FACTIBLE | `producto_variante.precio/costo` pobladas en el 100% de 1221 variantes + `pedido_detalle`/`producto_categoria` (`INVENTARIO` §3.5). |
| GERENCIA / DIRECCIÓN (incluye Marketing) | Medir evolución de ingresos vs. costos mensual | Compuesto (BDC) | FACTIBLE | `factura_venta` (30 filas, 2026-07-04 a 07-18) vs `factura_compra` (14 filas, 2026-07-04 a 07-17), ambas con fecha (`INVENTARIO` §2). |

**Conteo (25 objetivos)**: FACTIBLE 15 · SOPORTE NO VERIFICADO 6 · NO FACTIBLE 4. Coincide con la
proporción declarada del documento (12 simples / 13 compuestos): los 4 NO FACTIBLES y 5 de los 6
SOPORTE NO VERIFICADO son compuestos; solo 1 SOPORTE NO VERIFICADO ("gasto de compra por proveedor
por trimestre") es compuesto también — es decir, **ningún objetivo SIMPLE (BDR) resultó NO
FACTIBLE o con soporte dudoso**: el hueco está enteramente en el lado ClickHouse/agregado, que aún
no se construyó.

## 5. ESTRUCTURA DEPARTAMENTAL PROPUESTA

El documento TA11 declara en su introducción "los siete departamentos del negocio" pero en la
misma frase solo nombra seis, fusionando Marketing dentro de "Gerencia/Dirección" (`build_t11.py`
líneas 133-136 y tabla de objetivos línea 166-170). El inventario de datos (`INVENTARIO_DATOS_TACTICO.md`
§1, §3) ya usa 9 áreas de negocio para explicar el esquema completo. Cruzando ambos criterios —
¿tiene tablas propias con CRUD real?, ¿tiene paquete de backend/rol dedicado?, ¿responde preguntas
de dirección propias y distintas de otro departamento? — se proponen **7 departamentos
definitivos**:

1. **Ventas** — `pedido`, `pedido_detalle`, `estado_pedido`, `historial_estado_pedido`,
   `factura_venta(_detalle)`, `pago`, `transaccion_pago`, `carrito(_item)`, `wishlist(_item)`,
   `resena*`, `pregunta_producto`, `respuesta_pregunta`. Backend: `ventas/`, `carrito/`,
   `wishlist/`, `resenas/`, `pedidos/`.
2. **Compras** — `orden_compra(_detalle)`, `proveedor`, `recepcion_mercancia(_detalle)`,
   `factura_compra(_detalle)`, `cuenta_por_pagar`, `pago_proveedor`, `devolucion_proveedor*`,
   `item_defectuoso`. Backend: `compras/`.
3. **Inventario / Bodega** — `inventario`, `movimiento_inventario`, `tipo_movimiento`, `bodega`,
   `ajuste_inventario`, `transferencia_bodega`, `producto*`, `categoria`, `marca`, `atributo*`.
   Backend: `inventario/`, `admin/catalogo/`.
4. **Logística / Despacho** — `envio(_detalle)`, `seguimiento_envio`, `transportista`,
   `metodo_envio`, `tarifa_envio`, `zona_envio`, `novedad_envio`, `devolucion(_detalle)`,
   `historial_estado_devolucion`, `motivo_devolucion`, `reembolso`. Backend: `ventas/` (despacho),
   `devoluciones/`.
5. **Soporte** — `ticket_soporte`, `mensaje_ticket`, `categoria_ticket`, `correlativo_ticket`,
   `faq`. Backend: `soporte/`.
6. **Marketing** — `cupon`, `uso_cupon`, `promocion(_producto)`, `campana`, `banner`,
   `newsletter_suscriptor`. Backend: `marketing/` (paquete propio), frontend
   `features/operativo/marketing/` (propio, según `CLAUDE.md`). **Veredicto: SÍ debe ser
   departamento propio**, no absorbido en Gerencia — tiene tablas exclusivas, un servicio backend
   dedicado (`DescuentosService`) y preguntas de dirección que no comparte con Gerencia
   ("¿qué cupones se usaron?", "¿qué campañas están vigentes?"); fusionarlo diluye la trazabilidad
   de sus 7 tablas propias listadas en `INVENTARIO_DATOS_TACTICO.md` §3.3.
7. **Gerencia / Dirección** — transversal: `log_auditoria`, `configuracion_tienda`, más vistas
   consolidadas de los seis departamentos anteriores (no tiene tablas operativas propias más allá
   de esas dos, ver `INVENTARIO_DATOS_TACTICO.md` §3.8).

**Quedan fuera de los 7 departamentos de negocio** (transversales de plataforma, sin jefatura de
área en el sentido PyME): **Clientes/CRM** (`cliente`, `direccion`, `segmento_cliente*`) — sus
métricas viven dentro de Ventas/Marketing, no tiene jefe propio ni paquete backend dedicado — y
**Seguridad/Sistema/Referencia** (`usuario`, `rol`, `grupo_horario`, `pais/provincia/ciudad`…) —
es infraestructura de plataforma, no un área de negocio con necesidades tácticas propias.

## 6. MÓDULOS HUÉRFANOS

| Módulo | Tablas | Departamento al que debería pertenecer | Por qué importa |
|---|---|---|---|
| RMA / Devoluciones de cliente | `devolucion`, `devolucion_detalle`, `historial_estado_devolucion`, `motivo_devolucion`, `reembolso` | Logística/Despacho (ejecuta el ciclo) | Ciclo completo de 8 estados con SLA y reembolso propio (`CLAUDE.md` "RMA / logística inversa"); el único objetivo del TA11 que lo toca de refilón es "tasa de devoluciones sobre despachos", que no cubre estados, tiempos de resolución ni reembolsos. |
| Devolución a proveedor | `devolucion_proveedor`, `devolucion_proveedor_detalle`, `historial_devolucion_proveedor`, `item_defectuoso` | Compras | Ciclo completo DP-… (registrada→enviada→resuelta→cerrada) con nota de crédito/reposición; ningún objetivo del TA11 lo menciona pese a tener 2-8 filas reales y trazabilidad propia (`CLAUDE.md` "Devolución a proveedor"). |
| Novedades de envío | `novedad_envio` | Logística/Despacho | Incidencias en tránsito (cliente ausente, dirección incorrecta, etc.) con reprogramación/devolución al almacén; 6 filas reales, ningún objetivo del TA11 pregunta por tipos de incidencia ni tasa de resolución. |
| Reseñas y preguntas de producto | `resena`, `resena_util`, `reporte_resena`, `pregunta_producto`, `respuesta_pregunta` | Marketing (o Ventas) | Reseñas de compra verificada con moderación ADMIN/GERENTE; impactan directamente conversión (objetivo natural de Marketing/Ventas) pero el TA11 no tiene ningún objetivo de "calificación promedio de producto" o "reseñas pendientes de moderación". |
| Auditoría central | `log_auditoria` | Gerencia/Dirección | 39 registros con autor + jsonb antes/después; es exactamente el tipo de dato que sostiene control interno/gobernanza, pero el único objetivo de Gerencia que la roza es "estado consolidado de pedidos del día" — no hay objetivo de "acciones críticas auditadas por usuario/período". |
| Puntualidad de pago a proveedor | `cuenta_por_pagar`, `pago_proveedor` | Compras | El inventario confirma que "¿Pagamos a los proveedores a tiempo?" **SÍ** es respondible hoy (`fecha_vencimiento` vs `pago_proveedor.fecha_pago`, `INVENTARIO` §3.4), pero el único objetivo de Compras sobre CxP es "vigentes y vencidas" (snapshot), no puntualidad histórica — es una pregunta distinta que ningún objetivo del TA11 cubre. |

## 7. CONTRADICCIONES Y DESACTUALIZACIONES

1. **PostgreSQL "eliminado" vs. PostgreSQL como única BD operativa.** La Constitución
   (`.specify/memory/constitution.md` líneas 103-104) dice: *"PostgreSQL fue **eliminado**. El DDL
   en `retailmind/sql/` y las dependencias `psycopg2-binary`/`sqlalchemy` son **legacy** y MUST NOT
   usarse en código nuevo; su reintroducción está prohibida."* `CLAUDE.md` línea 3 dice exactamente
   lo opuesto: *"PostgreSQL (BD `retailmind`, ~103 tablas) es la ÚNICA base transaccional"*. El
   propio `CLAUDE.md` anticipa este tipo de choque: *"Si algún documento viejo dice 'PostgreSQL
   eliminado'… está desactualizado: ignóralo."* La Constitución nunca fue enmendada tras la
   migración de la tienda a PostgreSQL (2026-07-11) pese a que su sección de Governance exige
   enmienda formal ante cambios de esta magnitud.
2. **RBAC de "dos roles" vs. 9 roles de motor.** Constitución Principio V (línea 120): *"Control de
   acceso… con dos roles: ADMIN y CLIENTE."* `CLAUDE.md` "Seguridad a nivel de BD": *"9 roles de
   grupo en PostgreSQL: grp_administrador, grp_gerente, grp_vendedor, grp_compras, grp_bodega,
   grp_despacho, grp_cliente, grp_analista, grp_soporte."*
3. **Lista de paquetes "Operativo" incompleta.** Constitución Principio III (líneas 81-82) enumera
   el nivel Operativo como `auth, catalogo, carrito, wishlist, pedidos, perfil, recomendaciones` —
   ni un solo paquete de back-office. `.kiro/steering/structure.md` (líneas 52-81) lista
   adicionalmente `compras/`, `inventario/`, `ventas/`, `devoluciones/`, `marketing/`, `soporte/`,
   `resenas/`, `auditoria/`, `admin/horarios/`, `admin/usuarios/` dentro del mismo nivel.
4. **`OE-04` con dos definiciones incompatibles.** `specs/001-operativo-autenticacion/spec.md:348`
   y `specs/006-operativo-perfil/spec.md:279`: *"OE-04: Garantizar la seguridad mediante control de
   acceso basado en roles (RBAC)."* `specs/007-operativo-recomendaciones/spec.md:293`: *"OE-04:
   Inteligencia de negocio / personalización (y control de acceso)."* Mismo identificador, dos
   objetivos estratégicos de contenido distinto — no reconciliado en ningún documento del repo.
5. **Dos universos de "objetivo táctico" sin relación.** EVF04 (citado en `specs/001-007`) ya
   definió `OT-01` (experiencia de navegación), `OT-02` (engagement/retención), `OT-07`
   (identidades/credenciales) y `OT-08` (vistas por rol) — todos de alcance *tienda/cliente*.
   `docs/build_t11.py` genera 25 objetivos tácticos **departamentales** (Ventas, Compras,
   Inventario, Logística, Soporte, Gerencia) sin usar ningún ID `OT-` y sin referenciar los `OT-01/
   02/07/08` ya existentes. Ambos catálogos conviven en el repo sin ningún documento que los
   unifique o distinga explícitamente por alcance.
6. **Conteo de tablas desactualizado en cascada.** `CLAUDE.md`, `.kiro/steering/tech.md` y
   `docs/build_t11.py` (línea 127) dicen "~103 tablas"; `docs/AUDITORIA_USO_TABLAS.md` (2026-07-17)
   también dice "103 tablas del esquema public"; `docs/build_ev09.py` (línea 4) dice "102 tablas".
   El inventario nuevo (`INVENTARIO_DATOS_TACTICO.md` §1, levantado 2026-07-21 con `SELECT COUNT(*)`
   real) confirma **109 tablas**, por el crecimiento de los scripts 40-45 no reflejado todavía en
   ninguno de los documentos anteriores.
7. **"Siete departamentos" declarados, seis nombrados.** `docs/build_t11.py` líneas 133-136: *"Los
   objetivos tácticos se levantaron para los **siete** departamentos del negocio — Ventas, Compras,
   Inventario/Bodega, Logística/Despacho, Soporte y Gerencia/Dirección (que gestiona también
   Marketing)."* La lista enumera 6 nombres (Marketing no cuenta como el séptimo, va absorbido
   dentro de Gerencia); la propia tabla de 25 objetivos (líneas 145-171) confirma esto agrupando
   todo Marketing bajo la fila única *"GERENCIA / DIRECCIÓN (incluye Marketing)"*, sin fila propia.
   El documento se contradice entre lo que anuncia en prosa ("siete") y lo que efectivamente
   tabula (seis).
