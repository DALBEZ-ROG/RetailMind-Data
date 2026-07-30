import { ColorChip, DefinicionDepartamento } from '../../../../core/models/informe.model';

/**
 * INFORMES TÁCTICOS DE VENTAS — los seis objetivos SIMPLES del catálogo
 * (`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §3).
 *
 * Este archivo es TODO lo que hay que escribir para la pantalla: la
 * `InformesDepartamentoComponent` genérica pinta los filtros, la tabla, el
 * resumen y la paginación a partir de estas declaraciones. Para otro
 * departamento se copia el molde y se cambian las declaraciones.
 *
 * Los COMPUESTOS de Ventas (tendencias, top de productos por período, ticket
 * promedio) NO están aquí: son de la fase ETL → ClickHouse.
 */

/** Color de la píldora de estado del pedido, por etapa del ciclo. */
function colorEstadoPedido(fila: Record<string, any>): ColorChip {
  switch (fila['estado_codigo']) {
    case 'entregado':                        return 'ok';
    case 'cancelado':
    case 'no_entregado':
    case 'devuelto':                         return 'error';
    case 'pendiente':
    case 'confirmado':                       return 'warn';
    default:                                 return 'info';   // en curso
  }
}

const CANAL: Record<string, string> = {
  web:      'Tienda en línea',
  tienda:   'Mostrador',
  telefono: 'Teléfono'
};

const MOTIVO_MODERACION: Record<string, string> = {
  pendiente_aprobacion: 'Pendiente de aprobación',
  sin_respuesta:        'Publicada sin respuesta'
};

