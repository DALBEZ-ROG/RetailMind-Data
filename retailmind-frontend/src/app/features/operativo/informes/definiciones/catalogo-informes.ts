import { DefinicionDepartamento } from '../../../../core/models/informe.model';
import { INFORMES_VENTAS } from './ventas.informes';
import { INFORMES_INVENTARIO } from './inventario.informes';

/**
 * REGISTRO de los informes tácticos por departamento.
 *
 * Añadir el módulo de otro departamento (Compras, Inventario, Logística,
 * Soporte, Gerencia) = crear su archivo `<depto>.informes.ts` y sumarlo a este
 * mapa. La pantalla, el servicio y la tabla ya existen y no se tocan.
 *
 * Ver `docs/tactico/PATRON_INFORMES.md`.
 */
export const CATALOGO_INFORMES: Record<string, DefinicionDepartamento> = {
  ventas: INFORMES_VENTAS,
  inventario: INFORMES_INVENTARIO
};

export function definicionDepartamento(depto: string): DefinicionDepartamento | undefined {
  return CATALOGO_INFORMES[depto];
}
