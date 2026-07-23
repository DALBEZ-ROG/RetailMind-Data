-- ============================================================================
-- 46 — Cierre de brechas OTD-LOG-10: registro transaccional del reembolso
-- ============================================================================
-- DevolucionService.reembolsar ahora INSERTA la fila en `reembolso` además de
-- actualizar las columnas de `devolucion`. La transición 'reembolsada' la
-- ejecutan GERENTE y ADMIN (SecurityConfig): grp_administrador ya tenía INSERT
-- sobre `reembolso` desde el DDL base; grp_gerente solo tenía SELECT.
--
-- No hace falta grant de secuencia: reembolso.id es GENERATED ALWAYS AS
-- IDENTITY (la secuencia propia no exige privilegio aparte). La tabla no tiene
-- RLS ni trigger de horario, así que no se requieren políticas nuevas.
--
-- Ejecutar como superusuario:
--   psql -U postgres -d retailmind -f 46_grant_reembolso_gerente.sql

GRANT INSERT ON public.reembolso TO grp_gerente;
