# Auditoría de uso real de tablas — BD `retailmind` (PostgreSQL)

**Fecha:** 2026-07-17 · **Alcance:** 103 tablas del esquema `public` · **Tipo:** solo diagnóstico (sin cambios)

**Método:** conteo real de filas (`query_to_xml` sobre cada tabla), mapa de FKs (`pg_constraint`),
barrido de SQL en el backend (`retailmind-backend/src/main/java`, patrones FROM/JOIN/INTO/UPDATE por
tabla), revisión de funciones/triggers en `retailmind/sql/postgres/` y del ETL Python (`retailmind/`).

## Categorías

- **EN USO ACTIVO**: tiene CRUD o se escribe/lee en flujos reales del backend.
- **CATÁLOGO DE REFERENCIA**: se consulta y/o da integridad por FK, pocas filas estables, sin CRUD propio (correcto que exista).
- **SE LLENA COMO EFECTO**: se puebla desde otros procesos (historiales, kardex, logs), no por CRUD directo.
- **HUÉRFANA REAL**: ningún service la lee ni escribe, ninguna función/trigger la toca; solo la conecta (a veces) la FK declarada.

---

## 1. Tabla resumen por categoría

### EN USO ACTIVO — 52 tablas

| Tabla | Filas | Dónde se usa |
|---|---|---|
| ajuste_inventario | 3 | InventarioService (aplicar/anular ajuste) |
| atributo / valor_atributo / variante_valor_atributo | 2 / 11 / 30 | CatalogoAdminService |
| banner / campana | 1 / 1 | MarketingService (CRUD) |
| carrito / carrito_item | 17 / 21 | CarritoService |
| categoria / producto_categoria | 11 / 1214 | CatalogoAdminService, ProductoCatalogoService, RecomendacionesService |
| cliente | 2 | PerfilService, VentasService, SoporteService, ResenasService, PDF |
| cuenta_por_pagar | 14 | ComprasService (nace al registrar factura; se lee/actualiza al pagar) |
| cupon | 6 | MarketingService (CRUD), DescuentosService, VentasService, PDF |
| devolucion / devolucion_detalle | 7 / 9 | DevolucionService (RMA completo) |
| direccion | 4 | PerfilService (CRUD), CarritoService, VentasService |
| envio / envio_detalle | 22 / 26 | VentasService (despacho/entrega), DevolucionService |
| factura_compra / factura_compra_detalle | 14 / 22 | ComprasService, FacturaCompraPdfService |
| factura_venta / factura_venta_detalle | 26 / 32 | VentasService, FacturaVentaPdfService |
| grupo_horario | 56 | HorariosAdminService + función `esta_en_horario()` (triggers de horario) |
| inventario | 1227 | StockService, CarritoService, ComprasService, catálogo |
| marca | 35 | CatalogoAdminService, catálogo tienda |
| mensaje_ticket | 20 | SoporteService, DevolucionService |
| newsletter_suscriptor | 1 | MarketingService |
| nota_pedido | 3 | VentasService (bitácora de pedido) |
| orden_compra / orden_compra_detalle | 15 / 23 | ComprasService (ciclo con compuertas) |
| pago | 23 | VentasService (registrarPago, checkout online) |
| pago_proveedor | 10 | ComprasService |
| pedido / pedido_detalle | 31 / 40 | VentasService, CarritoService, DescuentosService, +6 services |
| pregunta_producto / respuesta_pregunta | 1 / 1 | ResenasService (Q&A con moderación) |
| producto / producto_variante | 1214 / 1221 | 12–13 services (núcleo del sistema) |
| promocion / promocion_producto | 1 / 2 | MarketingService (CRUD), DescuentosService |
| proveedor | 2 | ComprasService, ReferenciasService |
| recepcion_mercancia / recepcion_detalle | 13 / 21 | ComprasService, InventarioService |
| resena / resena_util / reporte_resena | 3 / 1 / 1 | ResenasService (compra verificada, votos, reportes) |
| ticket_soporte | 11 | SoporteService, DevolucionService |
| transferencia_bodega | 10 | InventarioService |
| usuario / usuario_rol | 10 / 10 | PostgresUserRepository (auth), admin usuarios, trazabilidad |
| wishlist / wishlist_item | 2 / 7 | WishlistService, PerfilService |

### CATÁLOGO DE REFERENCIA — 16 tablas

| Tabla | Filas | Cómo se usa |
|---|---|---|
| bodega | 2 | JOIN en 6 services (stock, RMA, compras); FK desde 9 tablas |
| categoria_ticket | 8 | SoporteService (prioridad automática), DevolucionService |
| ciudad / provincia / pais | 2 / 25 / 1 | Direcciones (PerfilService) y resolución de zona de envío (VentasService); `pais` no se lee directo pero da integridad a provincia/zona_envio/impuesto |
| estado_pedido | 10 | JOIN en VentasService, DevolucionService, ResenasService, SoporteService |
| faq | 3 | SoporteService (lectura) |
| metodo_envio / tarifa_envio / zona_envio | 2 / 3 / 3 | Asignación automática de transportista por zona (VentasService) |
| metodo_pago | 3 | ReferenciasService, VentasService, CarritoService |
| moneda | 1 | FK desde pedido/facturas/pago; leída en PDFs |
| motivo_devolucion | 4 | DevolucionService, ReferenciasService |
| rol | 9 | PostgresUserRepository (auth), SoporteService |
| tipo_movimiento | 8 | StockService/InventarioService (kardex) |
| transportista | 2 | VentasService (despacho), DevolucionService (guía retorno) |

### SE LLENA COMO EFECTO — 7 tablas

