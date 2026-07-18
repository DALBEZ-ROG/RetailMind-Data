# Inventario consolidado de deuda técnica — RetailMind

**Fecha:** 2026-07-18 · **Tipo:** solo diagnóstico (sin cambios en código ni BD) · **Auditor:** revisión técnica con verificación contra el sistema real

**Fuentes consolidadas:** `DEUDA_TECNICA.md` (40 ítems vigentes por fases), `docs/AUDITORIA_USO_TABLAS.md`
(28 tablas huérfanas + 3 deudas nuevas), barrido de comentarios en código (sin TODO/FIXME reales —
los hallazgos del grep son la palabra española "todo") e inspección directa de BD y backend.

**Verificaciones contra el sistema real (re-auditoría 2026-07-18, post scripts 43/44/45 —
sustituye a las verificaciones pre-saneamiento que este bloque contenía):**

- Los dos pedidos legacy quedaron saneados: `PED-20260711-24662` 'confirmado' con 0 facturas
  vivas (la legacy está anulada); `PED-20260715-87538` 'facturado' con su factura.
- RLS activo en `pago` (3 políticas), `transaccion_pago` (2), `cupon` (2), `uso_cupon` (3) y en
  las tablas nuevas de los scripts 44/45 (`novedad_envio`, `item_defectuoso`,
  `devolucion_proveedor`, detalle e historial).
- `seq_numero_documento` y `fn_siguiente_numero_ticket()` existen y son la única vía de
  numeración (los 4 `siguienteNumero` + `SoporteService.java:233`).
- `devolucion_proveedor` YA existe (script 45: 2 DP cerradas, pool `item_defectuoso` coherente);
  `lote` sigue en 0 filas (decisión de alcance, `ROADMAP.md`).
- `costo_envio` = 0.00 en el 100% de pedidos (máximo global = 0).
- `reembolso`, `configuracion_tienda`, `log_acceso`, `reserva_stock`, `pasarela_pago`,
  `segmento_cliente`, `ubicacion_bodega`, `contacto_proveedor` = 0 filas.
- `IVA_DEFECTO = 15` hardcodeado en `VentasService.java:42` y `ComprasService.java:33`.
- El paquete `catalogo/` no expone `descuentoPromocional` (la vitrina sigue sin precio promocional).
- GERENTE conserva estado/asignación de tickets (`SecurityConfig`).
- Corrección al ítem Tipo 2 #12: `/api/auth/refresh` SÍ existe (refresh stateless por JWT en
  `AuthController`); lo pendiente es persistencia/revocación (`refresh_token` 0 filas),
  "olvidé mi contraseña" (`token_recuperacion`) y auditoría de logins (`log_acceso`).
- Tipo 1 nuevo detectado y CERRADO el mismo día: `/api/health` se colgaba con ClickHouse
  apagado (datasource sin timeouts). Resuelto en `ClickHouseConfig`/`HealthCheckService` y
  verificado en vivo (~3.1s acotados, `status: UP, analytics: DEGRADED`); detalle en
  `DEUDA_TECNICA.md` → "Resuelto — Auditoría de re-consolidación".

---

## 1. RESUELTOS (ya no aplican — confirmado)

| Ítem original | Resuelto en |
|---|---|
| Lógica de cupones pendiente (solo UI) | Fase 3b, script 40 — validación/recalculo siempre en backend |
| Total no se recalcula al editar pedido con descuento | Descartado — el pedido es inmutable tras crearse (no existe endpoint de edición de líneas) |
| Concurrencia en uso de cupón | Script 40 — trigger `fn_registrar_uso_cupon` con `FOR UPDATE`, probado con transacciones simultáneas |
| Reseñas sin compra verificada | Fase 4 — `crearResena` exige pedido pagado→entregado del propio cliente |
| Trazabilidad de autor ausente (ventas/despacho/factura compra/moderación) | Fase 5, script 42 — columnas FK a usuario + `AuditoriaService` |
| Bodega/Despacho veían montos | Fase 4, script 41 — grants por columna + consultas role-aware |
| Novedades/incidencias de envío (Tipo 2 ítem 1, parte operativa) | Fase 6, script 44 (2026-07-18) — `novedad_envio` + estados 'fallido'/'devuelto' del envío + pedido 'no_entregado'; reprogramar (máx. 3 intentos) o devolver al almacén SIN reingreso de stock (criterio RMA); RLS + AuditoriaService; UI en Despachos y Mis Pedidos |

