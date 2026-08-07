import { Pipe, PipeTransform } from '@angular/core';

/**
 * TRADUCCIÓN DE IDENTIFICADORES INTERNOS A NOMBRE LEGIBLE — solo presentación.
 *
 * ═══ LA REGLA QUE NO SE PUEDE ROMPER ═══════════════════════════════════════
 * Esto es una capa de PINTADO. El valor que viaja al backend, el que se manda
 * en un formulario y el que se guarda en la base **sigue siendo el
 * identificador crudo**. En particular `grp_analista`, `grp_bodega`… son los
 * nombres REALES de los roles de PostgreSQL: de ellos dependen
 * `esta_en_horario()`, `fn_grupo_actual()`, las 95 políticas RLS y los
 * triggers `trg_horario_*`. Traducirlos en el DOM es cosmética; traducirlos en
 * el `[value]` de un `<mat-option>` o en el cuerpo de un PUT sería romper la
 * seguridad del motor.
 *
 * Por eso los pipes se aplican SIEMPRE sobre la interpolación que se ve
 * (`{{ v.rol_grupo | rolGrupo }}`) y NUNCA sobre el `[value]` / `[(ngModel)]`
 * que se envía. Y por eso cada sitio donde se aplican conserva el código crudo
 * en un `title`/`matTooltip`: sigue siendo consultable para depurar.
 * ═══════════════════════════════════════════════════════════════════════════
 */

// ── Roles de grupo del motor ────────────────────────────────────────────

/**
 * `grp_analista` → «Analista». Deja intacto cualquier cosa que no empiece por
 * `grp_` —`retailmind_app`, `retailmind_etl`, `postgres`— porque ésos no son
 * roles de negocio y renombrarlos confundiría más de lo que aclara.
 *
 * Acepta también una lista separada por comas (la columna «Roles» de las
 * políticas RLS llega así desde `pg_catalog`).
 */
export function nombreRolGrupo(valor: string | null | undefined): string {
  if (!valor) { return ''; }
  if (valor.includes(',')) {
    return valor.split(',').map(v => nombreRolGrupo(v.trim())).join(', ');
  }
  const bruto = valor.trim();
  if (!bruto.startsWith('grp_')) { return bruto; }
  const limpio = bruto.slice(4).replace(/_/g, ' ');
  return limpio.charAt(0).toUpperCase() + limpio.slice(1);
}

@Pipe({ name: 'rolGrupo', standalone: true })
export class RolGrupoPipe implements PipeTransform {
  transform(valor: string | null | undefined): string { return nombreRolGrupo(valor); }
}

// ── Códigos de estado y de catálogo ─────────────────────────────────────

/**
 * Diccionario de los códigos que el backend devuelve en minúsculas y con
 * guion bajo. Se escribe a mano y no se deriva mecánicamente porque en
 * castellano la derivación pierde las tildes («en_preparacion» daría «En
 * preparacion») y a veces la preposición («salida_venta» daría «Salida
 * venta»). Los textos son los MISMOS que la base ya guarda en la columna
 * `nombre` de sus catálogos (`estado_pedido`, `tipo_movimiento`), verificados
 * contra el motor: la pantalla no inventa una nomenclatura paralela.
 *
 * Lo que NO está aquí cae al formateo mecánico de abajo, así que un código
 * nuevo nunca deja la celda vacía.
 */
