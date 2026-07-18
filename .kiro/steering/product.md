# RetailMind

RetailMind es una **tienda PyME con back-office completo y analítica integrada**. Combina un núcleo
transaccional de retail (catálogo, compras, inventario, ventas, marketing) con un motor de
analítica sobre eventos (~2.3M) y una tienda online. Es un sistema web con arquitectura **híbrida
de dos bases de datos**:

- **PostgreSQL** (BD `retailmind`, ~103 tablas): **base de datos operativa principal**. Todo el
  núcleo transaccional vive aquí, **incluida la tienda del cliente** (catálogo `/api/catalogo`
  con ~1.214 productos reales cargados del dataset original vía ETL puntual, carrito, wishlist,
  perfil/direcciones, checkout y mis pedidos — migrados 2026-07-11).
- **ClickHouse**: **solo analítica** (esquema estrella `fact_eventos` + dimensiones `dim_*`),
  alimentado por el pipeline ETL de Python desde PocketBase. Con ClickHouse/Docker apagado TODO
  el sistema funciona; solo analytics/recomendaciones se degradan con aviso.

> Si algún documento viejo dice "PostgreSQL eliminado" o describe la tienda sobre ClickHouse,
> está desactualizado: ignorarlo.

El sistema se organiza en tres niveles empresariales:

- **Operativo (genera ventas — TERMINADO)**: catálogo maestro (productos/variantes/atributos),
  ciclo de compra con compuertas enforzadas (orden → aprobación → recepción → factura → pago),
  inventario (transferencias, ajustes con anulación, kardex), ciclo de venta completo con
  compuertas (pedido → pago → facturado → preparación → despacho → entrega → devolución RMA)
  con PDF, marketing con descuentos reales aplicados (cupones + promociones), tienda online
  completa (checkout con pago simulado y factura automática), reseñas de compra verificada,
  soporte con SLA, horarios de acceso por rol, trazabilidad de autor + auditoría
  centralizada (`log_auditoria`) en los procesos críticos, novedades/incidencias de envío
  (script 44), devolución a proveedor de mercancía defectuosa (script 45) y saneamiento
  completo de bugs Tipo 1 (scripts 43 + fix de health 2026-07-18: deuda de bugs reales = 0).
- **Táctico (toma de decisiones — EN ANÁLISIS, 2026-07-17)**: definidos 25 informes tácticos por
  departamento (`docs/RetailMind_T11_Analisis_Tactico.pdf`): 12 simples directo de la BDR
  PostgreSQL y 13 compuestos que se procesarán en ClickHouse vía ETL orquestado por Airflow.
  Hoy ya corren sobre ClickHouse: sesiones, conversiones, funnel, analytics por
  región/dispositivo/tráfico y reportes (Excel/PDF).
- **Estratégico**: dashboard ejecutivo con KPIs.

## Módulos operativos construidos

| Módulo | Alcance |
|---|---|
| Catálogo | CRUD de productos, variantes (SKU), marcas, categorías, atributos |
| Compras | Orden → aprobación (GERENTE/ADMIN, compuerta enforced) → recepción completa → factura → CxP/pagos |
| Inventario | Transferencias entre bodegas, ajustes (con anulación por contramovimiento de kardex), kardex |
| Ventas | Pedido confirmado → pago del cliente (tabla `pago`+`transaccion_pago`, abonos parciales) → pagado → facturado (interna MANUAL, online AUTOMÁTICA al pagar, script 39) → preparación por BODEGA (cola sin montos) → despacho por DESPACHO solo si 'preparado' (transportista asignado por zona con override registrado) → entregado; listado facturas con búsqueda/paginación; timeline (`historial_estado_pedido`); acciones encadenadas en detalle |
| Devoluciones (RMA) | Logística inversa completa (script 38, `devoluciones/`): nace del CLIENTE (30 días tras entrega o rechazo en puerta), engancha ticket de soporte; solicitada→en_revision→aprobada\|rechazada→en_transito→recibida→inspeccionada→reembolsada→cerrada, UN rol por transición (SOPORTE valida + guía RET-, DESPACHO tránsito/recepción, BODEGA inspección por ítem — solo `apto_reventa` reingresa stock —, GERENTE/ADMIN reembolso simulado, SOPORTE cierra) |
| Novedades de envío | Incidencias en tránsito (script 44, tabla `novedad_envio`: cliente_ausente/direccion_incorrecta/cliente_rechazo/zona_dificil_acceso/dano_en_transito): DESPACHO registra (envío → 'fallido') y resuelve — REPROGRAMAR (máx. 3 intentos) o DEVOLVER AL ALMACÉN (envío 'devuelto', pedido → estado terminal 'no_entregado', SIN reingreso de stock: el kardex solo se mueve tras inspección de bodega, criterio RMA). Una novedad abierta a la vez; `entregar` bloqueado con novedad abierta. UI en Despachos + timeline en Mis Pedidos |
| Devolución a proveedor | Espejo del RMA hacia el proveedor (script 45, `compras/DevolucionProveedorService`): pool `item_defectuoso` con DOS orígenes (inspección RMA 'defectuoso' automática + recepción: rechazo en puerta o marcado posterior de BODEGA con salida `salida_devolucion_proveedor`); COMPRAS agrupa por proveedor en `devolucion_proveedor` (DP-…, registrada→enviada→resuelta→cerrada) y resuelve: nota de crédito SIMULADA o reposición (reingreso apto, kardex `entrada_reposicion_proveedor`). Pantalla multi-rol `/operativo/compras/devoluciones-proveedor` |
| Checkout online | Tipo Amazon (script 36): dirección (o alta inline), cupón validado en backend, pago tarjeta/transferencia SIMULADO (Luhn; NUNCA se persiste PAN/CVV); el pedido online nace PAGADO con factura automática; `pedido.canal` 'web' vs 'tienda'/'telefono' (cobro manual solo interno) |
| Marketing | Cupones y promociones APLICADOS de verdad (script 40, `DescuentosService`): promociones automáticas por línea (prioridad + acumulables), cupón validado/recalculado SIEMPRE en backend (vigencia, `usos_maximos`, `usos_por_cliente`, monto mínimo), `uso_cupon` activa, factura prorratea el cupón; campañas, banners, newsletter (gestión) |
| Reseñas | Solo compra verificada (script 32 + pulido 2026-07-17, `resenas/`): reseñas, votos, reportes de abuso, preguntas/respuestas, moderación ADMIN/GERENTE; selector solo de productos comprados |
| Tienda online | Catálogo real ~1.214 productos (búsqueda/paginación server-side), carrito, wishlist, checkout → pedido del ciclo de venta; perfil + CRUD direcciones; "Mis Pedidos" con estado/guía/seguimiento/factura PDF y solicitud de devolución (RLS, script 35); recomendaciones (señal CH + productos PG, degradan a destacados) |
| Soporte | Rol SOPORTE dedicado (script 37): bandeja con filtros, 7 categorías con prioridad AUTOMÁTICA, número `TICK-AAAA-NNNN`, SLA calculado (2h/4h/24h/72h), tomar ticket, transiciones con guardias, reapertura por respuesta del cliente, RLS de cliente |
| Seguridad | Horarios de acceso por rol de grupo, gestión de usuarios; SEGREGACIÓN FINANCIERA (script 41): BODEGA/DESPACHO no leen montos (grants por columna + consultas role-aware; excepción documentada: bodega ve `precio_unitario` de detalles para valorizar kardex) |
| Notas de pedido | Bitácora `nota_pedido`: nota interna vs. visible al cliente |
| Trazabilidad / Auditoría | Script 42 + `auditoria/AuditoriaService`: columnas directas de autor con FK a usuario, SIEMPRE del JWT — `pedido.vendedor_id` (NULL si canal 'web': el autor es el CLIENTE, trazado por `cliente_id`+historial), `envio.despachado_por`, `factura_compra.registrado_por`, `resena.moderado_por`+`fecha_moderacion`, `pregunta_producto.moderado_por`+`fecha_moderacion`; `registrar()` escribe `log_auditoria` (jsonb antes/después, CHECK de acciones, append-only por grants, sin RLS) en crear pedido interno, despachar, registrar factura de compra y moderar reseña/pregunta; el checkout online NO loguea (grp_cliente sin INSERT a propósito) |