---

## 2. TIPO 1 — BUGS / INCONSISTENCIAS — **TODOS RESUELTOS (saneamiento 2026-07-18, script 43)**

Los 10 ítems se cerraron por causa raíz y se verificaron contra el sistema real
(curl con los roles implicados, psql `SET ROLE` y MCP). Detalle completo en
`DEUDA_TECNICA.md` → "Resuelto — Saneamiento Tipo 1".

| Ítem | Sev. | Estado / qué se hizo |
|---|---|---|
| Pedido interno `PED-20260711-24662` 'confirmado' CON factura legacy | Media | **RESUELTO** — Factura legacy FV-20260711-55374 ANULADA (nota en historial) y la guardia de idempotencia de `emitirFactura` ahora ignora facturas 'anulada': el pedido sigue su flujo normal desde 'confirmado' (verificado: el endpoint responde la compuerta "debe estar pagado", ya no "ya fue facturado"). |
| El cupón no recalcula el IVA | Media | **RESUELTO** — `aplicarCupon` prorratea el cupón por línea y reescala `pedido_detalle.monto_impuesto` a la base realmente cobrada ANTES de crear el pago; el trigger de cabecera rehace los totales y la factura hereda el IVA correcto (verificado: VERANO26 20% sobre $54.90 → IVA $6.59, total $50.51 idéntico en pedido/pago/factura). El preview del checkout usa la misma regla; `envio_gratis` no toca base imponible. |
| `pago`/`transaccion_pago` sin RLS | Media | **RESUELTO** — RLS habilitado con el patrón del proyecto: `pol_horario` staff, cliente INSERT solo sobre pedidos propios (helper SECURITY DEFINER `fn_pago_del_cliente` para `transaccion_pago`) y SELECT propio (verificado: maria ve 6/24 pagos; vendedor todos). |
| `cupon`/`uso_cupon` sin RLS | Media | **RESUELTO** — RLS habilitado: cliente solo ve cupones activos (o los que él usó, para Mis Pedidos) y SOLO sus usos; el conteo global de límites ya vivía en el trigger SECURITY DEFINER (verificado: maria ve 1/3 usos). |
| Correlativo `TICK-AAAA-NNNN` por conteo | Media | **RESUELTO** — `fn_siguiente_numero_ticket()` SECURITY DEFINER con `correlativo_ticket` por año y UPSERT bajo lock de fila: sin carrera posible (verificado: TICK-2026-0007). |
| Pedido web `PED-20260715-87538` 'pagado' sin factura | Baja | **RESUELTO** — Facturado por el endpoint manual de respaldo (ADMIN): FV-20260718-100000, pedido 'facturado' en cola de preparación. |
| Número de pedido por azar (`PED-fecha-rand`) | Baja | **RESUELTO** — Secuencia global `seq_numero_documento` (desde 100000, sin choque con legacy) en los tres `siguienteNumero` (ventas/compras/devoluciones): colisión imposible. |
| `resena.moderado_por` escribible por `grp_cliente` a nivel de motor | Baja | **RESUELTO** — Grant de cliente por columna (INSERT solo de sus campos; UPDATE revocado — el cliente no edita reseñas). Verificado: UPDATE de moderación bajo grp_cliente → permiso denegado; crear reseña por la app sigue OK. |
| `grp_soporte` con escritura en `categoria_ticket` sin pantalla | Baja | **RESUELTO** — INSERT/UPDATE/DELETE revocados (queda SELECT), alineado con `SecurityConfig` (verificado: permiso denegado). |
| RMA: pedido 'despachado' permite solicitar devolución sin plazo | Baja | **RESUELTO** — El plazo de 30 días del rechazo en puerta corre desde `envio.fecha_despacho` (historial como respaldo), misma regla en `elegibilidad()` y `solicitar()` (verificado: despachado el 16-jul → 29 días restantes). |

