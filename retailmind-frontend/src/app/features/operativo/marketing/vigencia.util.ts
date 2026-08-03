/**
 * Vigencia calculada de una entidad con ventana de fechas (cupón, promoción,
 * banner). No es una columna de la BD: se deduce comparando la ventana con el
 * reloj, así que un registro «vigente» deja de serlo solo con que pase el
 * tiempo, sin que nadie lo toque.
 *
 * OJO: esto es INDEPENDIENTE de `activo`. Un cupón puede estar activo y
 * caducado a la vez —el motor lo rechaza igualmente en el canje—, por eso la
 * grilla ofrece los dos criterios por separado.
 */
export type Vigencia = 'vigente' | 'programado' | 'caducado';
export type FiltroVigencia = Vigencia | 'todos';

export function vigenciaDe(fechaInicio: string | null, fechaFin: string | null): Vigencia {
  const ahora = Date.now();
  const inicio = fechaInicio ? new Date(fechaInicio).getTime() : null;
  const fin = fechaFin ? new Date(fechaFin).getTime() : null;

  if (inicio !== null && inicio > ahora) return 'programado';
  if (fin !== null && fin < ahora) return 'caducado';
  return 'vigente';
}
