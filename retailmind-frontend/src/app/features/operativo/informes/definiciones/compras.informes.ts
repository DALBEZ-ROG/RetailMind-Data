import { ColorChip, DefinicionDepartamento, FiltroInforme } from '../../../../core/models/informe.model';
import { informePrevisionDemanda } from './prevision.informe';

/**
 * INFORMES TÁCTICOS DE COMPRAS — los CINCO objetivos SIMPLES y los SIETE
 * COMPUESTOS del catálogo (`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §4).
 * Con este archivo el departamento queda COMPLETO: 12 de 12.
 *
 * Este archivo es TODO lo que hay que escribir para la pantalla: la
 * `InformesDepartamentoComponent` genérica pinta los filtros, la tabla, el
 * resumen y la paginación a partir de estas declaraciones. Ni un componente,
 * ni un servicio, ni un estilo nuevo (ver `docs/tactico/PATRON_INFORMES.md`).
 *
 * SIMPLES (PostgreSQL): COM-01 órdenes, COM-02 cuentas por pagar, COM-08
 * defectuosos, COM-10 catálogo proveedor–producto y COM-11 entregas
 * incompletas — el último objetivo SIMPLE del catálogo que quedaba por
 * construir. Sigue siendo simple porque agrega sobre la foto presente del
 * abastecimiento y no compara períodos: por eso su filtro «Ver por» ofrece
 * proveedor y producto, y NO mes.
 *
 * COMPUESTOS (ClickHouse, con marca de agua «Datos al …»): COM-03 puntualidad
 * de pago, COM-04 gasto mensual, COM-05 cumplimiento de plazo, COM-06 días de
 * ciclo, COM-07 rechazos en puerta, COM-09 recuperación al proveedor y COM-12
 * evolución del costo. Cinco de los siete envían además una `salvedad`
 * metodológica que la pantalla pinta ENCIMA de la tabla: son informes que se
 * leen mal si no se sabe cuál es su denominador.
 *
 * SEGREGACIÓN FINANCIERA. BODEGA aparece en `roles` de los TRES informes que el
 * catálogo le da «en cantidades, sin montos»: COM-08 (que no tiene ni una
 * columna de dinero), COM-07 y COM-11 (que sí las tienen para los demás roles y
 * no se las envían a ella — el sobre llega con `conValorizacion: false` y sus
 * celdas de importe quedan en «—»). En los tres el motor NO puede ser la última
 * línea: grp_bodega conserva SELECT sobre `item_defectuoso.costo_unitario`
 * (script 45) y sobre `orden_compra_detalle.precio_unitario` (script 41), y
 * ClickHouse no tiene GRANT por columna. El control es la CONSULTA.
 *
 * El ANALISTA entra en cuatro compuestos (COM-03, COM-04, COM-06 y COM-12), tal
 * como pide la columna de destinatarios del catálogo, y queda fuera de COM-05
 * —que no lleva ni un importe— porque el catálogo lo reserva a Compras y
 * Gerencia como material de negociación con el proveedor.
 *
 * Todo esto espeja SecurityConfig —que es quien realmente decide— y evita
 * disparar una petición que la API negaría con 403.
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

/** Filtro de período compartido por los compuestos que van por fecha. */
const FILTRO_DESDE: FiltroInforme = { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' };
const FILTRO_HASTA: FiltroInforme = { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' };

/** Verde a partir del 80 % de puntualidad; rojo por debajo del 60 %. */
function colorPuntualidad(fila: Record<string, any>): ColorChip {
  const v = Number(fila['pct_a_tiempo']);
  return v >= 80 ? 'ok' : v >= 60 ? 'warn' : 'error';
}

function colorCumplimiento(fila: Record<string, any>): ColorChip {
  const v = Number(fila['pct_cumplimiento']);
  return v >= 80 ? 'ok' : v >= 50 ? 'warn' : 'error';
}

/** El rechazo se mide al revés: cuanto más alto, peor. */
function colorRechazo(fila: Record<string, any>): ColorChip {
  const v = Number(fila['pct_rechazo']);
  return v === 0 ? 'neutral' : v < 0.2 ? 'ok' : v < 0.5 ? 'warn' : 'error';
}

/** Una subida de precio es mala noticia; una bajada, buena. */
function colorVariacion(fila: Record<string, any>): ColorChip {
  const v = Number(fila['variacion_pct']);
  if (Number(fila['compras']) < 2) { return 'neutral'; }
  return v > 5 ? 'error' : v > 0 ? 'warn' : v < 0 ? 'ok' : 'neutral';
}

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
      fuente: 'simple',
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
      fuente: 'simple',
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
      fuente: 'simple',
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
      fuente: 'simple',
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
    },

    // ── OTD-COM-09 ── COMPUESTO: fact_devolucion_proveedor (Fase 4) ───
    // MUESTRA DÉBIL DECLARADA (catálogo: REQUIERE VOLUMEN). El resumen
    // empieza por el tamaño de la muestra y solo después da el dinero.
    {
      id: 'OTD-COM-09',
      endpoint: 'recuperacion-proveedor',
      fuente: 'compuesto',
      titulo: 'Recuperación al proveedor por mercancía defectuosa',
      descripcion: 'Cuánto se recupera del proveedor, en nota de crédito o en reposición. '
                 + 'MUESTRA DÉBIL: hay pocas devoluciones resueltas, así que la columna '
                 + '«Resoluciones» es el denominador de todo lo demás y hay que leerla '
                 + 'primero. Solo cuenta como recuperado lo que YA tiene desenlace; el '
                 + 'pool pendiente va aparte, en «Costo del pool».',
      icono: 'assignment_returned',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS'],
      sinPaginar: true,
      vacio: 'No hay ítems defectuosos con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',           etiqueta: 'Proveedor' },
          { valor: 'mes',        etiqueta: 'Mes de detección' },
          { valor: 'categoria',  etiqueta: 'Categoría' },
          { valor: 'resolucion', etiqueta: 'Tipo de resolución' }
        ] },
        // OJO: los valores son `rma` / `recepcion`, no los del diseño del ETL
        // (`inspeccion_rma` / `recepcion_compra`), que casan con cero filas.
        { param: 'origen', etiqueta: 'Origen del defecto', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Los dos orígenes' },
          { valor: 'rma',       etiqueta: 'Inspección de devolución (RMA)' },
          { valor: 'recepcion', etiqueta: 'Recepción de compra' }
        ], ancho: 'ancho' },
        { param: 'resolucion', etiqueta: 'Resolución', tipo: 'select', opciones: [
          { valor: '',             etiqueta: 'Todas' },
          { valor: 'nota_credito', etiqueta: 'Nota de crédito' },
          { valor: 'reposicion',   etiqueta: 'Reposición de producto' },
          { valor: 'sin_resolver', etiqueta: 'Todavía sin resolver' }
        ] },
        { param: 'estado', etiqueta: 'Estado del ítem', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todos' },
          { valor: 'pendiente',     etiqueta: 'Pendiente (sin reclamar)' },
          { valor: 'en_devolucion', etiqueta: 'En devolución' },
          { valor: 'resuelto',      etiqueta: 'Resuelto' }
        ] },
        { param: 'desde', etiqueta: 'Detectado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Detectado hasta', tipo: 'fecha' },
        { param: 'proveedor', etiqueta: 'Proveedor', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'etiqueta',         titulo: 'Proveedor / período', tipo: 'texto',
          recortar: 26 },
        { campo: 'resoluciones',     titulo: 'Resoluciones (n)', tipo: 'chip',
          color: f => Number(f['resoluciones']) >= 3 ? 'ok'
                    : Number(f['resoluciones']) > 0 ? 'warn' : 'neutral' },
        { campo: 'devoluciones',     titulo: 'Devoluciones', tipo: 'numero' },
        { campo: 'items',            titulo: 'Ítems',        tipo: 'numero' },
        { campo: 'unidades',         titulo: 'Unidades',     tipo: 'numero' },
        { campo: 'costo_pool',       titulo: 'Costo del pool', tipo: 'moneda',
          monto: true },
        { campo: 'credito',          titulo: 'Nota de crédito', tipo: 'moneda',
          monto: true },
        { campo: 'reposicion',       titulo: 'Reposición',   tipo: 'moneda', monto: true },
        { campo: 'recuperado',       titulo: 'Recuperado',   tipo: 'moneda', monto: true },
        { campo: 'pct_recuperado',   titulo: '% recuperado', tipo: 'porcentaje' },
        { campo: 'items_pendientes', titulo: 'Sin reclamar', tipo: 'chip',
          color: f => Number(f['items_pendientes']) > 0 ? 'warn' : 'neutral' },
        { campo: 'dias_resolucion',  titulo: 'Días al resolver', tipo: 'dias' },
        { campo: 'de_rma',           titulo: 'De RMA',       tipo: 'numero' },
        { campo: 'de_recepcion',     titulo: 'De recepción', tipo: 'numero' }
      ]
    },

    // ── OTD-COM-11 ── SIMPLE: PostgreSQL. MIXTO (Bodega sin montos) ───
    // Último objetivo SIMPLE del catálogo. No lleva eje de mes A PROPÓSITO:
    // agregar sobre la foto presente es lo que lo mantiene simple.
    {
      id: 'OTD-COM-11',
      endpoint: 'entregas-incompletas',
      fuente: 'simple',
      titulo: 'Quién entrega incompleto',
      descripcion: 'Lo que se pidió contra lo que de verdad llegó, línea a línea. Por '
                 + 'defecto solo cuentan las órdenes YA entregadas: una orden cancelada o '
                 + 'todavía en camino tiene cero unidades recibidas, y sumarla haría '
                 + 'parecer que el proveedor incumplió cuando no llegó a deberlo.',
      icono: 'inventory',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'],
      vacio: 'Ninguna línea de compra coincide con el alcance y los filtros elegidos.',
      filtros: [
        { param: 'alcance', etiqueta: 'Qué órdenes cuentan', tipo: 'select', opciones: [
          { valor: '',           etiqueta: 'Ya entregadas (recomendado)' },
          { valor: 'en_camino',  etiqueta: 'Todavía en camino' },
          { valor: 'canceladas', etiqueta: 'Canceladas' },
          { valor: 'todas',      etiqueta: 'Todas, sin distinguir' }
        ], ancho: 'ancho' },
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Proveedor' },
          { valor: 'producto',  etiqueta: 'Producto (SKU)' }
        ] },
        FILTRO_PROVEEDOR,
        { param: 'desde', etiqueta: 'Orden emitida desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Orden emitida hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'etiqueta',               titulo: 'Proveedor / SKU', tipo: 'texto',
          recortar: 30 },
        { campo: 'detalle',                titulo: 'Producto',    tipo: 'texto', recortar: 26 },
        { campo: 'pct_cumplimiento',       titulo: 'Cumplimiento', tipo: 'chip',
          color: colorCumplimiento, etiqueta: v => `${v} %` },
        { campo: 'uds_faltantes',          titulo: 'No servidas', tipo: 'numero' },
        { campo: 'uds_pedidas',            titulo: 'Pedidas',     tipo: 'numero' },
        { campo: 'uds_recibidas',          titulo: 'Recibidas',   tipo: 'numero' },
        { campo: 'lineas_incompletas',     titulo: 'Líneas cortas', tipo: 'numero' },
        { campo: 'lineas',                 titulo: 'Líneas',      tipo: 'numero' },
        { campo: 'pct_lineas_incompletas', titulo: '% líneas cortas', tipo: 'porcentaje' },
        { campo: 'ordenes',                titulo: 'Órdenes',     tipo: 'numero' },
        // Bodega no la recibe: el backend no la selecciona y la celda queda «—».
        { campo: 'valor_faltante',         titulo: 'Valor no servido', tipo: 'moneda',
          monto: true }
      ]
    },

    // ── OTD-COM-03 ── COMPUESTO: fact_flujo_caja (egreso) ────────────
    {
      id: 'OTD-COM-03',
      endpoint: 'puntualidad-pago',
      fuente: 'compuesto',
      titulo: 'Puntualidad de pago al proveedor',
      descripcion: 'Pagos hechos antes o después del vencimiento. Anticipo y retraso van '
                 + 'en columnas SEPARADAS a propósito: promediados juntos se cancelan y un '
                 + 'proveedor al que se le paga tarde puede aparecer con un desvío medio '
                 + 'negativo, es decir, como si se le adelantara el dinero.',
      icono: 'schedule_send',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay pagos a proveedor con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',       etiqueta: 'Proveedor' },
          { valor: 'mes',    etiqueta: 'Mes de pago' },
          { valor: 'metodo', etiqueta: 'Método de pago' }
        ] },
        // Los anticipados son un SUBCONJUNTO de los puntuales (506 de 564), no
        // una tercera categoría: elegirlos no excluye a los que pagaron justo.
        { param: 'puntualidad', etiqueta: 'Puntualidad', tipo: 'select', opciones: [
          { valor: '',           etiqueta: 'Todos los pagos' },
          { valor: 'a_tiempo',   etiqueta: 'Pagados a tiempo' },
          { valor: 'tarde',      etiqueta: 'Pagados tarde' },
          { valor: 'anticipado', etiqueta: 'Pagados por adelantado' }
        ], ancho: 'ancho' },
        { param: 'metodo', etiqueta: 'Método', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todos los métodos' },
          { valor: 'transferencia', etiqueta: 'Transferencia bancaria' },
          { valor: 'efectivo',      etiqueta: 'Efectivo' }
        ] },
        FILTRO_PROVEEDOR,
        { param: 'desde', etiqueta: 'Pagado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Pagado hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'etiqueta',            titulo: 'Proveedor / período', tipo: 'texto',
          recortar: 30 },
        { campo: 'pct_a_tiempo',        titulo: 'Puntualidad', tipo: 'chip',
          color: colorPuntualidad, etiqueta: v => `${v} %` },
        { campo: 'pagos',               titulo: 'Pagos',       tipo: 'numero' },
        { campo: 'pagos_a_tiempo',      titulo: 'A tiempo',    tipo: 'numero' },
        { campo: 'pagos_tarde',         titulo: 'Tarde',       tipo: 'numero' },
        { campo: 'pagos_anticipados',   titulo: 'Adelantados', tipo: 'numero' },
        { campo: 'pagos_en_fecha',      titulo: 'Justo el día', tipo: 'numero' },
        { campo: 'dias_anticipo_medio', titulo: 'Anticipo medio', tipo: 'dias' },
        { campo: 'dias_retraso_medio',  titulo: 'Retraso medio', tipo: 'dias' },
        { campo: 'max_retraso',         titulo: 'Peor retraso', tipo: 'dias' },
        { campo: 'monto_pagado',        titulo: 'Pagado',      tipo: 'moneda', monto: true },
        { campo: 'monto_tarde',         titulo: 'Pagado tarde', tipo: 'moneda', monto: true }
      ]
    },

    // ── OTD-COM-04 ── COMPUESTO: fact_orden_compra ───────────────────
    // El mes es el de la FACTURA y no el de la orden: 360 de 839 caen en un
    // mes distinto. El sobre lo declara en `salvedad`.
    {
      id: 'OTD-COM-04',
      endpoint: 'gasto-mensual',
      fuente: 'compuesto',
      titulo: 'Gasto de compras por proveedor y mes',
      descripcion: 'Cuánto gastamos de verdad. El gasto es lo que el proveedor FACTURA, no '
                 + 'el total de la orden: cuando la entrega es parcial factura lo que '
                 + 'entregó, y sumar la orden inventaría un 2,4 % de gasto. Las dos cifras '
                 + 'van una al lado de la otra y su diferencia es la columna «Brecha».',
      icono: 'payments',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay facturas de compra con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Mes de la factura' },
          { valor: 'proveedor', etiqueta: 'Proveedor' },
          { valor: 'bodega',    etiqueta: 'Bodega de destino' }
        ] },
        FILTRO_PROVEEDOR,
        { param: 'desde', etiqueta: 'Facturado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Facturado hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'etiqueta',          titulo: 'Período / proveedor', tipo: 'texto',
          recortar: 30 },
        { campo: 'gasto',             titulo: 'Gasto facturado', tipo: 'moneda',
          monto: true },
        { campo: 'facturas',          titulo: 'Facturas',   tipo: 'numero' },
        { campo: 'comprometido_oc',   titulo: 'Comprometido', tipo: 'moneda', monto: true },
        { campo: 'brecha',            titulo: 'Brecha',     tipo: 'moneda', monto: true },
        { campo: 'brecha_pct',        titulo: '% brecha',   tipo: 'porcentaje' },
        { campo: 'ordenes_parciales', titulo: 'Parciales',  tipo: 'numero' },
        { campo: 'uds_recibidas',     titulo: 'Unidades',   tipo: 'numero' },
        { campo: 'factura_media',     titulo: 'Factura media', tipo: 'moneda', monto: true },
        { campo: 'pagado',            titulo: 'Pagado',     tipo: 'moneda', monto: true },
        { campo: 'saldo_cxp',         titulo: 'Saldo por pagar', tipo: 'moneda',
          monto: true },
        { campo: 'proveedores',       titulo: 'Proveedores', tipo: 'numero' }
      ]
    },

    // ── OTD-COM-05 ── COMPUESTO: fact_orden_compra ───────────────────
    // Sin ANALISTA: el catálogo lo reserva a Compras y Gerencia.
    {
      id: 'OTD-COM-05',
      endpoint: 'cumplimiento-plazo',
      fuente: 'compuesto',
      titulo: 'Cumplimiento del plazo prometido',
      descripcion: 'La fecha que el proveedor prometió contra el día en que la mercancía '
                 + 'llegó de verdad. La columna «Medidas» es el denominador de todo lo '
                 + 'demás: solo 825 de 865 órdenes tienen a la vez promesa y recepción, y '
                 + 'las que no la tienen NO se reparten como incumplimientos.',
      icono: 'event_available',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS'],
      sinPaginar: true,
      vacio: 'No hay órdenes de compra con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',    etiqueta: 'Proveedor' },
          { valor: 'mes', etiqueta: 'Mes de la orden' }
        ] },
        { param: 'resultado', etiqueta: 'Resultado', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Cumplidas e incumplidas' },
          { valor: 'cumplio',   etiqueta: 'Solo las que llegaron en plazo' },
          { valor: 'incumplio', etiqueta: 'Solo las que llegaron tarde' }
        ], ancho: 'ancho' },
        FILTRO_PROVEEDOR,
        { param: 'desde', etiqueta: 'Orden emitida desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Orden emitida hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'etiqueta',            titulo: 'Proveedor / período', tipo: 'texto',
          recortar: 30 },
        { campo: 'pct_cumplimiento',    titulo: 'Cumplimiento', tipo: 'chip',
          color: colorCumplimiento, etiqueta: v => `${v} %` },
        { campo: 'medidas',             titulo: 'Medidas (n)', tipo: 'numero' },
        { campo: 'cumplidas',           titulo: 'En plazo',   tipo: 'numero' },
        { campo: 'incumplidas',         titulo: 'Tarde',      tipo: 'numero' },
        { campo: 'dias_retraso_medio',  titulo: 'Retraso medio', tipo: 'dias' },
        { campo: 'dias_adelanto_medio', titulo: 'Adelanto medio', tipo: 'dias' },
        { campo: 'peor_retraso',        titulo: 'Peor retraso', tipo: 'dias' },
        { campo: 'dias_desvio_medio',   titulo: 'Desvío medio', tipo: 'dias' },
        { campo: 'ordenes',             titulo: 'Órdenes',    tipo: 'numero' },
        { campo: 'sin_promesa',         titulo: 'Sin promesa', tipo: 'numero' },
        { campo: 'sin_recepcion',       titulo: 'Sin llegar', tipo: 'numero' }
      ]
    },

    // ── OTD-COM-06 ── COMPUESTO: fact_orden_compra ───────────────────
    // Base distinta de COM-05 a propósito: 839 con recepción, no 825 pares.
    {
      id: 'OTD-COM-06',
      endpoint: 'ciclo-compra',
      fuente: 'compuesto',
      titulo: 'Días reales del ciclo de compra',
      descripcion: 'Cuánto tarda de verdad la mercancía en llegar desde que se emite la '
                 + 'orden, exista o no promesa de por medio. No es el informe anterior con '
                 + 'otro nombre: aquí entran también las órdenes que llegaron sin fecha '
                 + 'prometida. Se dan promedio y mediana porque la distribución no es '
                 + 'simétrica.',
      icono: 'timelapse',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay órdenes de compra con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',       etiqueta: 'Proveedor' },
          { valor: 'mes',    etiqueta: 'Mes de la orden' },
          { valor: 'bodega', etiqueta: 'Bodega de destino' }
        ] },
        FILTRO_PROVEEDOR,
        FILTRO_DESDE,
        FILTRO_HASTA
      ],
      columnas: [
        { campo: 'etiqueta',       titulo: 'Proveedor / período', tipo: 'texto',
          recortar: 30 },
        { campo: 'dias_promedio',  titulo: 'Ciclo medio',   tipo: 'dias' },
        { campo: 'dias_mediana',   titulo: 'Ciclo mediano', tipo: 'dias' },
        { campo: 'medidas',        titulo: 'Medidas (n)',   tipo: 'numero' },
        { campo: 'dias_min',       titulo: 'La más rápida', tipo: 'dias' },
        { campo: 'dias_max',       titulo: 'La más lenta',  tipo: 'dias' },
        { campo: 'hasta_7_dias',   titulo: '≤ 7 días',      tipo: 'numero' },
        { campo: 'pct_hasta_7',    titulo: '% en 7 días',   tipo: 'porcentaje' },
        { campo: 'mas_de_14_dias', titulo: '> 14 días',     tipo: 'numero' },
        { campo: 'ordenes',        titulo: 'Órdenes',       tipo: 'numero' },
        { campo: 'sin_recepcion',  titulo: 'Sin llegar',    tipo: 'numero' },
        { campo: 'uds_recibidas',  titulo: 'Unidades',      tipo: 'numero' }
      ]
    },

    // ── OTD-COM-07 ── COMPUESTO: fact_compra_linea. MIXTO ────────────
    // BODEGA entra y NO recibe «Valor rechazado»: la celda queda en «—».
    {
      id: 'OTD-COM-07',
      endpoint: 'rechazos',
      fuente: 'compuesto',
      titulo: 'Mercancía rechazada en puerta',
      descripcion: 'Cuánta mercancía llega mal y por qué. El porcentaje va sobre lo que '
                 + 'FÍSICAMENTE LLEGÓ (aceptado + rechazado) y no sobre lo que se pidió: '
                 + 'en 37 de las 92 líneas con rechazo el almacén lo registró por encima '
                 + 'de lo recibido, y sobre lo pedido esas líneas saldrían infladas — '
                 + 'siempre al alza y siempre en los mismos proveedores.',
      icono: 'block',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'],
      sinPaginar: true,
      vacio: 'No hay líneas de compra con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Proveedor' },
          { valor: 'motivo',    etiqueta: 'Motivo del rechazo' },
          { valor: 'mes',       etiqueta: 'Mes de la orden' },
          { valor: 'categoria', etiqueta: 'Categoría' }
        ] },
        // Cinco motivos, no seis: el ETL funde el valor tecleado a mano
        // «cajas mojadas en el transporte» con «Empaque danado en transito».
        { param: 'motivo', etiqueta: 'Motivo', tipo: 'select', opciones: [
          { valor: '',                     etiqueta: 'Todos los motivos' },
          { valor: 'empaque_danado',       etiqueta: 'Empaque dañado en tránsito' },
          { valor: 'defecto_fabrica',      etiqueta: 'Defecto de fábrica' },
          { valor: 'caducidad_proxima',    etiqueta: 'Caducidad próxima' },
          { valor: 'no_coincide',          etiqueta: 'No coincide con la especificación' },
          { valor: 'unidades_incompletas', etiqueta: 'Unidades incompletas en caja' }
        ], ancho: 'ancho' },
        { param: 'soloConRechazo', etiqueta: 'Qué líneas', tipo: 'select', opciones: [
          { valor: '',     etiqueta: 'Todas (para ver la tasa real)' },
          { valor: 'true', etiqueta: 'Solo las que tuvieron rechazo' }
        ], ancho: 'ancho' },
        FILTRO_PROVEEDOR,
        { param: 'desde', etiqueta: 'Orden emitida desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Orden emitida hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'etiqueta',           titulo: 'Proveedor / motivo', tipo: 'texto',
          recortar: 30 },
        { campo: 'pct_rechazo',        titulo: 'Tasa de rechazo', tipo: 'chip',
          color: colorRechazo, etiqueta: v => `${v} %` },
        { campo: 'uds_rechazadas',     titulo: 'Rechazadas', tipo: 'numero' },
        { campo: 'uds_llegadas',       titulo: 'Llegaron',   tipo: 'numero' },
        { campo: 'uds_aceptadas',      titulo: 'Aceptadas',  tipo: 'numero' },
        { campo: 'uds_pedidas',        titulo: 'Pedidas',    tipo: 'numero' },
        { campo: 'lineas_con_rechazo', titulo: 'Líneas con rechazo', tipo: 'numero' },
        { campo: 'lineas',             titulo: 'Líneas',     tipo: 'numero' },
        // Bodega no la recibe: el backend no la selecciona y la celda queda «—».
        { campo: 'valor_rechazado',    titulo: 'Valor rechazado', tipo: 'moneda',
          monto: true },
        { campo: 'motivos',            titulo: 'Motivos',    tipo: 'numero' },
        { campo: 'proveedores',        titulo: 'Proveedores', tipo: 'numero' },
        { campo: 'ordenes',            titulo: 'Órdenes',    tipo: 'numero' }
      ]
    },

    // ── OTD-COM-12 ── COMPUESTO: fact_compra_linea (ventana) ─────────
    // El informe para el que se invirtió el ORDER BY de la tabla.
    {
      id: 'OTD-COM-12',
      endpoint: 'evolucion-costo',
      fuente: 'compuesto',
      titulo: 'Evolución del costo de compra',
      descripcion: 'Cómo cambia el precio que cobra el proveedor por cada producto entre '
                 + 'una compra y la siguiente. La variación se mide dentro de la serie de '
                 + 'CADA proveedor: un producto que se compra a dos proveedores aparece en '
                 + 'dos filas y sus precios no se comparan entre sí.',
      icono: 'trending_up',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA'],
      vacio: 'Ningún producto coincide con los filtros elegidos.',
      filtros: [
        { param: 'tendencia', etiqueta: 'Qué le pasó al precio', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Todo' },
          { valor: 'subio',     etiqueta: 'Subió' },
          { valor: 'bajo',      etiqueta: 'Bajó' },
          { valor: 'estable',   etiqueta: 'Sin cambio (con dos o más compras)' },
          { valor: 'sin_serie', etiqueta: 'Una sola compra (sin serie)' }
        ], ancho: 'ancho' },
        { param: 'buscar', etiqueta: 'SKU o producto', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        FILTRO_PROVEEDOR,
        { param: 'minimoCompras', etiqueta: 'Mínimo de compras', tipo: 'numero' },
        { param: 'desde', etiqueta: 'Compras desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Compras hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'sku',             titulo: 'SKU',        tipo: 'texto' },
        { campo: 'producto',        titulo: 'Producto',   tipo: 'texto', recortar: 26 },
        { campo: 'proveedor',       titulo: 'Proveedor',  tipo: 'texto', recortar: 26 },
        { campo: 'variacion_pct',   titulo: 'Variación',  tipo: 'chip',
          color: colorVariacion,
          etiqueta: (v, f) => Number(f['compras']) < 2 ? 'Sin serie' : `${v} %` },
        { campo: 'precio_inicial',  titulo: 'Primer precio', tipo: 'moneda', monto: true },
        { campo: 'precio_actual',   titulo: 'Último precio', tipo: 'moneda', monto: true },
        { campo: 'variacion',       titulo: 'Diferencia', tipo: 'moneda', monto: true },
        { campo: 'compras',         titulo: 'Compras',    tipo: 'numero' },
        { campo: 'subidas',         titulo: 'Subidas',    tipo: 'numero' },
        { campo: 'bajadas',         titulo: 'Bajadas',    tipo: 'numero' },
        { campo: 'estables',        titulo: 'Sin cambio', tipo: 'numero' },
        { campo: 'ultimo_cambio',   titulo: 'Último salto', tipo: 'moneda', monto: true },
        { campo: 'primera_compra',  titulo: 'Primera',    tipo: 'texto' },
        { campo: 'ultima_compra',   titulo: 'Última',     tipo: 'texto' },
        { campo: 'categoria',       titulo: 'Categoría',  tipo: 'texto', recortar: 16 },
        { campo: 'uds_compradas',   titulo: 'Unidades',   tipo: 'numero' }
      ]
    },

    // ── OTD-GER-13 ── PREDICTIVO: fact_prevision_demanda (fase E2, §5.1) ──
    // El mismo informe que sirve Gerencia, con OTRO reparto: aquí entra
    // COMPRAS y sale el ANALISTA. Sirve a D-11.1 (el plan de compra del
    // próximo trimestre) y a D-07.5 (el nivel objetivo de stock).
    // Para dimensionar una compra la columna que decide es «Máximo 80 %»:
    // pedir por la previsión central deja fuera la mitad de los escenarios.
    informePrevisionDemanda(['ADMIN', 'GERENTE', 'COMPRAS'])
  ]
};
