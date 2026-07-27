import { ColorChip, DefinicionDepartamento, FiltroInforme } from '../../../../core/models/informe.model';

/**
 * INFORMES TÁCTICOS DE COMPRAS — los cuatro objetivos SIMPLES del catálogo
 * (`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §4).
 *
 * Este archivo es TODO lo que hay que escribir para la pantalla: la
 * `InformesDepartamentoComponent` genérica pinta los filtros, la tabla, el
 * resumen y la paginación a partir de estas declaraciones. Ni un componente,
 * ni un servicio, ni un estilo nuevo (ver `docs/tactico/PATRON_INFORMES.md`).
 *
 * Los COMPUESTOS de Compras (puntualidad de pago — COM-03, gasto mensual —
 * COM-04, cumplimiento de plazo — COM-05, días de ciclo — COM-06, rechazos en
 * puerta — COM-07, monto recuperado — COM-09, entregas incompletas — COM-11,
 * evolución del costo — COM-12) NO están aquí: son de la fase ETL → ClickHouse.
 *
 * SEGREGACIÓN FINANCIERA: BODEGA aparece en `roles` SOLO de OTD-COM-08, que es
 * el único informe sin columnas de dinero. Esto espeja SecurityConfig — que es
 * quien realmente decide — y evita disparar una petición que la API negaría
 * con 403.
 */

/**
 * Proveedor como BÚSQUEDA y no como select: son 11 activos y crecen con el
 * negocio; una lista de ids incrustada aquí envejecería en silencio. El backend
 * busca por razón social o RUC.
 */
const FILTRO_PROVEEDOR: FiltroInforme = {
  param: 'proveedor', etiqueta: 'Proveedor (nombre o RUC)', tipo: 'texto',
  debounce: true, ancho: 'ancho'
};

/** Rojo mientras la orden espera el visto bueno de Gerencia. */
function colorEstadoOrden(fila: Record<string, any>): ColorChip {
  if (fila['pendiente_aprobacion']) { return 'warn'; }
  switch (fila['estado']) {
    case 'recibida':         return 'ok';
    case 'cancelada':        return 'neutral';
    case 'recibida_parcial': return 'info';
    default:                 return 'info';   // confirmada: aprobada, en camino
  }
}

const ESTADO_ORDEN: Record<string, string> = {
  borrador:         'Borrador (sin aprobar)',
  enviada:          'Enviada (sin aprobar)',
  confirmada:       'Aprobada',
  recibida_parcial: 'Recibida parcial',
  recibida:         'Recibida',
  cancelada:        'Cancelada'
};

/** El semáforo de la cuenta por pagar lo marca la SITUACIÓN calculada hoy. */
function colorSituacionCxp(fila: Record<string, any>): ColorChip {
  switch (fila['situacion']) {
    case 'vencida':    return 'error';
    case 'por_vencer': return 'warn';
    case 'saldada':    return 'neutral';
    default:           return 'ok';
  }
}

const SITUACION_CXP: Record<string, string> = {
  vencida:    'VENCIDA',
  por_vencer: 'Vence esta semana',
  vigente:    'Vigente',
  saldada:    'Saldada'
};

const ESTADO_CXP: Record<string, string> = {
  pendiente: 'Pendiente',
  parcial:   'Pago parcial',
  pagada:    'Pagada',
  vencida:   'Vencida'
};

function colorDefectuoso(fila: Record<string, any>): ColorChip {
  switch (fila['estado']) {
    case 'resuelto':      return 'ok';
    case 'en_devolucion': return 'info';
    default:              return fila['sin_proveedor'] ? 'error' : 'warn';
  }
}

const ESTADO_DEFECTUOSO: Record<string, string> = {
  pendiente:     'En el pool',
  en_devolucion: 'En devolución',
  resuelto:      'Resuelto'
};

const ORIGEN_DEFECTUOSO: Record<string, string> = {
  rma:       'Devolución de cliente',
  recepcion: 'Recepción de compra'
};

const ESTADO_DEV_PROVEEDOR: Record<string, string> = {
  registrada: 'Registrada',
  enviada:    'Enviada al proveedor',
  resuelta:   'Resuelta',
  cerrada:    'Cerrada'
};

const TIPO_RESOLUCION: Record<string, string> = {
  nota_credito: 'Nota de crédito',
  reposicion:   'Reposición de producto'
};

