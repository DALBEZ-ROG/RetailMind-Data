/**
 * Extrae el mensaje de negocio de un error HTTP del backend.
 *
 * El GlobalExceptionHandler responde ApiErrorDTO con el campo `mensaje`
 * (p. ej. "Stock insuficiente para SKU X: disponible 5, solicitado 10").
 * Nunca se muestra el texto técnico del status ("Bad Request", "Conflict"):
 * si no hay mensaje de negocio se usa el fallback del componente.
 */
export function mensajeError(e: any, fallback: string): string {
  const cuerpo = e?.error;
  const msg = cuerpo?.mensaje || cuerpo?.message;
  if (typeof msg === 'string' && msg.trim().length > 0) {
    return msg;
  }
  return fallback;
}
