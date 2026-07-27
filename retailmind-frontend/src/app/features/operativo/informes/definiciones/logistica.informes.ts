import { ColorChip, DefinicionDepartamento, FiltroInforme } from '../../../../core/models/informe.model';

/**
 * INFORMES TÁCTICOS DE LOGÍSTICA / DESPACHO — los cuatro objetivos del catálogo
 * (`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §7) que se resuelven con una
 * consulta directa a PostgreSQL.
 *
 * Este archivo es TODO lo que hay que escribir para la pantalla: la
 * `InformesDepartamentoComponent` genérica pinta los filtros, la tabla, el
 * resumen y la paginación a partir de estas declaraciones. Ni un componente,
 * ni un servicio, ni un estilo nuevo (ver `docs/tactico/PATRON_INFORMES.md`).
 *
 * Los COMPUESTOS de Logística (entregas a tiempo — LOG-03, días de tránsito —
 * LOG-04, novedades por desenlace — LOG-05, ciclo de devolución — LOG-07,
 * motivos y destino — LOG-08, tasa mensual — LOG-09, reembolsos — LOG-10,
 * tiempos por etapa — LOG-12) NO están aquí: son de la fase ETL → ClickHouse.
 *
 * SEGREGACIÓN FINANCIERA: DESPACHO aparece en `roles` de los TRES informes de
 * estados y cantidades y NO en OTD-LOG-11, que es el único con dinero. Esto
 * espeja SecurityConfig — que es quien realmente decide — y evita disparar una
 * petición que la API negaría con 403.
 */

/**
 * Transportista como búsqueda por nombre (contiene), no por id: la lista es
 * corta pero cambia con los contratos, y el backend filtra con ILIKE. Las
 * opciones son atajos, no una lista cerrada.
 */
const FILTRO_TRANSPORTISTA: FiltroInforme = {
  param: 'transportista', etiqueta: 'Transportista', tipo: 'texto',
  debounce: true, ancho: 'ancho'
};

/** En la cola, lo verde es lo que YA se puede despachar. */
function colorCola(fila: Record<string, any>): ColorChip {
  if (fila['listo_para_despachar']) { return 'ok'; }
  return fila['estado'] === 'en_preparacion' ? 'info' : 'warn';
}

const ESTADO_COLA: Record<string, string> = {
  facturado:      'Facturado (espera picking)',
  en_preparacion: 'En preparación',
  preparado:      'LISTO para despachar'
};

const CANAL: Record<string, string> = {
  web:      'Tienda en línea',
  tienda:   'Mostrador',
  telefono: 'Teléfono'
};

function colorEnvio(fila: Record<string, any>): ColorChip {
  switch (fila['estado']) {
    case 'entregado':   return 'ok';
    case 'fallido':
    case 'devuelto':    return 'error';
    case 'en_transito': return fila['atrasado'] ? 'warn' : 'info';
    default:            return 'neutral';
  }
}

const ESTADO_ENVIO: Record<string, string> = {
  preparando:  'Preparando',
  listo:       'Listo',
  en_transito: 'En camino',
  entregado:   'Entregado',
  fallido:     'Fallido',
  devuelto:    'Devuelto al almacén'
};

/** El ciclo RMA en colores: terminal, en curso o rechazado. */
function colorDevolucion(fila: Record<string, any>): ColorChip {
  switch (fila['estado']) {
    case 'cerrada':
    case 'reembolsada':  return 'ok';
    case 'rechazada':    return 'error';
    case 'solicitada':   return 'warn';
    default:             return 'info';
  }
}

const ESTADO_DEVOLUCION: Record<string, string> = {
  solicitada:    'Solicitada por el cliente',
  en_revision:   'En revisión de Soporte',
  aprobada:      'Aprobada (guía emitida)',
  rechazada:     'Rechazada',
  en_transito:   'En camino de vuelta',
  recibida:      'Recibida en bodega',
  inspeccionada: 'Inspeccionada',
  reembolsada:   'Reembolsada',
  cerrada:       'Cerrada'
};