## Pendientes
- Contenerización completa (PostgreSQL sigue local, fuera de compose). `/api/health` ya sirve
  como healthcheck de contenedores: responde acotado (~3s) con ClickHouse apagado
  (`status: UP, analytics: DEGRADED`; fix 2026-07-18 en `ClickHouseConfig`).
- Orquestación ETL con Airflow — siguiente fase, derivada del análisis del nivel táctico
  (2026-07-17, `docs/RetailMind_T11_Analisis_Tactico.pdf`): los 13 informes tácticos compuestos
  se procesarán en ClickHouse alimentado por ese pipeline.

## Deuda técnica
Re-auditada 2026-07-18 (post scripts 43-45) contra el sistema real: **cero bugs reales (Tipo 1)
vigentes**; lo que queda es funcionalidad futura o mejoras opcionales. Registro canónico en
`DEUDA_TECNICA.md` (raíz) y foto consolidada en `docs/INVENTARIO_DEUDA_CONSOLIDADO.md`.
Decisiones de alcance formales (p. ej. Lote/FEFO pospuesto) en `ROADMAP.md`.
- `lote` (0 filas): FEFO evaluado y POSPUESTO deliberadamente (`ROADMAP.md`); las FK
  `movimiento_inventario.lote_id` y `recepcion_detalle.lote_id` quedan listas.
- `ajuste_inventario.estado = 'borrador'`: CHECK lo admite pero exigiría tabla de detalle de
  líneas que no existe.
- `devolucion_proveedor` **ya existe** (script 45): los ítems `defectuoso` caen al pool
  `item_defectuoso` y se devuelven al proveedor (nota de crédito o reposición);
  `salida_devolucion_proveedor` y `entrada_reposicion_proveedor` en uso.

## Roles y seguridad

**9 roles de aplicación** espejados en **roles de grupo de PostgreSQL** (`grp_*`): ADMIN
(administrador), GERENTE, VENDEDOR, COMPRAS, BODEGA, DESPACHO, CLIENTE, ANALISTA y SOPORTE
(9º rol, script 37: `soporte@retailmind.com`, horario 24/7).

La seguridad se aplica en **dos capas**:
1. **Aplicación**: Spring Security JWT (`SecurityConfig` por ruta) + `roleGuard` en Angular.
2. **Motor de BD**: matriz de privilegios por grupo, RLS (p. ej. cliente solo ve sus pedidos) y
   restricción por horario. La app asume el rol del usuario en cada transacción con
   `SET LOCAL ROLE` (aspecto `PgSessionRoleAspect`). El admin está exento de horario.

El catálogo de tienda es público; todo lo demás requiere autenticación.

## Idioma

El proyecto usa **español** para términos de dominio, comentarios y etiquetas de UI. Los
identificadores mezclan inglés (convenciones de framework) con español (entidades de dominio como
`compras`, `ventas`, `inventario`, `cupon`, `promocion`).
