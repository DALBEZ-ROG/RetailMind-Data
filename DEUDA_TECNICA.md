# Deuda técnica — RetailMind

Registro acumulado por fase. Severidad: **Alta** (riesgo funcional o de seguridad),
**Media** (limitación conocida con workaround), **Baja** (mejora deseable).

| Fase | Ítem | Severidad | Descripción |
|------|------|-----------|-------------|
| Base (pre-fases) | Tabla `lote` huérfana | Media | 0 filas; darle uso obliga a tocar recepción de compra (captura de lote), kardex (arrastrar `lote_id`) y salida FEFO en despacho. No es un CRUD suelto. |
| Base (pre-fases) | `ajuste_inventario.estado='borrador'` sin flujo | Baja | El CHECK lo admite pero un borrador aplicable exigiría tabla de detalle de líneas del ajuste, que no existe (el ajuste escribe kardex directo al aplicarse). |
| Base (pre-fases) | Orquestación ETL con Airflow | Baja | El ETL PocketBase→Parquet→ClickHouse corre manual/por script; sin scheduler. |
| Fase 1 (checkout online, 2026-07-15) | Lógica de cupones pendiente | Media | El campo de cupón del checkout es solo UI; el enganche documentado está en `CarritoService.checkout` (validar contra `cupon`/`uso_cupon` ANTES de `crearPedido` y alimentar el descuento del pedido). |
| Fase 1 (checkout online) | `pago`/`transaccion_pago` sin RLS | Media | Precedente del script 35: el aislamiento del pago lo da la capa de servicio (el cliente solo inserta el pago de su pedido recién creado, con grants mínimos INSERT + SELECT(id)). Llevarlo al motor exigiría políticas para todos los roles que hoy escriben pago. |
| Fase 1 (checkout online) | Sin método contra-entrega online | Baja | Se excluyó a propósito: rompería el invariante "el pedido online nace pagado". Habilitarlo exige decidir su estado inicial y su compuerta de cobro. |
| Fase 1 (checkout online) | Número de pedido por azar (`PED-fecha-rand`) | Baja | Colisión improbable pero posible; el UNIQUE la detiene con un 400 genérico. |
| Fase 2a (rol soporte, 2026-07-15) | Correlativo `TICK-AAAA-NNNN` por `count(+1)` | Media | Dos creaciones simultáneas pueden chocar (el UNIQUE de `numero` corta con 400 genérico y el usuario reintenta). Lo limpio sería una secuencia por año o retry automático. |
| Fase 2a (rol soporte) | SLA calculado, no persistido | Baja | `sla_vence` se deriva de `fecha_creacion + intervalo(prioridad)` en la consulta: si el agente cambia la prioridad, el vencimiento se recalcula retroactivamente. Persistir `fecha_limite` al crear evitaría ese corrimiento (decisión consciente: solo es indicador visual). |
| Fase 2a (rol soporte) | GERENTE conserva acceso total a tickets | Media | Para no romper la matriz previa, GERENTE sigue viendo/gestionando tickets aunque el rol correcto ahora es SOPORTE. Retirarle ese acceso es un cambio de matriz que debe decidirse aparte. |
| Fase 2a (rol soporte) | `grp_soporte` con escritura en `categoria_ticket` sin pantalla | Baja | El grant existe (pedido de la fase) pero los endpoints de categorías siguen ADMIN-only; el privilegio de motor queda latente. |
| Fase 2a (rol soporte) | Soporte ve TODOS los tickets | Baja | No hay aislamiento por agente (la bandeja filtra "míos"/"sin asignar" en UI). Aceptable para un equipo pequeño; RLS por `asignado_usuario_id` sería el paso siguiente. |
| Fase 2a (rol soporte) | Reapertura solo desde 'resuelto' | Baja | Si el cliente responde un ticket 'resuelto' se reabre a 'en_proceso'; 'cerrado' es terminal (mensaje claro). Reabrir cerrados exigiría decidir política de reapertura/auditoría. |