export const INFORMES_COMPRAS: DefinicionDepartamento = {
  departamento: 'compras',
  titulo: 'Informes de Compras',
  descripcion: 'Órdenes y aprobaciones, deuda con proveedores, mercancía defectuosa y a quién comprar',
  icono: 'shopping_cart',
  informes: [

    // ── OTD-COM-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-COM-01',
      endpoint: 'ordenes',
      titulo: 'Órdenes de compra por estado',
      descripcion: 'Todas las órdenes con su estado, proveedor y monto. Las que esperan '
                 + 'el visto bueno de Gerencia salen primero: sin aprobar no se recibe.',
      icono: 'assignment',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS'],
      vacio: 'Ninguna orden de compra coincide con los filtros elegidos.',
      filtros: [
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [
          { valor: '',                     etiqueta: 'Todos los estados' },
          { valor: 'pendiente_aprobacion', etiqueta: 'Esperan aprobación' },
          { valor: 'borrador',             etiqueta: 'Borrador' },
          { valor: 'enviada',              etiqueta: 'Enviada' },
          { valor: 'confirmada',           etiqueta: 'Aprobada' },
          { valor: 'recibida_parcial',     etiqueta: 'Recibida parcial' },
          { valor: 'recibida',             etiqueta: 'Recibida' },
          { valor: 'cancelada',            etiqueta: 'Cancelada' }
        ] },
        FILTRO_PROVEEDOR,
        { param: 'desde', etiqueta: 'Emitida desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Emitida hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'numero',                 titulo: 'Orden',      tipo: 'texto' },
        { campo: 'estado',                 titulo: 'Estado',     tipo: 'chip',
          color: colorEstadoOrden, etiqueta: v => ESTADO_ORDEN[v] || v },
        { campo: 'proveedor',              titulo: 'Proveedor',  tipo: 'texto', recortar: 30 },
        { campo: 'fecha_emision',          titulo: 'Emitida',    tipo: 'fecha' },
        { campo: 'fecha_entrega_esperada', titulo: 'Prometida',  tipo: 'fecha' },
        { campo: 'dias_esperando',         titulo: 'Esperando',  tipo: 'dias' },
        { campo: 'bodega',                 titulo: 'Bodega',     tipo: 'texto', recortar: 20 },
        { campo: 'lineas',                 titulo: 'Líneas',     tipo: 'numero' },
        { campo: 'unidades_pedidas',       titulo: 'Pedidas',    tipo: 'numero' },
        { campo: 'recibido_pct',           titulo: 'Recibido',   tipo: 'porcentaje' },
        { campo: 'total',                  titulo: 'Total',      tipo: 'moneda', monto: true }
      ]
    },

    // ── OTD-COM-02 ────────────────────────────────────────────────────
    {
      id: 'OTD-COM-02',
      endpoint: 'cuentas-por-pagar',
      titulo: 'Cuentas por pagar',
      descripcion: 'Cuánto le debemos a cada proveedor y qué cuotas ya vencieron. La '
                 + 'situación se calcula hoy contra la fecha de vencimiento.',
      icono: 'account_balance_wallet',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS'],
      vacio: 'Ninguna cuenta por pagar coincide con los filtros elegidos.',
      filtros: [
        // Dos clasificaciones distintas a propósito: `estado` es la columna que
        // mantiene el flujo de pagos; `situación` se recalcula hoy contra la
        // fecha de vencimiento y es la que dice qué hay que pagar ya.
        { param: 'situacion', etiqueta: 'Situación (hoy)', tipo: 'select', opciones: [
          { valor: '',           etiqueta: 'Todas' },
          { valor: 'vencida',    etiqueta: 'Vencidas' },
          { valor: 'por_vencer', etiqueta: 'Vencen esta semana' },
          { valor: 'vigente',    etiqueta: 'Vigentes' },
          { valor: 'saldada',    etiqueta: 'Saldadas' }
        ] },
        { param: 'estado', etiqueta: 'Estado registrado', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Todos los estados' },
          { valor: 'pendiente', etiqueta: 'Pendiente' },
          { valor: 'parcial',   etiqueta: 'Pago parcial' },
          { valor: 'vencida',   etiqueta: 'Vencida' },
          { valor: 'pagada',    etiqueta: 'Pagada' }
        ] },
        FILTRO_PROVEEDOR
      ],
      columnas: [
        { campo: 'proveedor',         titulo: 'Proveedor',   tipo: 'texto', recortar: 30 },
        { campo: 'factura',           titulo: 'Factura',     tipo: 'texto', recortar: 22 },
        { campo: 'situacion',         titulo: 'Situación',   tipo: 'chip',
          color: colorSituacionCxp, etiqueta: v => SITUACION_CXP[v] || v },
        { campo: 'fecha_vencimiento', titulo: 'Vence',       tipo: 'fecha' },
        { campo: 'dias_vencida',      titulo: 'Días',        tipo: 'dias' },
        { campo: 'dias_credito',      titulo: 'Crédito',     tipo: 'dias' },
        { campo: 'estado',            titulo: 'Estado',      tipo: 'texto',
          etiqueta: v => ESTADO_CXP[v] || v },
        { campo: 'monto_original',    titulo: 'Facturado',   tipo: 'moneda', monto: true },
        { campo: 'pagado',            titulo: 'Pagado',      tipo: 'moneda', monto: true },
        { campo: 'saldo_pendiente',   titulo: 'Saldo',       tipo: 'moneda', monto: true }
      ]
    },

    // ── OTD-COM-08 ────────────────────────────────────────────────────
    // ÚNICO informe de Compras SIN dinero: por eso BODEGA sí entra.
    {
      id: 'OTD-COM-08',
      endpoint: 'defectuosos',
      titulo: 'Defectuosos y devoluciones a proveedor',
      descripcion: 'Mercancía defectuosa esperando devolución y en qué paso va cada una. '
                 + 'Sin proveedor asignado, Compras no puede agruparla ni enviarla.',
      icono: 'report_problem',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'],
      vacio: 'No hay mercancía defectuosa con esos filtros.',
      filtros: [
        { param: 'estado', etiqueta: 'Estado del ítem', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todos los estados' },
          { valor: 'pendiente',     etiqueta: 'En el pool (sin devolver)' },
          { valor: 'en_devolucion', etiqueta: 'En devolución' },
          { valor: 'resuelto',      etiqueta: 'Resuelto' }
        ] },
        { param: 'origen', etiqueta: 'Origen', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Todos los orígenes' },
          { valor: 'rma',       etiqueta: 'Devolución de cliente' },
          { valor: 'recepcion', etiqueta: 'Recepción de compra' }
        ] },
        FILTRO_PROVEEDOR,
        { param: 'buscar', etiqueta: 'SKU o nombre del producto', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'fecha_creacion',     titulo: 'Detectado',   tipo: 'fecha' },
        { campo: 'sku',                titulo: 'SKU',         tipo: 'texto' },
        { campo: 'producto',           titulo: 'Producto',    tipo: 'texto', recortar: 26 },
        { campo: 'cantidad',           titulo: 'Unidades',    tipo: 'numero' },
        { campo: 'estado',             titulo: 'Estado',      tipo: 'chip',
          color: colorDefectuoso, etiqueta: v => ESTADO_DEFECTUOSO[v] || v },
        { campo: 'origen',             titulo: 'Origen',      tipo: 'texto',
          etiqueta: v => ORIGEN_DEFECTUOSO[v] || v },
        { campo: 'proveedor',          titulo: 'Proveedor',   tipo: 'texto', recortar: 28 },
        { campo: 'bodega',             titulo: 'Bodega',      tipo: 'texto', recortar: 20 },
        { campo: 'dias_en_pool',       titulo: 'Antigüedad',  tipo: 'dias' },
        { campo: 'devolucion',         titulo: 'Devolución',  tipo: 'texto', recortar: 22 },
        { campo: 'estado_devolucion',  titulo: 'Paso',        tipo: 'texto',
          etiqueta: v => ESTADO_DEV_PROVEEDOR[v] || v },
        { campo: 'tipo_resolucion',    titulo: 'Resolución',  tipo: 'texto',
          etiqueta: v => TIPO_RESOLUCION[v] || v }
      ]
    },

    // ── OTD-COM-10 ────────────────────────────────────────────────────
    {
      id: 'OTD-COM-10',
      endpoint: 'catalogo-proveedor',
      titulo: 'Catálogo proveedor–producto',
      descripcion: 'A quién conviene comprarle cada producto: costo, plazo de entrega, '
                 + 'cantidad mínima y proveedor preferido.',
      icono: 'compare_arrows',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS'],
      vacio: 'Ningún proveedor ofrece un producto que coincida con los filtros.',
      filtros: [
        { param: 'buscar', etiqueta: 'SKU, producto o código del proveedor', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        FILTRO_PROVEEDOR,
        { param: 'oferta', etiqueta: 'Marca de la oferta', tipo: 'select', opciones: [
          { valor: '',           etiqueta: 'Todas las ofertas' },
          { valor: 'preferida',  etiqueta: 'Proveedor preferido' },
          { valor: 'mas_barata', etiqueta: 'La más barata del producto' }
        ] }
      ],
      columnas: [
        { campo: 'sku',                 titulo: 'SKU',          tipo: 'texto' },
        { campo: 'producto',            titulo: 'Producto',     tipo: 'texto', recortar: 28 },
        { campo: 'proveedor',           titulo: 'Proveedor',    tipo: 'texto', recortar: 30 },
        { campo: 'codigo_proveedor',    titulo: 'Código',       tipo: 'texto', recortar: 16 },
        { campo: 'costo',               titulo: 'Costo',        tipo: 'moneda', monto: true },
        { campo: 'es_mas_barato',       titulo: 'Mejor precio', tipo: 'chip',
          color: f => f['es_mas_barato'] ? 'ok' : 'neutral',
          etiqueta: v => v ? 'Sí' : '—' },
        { campo: 'costo_catalogo',      titulo: 'Costo catálogo', tipo: 'moneda', monto: true },
        { campo: 'brecha_catalogo_pct', titulo: 'Brecha',       tipo: 'porcentaje' },
        { campo: 'tiempo_entrega_dias', titulo: 'Plazo',        tipo: 'dias' },
        { campo: 'cantidad_minima',     titulo: 'Mínimo',       tipo: 'numero' },
        { campo: 'es_preferido',        titulo: 'Preferido',    tipo: 'booleano' },
        { campo: 'ofertas',             titulo: 'Proveedores',  tipo: 'numero' }
      ]
    }
  ]
};