| Tabla | Filas | Quién la puebla |
|---|---|---|
| historial_estado_pedido | 142 | VentasService/CarritoService en cada transición |
| historial_estado_devolucion | 14 | DevolucionService (autor usuario o cliente) |
| log_auditoria | 12 | AuditoriaService (script 42) |
| movimiento_inventario | 91 | StockService (kardex de todo movimiento) |
| seguimiento_envio | 31 | VentasService al despachar/override |
| transaccion_pago | 23 | VentasService junto a cada pago |
| uso_cupon | 2 | DescuentosService + trigger `fn_registrar_uso_cupon` |

---

## 2. HUÉRFANAS REALES — 28 tablas

Ningún service las lee ni escribe, ninguna función/trigger de los scripts SQL las toca (verificado),
y su única conexión es la FK declarada.

### Reservadas para fase futura razonable (conservar)

| Tabla(s) | Filas | Situación y recomendación |
|---|---|---|
| lote | 0 | FK entrante desde `movimiento_inventario.lote_id` y `recepcion_detalle.lote_id` (hoy NULL). Documentada en `DEUDA_TECNICA.md`: trazabilidad FEFO. **Conservar.** |
| pasarela_pago | 0 | FK entrante desde `metodo_pago` y `pago` (columnas NULL): el pago es simulado. Se usará si se integra una pasarela real. **Conservar.** |
| impuesto + producto_impuesto | 2 (seed) / 0 | El IVA está hardcodeado (`IVA_DEFECTO = 15` en VentasService); nada consulta la tabla. Candidata si se parametrizan impuestos por producto. **Conservar; deuda: el 15% debería salir de aquí.** |
| reserva_stock | 0 | FK a carrito/pedido/inventario; el checkout descuenta stock directo sin reservas. Útil para "apartar stock en carrito". **Conservar.** |
| segmento_cliente + cliente_segmento + grupo_cliente | 0 / 0 / 0 | Segmentación de clientes para marketing/táctico; `cliente.grupo_cliente_id` existe pero está NULL. Encaja con la fase táctica/marketing futura. **Conservar.** |
| refresh_token / token_recuperacion | 0 / 0 | JWT stateless, sin refresh ni "olvidé mi contraseña". Se usarían al robustecer auth. **Conservar.** |
| log_acceso | 0 | Auditoría de logins nunca escrita (la auditoría real es `log_auditoria`, que sí se usa). O se puebla desde el login o es redundante. |
| ubicacion_bodega | 0 | `inventario.ubicacion_id` la referencia (NULL); slotting interno de bodega no implementado. |
| contacto_proveedor / producto_proveedor | 0 / 0 | Enriquecimiento de compras (múltiples contactos, catálogo por proveedor) no implementado. |

### Funcionalidad de tienda nunca construida (residuo del modelo original; decisión de producto)

| Tabla(s) | Filas | Situación |
|---|---|---|
| comparacion + comparacion_item | 0 / 0 | Comparador de productos. |
| etiqueta + producto_etiqueta | 0 / 0 | Tags de producto. |
| producto_especificacion / producto_imagen / producto_relacionado | 0 | Ficha extendida: specs/imágenes no se cargaron en el ETL del catálogo; las recomendaciones reales van por ClickHouse+PG, no por `producto_relacionado`. |
| idioma + traduccion | 2 (seed) / 0 | i18n no implementado; `idioma` solo es referenciada por `traduccion`, que a su vez está vacía (huérfanas encadenadas). |
| tipo_cambio | 0 | Multimoneda no implementada (hay 1 moneda). |
| configuracion_tienda | 0 | **La única totalmente aislada**: sin FK entrante ni saliente, sin código. Residuo puro. |
| permiso + rol_permiso | 0 / 0 | La autorización real es por roles de grupo de PostgreSQL + SecurityConfig; el modelo de permisos granulares nunca se usó. |
| reembolso | 0 | FK a devolucion y pago, pero el reembolso del RMA se registra en columnas de `devolucion` (`monto_reembolsado`, `metodo_reembolso`, `fecha_reembolso`); la tabla quedó **obsoleta por diseño**. Única huérfana cuya funcionalidad SÍ existe pero se implementó por otro camino — candidata a documentarse como deuda o eliminarse en una limpieza futura. |

---

## 3. Conteo por categoría

| Categoría | Tablas |
|---|---|
| EN USO ACTIVO | 52 |
| CATÁLOGO DE REFERENCIA | 16 |
| SE LLENA COMO EFECTO | 7 |
| HUÉRFANA REAL | 28 |
| **Total** | **103** |

---

## 4. Reservadas para fases futuras (nota final)

- **Fase táctica (en análisis, T11):** no consume tablas nuevas de PG — los 13 informes compuestos
  van por ETL a ClickHouse; los 12 simples usan tablas ya activas. La segmentación
  (`segmento_cliente` / `cliente_segmento` / `grupo_cliente`) sería el insumo PG natural si el
  táctico de marketing la requiere.
- **Documentadas en `DEUDA_TECNICA.md`:** `lote` (FEFO) y la inexistente `devolucion_proveedor`
  (los ítems `defectuoso` del RMA esperan ese proceso — coherente con el hallazgo: el kardex tiene
  `salida_devolucion_proveedor` sin uso).
- **Deuda nueva detectada por esta auditoría:**
  1. IVA 15% hardcodeado en `VentasService` ignorando la tabla `impuesto`.
  2. `reembolso` obsoleta por diseño (el dato vive en columnas de `devolucion`).
  3. `configuracion_tienda` totalmente aislada (sin FKs ni código).

Ninguna es urgente. Esta auditoría es solo diagnóstico: **no se modificó nada** en BD ni en código.