const ETIQUETAS: Record<string, string> = {
  // estado_pedido.codigo → estado_pedido.nombre
  pendiente: 'Pendiente',
  confirmado: 'Confirmado',
  pagado: 'Pagado',
  facturado: 'Facturado',
  en_preparacion: 'En preparación',
  preparado: 'Preparado',
  despachado: 'Despachado',
  entregado: 'Entregado',
  no_entregado: 'No entregado',
  cancelado: 'Cancelado',
  devuelto: 'Devuelto',

  // pedido.canal
  web: 'Web (tienda en línea)',
  tienda: 'Tienda',
  telefono: 'Teléfono',

  // envio.estado
  en_transito: 'En tránsito',
  fallido: 'Fallido',

  // factura_venta.estado / factura_compra
  emitida: 'Emitida',
  autorizada: 'Autorizada',
  anulada: 'Anulada',

  // pago.estado
  completado: 'Completado',
  procesando: 'Procesando',
  reembolsado: 'Reembolsado',

  // orden_compra.estado
  borrador: 'Borrador',
  enviada: 'Enviada',
  confirmada: 'Confirmada',
  recibida_parcial: 'Recibida parcial',
  recibida: 'Recibida',
  cancelada: 'Cancelada',

  // devolucion (RMA)
  solicitada: 'Solicitada',
  en_revision: 'En revisión',
  aprobada: 'Aprobada',
  rechazada: 'Rechazada',
  inspeccionada: 'Inspeccionada',
  reembolsada: 'Reembolsada',
  cerrada: 'Cerrada',
  apto_reventa: 'Apto para reventa',
  defectuoso: 'Defectuoso',
  rechazado: 'Rechazado',

  // ticket_soporte
  abierto: 'Abierto',
  en_proceso: 'En proceso',
  esperando_cliente: 'Esperando al cliente',
  resuelto: 'Resuelto',
  cerrado: 'Cerrado',
  urgente: 'Urgente',
  alta: 'Alta',
  media: 'Media',
  baja: 'Baja',

  // resena / reporte_resena
  atendido: 'Atendido',
  descartado: 'Descartado',
  spam: 'Spam',
  ofensivo: 'Ofensivo',
  inapropiado: 'Inapropiado',
  otro: 'Otro',

  // formas de pago / reembolso
  tarjeta: 'Tarjeta',
  transferencia: 'Transferencia',
  efectivo: 'Efectivo',
  nota_credito: 'Nota de crédito',
  reposicion: 'Reposición',

  // direccion.tipo
  envio: 'Envío',
  facturacion: 'Facturación',
  ambas: 'Envío y facturación',

  // tipo_movimiento.codigo → tipo_movimiento.nombre
  entrada_compra: 'Entrada por compra',
  entrada_devolucion_cliente: 'Entrada por devolución de cliente',
  entrada_transferencia: 'Entrada por transferencia',
  entrada_reposicion_proveedor: 'Entrada por reposición de proveedor',
  entrada_ajuste: 'Ajuste positivo',
  salida_venta: 'Salida por venta',
  salida_devolucion_proveedor: 'Salida por devolución a proveedor',
  salida_transferencia: 'Salida por transferencia',
  salida_ajuste: 'Ajuste negativo'
};

/**
 * `en_preparacion` → «En preparación». Un valor que ya viene legible (lleva
 * espacios o mayúsculas) se devuelve TAL CUAL: varias pantallas reciben del
 * backend el `nombre` del catálogo y no su `codigo`, y volver a formatearlo
 * lo estropearía.
 */
export function etiquetaCodigo(valor: string | null | undefined): string {
  if (valor === null || valor === undefined || valor === '') { return ''; }
  const bruto = String(valor).trim();
  const clave = bruto.toLowerCase();
  if (ETIQUETAS[clave]) { return ETIQUETAS[clave]; }
  // Ya legible: tiene espacios o alguna mayúscula, y no un guion bajo.
  if (!bruto.includes('_') && (bruto.includes(' ') || /[A-ZÁÉÍÓÚÑ]/.test(bruto))) {
    return bruto;
  }
  const limpio = bruto.replace(/[_-]+/g, ' ').toLowerCase();
  return limpio.charAt(0).toUpperCase() + limpio.slice(1);
}

@Pipe({ name: 'codigoLegible', standalone: true })
export class CodigoLegiblePipe implements PipeTransform {
  transform(valor: string | null | undefined): string { return etiquetaCodigo(valor); }
}