---

## 3. TIPO 2 — FUNCIONALIDAD FUTURA (15 grupos)

1. **Cobro de envío** (antes "novedades de envío"; la parte OPERATIVA — incidencias de entrega,
   reintentos y devolución al almacén — quedó **IMPLEMENTADA** el 2026-07-18 con el script 44):
   cobrar `costo_envio` real (hoy 0.00 en el 100% de pedidos), tarifas por peso (`costo_por_kg`
   sin usar), tracking del retorno RMA, y con ello se activa el cupón `envio_gratis` (hoy
   rechazado con mensaje). Deuda nueva de la fase: reingreso de stock del pedido 'no_entregado'
   es manual (ver `DEUDA_TECNICA.md`, Fase 6).
2. **Devolución a proveedor** — **IMPLEMENTADA (2026-07-18, script 45)**: pool `item_defectuoso`
   con DOS orígenes (inspección RMA 'defectuoso' automática + recepción: rechazo en puerta
   automático y marcado posterior de BODEGA con salida de stock `salida_devolucion_proveedor`);
   COMPRAS agrupa por proveedor en `devolucion_proveedor` (registrada→enviada→resuelta→cerrada)
   y registra la resolución: nota de crédito SIMULADA (sin stock) o reposición (reingreso apto
   vía StockService, kardex `entrada_reposicion_proveedor`). Pantalla
   `/operativo/compras/devoluciones-proveedor` + rechazo en puerta en Recepciones. Deuda nueva
   de la fase (ver `DEUDA_TECNICA.md`, Fase 7): bodega de cuarentena física, asiento contable
   de la nota de crédito, recepción física separada de la reposición y rechazo total en puerta.
3. **Lote / FEFO** — **ROADMAP: DECISIÓN DE ALCANCE (2026-07-18), no pendiente**: se evaluó y
   se decidió NO implementarlo (ver `ROADMAP.md` en la raíz). Aunque hay ~300 productos
   caducables (Abarrotes/Belleza), exige un cambio transversal a recepción (capturar
   lote+vencimiento), inventario (stock por lote), kardex (arrastrar `lote_id`) y salida FEFO
   en despacho, sin aporte al objetivo del proyecto (flujo retail general). La tabla `lote` y
   las FK `recepcion_detalle.lote_id` / `movimiento_inventario.lote_id` quedan modeladas y
   listas para una fase futura.
4. **Pasarela de pago real + reembolso contable**: `pasarela_pago` (0 filas), asiento negativo en
   `pago`/`transaccion_pago`; decidir destino de la tabla `reembolso` (obsoleta por diseño).
5. **Contra-entrega online**: excluida a propósito (rompe "el pedido online nace pagado"); exige
   estado inicial y compuerta de cobro propios.
6. **RMA cambio/crédito**: `devolucion_detalle.accion` fijada a 'reembolso' aunque el CHECK admite más.
7. **Picking por ítem + slotting**: confirmación línea a línea, operario asignado,
   `ubicacion_bodega` (0 filas).
8. **Cupones en pedidos internos**: `aplicarCupon` es genérico pero ningún endpoint interno lo invoca.
9. **Promociones por categoría / carrito completo**: `promocion_producto` solo admite productos.
10. **Ajuste de inventario 'borrador'**: requiere tabla de detalle de líneas que no existe.
11. **Reserva de stock en carrito**: `reserva_stock` (0 filas); el checkout descuenta directo.
12. **Auth robusto**: `refresh_token`, `token_recuperacion`, `log_acceso` (0 filas) — sin refresh,
    sin "olvidé mi contraseña", sin auditoría de logins.
