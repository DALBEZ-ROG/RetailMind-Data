# RetailMind — Roadmap

Este documento registra las **decisiones de alcance** del proyecto: funcionalidad evaluada
formalmente y pospuesta de manera deliberada y justificada. No es deuda técnica por descuido —
cada entrada documenta qué se decidió, por qué, qué quedó preparado en el modelo y qué
implicaría implementarla en una fase futura.

---

## Decisiones de alcance / Funcionalidad futura documentada

### 1. Gestión de LOTES con caducidad y salida FEFO — **DECISIÓN DE ALCANCE (2026-07-18)**

**Estado: POSPUESTO deliberadamente. No es deuda por descuido.**

#### Descripción

Trazabilidad de inventario por lote de fabricación con fecha de vencimiento, y política de
salida **FEFO** (*First Expired, First Out*): al despachar, el sistema elegiría primero las
unidades del lote más próximo a caducar, con alertas de vencimiento y bloqueo de venta de
mercancía caducada.

#### Justificación de la posposición

- El catálogo tiene **~300 productos caducables** (categorías Abarrotes y Belleza), pero el
  modelo operativo actual **no captura fecha de vencimiento** en ningún punto del flujo:
  la recepción de compra ingresa cantidades sin lote y el inventario es un saldo único por
  variante y bodega (`inventario.stock_actual`).
- Implementar FEFO **no es un CRUD suelto**: obliga a un cambio **transversal** sobre cuatro
  módulos estables del núcleo (ver "Qué implicaría" abajo), con migración del stock existente
  a un lote sintético y cambios de compuertas ya verificadas (recepción, kardex, despacho).
- El objetivo del proyecto es el **flujo retail general** (ciclo de compra, venta, logística,
  devoluciones, seguridad por roles en motor). La rotación por caducidad no aporta a ese
  objetivo y su costo/beneficio no justifica desestabilizar módulos ya robustecidos.

#### Qué ya está preparado en el modelo (sin uso, listo para la fase futura)

| Estructura | Situación actual |
|------------|-----------------|
| Tabla `lote` (id, producto_variante_id, codigo, fecha_fabricacion, fecha_vencimiento) | Modelada en el esquema, **0 filas** |
| `recepcion_detalle.lote_id` (FK a `lote`, NULL-able) | Columna lista para capturar el lote al recibir |
| `movimiento_inventario.lote_id` (FK a `lote`, NULL-able) | El kardex ya puede arrastrar el lote en cada movimiento |

Es decir: el **esqueleto de datos existe por diseño**; lo pospuesto es la lógica de negocio y
la interfaz que lo pondrían en uso.

#### Qué implicaría implementarlo (alcance del cambio transversal)

1. **Recepción de compra** (`ComprasService.registrarRecepcion` + pantalla Recepciones):
   capturar código de lote y fecha de vencimiento por línea recibida (alta en `lote` +
   `recepcion_detalle.lote_id`).
2. **Inventario**: pasar de saldo único por variante/bodega a **stock por lote**
   (nueva tabla de saldos o desglose del inventario), con la migración del stock existente
   a un lote sintético "sin lote".
3. **Kardex** (`StockService.mover` y todos sus llamadores): arrastrar `lote_id` en cada
   movimiento (venta, transferencia, ajuste, devoluciones, reposición de proveedor).
4. **Salida en despacho/preparación** (ciclo de venta): lógica FEFO de selección de lote por
   fecha de vencimiento al preparar/despachar, más las reglas asociadas (alertas de
   caducidad próxima, bloqueo de lote vencido, cruce con el picking por ítem — que es a su
   vez deuda futura propia).

Adicionalmente tocaría transferencias entre bodegas, la anulación de ajustes por
contramovimiento y la inspección RMA/reposición de proveedor (todo lo que hoy mueve stock).

#### Condición de reapertura

Se reabriría como fase propia si el negocio prioriza categorías perecederas (rotación por
caducidad, mermas por vencimiento) o si un requisito regulatorio exige trazabilidad por lote.
La entrada correspondiente en `docs/INVENTARIO_DEUDA_CONSOLIDADO.md` (Tipo 2, ítem 3) quedó
marcada como **"roadmap — decisión de alcance"**.

---

*Las demás funcionalidades futuras (cobro de envío, pasarela real, picking por ítem, etc.)
siguen inventariadas en `docs/INVENTARIO_DEUDA_CONSOLIDADO.md` (Tipo 2) y en
`DEUDA_TECNICA.md`; se promoverán a este roadmap conforme se tomen decisiones formales de
alcance sobre cada una.*
