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
 * Desde 2026-07-30 convive aquí el PRIMER informe COMPUESTO —OTD-VEN-06—, que
 * se sirve desde ClickHouse (`retailmind_dwh`) en vez de PostgreSQL. Para la
 * pantalla no cambia nada: mismo sobre, mismas columnas declarativas, mismos
 * filtros. Lo único que añade es la marca de agua «Datos al …», que el sobre
 * trae en `datosAl` y la pantalla genérica pinta sola.
 *
 * Con la Fase 4 se cierran los compuestos de posventa del departamento:
 * OTD-VEN-11 (calificación de los productos, el ÚNICO compuesto de Ventas sin
 * dinero) y OTD-VEN-14 (devoluciones y su peso sobre la venta, que sí lo lleva
 * y por eso deja fuera al vendedor además de a Bodega y Despacho).
 *
 * VEN-14 trae una `salvedad` que hay que leer antes que la tabla: el porcentaje
 * cambia según contra qué mes se divida —el de la devolución o el del pedido— y
 * el filtro «Base del porcentaje» decide cuál. Son dos preguntas distintas y
 * las dos son defendibles; lo que no se puede es publicar una sin decir cuál.
 */

/** Las 10 categorías del catálogo (`dim_producto`). */
const CATEGORIAS = ['Abarrotes', 'Accesorios', 'Belleza', 'Calzado', 'Deportes',
  'Electrónica', 'Hogar', 'Ropa', 'Ropa Hombre', 'Ropa Mujer'];

// ── OTD-VEN-19 · alerta de abandono (fase E3 del nivel estratégico) ────────

/**
 * Color del nivel de alerta. `sin_muestra` NO es «normal» y no puede pintarse
 * como tal: es «el modelo no puede opinar», y son precisamente los clientes con
 * más silencio los que caen ahí —su silencio es lo que los dejó sin pedidos en
 * la ventana—. Va en gris y con su etiqueta explícita.
 */
function colorNivelAlerta(fila: Record<string, any>): ColorChip {
  switch (fila['nivel_alerta']) {
    case 'critica':     return 'error';
    case 'atencion':    return 'warn';
    case 'normal':      return 'ok';
    default:            return 'neutral';
  }
}

function etiquetaNivelAlerta(valor: any): string {
  switch (valor) {
    case 'critica':     return 'crítica';
    case 'atencion':    return 'atención';
    case 'normal':      return 'normal';
    case 'sin_muestra': return 'sin muestra';
    default:            return String(valor);
  }
}

/**
 * El silencio se lee EN INTERVALOS PROPIOS y no en días: 3 veces el ritmo
 * habitual es el umbral de α = 0,05 (e⁻³ ≈ 5 %), sea el ritmo semanal o
 * trimestral.
 */
function colorSilencio(fila: Record<string, any>): ColorChip {
  if (Number(fila['pedidos_ventana']) < 3) { return 'neutral'; }
  const veces = Number(fila['silencio_en_intervalos']);
  if (veces >= 3) { return 'error'; }
  return veces >= 2.3 ? 'warn' : 'ok';
}

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

/** Opciones del filtro de canal — se repiten en cinco informes. */
const FILTRO_CANAL = [
  { valor: '',         etiqueta: 'Todos los canales' },
  { valor: 'web',      etiqueta: 'Tienda en línea' },
  { valor: 'tienda',   etiqueta: 'Mostrador' },
  { valor: 'telefono', etiqueta: 'Teléfono' }
];

/** Color por forma de cobro (OTD-VEN-09). */
const COLOR_FORMA: Record<string, ColorChip> = {
  efectivo:      'ok',
  tarjeta:       'info',
  transferencia: 'neutral'
};

/**
 * Los CINCO motivos de rechazo, ya normalizados por el ETL, más el `otro` de
 * la regla de escape del pipeline: si algún día la pasarela devuelve un motivo
 * no previsto, el ETL lo carga como 'otro' en vez de descartarlo, y desde aquí
 * se puede aislar sin abrir el log del pipeline.
 */
const MOTIVO_RECHAZO: Record<string, string> = {
  fondos_insuficientes: 'Fondos insuficientes',
  datos_incorrectos:    'Datos de la tarjeta incorrectos',
  tarjeta_rechazada:    'Tarjeta rechazada por el emisor',
  error_pasarela:       'Error de la pasarela',
  limite_excedido:      'Límite de la tarjeta excedido',
  otro:                 'Otro motivo (no previsto)'
};