13. **Enriquecimiento de compras**: `contacto_proveedor`, `producto_proveedor` (0 filas).
14. **Trazabilidad pendiente**: autor en producto y marketing, historial de estados de ticket
    (fuera del alcance del script 42 a propósito).
15. **Tienda nunca construida** (residuo del modelo original, decisión de producto — candidatas a
    descartarse formalmente): comparador, tags, ficha extendida (specs/imágenes/relacionados),
    i18n (`idioma`/`traduccion`), multimoneda (`tipo_cambio`).

---

## 4. TIPO 3 — RESERVADO PARA TÁCTICO

- **Orquestación ETL con Airflow** — es la siguiente fase declarada (T11 en análisis): 13 informes
  compuestos vía ClickHouse + 12 simples directo de PG.
- **`segmento_cliente` / `cliente_segmento` / `grupo_cliente`** (0 filas; `cliente.grupo_cliente_id`
  NULL) — insumo PG natural si el táctico de marketing requiere segmentación.
- Nota de la auditoría de tablas: el táctico **no** consume tablas PG nuevas adicionales; los
  eventos a ClickHouse ya fluyen.

---

## 5. TIPO 4 — MEJORAS OPCIONALES / DECISIONES DE ALCANCE (16)

1. IVA 15% hardcodeado ignorando la tabla `impuesto` (verificado: `VentasService.java:42`,
   `ComprasService.java:33`) — hoy no produce resultados incorrectos, pero debería parametrizarse.
2. Precio promocional en vitrina y detalle (`/shop`): la señal existe, falta solo pintarla en
   `ProductoCatalogoService` (verificado ausente).
3. Tope máximo en cupones porcentuales ("20% hasta $50") — una columna + un `min()`.
4. Política de liberación de `uso_cupon` al cancelar/devolver (decisión de negocio sin definir).
5. GERENTE conserva gestión de tickets (decisión de matriz; verificado vigente en `SecurityConfig`).
6. Soporte ve todos los tickets (RLS por agente sería el siguiente paso).
7. Reapertura de tickets 'cerrado' (política pendiente; hoy terminal con mensaje claro).
8. RMA 'rechazado' consume cupo sin apelación (decisión consciente).
9. VENDEDOR ve el tablero RMA sin acciones (retirarlo = solo matriz).
10. Override de transportista sin catálogo tarifado (consciente: es para excepciones).
11. SLA calculado y no persistido (corrimiento retroactivo al cambiar prioridad; decisión consciente).
12. Reseñas: sin edición/borrado del cliente ni filtro de lenguaje.
13. Backfill parcial de autores históricos (NULLs irrecuperables — aceptar).
14. `log_auditoria` sin checkout online ni bodega (decisión de seguridad; alternativa = trigger
    SECURITY DEFINER).
15. Excepción `grp_bodega` → `precio_unitario` en detalles (documentada; cierre = valorización
    SECURITY DEFINER).
16. Limpieza de esquema: `reembolso` (obsoleta por diseño), `configuracion_tienda` (totalmente
    aislada, verificado 0 filas sin FKs), `permiso`/`rol_permiso` (modelo de permisos nunca usado).

---

## 6. Conteo

| Tipo | Ítems |
|---|---|
| Resueltos (auditorías previas) | 6 |
| **Tipo 1 — Bugs/inconsistencias** | **10 — TODOS RESUELTOS** (saneamiento 2026-07-18, script 43) |
| Tipo 2 — Funcionalidad futura | 15 grupos |
| Tipo 3 — Reservado táctico | 2 (Airflow/ETL + segmentación) |
| Tipo 4 — Mejoras opcionales | 16 |

---

## 7. Recomendación (actualizada tras el saneamiento 2026-07-18)

El saneamiento cerró TODO el Tipo 1 (script 43 + cambios de backend/front verificados
end-to-end). Lo que queda en este inventario es alcance, no deuda rota: el Tipo 2/3/4 no
requiere acción hasta que se decida abrir su fase (la siguiente declarada es el NIVEL
TÁCTICO con Airflow/ClickHouse, Tipo 3).
