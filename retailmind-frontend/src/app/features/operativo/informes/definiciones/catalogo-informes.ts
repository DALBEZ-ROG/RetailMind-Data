import { DefinicionDepartamento } from '../../../../core/models/informe.model';
import { INFORMES_VENTAS } from './ventas.informes';
import { INFORMES_INVENTARIO } from './inventario.informes';
import { INFORMES_COMPRAS } from './compras.informes';
import { INFORMES_LOGISTICA } from './logistica.informes';
import { INFORMES_SOPORTE } from './soporte.informes';
import { INFORMES_GERENCIA } from './gerencia.informes';

/**
 * REGISTRO de los informes tácticos por departamento.
 *
 * Añadir el módulo de otro departamento = crear su archivo `<depto>.informes.ts`
 * y sumarlo a este mapa. La pantalla, el servicio y la tabla ya existen y no se
 * tocan. Con Soporte y Gerencia (2026-07-26) los SEIS departamentos del nivel
 * táctico están cubiertos.
 *
 * Ver `docs/tactico/PATRON_INFORMES.md`.
 */
export const CATALOGO_INFORMES: Record<string, DefinicionDepartamento> = {
  ventas: INFORMES_VENTAS,
  inventario: INFORMES_INVENTARIO,
  compras: INFORMES_COMPRAS,
  logistica: INFORMES_LOGISTICA,
  soporte: INFORMES_SOPORTE,
  gerencia: INFORMES_GERENCIA
};

export function definicionDepartamento(depto: string): DefinicionDepartamento | undefined {
  return CATALOGO_INFORMES[depto];
}