export const INFORMES_LOGISTICA: DefinicionDepartamento = {
  departamento: 'logistica',
  titulo: 'Informes de Logística',
  descripcion: 'Cola de despacho, seguimiento de envíos, devoluciones en curso y costo del transporte',
  icono: 'local_shipping',
  informes: [

    // ── OTD-LOG-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-LOG-01',
      endpoint: 'cola-despacho',
      titulo: 'Cola de despacho',
      descripcion: 'Pedidos del tramo de salida esperando irse. Los marcados como LISTO '
                 + 'son los únicos que ya se pueden despachar.',
      icono: 'pending_actions',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO'],
      vacio: 'No hay pedidos esperando despacho con esos filtros.',
      filtros: [
        { param: 'estado', etiqueta: 'Paso', tipo: 'select', opciones: [
          { valor: '',               etiqueta: 'Todo el tramo de salida' },
          { valor: 'preparado',      etiqueta: 'Listos para despachar' },
          { valor: 'en_preparacion', etiqueta: 'En preparación' },
          { valor: 'facturado',      etiqueta: 'Facturados (sin picking)' }
        ] },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] },
        FILTRO_TRANSPORTISTA,
        { param: 'buscar', etiqueta: 'Nº de pedido o cliente', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'numero',        titulo: 'Pedido',        tipo: 'texto' },
        { campo: 'estado',        titulo: 'Paso',          tipo: 'chip',
          color: colorCola, etiqueta: v => ESTADO_COLA[v] || v },
        { campo: 'fecha_pedido',  titulo: 'Pedido el',     tipo: 'fecha' },
        { campo: 'dias_en_cola',  titulo: 'Esperando',     tipo: 'dias' },
        { campo: 'cliente',       titulo: 'Cliente',       tipo: 'texto', recortar: 26 },
        { campo: 'ciudad',        titulo: 'Ciudad',        tipo: 'texto', recortar: 18 },
        { campo: 'transportista', titulo: 'Transportista', tipo: 'texto', recortar: 20 },
        { campo: 'metodo_envio',  titulo: 'Método',        tipo: 'texto', recortar: 18 },
        { campo: 'lineas',        titulo: 'Líneas',        tipo: 'numero' },
        { campo: 'unidades',      titulo: 'Unidades',      tipo: 'numero' },
        { campo: 'canal',         titulo: 'Canal',         tipo: 'texto',
          etiqueta: v => CANAL[v] || v }
      ]
    },

    // ── OTD-LOG-02 ────────────────────────────────────────────────────
    {
      id: 'OTD-LOG-02',
      endpoint: 'envios',
      titulo: 'Envíos por estado y transportista',
      descripcion: 'Qué va en camino, qué se entregó y qué volvió, con guía y fechas. '
                 + 'Un envío con novedad abierta no se puede marcar como entregado.',
      icono: 'local_shipping',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO'],
      vacio: 'Ningún envío coincide con los filtros elegidos.',
      filtros: [
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [
          { valor: '',            etiqueta: 'Todos los estados' },
          { valor: 'en_transito', etiqueta: 'En camino' },
          { valor: 'entregado',   etiqueta: 'Entregado' },
          { valor: 'fallido',     etiqueta: 'Fallido' },
          { valor: 'devuelto',    etiqueta: 'Devuelto al almacén' },
          { valor: 'listo',       etiqueta: 'Listo' },
          { valor: 'preparando',  etiqueta: 'Preparando' }
        ] },
        FILTRO_TRANSPORTISTA,
        { param: 'desde', etiqueta: 'Despachado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Despachado hasta', tipo: 'fecha' },
        { param: 'buscar', etiqueta: 'Guía, envío, pedido o cliente', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'numero_guia',            titulo: 'Guía',          tipo: 'texto', recortar: 22 },
        { campo: 'estado',                 titulo: 'Estado',        tipo: 'chip',
          color: colorEnvio, etiqueta: v => ESTADO_ENVIO[v] || v },
        { campo: 'transportista',          titulo: 'Transportista', tipo: 'texto', recortar: 20 },
        { campo: 'pedido',                 titulo: 'Pedido',        tipo: 'texto' },
        { campo: 'cliente',                titulo: 'Cliente',       tipo: 'texto', recortar: 24 },
        { campo: 'fecha_despacho',         titulo: 'Despachado',    tipo: 'fecha' },
        { campo: 'fecha_entrega_estimada', titulo: 'Prometido',     tipo: 'fecha' },
        { campo: 'fecha_entrega_real',     titulo: 'Entregado',     tipo: 'fecha' },
        { campo: 'dias_transito',          titulo: 'En tránsito',   tipo: 'dias' },
        { campo: 'novedades_abiertas',     titulo: 'Novedades',     tipo: 'chip',
          color: f => Number(f['novedades_abiertas']) > 0 ? 'error' : 'neutral' },
        { campo: 'metodo_envio',           titulo: 'Método',        tipo: 'texto', recortar: 18 }
      ]
    },

    // ── OTD-LOG-06 ────────────────────────────────────────────────────
    {
      id: 'OTD-LOG-06',
      endpoint: 'devoluciones',
      titulo: 'Devoluciones de cliente en curso',
      descripcion: 'El ciclo RMA paso a paso, con el motivo del cliente y el resultado de '
                 + 'la inspección: solo lo apto para reventa vuelve al stock.',
      icono: 'assignment_return',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO', 'SOPORTE', 'BODEGA'],
      vacio: 'No hay devoluciones con esos filtros.',
      filtros: [
        { param: 'estado', etiqueta: 'Paso del ciclo', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todos los pasos' },
          { valor: 'solicitada',    etiqueta: 'Solicitada' },
          { valor: 'en_revision',   etiqueta: 'En revisión' },
          { valor: 'aprobada',      etiqueta: 'Aprobada' },
          { valor: 'en_transito',   etiqueta: 'En camino de vuelta' },
          { valor: 'recibida',      etiqueta: 'Recibida' },
          { valor: 'inspeccionada', etiqueta: 'Inspeccionada' },
          { valor: 'reembolsada',   etiqueta: 'Reembolsada' },
          { valor: 'cerrada',       etiqueta: 'Cerrada' },
          { valor: 'rechazada',     etiqueta: 'Rechazada' }
        ] },
        { param: 'motivo', etiqueta: 'Motivo', tipo: 'select', opciones: [
          { valor: '',                 etiqueta: 'Todos los motivos' },
          { valor: 'producto_danado',  etiqueta: 'Producto dañado o defectuoso' },
          { valor: 'no_corresponde',   etiqueta: 'No corresponde a lo pedido' },
          { valor: 'talla_incorrecta', etiqueta: 'Talla o ajuste incorrecto' },
          { valor: 'arrepentimiento',  etiqueta: 'Cambio de opinión' }
        ] },
        { param: 'desde', etiqueta: 'Solicitada desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Solicitada hasta', tipo: 'fecha' },
        { param: 'buscar', etiqueta: 'Devolución, guía, pedido o cliente', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'numero',         titulo: 'Devolución',    tipo: 'texto', recortar: 22 },
        { campo: 'estado',         titulo: 'Paso',          tipo: 'chip',
          color: colorDevolucion, etiqueta: v => ESTADO_DEVOLUCION[v] || v },
        { campo: 'motivo',         titulo: 'Motivo',        tipo: 'texto', recortar: 26 },
        { campo: 'fecha_creacion', titulo: 'Solicitada',    tipo: 'fecha' },
        { campo: 'dias_abierta',   titulo: 'Antigüedad',    tipo: 'dias' },
        { campo: 'cliente',        titulo: 'Cliente',       tipo: 'texto', recortar: 24 },
        { campo: 'pedido',         titulo: 'Pedido',        tipo: 'texto' },
        { campo: 'guia_retorno',   titulo: 'Guía retorno',  tipo: 'texto', recortar: 22 },
        { campo: 'transportista',  titulo: 'Transportista', tipo: 'texto', recortar: 18 },
        { campo: 'bodega',         titulo: 'Bodega',        tipo: 'texto', recortar: 20 },
        { campo: 'unidades',       titulo: 'Unidades',      tipo: 'numero' },
        { campo: 'aptas',          titulo: 'Aptas',         tipo: 'numero' },
        { campo: 'defectuosas',    titulo: 'Defectuosas',   tipo: 'numero' },
        { campo: 'rechazadas',     titulo: 'Rechazadas',    tipo: 'numero' }
      ]
    },

    // ── OTD-LOG-11 ────────────────────────────────────────────────────
    // ÚNICO informe de Logística con DINERO: sin DESPACHO ni BODEGA.
    {
      id: 'OTD-LOG-11',
      endpoint: 'costo-envio',
      titulo: 'Costo de envío por zona y transportista',
      descripcion: 'Cuánto cuesta llevar la mercancía a cada zona y con cada transportista, '
                 + 'contra lo que se le cobró al cliente por el flete.',
      icono: 'payments',
      roles: ['ADMIN', 'GERENTE'],
      sinPaginar: true,
      vacio: 'No hay envíos que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'zona', etiqueta: 'Zona de envío', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        FILTRO_TRANSPORTISTA,
        { param: 'desde', etiqueta: 'Despachado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Despachado hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'zona',               titulo: 'Zona',          tipo: 'texto', recortar: 26 },
        { campo: 'transportista',      titulo: 'Transportista', tipo: 'texto', recortar: 24 },
        { campo: 'envios',             titulo: 'Envíos',        tipo: 'numero' },
        { campo: 'costo_real',         titulo: 'Costo real',    tipo: 'moneda', monto: true },
        { campo: 'costo_promedio',     titulo: 'Costo medio',   tipo: 'moneda', monto: true },
        { campo: 'cobrado_al_cliente', titulo: 'Cobrado',       tipo: 'moneda', monto: true },
        { campo: 'diferencia',         titulo: 'Diferencia',    tipo: 'moneda', monto: true },
        { campo: 'sin_costo',          titulo: 'Sin tarifa',    tipo: 'numero' }
      ]
    }
  ]
};