export const INFORMES_VENTAS: DefinicionDepartamento = {
  departamento: 'ventas',
  titulo: 'Informes de Ventas',
  descripcion: 'Dirección y control de la cartera, el equipo comercial, la voz del cliente y '
             + 'la composición de la venta por canal',
  icono: 'insights',
  informes: [

    // ── OTD-VEN-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-VEN-01',
      endpoint: 'cartera-pedidos',
      titulo: 'Cartera de pedidos por estado',
      descripcion: 'Todos los pedidos y en qué paso del proceso está cada uno hoy.',
      icono: 'receipt_long',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR'],
      vacio: 'Ningún pedido coincide con los filtros elegidos.',
      filtros: [
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [
          { valor: '',               etiqueta: 'Todos los estados' },
          { valor: 'pendiente',      etiqueta: 'Pendiente' },
          { valor: 'confirmado',     etiqueta: 'Confirmado' },
          { valor: 'pagado',         etiqueta: 'Pagado' },
          { valor: 'facturado',      etiqueta: 'Facturado' },
          { valor: 'en_preparacion', etiqueta: 'En preparación' },
          { valor: 'preparado',      etiqueta: 'Preparado' },
          { valor: 'despachado',     etiqueta: 'Despachado' },
          { valor: 'entregado',      etiqueta: 'Entregado' },
          { valor: 'cancelado',      etiqueta: 'Cancelado' },
          { valor: 'devuelto',       etiqueta: 'Devuelto' },
          { valor: 'no_entregado',   etiqueta: 'No entregado' }
        ] },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] },
        { param: 'desde',  etiqueta: 'Desde',  tipo: 'fecha' },
        { param: 'hasta',  etiqueta: 'Hasta',  tipo: 'fecha' },
        { param: 'buscar', etiqueta: 'Nº de pedido o cliente', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'numero',       titulo: 'Nº pedido',  tipo: 'texto' },
        { campo: 'fecha_pedido', titulo: 'Fecha',      tipo: 'fecha' },
        { campo: 'estado',       titulo: 'Estado',     tipo: 'chip', color: colorEstadoPedido },
        { campo: 'canal',        titulo: 'Canal',      tipo: 'texto',
          etiqueta: v => CANAL[v] || v },
        { campo: 'cliente',      titulo: 'Cliente',    tipo: 'texto', recortar: 28 },
        { campo: 'vendedor',     titulo: 'Vendedor',   tipo: 'texto', recortar: 22 },
        { campo: 'monto_descuento', titulo: 'Descuento', tipo: 'moneda', monto: true },
        { campo: 'total',        titulo: 'Total',      tipo: 'moneda', monto: true }
      ]
    },

    // ── OTD-VEN-02 ────────────────────────────────────────────────────
    {
      id: 'OTD-VEN-02',
      endpoint: 'por-vendedor',
      titulo: 'Ventas por vendedor',
      descripcion: 'Pedidos y monto de cada vendedor en el período, para evaluar el '
                 + 'cumplimiento individual. Un vendedor solo ve lo suyo.',
      icono: 'badge',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR'],
      sinPaginar: true,
      vacio: 'No hay ventas registradas en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'vendedor',          titulo: 'Vendedor',        tipo: 'texto' },
        { campo: 'pedidos',           titulo: 'Pedidos',         tipo: 'numero' },
        { campo: 'monto_total',       titulo: 'Monto vendido',   tipo: 'moneda', monto: true },
        { campo: 'ticket_promedio',   titulo: 'Ticket promedio', tipo: 'moneda', monto: true },
        { campo: 'participacion_pct', titulo: 'Participación',   tipo: 'porcentaje' },
        { campo: 'cancelados',        titulo: 'Cancelados',      tipo: 'numero' },
        { campo: 'ultima_venta',      titulo: 'Última venta',    tipo: 'fecha' }
      ]
    },

    // ── OTD-VEN-08 ────────────────────────────────────────────────────
    {
      id: 'OTD-VEN-08',
      endpoint: 'carritos-abandonados',
      titulo: 'Carritos abandonados',
      descripcion: 'Carritos que el cliente dejó a medias sin llegar a pagar, con su '
                 + 'antigüedad y contenido.',
      icono: 'remove_shopping_cart',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR'],
      vacio: 'No hay carritos a medias con esa antigüedad.',
      filtros: [
        { param: 'estado', etiqueta: 'Situación', tipo: 'select', valorInicial: 'abandonado',
          opciones: [
            { valor: 'abandonado', etiqueta: 'Abandonados' },
            { valor: 'activo',     etiqueta: 'Activos sin pagar' },
            { valor: 'ambos',      etiqueta: 'Ambos' }
          ] },
        { param: 'diasMinimos', etiqueta: 'Antigüedad mínima (días)', tipo: 'numero',
          valorInicial: '0' }
      ],
      columnas: [
        { campo: 'estado',           titulo: 'Situación', tipo: 'chip',
          color: f => f['estado'] === 'abandonado' ? 'error' : 'warn' },
        { campo: 'dias_inactivo',    titulo: 'Inactivo',  tipo: 'dias' },
        { campo: 'ultima_actividad', titulo: 'Última actividad', tipo: 'fecha' },
        { campo: 'cliente',          titulo: 'Cliente',   tipo: 'texto', recortar: 26 },
        { campo: 'cliente_email',    titulo: 'Correo',    tipo: 'texto', recortar: 26 },
        { campo: 'lineas',           titulo: 'Líneas',    tipo: 'numero' },
        { campo: 'unidades',         titulo: 'Unidades',  tipo: 'numero' },
        { campo: 'contenido',        titulo: 'Contenido', tipo: 'texto', recortar: 46 },
        { campo: 'valor',            titulo: 'Valor',     tipo: 'moneda', monto: true }
      ]
    },

    // ── OTD-VEN-10 ────────────────────────────────────────────────────
    {
      id: 'OTD-VEN-10',
      endpoint: 'moderacion',
      titulo: 'Cola de moderación',
      descripcion: 'Reseñas en espera de aprobación y preguntas de producto sin atender. '
                 + 'Solo moderadores (Administración y Gerencia).',
      icono: 'rate_review',
      roles: ['ADMIN', 'GERENTE'],
      vacio: 'La cola de moderación está al día: nada en espera con ese filtro.',
      filtros: [
        { param: 'tipo', etiqueta: 'Tipo', tipo: 'select', valorInicial: 'ambos', opciones: [
          { valor: 'ambos',    etiqueta: 'Reseñas y preguntas' },
          { valor: 'resena',   etiqueta: 'Solo reseñas' },
          { valor: 'pregunta', etiqueta: 'Solo preguntas' }
        ] },
        { param: 'diasMinimos', etiqueta: 'En espera desde (días)', tipo: 'numero',
          valorInicial: '0' }
      ],
      columnas: [
        { campo: 'tipo',        titulo: 'Tipo', tipo: 'chip',
          color: f => f['tipo'] === 'resena' ? 'info' : 'neutral',
          etiqueta: v => v === 'resena' ? 'Reseña' : 'Pregunta' },
        { campo: 'motivo',      titulo: 'Motivo', tipo: 'texto',
          etiqueta: v => MOTIVO_MODERACION[v] || v },
        { campo: 'dias_espera', titulo: 'En espera', tipo: 'dias' },
        { campo: 'fecha_creacion', titulo: 'Recibido', tipo: 'fecha' },
        { campo: 'producto',    titulo: 'Producto', tipo: 'texto', recortar: 26 },
        { campo: 'cliente',     titulo: 'Cliente',  tipo: 'texto', recortar: 22 },
        { campo: 'calificacion', titulo: 'Calificación', tipo: 'estrellas' },
        { campo: 'compra_verificada', titulo: 'Compra verificada', tipo: 'booleano' },
        { campo: 'detalle',     titulo: 'Contenido', tipo: 'texto', recortar: 52 }
      ]
    },

    // ── OTD-VEN-15 ────────────────────────────────────────────────────
    {
      id: 'OTD-VEN-15',
      endpoint: 'avance-meta',
      titulo: 'Venta contra la meta del mes',
      descripcion: 'Meta del período vigente contra la venta real acumulada, con el '
                 + 'porcentaje de avance.',
      icono: 'flag',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR'],
      sinPaginar: true,
      // ÚNICO informe con barra de avance: aquí el porcentaje SÍ es el avance
      // sobre una meta fijada. En el resto un porcentaje es solo un ratio.
      barraAvance: true,
      vacio: 'No hay meta de ventas fijada para ese período.',
      filtros: [
        { param: 'periodo', etiqueta: 'Período', tipo: 'periodo' }
      ],
      columnas: [
        { campo: 'departamento',      titulo: 'Departamento',  tipo: 'texto' },
        { campo: 'monto_meta',        titulo: 'Meta',          tipo: 'moneda', monto: true },
        { campo: 'venta_real',        titulo: 'Venta real',    tipo: 'moneda', monto: true },
        { campo: 'avance_pct',        titulo: 'Avance',        tipo: 'porcentaje' },
        { campo: 'faltante',          titulo: 'Falta',         tipo: 'moneda', monto: true },
        { campo: 'facturas',          titulo: 'Facturas',      tipo: 'numero' },
        { campo: 'dias_transcurridos', titulo: 'Días corridos', tipo: 'numero' },
        { campo: 'dias_del_periodo',  titulo: 'Días del mes',  tipo: 'numero' },
        { campo: 'fijada_por',        titulo: 'Fijada por',    tipo: 'texto', recortar: 22 }
      ]
    },

    // ── OTD-VEN-16 ────────────────────────────────────────────────────
    // Sostiene el objetivo estratégico OE-06. Es la FOTO del período; la
    // evolución mensual de esta misma participación es OTD-VEN-13, COMPUESTO,
    // y se resuelve en ClickHouse por el ETL.
    {
      id: 'OTD-VEN-16',
      endpoint: 'participacion-canal',
      titulo: 'Participación de la venta por canal',
      descripcion: 'Cuánto pone cada canal en la venta del período: pedidos, monto, ticket '
                 + 'promedio y porcentaje de participación. No separa B2B de B2C: el segmento '
                 + 'del comprador no está capturado en la base (la columna «Clientes negocio» '
                 + 'mide esa ausencia).',
      icono: 'donut_large',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay pedidos registrados en el período elegido.',
      filtros: [
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'canal',            titulo: 'Canal',   tipo: 'chip',
          color: f => f['canal'] === 'web' ? 'info' : 'neutral',
          etiqueta: v => CANAL[v] || v },
        { campo: 'pedidos',          titulo: 'Pedidos', tipo: 'numero' },
        { campo: 'monto_vendido',    titulo: 'Monto vendido',   tipo: 'moneda', monto: true },
        { campo: 'ticket_promedio',  titulo: 'Ticket promedio', tipo: 'moneda', monto: true },
        { campo: 'participacion_pedidos_pct', titulo: '% de pedidos', tipo: 'porcentaje' },
        { campo: 'participacion_monto_pct',   titulo: '% del monto',  tipo: 'porcentaje' },
        { campo: 'clientes',         titulo: 'Clientes', tipo: 'numero' },
        { campo: 'clientes_negocio', titulo: 'Clientes negocio (B2B)', tipo: 'numero' },
        { campo: 'cancelados',       titulo: 'Cancelados',   tipo: 'numero' },
        { campo: 'ultima_venta',     titulo: 'Última venta', tipo: 'fecha' }
      ]
    }
  ]
};