export const INFORMES_VENTAS: DefinicionDepartamento = {
  departamento: 'ventas',
  titulo: 'Informes de Ventas',
  descripcion: 'Dirección y control de la cartera, el equipo comercial, la voz del cliente y '
             + 'la composición de la venta por canal',
  icono: 'insights',

  // ── PILOTO DE PRESENTACIÓN (2026-08-15) ────────────────────────────────
  // Ventas es el departamento con MÁS informes (17: 6 simples + 11
  // compuestos) y por eso el que peor sufría la parrilla horizontal — cinco
  // filas de tarjetas antes de llegar a los filtros. Las dos banderas se
  // declaran SOLO aquí: los otros cinco departamentos conservan su pintado
  // actual hasta que el piloto se dé por bueno.
  selectorVertical: true,
  kpiVidrio: true,

  informes: [

    // ── OTD-VEN-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-VEN-01',
      endpoint: 'cartera-pedidos',
      fuente: 'simple',
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
      fuente: 'simple',
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
      fuente: 'simple',
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
      fuente: 'simple',
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
      fuente: 'simple',
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
    // Sostiene el objetivo estratégico OE-06 (Consolidación de la Experiencia
    // Omnicanal). Es la FOTO del período; la evolución mensual de esta misma
    // participación es OTD-VEN-13, COMPUESTO, y se resuelve en ClickHouse por
    // el ETL.
    // Mide el MEDIO de entrada del pedido, nunca el tipo de cliente: la
    // segmentación B2B/B2C fue DESCARTADA el 2026-07-30 (veredicto (c)
    // población homogénea, docs/estrategico/DIAGNOSTICO_SEGMENTO_CLIENTE.md).
    // Por eso «clientes_negocio» se rotula «Clientes con segmento registrado»:
    // mide la ausencia de segmentación registrada, no un segmento por llenar.
    {
      id: 'OTD-VEN-16',
      endpoint: 'participacion-canal',
      fuente: 'simple',
      titulo: 'Participación de la venta por canal',
      descripcion: 'Cuánto pone cada canal de entrada —tienda en línea, mostrador y teléfono— '
                 + 'en la venta del período: pedidos, monto, ticket promedio y porcentaje de '
                 + 'participación. Mide el MEDIO por el que entró el pedido, no el tipo de '
                 + 'cliente: no existe segmentación de clientes registrada en el sistema (la '
                 + 'columna «Clientes con segmento registrado» mide esa ausencia).',
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
        { campo: 'clientes_negocio', titulo: 'Clientes con segmento registrado', tipo: 'numero' },
        { campo: 'cancelados',       titulo: 'Cancelados',   tipo: 'numero' },
        { campo: 'ultima_venta',     titulo: 'Última venta', tipo: 'fecha' }
      ]
    },

    // ── OTD-VEN-06 ── COMPUESTO: la fuente es ClickHouse ──────────────
    // Primer informe del nivel compuesto y piloto del pipeline ETL. La
    // pantalla no sabe —ni necesita saber— que el dato viene del data
    // warehouse: el sobre es el mismo. Lo único propio es la marca de agua
    // «Datos al …», que el backend adjunta en `datosAl` porque un informe
    // analítico puede tener hasta 24 h de desfase y callarlo sería mentir
    // por omisión (§8.4 del diseño del pipeline).
    // El corte de permisos lo hace ÍNTEGRAMENTE SecurityConfig: ClickHouse
    // no tiene RLS ni GRANT por columna que respalden el corte financiero.
    {
      id: 'OTD-VEN-06',
      endpoint: 'evolucion-mensual',
      fuente: 'compuesto',
      titulo: 'Evolución de la venta por mes y categoría',
      descripcion: 'Cómo evoluciona la venta mes a mes, con el desglose por categoría de '
                 + 'producto: unidades, venta neta, margen, peso de cada categoría en su mes '
                 + 'y variación contra el mes anterior. Excluye los pedidos cancelados. El '
                 + 'rango se ajusta a meses completos, y el margen se calcula con el costo '
                 + 'vigente del producto (el sistema no guarda costo histórico).',
      icono: 'stacked_line_chart',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'No hay ventas registradas en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'select', opciones: [
          { valor: '', etiqueta: 'Todas las categorías' },
          ...CATEGORIAS.map(c => ({ valor: c, etiqueta: c }))
        ] },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] }
      ],
      columnas: [
        // `mes` llega como texto 'AAAA-MM' ya formateado desde dim_fecha: una
        // fecha-día cruda la mostraría el formateador un día antes.
        { campo: 'mes',               titulo: 'Mes',        tipo: 'texto' },
        { campo: 'categoria',         titulo: 'Categoría',  tipo: 'chip',
          color: f => f['categoria'] === '(sin ventas)' ? 'warn' : 'neutral' },
        { campo: 'pedidos',           titulo: 'Pedidos',    tipo: 'numero' },
        { campo: 'unidades',          titulo: 'Unidades',   tipo: 'numero' },
        { campo: 'venta_bruta',       titulo: 'Venta bruta', tipo: 'moneda', monto: true },
        { campo: 'descuentos',        titulo: 'Descuentos', tipo: 'moneda', monto: true },
        { campo: 'venta_neta',        titulo: 'Venta neta', tipo: 'moneda', monto: true },
        { campo: 'costo',             titulo: 'Costo',      tipo: 'moneda', monto: true },
        { campo: 'margen',            titulo: 'Margen',     tipo: 'moneda', monto: true },
        { campo: 'margen_pct',        titulo: '% margen',   tipo: 'porcentaje' },
        { campo: 'participacion_pct', titulo: '% del mes',  tipo: 'porcentaje' },
        { campo: 'variacion_pct',     titulo: 'vs. mes anterior', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-VEN-05 ── COMPUESTO ───────────────────────────────────────
    {
      id: 'OTD-VEN-05',
      endpoint: 'clientes',
      fuente: 'compuesto',
      titulo: 'Compra por cliente',
      descripcion: 'El negocio visto desde el cliente: cuánto gastó cada uno, en cuántos '
                 + 'pedidos y cuándo compró por última vez. El dinero excluye los pedidos '
                 + 'cancelados, que se cuentan aparte. La columna «Segmento» muestra '
                 + '«sin_segmentar» en todos los clientes porque el sistema no registra '
                 + 'segmentación B2B/B2C ni es derivable de la conducta de compra.',
      icono: 'groups',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR', 'ANALISTA'],
      vacio: 'Ningún cliente compró en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: FILTRO_CANAL },
        { param: 'buscar', etiqueta: 'Cliente o correo', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'cliente',           titulo: 'Cliente',   tipo: 'texto', recortar: 26 },
        { campo: 'email',             titulo: 'Correo',    tipo: 'texto', recortar: 26 },
        { campo: 'ciudad',            titulo: 'Ciudad',    tipo: 'texto' },
        { campo: 'segmento',          titulo: 'Segmento',  tipo: 'chip', color: () => 'neutral',
          etiqueta: v => v === 'sin_segmentar' ? 'Sin segmentar' : v },
        { campo: 'pedidos',           titulo: 'Pedidos',   tipo: 'numero' },
        { campo: 'unidades',          titulo: 'Unidades',  tipo: 'numero' },
        { campo: 'monto_total',       titulo: 'Total comprado', tipo: 'moneda', monto: true },
        { campo: 'ticket_promedio',   titulo: 'Ticket medio',   tipo: 'moneda', monto: true },
        { campo: 'descuento_cupon',   titulo: 'Cupones',   tipo: 'moneda', monto: true },
        { campo: 'participacion_pct', titulo: '% del total', tipo: 'porcentaje' },
        { campo: 'cancelados',        titulo: 'Cancelados', tipo: 'numero' },
        // Ya viene formateada como texto desde el backend: una fecha serializada
        // la interpreta el formateador como UTC y la muestra un día antes.
        { campo: 'primera_compra',    titulo: 'Primera compra', tipo: 'texto' },
        { campo: 'ultima_compra',     titulo: 'Última compra',  tipo: 'texto' },
        { campo: 'dias_sin_comprar',  titulo: 'Sin comprar',    tipo: 'dias' }
      ]
    },

    // ── OTD-VEN-07 ── COMPUESTO ───────────────────────────────────────
    {
      id: 'OTD-VEN-07',
      endpoint: 'ticket-promedio',
      fuente: 'compuesto',
      titulo: 'Valor promedio del pedido por canal',
      descripcion: 'Cuánto vale un pedido típico, mes a mes y por canal de entrada. Junto al '
                 + 'promedio va la MEDIANA: el promedio lo mueve un pedido grande y la mediana '
                 + 'no, así que las dos juntas dicen si el mes subió por precio general o por '
                 + 'un pedido excepcional. Excluye los pedidos cancelados y ajusta el rango a '
                 + 'meses completos.',
      icono: 'shopping_bag',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'No hay pedidos en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: FILTRO_CANAL }
      ],
      columnas: [
        { campo: 'mes',                 titulo: 'Mes',     tipo: 'texto' },
        { campo: 'canal',               titulo: 'Canal',   tipo: 'chip',
          color: f => f['canal'] === 'web' ? 'info' : 'neutral',
          etiqueta: v => CANAL[v] || v },
        { campo: 'pedidos',             titulo: 'Pedidos', tipo: 'numero' },
        { campo: 'monto_total',         titulo: 'Monto',   tipo: 'moneda', monto: true },
        { campo: 'ticket_promedio',     titulo: 'Ticket medio',   tipo: 'moneda', monto: true },
        { campo: 'ticket_mediana',      titulo: 'Ticket mediano', tipo: 'moneda', monto: true },
        { campo: 'ticket_minimo',       titulo: 'Menor',   tipo: 'moneda', monto: true },
        { campo: 'ticket_maximo',       titulo: 'Mayor',   tipo: 'moneda', monto: true },
        { campo: 'unidades_por_pedido', titulo: 'Uds/pedido',    tipo: 'numero' },
        { campo: 'lineas_por_pedido',   titulo: 'Líneas/pedido', tipo: 'numero' },
        { campo: 'variacion_pct',       titulo: 'vs. mes anterior', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-VEN-13 ── COMPUESTO ───────────────────────────────────────
    // Par temporal de OTD-VEN-16: misma pregunta, pero como serie de 19 meses
    // en vez de foto del período. Mide el MEDIO de entrada del pedido, jamás
    // el tipo de cliente (ver la nota de VEN-16 más arriba).
    {
      id: 'OTD-VEN-13',
      endpoint: 'evolucion-canal',
      fuente: 'compuesto',
      titulo: 'Evolución de la participación por canal',
      descripcion: 'Cómo cambia mes a mes el peso de cada canal —tienda en línea, mostrador y '
                 + 'teléfono— en la venta. La cuota se mide sobre PEDIDOS y sobre MONTO, y casi '
                 + 'nunca coinciden: un canal puede poner más pedidos y menos dinero porque su '
                 + 'ticket es menor. Mide el medio de entrada del pedido, no el tipo de cliente.',
      icono: 'timeline',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR', 'ANALISTA'],
      vacio: 'No hay pedidos en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: FILTRO_CANAL }
      ],
      columnas: [
        { campo: 'mes',      titulo: 'Mes',    tipo: 'texto' },
        { campo: 'canal',    titulo: 'Canal',  tipo: 'chip',
          color: f => f['canal'] === 'web' ? 'info' : 'neutral',
          etiqueta: v => CANAL[v] || v },
        { campo: 'pedidos',  titulo: 'Pedidos',  tipo: 'numero' },
        { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
        { campo: 'clientes', titulo: 'Clientes', tipo: 'numero' },
        { campo: 'monto',    titulo: 'Monto',    tipo: 'moneda', monto: true },
        { campo: 'ticket_promedio', titulo: 'Ticket medio', tipo: 'moneda', monto: true },
        { campo: 'participacion_pedidos_pct', titulo: '% de pedidos', tipo: 'porcentaje' },
        { campo: 'participacion_monto_pct',   titulo: '% del monto',  tipo: 'porcentaje' },
        { campo: 'variacion_pct', titulo: 'vs. mes anterior', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-VEN-09 ── COMPUESTO ───────────────────────────────────────
    {
      id: 'OTD-VEN-09',
      endpoint: 'formas-cobro',
      fuente: 'compuesto',
      titulo: 'Mezcla de formas de cobro',
      descripcion: 'Con qué se cobra —efectivo, tarjeta o transferencia— y cómo cambia esa '
                 + 'mezcla mes a mes. Cuenta COBROS y no pedidos, porque un pedido puede '
                 + 'cobrarse en dos abonos con métodos distintos. Solo cobros efectivos: los '
                 + 'intentos rechazados tienen su propio informe.',
      icono: 'account_balance_wallet',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'No hay cobros registrados en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'tipo', etiqueta: 'Forma de cobro', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todas las formas' },
          { valor: 'efectivo',      etiqueta: 'Efectivo' },
          { valor: 'tarjeta',       etiqueta: 'Tarjeta' },
          { valor: 'transferencia', etiqueta: 'Transferencia' }
        ] }
      ],
      columnas: [
        { campo: 'mes',         titulo: 'Mes',   tipo: 'texto' },
        { campo: 'forma_cobro', titulo: 'Forma', tipo: 'chip',
          color: f => COLOR_FORMA[f['forma_cobro']] || 'neutral',
          etiqueta: v => String(v).charAt(0).toUpperCase() + String(v).slice(1) },
        { campo: 'metodo',      titulo: 'Método', tipo: 'texto', recortar: 26 },
        { campo: 'cobros',      titulo: 'Cobros', tipo: 'numero' },
        { campo: 'monto',       titulo: 'Monto',  tipo: 'moneda', monto: true },
        { campo: 'cobro_promedio',    titulo: 'Cobro medio', tipo: 'moneda', monto: true },
        { campo: 'participacion_pct', titulo: '% del mes',   tipo: 'porcentaje' },
        { campo: 'variacion_pct',     titulo: 'vs. mes anterior', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-VEN-12 ── COMPUESTO ───────────────────────────────────────
    // Los motivos llegan NORMALIZADOS por el ETL: en PostgreSQL conviven SEIS
    // valores donde el negocio tiene CINCO (el código `tarjeta_rechazada` y su
    // texto libre «Tarjeta rechazada por el emisor»). Sin esa normalización la
    // tabla mostraría dos filas del mismo rechazo.
    {
      id: 'OTD-VEN-12',
      endpoint: 'cobros-fallidos',
      fuente: 'compuesto',
      titulo: 'Cobros rechazados y su motivo',
      descripcion: 'Cuántos intentos de cobro se rechazan y por qué, mes a mes: dónde se está '
                 + 'perdiendo venta en el paso del pago. El monto es lo que se INTENTÓ cobrar '
                 + 'en el intento fallido, no venta perdida definitivamente — parte pudo '
                 + 'reintentarse con éxito, y el sistema no encadena intento y reintento. La '
                 + 'fecha de la fila es la del intento, porque un cobro rechazado nunca llega '
                 + 'a tener fecha de liquidación.',
      icono: 'credit_card_off',
      roles: ['ADMIN', 'GERENTE'],
      vacio: 'No hubo cobros rechazados en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'motivo', etiqueta: 'Motivo', tipo: 'select', opciones: [
          { valor: '', etiqueta: 'Todos los motivos' },
          ...Object.entries(MOTIVO_RECHAZO).map(([valor, etiqueta]) => ({ valor, etiqueta }))
        ] }
      ],
      columnas: [
        { campo: 'mes',      titulo: 'Mes',    tipo: 'texto' },
        { campo: 'motivo',   titulo: 'Motivo', tipo: 'chip', color: () => 'error',
          etiqueta: v => MOTIVO_RECHAZO[v] || v },
        { campo: 'intentos', titulo: 'Intentos', tipo: 'numero' },
        { campo: 'monto_intentado',  titulo: 'Monto no cobrado', tipo: 'moneda', monto: true },
        { campo: 'intento_promedio', titulo: 'Intento medio',    tipo: 'moneda', monto: true },
        { campo: 'metodo_predominante', titulo: 'Método', tipo: 'texto', recortar: 26 },
        { campo: 'participacion_mes_pct', titulo: '% del mes', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-VEN-11 ── COMPUESTO: fact_resena (Fase 4) ─────────────────
    // El ÚNICO compuesto de Ventas sin dinero: es una escala de 1 a 5.
    {
      id: 'OTD-VEN-11',
      endpoint: 'resenas',
      fuente: 'compuesto',
      titulo: 'Calificación de los productos',
      descripcion: 'Lo que los clientes puntúan, y cómo se mueve. El orden por defecto es '
                 + 'por VOLUMEN de reseñas y no por nota: un producto con un solo cinco '
                 + 'no es el mejor del catálogo, así que la columna «Reseñas» es el '
                 + 'denominador y va antes que la media. Se incluyen las tres situaciones '
                 + 'de moderación — restringir a las aprobadas mediría la moderación y no '
                 + 'la opinión.',
      icono: 'star_rate',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR', 'ANALISTA'],
      vacio: 'No hay reseñas que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Producto' },
          { valor: 'mes',       etiqueta: 'Evolución mensual' },
          { valor: 'categoria', etiqueta: 'Categoría' },
          { valor: 'marca',     etiqueta: 'Marca' }
        ] },
        { param: 'estado', etiqueta: 'Moderación', tipo: 'select', opciones: [
          { valor: '',           etiqueta: 'Todas' },
          { valor: 'aprobada',   etiqueta: 'Aprobadas (visibles)' },
          { valor: 'pendiente',  etiqueta: 'Pendientes de moderar' },
          { valor: 'rechazada',  etiqueta: 'Rechazadas' }
        ] },
        { param: 'desde', etiqueta: 'Escrita desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Escrita hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'buscar', etiqueta: 'Producto', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'etiqueta',      titulo: 'Producto / período', tipo: 'texto', recortar: 34 },
        { campo: 'resenas',       titulo: 'Reseñas',       tipo: 'numero' },
        { campo: 'media',         titulo: 'Calificación',  tipo: 'estrellas' },
        { campo: 'pct_positivas', titulo: '% positivas',   tipo: 'porcentaje' },
        { campo: 'positivas',     titulo: '4-5',           tipo: 'numero' },
        { campo: 'neutras',       titulo: '3',             tipo: 'numero' },
        { campo: 'negativas',     titulo: '1-2',           tipo: 'chip',
          color: f => Number(f['negativas']) > 0 ? 'warn' : 'neutral' },
        { campo: 'verificadas',   titulo: 'Con compra',    tipo: 'numero' },
        { campo: 'pendientes',    titulo: 'Sin moderar',   tipo: 'chip',
          color: f => Number(f['pendientes']) > 0 ? 'info' : 'neutral' },
        { campo: 'clientes',      titulo: 'Clientes',      tipo: 'numero' },
        { campo: 'categoria',     titulo: 'Categoría',     tipo: 'texto', recortar: 18 },
        { campo: 'ultima_resena', titulo: 'Última',        tipo: 'fecha' }
      ]
    },

    // ── OTD-VEN-14 ── COMPUESTO: fact_devolucion (Fase 4) ─────────────
    // DINERO: sin Bodega ni Despacho, y tampoco el vendedor.
    {
      id: 'OTD-VEN-14',
      endpoint: 'devoluciones',
      fuente: 'compuesto',
      titulo: 'Devoluciones y su peso sobre la venta',
      descripcion: 'Cuánto vuelve cada mes y qué porcentaje de la venta representa. '
                 + 'ATENCIÓN a la base del porcentaje: por defecto divide lo devuelto EN '
                 + 'el mes entre lo vendido EN ese mes, que NO son la misma población — '
                 + 'una devolución de julio puede venir de un pedido de mayo. Cambia la '
                 + 'base a «mes del pedido» para leer la calidad de cada cohorte de venta.',
      icono: 'assignment_return',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay devoluciones en el período elegido.',
      filtros: [
        { param: 'base', etiqueta: 'Base del porcentaje', tipo: 'select', opciones: [
          { valor: '',           etiqueta: 'Mes de la devolución' },
          { valor: 'pedido',     etiqueta: 'Mes del pedido original' }
        ], ancho: 'ancho' },
        { param: 'estado', etiqueta: 'Paso del ciclo', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todos los pasos' },
          { valor: 'solicitada',    etiqueta: 'Solicitada' },
          { valor: 'en_revision',   etiqueta: 'En revisión' },
          { valor: 'aprobada',      etiqueta: 'Aprobada' },
          { valor: 'rechazada',     etiqueta: 'Rechazada' },
          { valor: 'en_transito',   etiqueta: 'En camino de vuelta' },
          { valor: 'recibida',      etiqueta: 'Recibida' },
          { valor: 'inspeccionada', etiqueta: 'Inspeccionada' },
          { valor: 'reembolsada',   etiqueta: 'Reembolsada' },
          { valor: 'cerrada',       etiqueta: 'Cerrada' }
        ] },
        { param: 'desde', etiqueta: 'Solicitada desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Solicitada hasta', tipo: 'fecha' },
        { param: 'motivo', etiqueta: 'Motivo', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'periodo',            titulo: 'Mes',             tipo: 'texto' },
        { campo: 'devoluciones',       titulo: 'Devoluciones',    tipo: 'numero' },
        { campo: 'unidades',           titulo: 'Unidades',        tipo: 'numero' },
        { campo: 'monto_devuelto',     titulo: 'Mercancía devuelta', tipo: 'moneda',
          monto: true },
        { campo: 'monto_reembolsado',  titulo: 'Reembolsado',     tipo: 'moneda',
          monto: true },
        { campo: 'reembolsos',         titulo: 'Reembolsos',      tipo: 'numero' },
        { campo: 'venta',              titulo: 'Venta del mes',   tipo: 'moneda',
          monto: true },
        { campo: 'pedidos_vendidos',   titulo: 'Pedidos',         tipo: 'numero' },
        { campo: 'pct_sobre_venta',    titulo: '% sobre la venta', tipo: 'porcentaje' },
        { campo: 'pct_reembolsado',    titulo: '% reembolsado',   tipo: 'porcentaje' },
        { campo: 'pct_pedidos',        titulo: '% de los pedidos', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-VEN-19 ── MODELO: fact_alerta_cliente (fase E3, §5.2) ─────
    // El único informe del departamento servido por un MODELO y no por un
    // hecho. Lleva monto: sin Bodega ni Despacho. El VENDEDOR entra y se
    // recorta a SU cartera.
    {
      id: 'OTD-VEN-19',
      endpoint: 'clientes-en-riesgo',
      fuente: 'compuesto',
      titulo: 'Clientes en riesgo',
      descripcion: 'Clientes cuyo silencio es inusual PARA SU PROPIO RITMO de compra, '
                 + 'ordenados por valor en riesgo. Lee primero las tres tarjetas del '
                 + 'encabezado: son el lift medido del modelo, la muestra sobre la que '
                 + 'se midió y si supera al azar. En esta base NO lo supera de forma '
                 + 'significativa, y eso cambia cómo hay que usar la lista: sirve para '
                 + 'priorizar una llamada, no para dar por perdido a un cliente.',
      icono: 'person_alert',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR'],
      vacio: 'Ningún cliente cruza el umbral de alerta con los filtros elegidos. '
           + 'Prueba con «Todos» para ver la cartera completa y su nivel.',
      filtros: [
        // Arranca en «alerta» a propósito: un informe de alerta que abre con los
        // 69 clientes obliga a buscar la alerta dentro de la lista.
        { param: 'nivel', etiqueta: 'Nivel', tipo: 'select', valorInicial: 'alerta',
          ancho: 'ancho', opciones: [
            { valor: 'alerta',      etiqueta: 'En alerta (crítica + atención)' },
            { valor: 'critica',     etiqueta: 'Solo críticas (P < 5 %)' },
            { valor: 'atencion',    etiqueta: 'Solo atención (5 % ≤ P < 10 %)' },
            { valor: 'normal',      etiqueta: 'Solo normales' },
            { valor: 'sin_muestra', etiqueta: 'Sin muestra para opinar (< 3 pedidos)' },
            { valor: 'todos',       etiqueta: 'Todos los clientes' }
          ] },
        { param: 'buscar', etiqueta: 'Cliente o correo', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'cliente',      titulo: 'Cliente',   tipo: 'texto', recortar: 26 },
        { campo: 'facturacion_12m', titulo: 'Fact. 12m', tipo: 'moneda', monto: true },
        // Regla 3: la lista se ordena por ESTO, no por probabilidad.
        { campo: 'valor_en_riesgo', titulo: 'Valor en riesgo', tipo: 'moneda',
          monto: true },
        // Regla 1: la medida principal es «veces su intervalo propio». Va
        // inmediatamente después del ritmo y de los días, que son lo que la
        // hacen legible — «67 días» no dice nada sin el «cada 9».
        { campo: 'intervalo_medio_dias', titulo: 'Su ritmo', tipo: 'texto',
          etiqueta: (v, f) => Number(f['pedidos_ventana']) < 3
            ? '—' : 'cada ' + Number(v).toLocaleString('es-EC',
                { maximumFractionDigits: 1 }) + ' d' },
        { campo: 'dias_silencio', titulo: 'Silencio', tipo: 'dias' },
        { campo: 'silencio_en_intervalos', titulo: '= veces su ritmo', tipo: 'chip',
          color: colorSilencio,
          etiqueta: (v, f) => Number(f['pedidos_ventana']) < 3
            ? 'sin ritmo' : Number(v).toLocaleString('es-EC',
                { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + '×' },
        { campo: 'prob_pct', titulo: 'Probabilidad', tipo: 'texto',
          etiqueta: (v, f) => Number(f['pedidos_ventana']) < 3
            ? '—' : Number(v).toLocaleString('es-EC',
                { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' %' },
        { campo: 'nivel_alerta', titulo: 'Nivel', tipo: 'chip',
          color: colorNivelAlerta, etiqueta: etiquetaNivelAlerta },
        // Regla 2: el sparkline EN LA FILA.
        { campo: 'compras_por_mes', titulo: 'Compras por mes', tipo: 'sparkline' },
        // La MUESTRA que sostiene el ritmo de esta fila (limitación 5 de
        // §5.2.10). Con menos de 5 pedidos el ritmo es una conjetura, y el
        // color lo dice.
        { campo: 'pedidos_ventana', titulo: 'Pedidos', tipo: 'chip',
          color: f => Number(f['pedidos_ventana']) < 3 ? 'error'
                    : Number(f['pedidos_ventana']) < 5 ? 'warn' : 'ok' },
        { campo: 'ultima_compra', titulo: 'Última compra', tipo: 'texto' },
        { campo: 'percentil_valor', titulo: 'Percentil de valor', tipo: 'porcentaje' },
        // CONTEXTO, no entradas del modelo (§5.2.4). Se muestran para informar
        // el gesto comercial: no es lo mismo llamar a quien se fue en silencio
        // que a quien se fue con un reclamo abierto.
        { campo: 'reclamos_abiertos', titulo: 'Reclamos abiertos', tipo: 'chip',
          color: f => Number(f['reclamos_abiertos']) > 0 ? 'warn' : 'neutral' },
        { campo: 'devoluciones_12m', titulo: 'Devoluciones 12m', tipo: 'chip',
          color: f => Number(f['devoluciones_12m']) > 0 ? 'info' : 'neutral' },
        { campo: 'ciudad',  titulo: 'Ciudad', tipo: 'texto' },
        { campo: 'email',   titulo: 'Correo', tipo: 'texto', recortar: 24 },
        { campo: 'activo',  titulo: 'Activo', tipo: 'chip',
          color: f => Number(f['activo']) ? 'ok' : 'error',
          etiqueta: v => Number(v) ? 'sí' : 'DADO DE BAJA' }
      ]
    },

    // ── OTD-VEN-03 · Producto estrella ───────────────────────────────────
    // El reparto de roles MÁS ANCHO de Ventas, y es el del catálogo: la
    // pregunta es «qué reponer», que es operativa. Por eso este informe NO
    // trae margen ni costo — esa es OTD-GER-10 y el catálogo la reserva a la
    // dirección. Lo garantiza la consulta del backend, que no los selecciona.
    {
      id: 'OTD-VEN-03',
      endpoint: 'top-productos',
      fuente: 'compuesto',
      titulo: 'Los productos que más se venden',
      descripcion: 'El «producto estrella»: el ranking de lo que más sale en el período '
                 + 'elegido, por UNIDADES vendidas. Trae también los pedidos en que aparece, '
                 + 'la venta neta, el precio medio realizado y el peso de cada producto en '
                 + 'las unidades del período. Excluye los pedidos cancelados. Arranca en los '
                 + '10 primeros; se puede pasar de página para ver el resto.',
      icono: 'trending_up',
      roles: ['ADMIN', 'GERENTE', 'VENDEDOR', 'COMPRAS', 'ANALISTA'],
      vacio: 'No hay ventas registradas en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'select', opciones: [
          { valor: '', etiqueta: 'Todas las categorías' },
          ...CATEGORIAS.map(c => ({ valor: c, etiqueta: c }))
        ] },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: FILTRO_CANAL }
      ],
      columnas: [
        { campo: 'producto',          titulo: 'Producto',   tipo: 'texto', recortar: 30 },
        { campo: 'sku',               titulo: 'SKU',        tipo: 'texto' },
        { campo: 'categoria',         titulo: 'Categoría',  tipo: 'texto' },
        { campo: 'marca',             titulo: 'Marca',      tipo: 'texto' },
        { campo: 'unidades',          titulo: 'Unidades',   tipo: 'numero' },
        { campo: 'pedidos',           titulo: 'Pedidos',    tipo: 'numero' },
        { campo: 'venta',             titulo: 'Venta neta', tipo: 'moneda', monto: true },
        { campo: 'precio_medio',      titulo: 'Precio medio', tipo: 'moneda', monto: true },
        { campo: 'participacion_pct', titulo: '% de unidades', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-VEN-04 · Producto hueso ──────────────────────────────────────
    // SIN vendedor: la decisión que sostiene —liquidar o dejar de comprar— es
    // de compras y de dirección. COMPRAS entra AQUÍ y no en el tablero T-2,
    // que responde una pregunta parecida pero ordena por capital retenido y
    // lleva margen. Este informe no trae ni una columna de dinero.
    //
    // El filtro «Criterio» no es un matiz: «sin venta nunca» y «sin venta en
    // el período» son dos listas y dos decisiones distintas, y el sobre trae
    // una `salvedad` que dice cuál se está viendo.
    {
      id: 'OTD-VEN-04',
      endpoint: 'productos-hueso',
      fuente: 'compuesto',
      titulo: 'Los productos que no se venden',
      descripcion: 'El «producto hueso»: las variantes del catálogo que no rotan, para '
                 + 'liquidarlas o dejar de comprarlas. Ordenadas por tiempo sin vender, con '
                 + 'las que no han vendido NUNCA primero. Los días se cuentan contra la '
                 + 'última salida registrada en el almacén, no contra la fecha de hoy. '
                 + 'Arranca en las 10 primeras.',
      icono: 'trending_down',
      roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA'],
      vacio: 'No hay variantes paradas con el criterio elegido.',
      filtros: [
        { param: 'alcance', etiqueta: 'Criterio', tipo: 'select', valorInicial: 'nunca',
          opciones: [
            { valor: 'nunca',   etiqueta: 'Sin venta NUNCA' },
            { valor: 'periodo', etiqueta: 'Sin venta en el período' }
          ] },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'select', opciones: [
          { valor: '', etiqueta: 'Todas las categorías' },
          ...CATEGORIAS.map(c => ({ valor: c, etiqueta: c }))
        ] },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: FILTRO_CANAL }
      ],
      columnas: [
        { campo: 'sku',            titulo: 'SKU',       tipo: 'texto' },
        { campo: 'producto',       titulo: 'Producto',  tipo: 'texto', recortar: 30 },
        { campo: 'categoria',      titulo: 'Categoría', tipo: 'texto' },
        { campo: 'marca',          titulo: 'Marca',     tipo: 'texto' },
        { campo: 'stock_actual',   titulo: 'Stock',     tipo: 'numero' },
        { campo: 'nunca_vendida',  titulo: 'Estado',    tipo: 'chip',
          color: f => Number(f['nunca_vendida']) ? 'error' : 'warn',
          etiqueta: v => Number(v) ? 'nunca vendida' : 'parada' },
        { campo: 'ultima_venta',   titulo: 'Última venta', tipo: 'texto' },
        // Vacía en las que no vendieron nunca, y eso es lo correcto: no hay
        // fecha desde la que contar. La columna «Estado» las identifica.
        { campo: 'dias_sin_venta', titulo: 'Días sin vender', tipo: 'dias' }
      ]
    }
  ]
};
