-- 47: DESPACHO lee el peso de la variante para calcular el peso del envío
-- (2026-07-22, cierre del peso_total_kg de OTD-LOG-11).
--
-- VentasService.despachar ahora calcula peso_total_kg = Σ peso_kg × cantidad
-- de las líneas del pedido (pesoTotalPedido) y lo persiste en envio. Corre
-- bajo grp_despacho, que desde el script 41 NO lee producto_variante (la
-- segregación financiera le quitó el catálogo completo, que incluye
-- precio/costo). Se le concede SOLO id y peso_kg — columnas sin dinero —
-- así el cálculo funciona y la segregación queda intacta.
--
-- producto_variante NO tiene RLS (verificado 2026-07-22), por lo que no hay
-- política de horario que agregar: basta el GRANT de columna.
-- GRANT es idempotente por naturaleza (re-otorgar no falla ni duplica).

BEGIN;

GRANT SELECT (id, peso_kg) ON producto_variante TO grp_despacho;

COMMIT;
