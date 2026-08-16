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
 * Conviven aquí los SIMPLES (PostgreSQL) y los COMPUESTOS (ClickHouse): para la
 * pantalla genérica son iguales —mismo sobre, mismos filtros declarativos— y la
 * única diferencia visible es la marca de agua «Datos al …» que traen los
 * compuestos. Con la Fase 4 entran los cuatro de POSVENTA: ciclo de la
 * devolución (LOG-07), motivos y destino de la mercancía (LOG-08), tasa mensual
 * sobre los envíos (LOG-09) y reembolsos pagados (LOG-10).
 *
 * SEGREGACIÓN FINANCIERA: DESPACHO aparece en `roles` de todos los informes de
 * estados, fechas y cantidades, y NO en los TRES con dinero — OTD-LOG-11 (la
 * foto por zona), su serie mensual y OTD-LOG-10 (reembolsos). Esto espeja
 * SecurityConfig —que es quien realmente decide— y evita disparar una petición
 * que la API negaría con 403.
 *
 * Los repartos de la posventa NO coinciden entre sí, y es deliberado: BODEGA
 * entra solo en LOG-08 (inspecciona la mercancía, «en cantidades»), DESPACHO
 * solo en LOG-09 («en conteos») y SOPORTE en LOG-07, LOG-08 y LOG-10, que son
 * su ciclo.
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

/**
 * Cumplimiento de la promesa, en tres tramos. Los cortes no son arbitrarios: el
 * mejor transportista de la flota está en 87 % y el peor en 52 %, así que un
 * umbral de «bueno» por encima del 85 % separa lo que de verdad se distingue.
 */
function colorPuntualidad(fila: Record<string, any>): ColorChip {
  const pct = Number(fila['pct_a_tiempo']);
  if (!isFinite(pct)) { return 'neutral'; }
  if (pct >= 85) { return 'ok'; }
  if (pct >= 60) { return 'warn'; }
  return 'error';
}

/** Cualquier cantidad por encima de cero merece mirarse. */
function colorSiHay(campo: string): (fila: Record<string, any>) => ColorChip {
  return fila => Number(fila[campo]) > 0 ? 'error' : 'neutral';
}

const TIPO_NOVEDAD: Record<string, string> = {
  cliente_ausente:      'Cliente ausente',
  direccion_incorrecta: 'Dirección incorrecta',
  cliente_rechazo:      'Rechazo en la puerta',
  zona_dificil_acceso:  'Zona de difícil acceso',
  dano_en_transito:     'Daño en el camino'
};

/**
 * Los desenlaces REALES que guarda la base. El diseño del ETL decía
 * `reprogramar` / `devolver_almacen` —los verbos del API— y con esos valores el
 * filtro casa con cero filas sin dar error (corrección C3C.3).
 */
const ACCION_NOVEDAD: Record<string, string> = {
  devuelto_almacen: 'Devuelto al almacén',
  reprogramada:     'Reprogramada',
  sin_resolver:     'Sin resolver (abierta)'
};

/**
 * El destino de la mercancía devuelta. `sin_inspeccionar` NO está en la base:
 * lo pone el ETL sobre las líneas cuya devolución aún no llegó a bodega, y se
 * muestra a propósito — ocultarlas haría creer que todo ya se revisó.
 */
const RESULTADO_INSPECCION: Record<string, string> = {
  apto_reventa:     'Apto: vuelve al stock',
  defectuoso:       'Defectuoso: al proveedor',
  rechazado:        'Rechazado: sin reembolso',
  sin_inspeccionar: 'Aún sin inspeccionar'
};

/** Verde solo lo que se recupera; el resto es pérdida o está pendiente. */
function colorInspeccion(fila: Record<string, any>): ColorChip {
  switch (fila['resultado']) {
    case 'apto_reventa': return 'ok';
    case 'defectuoso':   return 'warn';
    case 'rechazado':    return 'error';
    default:             return 'neutral';
  }
}

/** El desenlace ordena la gravedad: devolver al almacén es perder la venta. */
function colorDesenlace(fila: Record<string, any>): ColorChip {
  switch (fila['accion']) {
    case 'devuelto_almacen': return 'error';
    case 'reprogramada':     return 'warn';
    default:                 return 'neutral';
  }
}

export const INFORMES_LOGISTICA: DefinicionDepartamento = {
  departamento: 'logistica',
  titulo: 'Informes de Logística',
  descripcion: 'Cola de despacho, seguimiento de envíos, devoluciones en curso y costo del transporte',
  icono: 'local_shipping',

  // ── PRESENTACIÓN (2026-08-16): el piloto de Ventas, ya validado ────────
  // 13 informes en la columna, 9 indicadores como mucho (LOG-04/07/08/10 y la
  // serie de LOG-11) y 5 filtros. Lo único que Ventas no tenía es OTD-LOG-08,
  // con TRES filtros anchos en la misma barra; la barra los reparte en dos
  // líneas antes que desbordar, que es justo lo que `flex-wrap` con
  // `min-width: 0` garantiza.
  selectorVertical: true,
  kpiVidrio: true,

  informes: [

    // ── OTD-LOG-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-LOG-01',
      endpoint: 'cola-despacho',
      fuente: 'simple',
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
      fuente: 'simple',
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
      fuente: 'simple',
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
      fuente: 'simple',
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
    },

    // ── OTD-LOG-12 ── COMPUESTO: la fuente es ClickHouse ──────────────
    // DESPACHO entra aquí aunque el informe salga de `fact_pedido`, que sí
    // tiene columnas de dinero: la consulta no selecciona ni un importe, y esa
    // —y no el motor— es la barrera. Mismo mecanismo que OTD-COM-08.
    {
      id: 'OTD-LOG-12',
      endpoint: 'tiempos-ciclo',
      fuente: 'compuesto',
      titulo: 'Tiempo por etapa del ciclo del pedido',
      descripcion: 'Cuánto tarda un pedido en cada tramo del camino, para encontrar el cuello '
                 + 'de botella real en vez de suponerlo. Cada etapa declara sobre CUÁNTOS '
                 + 'pedidos se midió: no todos recorren todos los tramos, y comparar dos '
                 + 'promedios calculados sobre poblaciones distintas sin saberlo es la manera '
                 + 'silenciosa de buscar el problema donde no está. Junto al promedio van la '
                 + 'mediana (el pedido corriente) y el percentil 90 (el 10 % peor).',
      icono: 'schedule',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay pedidos con hitos registrados en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Pedidos desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Pedidos hasta', tipo: 'fecha' },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] }
      ],
      columnas: [
        { campo: 'etapa',           titulo: 'Etapa', tipo: 'chip',
          color: f => f['etapa'] === 'Ciclo completo (pago → entrega)' ? 'neutral' : 'info' },
        { campo: 'descripcion',     titulo: 'Qué mide', tipo: 'texto', recortar: 52 },
        { campo: 'pedidos_medidos', titulo: 'Pedidos medidos', tipo: 'numero' },
        { campo: 'cobertura_pct',   titulo: 'Cobertura',       tipo: 'porcentaje' },
        { campo: 'horas_promedio',  titulo: 'Horas (media)',   tipo: 'numero' },
        { campo: 'horas_mediana',   titulo: 'Horas (mediana)', tipo: 'numero' },
        { campo: 'horas_p90',       titulo: 'Horas (p90)',     tipo: 'numero' },
        { campo: 'horas_minimo',    titulo: 'Mínimo',          tipo: 'numero' },
        { campo: 'horas_maximo',    titulo: 'Máximo',          tipo: 'numero' },
        { campo: 'dias_promedio',   titulo: 'Días (media)',    tipo: 'numero' }
      ]
    },

    // ── OTD-LOG-03 ── COMPUESTO: fact_envio ───────────────────────────
    {
      id: 'OTD-LOG-03',
      endpoint: 'cumplimiento-promesa',
      fuente: 'compuesto',
      titulo: 'Cumplimiento de la fecha prometida',
      descripcion: 'De los envíos ya entregados, cuántos llegaron a más tardar el día que se '
                 + 'le prometió al cliente. Se juzga solo sobre los envíos que tienen LAS DOS '
                 + 'fechas —la prometida y la real—: los que volvieron al almacén o siguen en '
                 + 'camino no llegaron tarde, es que no llegaron, y contarlos como '
                 + 'incumplimiento sería tan falso como ignorarlos. La columna «Cobertura» dice '
                 + 'sobre qué parte de los envíos se emitió el veredicto.',
      icono: 'event_available',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay envíos que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'desde', etiqueta: 'Despachado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Despachado hasta', tipo: 'fecha' },
        { param: 'zona', etiqueta: 'Zona de envío', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        FILTRO_TRANSPORTISTA,
        { param: 'estado', etiqueta: 'Estado del envío', tipo: 'select', opciones: [
          { valor: '',            etiqueta: 'Todos los estados' },
          { valor: 'entregado',   etiqueta: 'Entregado' },
          { valor: 'en_transito', etiqueta: 'En camino' },
          { valor: 'devuelto',    etiqueta: 'Devuelto al almacén' },
          { valor: 'fallido',     etiqueta: 'Fallido' },
          { valor: 'listo',       etiqueta: 'Listo' },
          { valor: 'preparando',  etiqueta: 'Preparando' }
        ] }
      ],
      columnas: [
        { campo: 'transportista',  titulo: 'Transportista', tipo: 'texto', recortar: 24 },
        { campo: 'envios',         titulo: 'Envíos',        tipo: 'numero' },
        { campo: 'medidos',        titulo: 'Con promesa',   tipo: 'numero' },
        { campo: 'cobertura_pct',  titulo: 'Cobertura',     tipo: 'porcentaje' },
        { campo: 'a_tiempo',       titulo: 'A tiempo',      tipo: 'numero' },
        { campo: 'tarde',          titulo: 'Tarde',         tipo: 'numero' },
        { campo: 'pct_a_tiempo',   titulo: 'Cumplimiento',  tipo: 'chip',
          color: colorPuntualidad, etiqueta: v => v == null ? '—' : `${v} %` },
        { campo: 'desvio_medio',   titulo: 'Desvío medio',  tipo: 'numero' },
        { campo: 'desvio_mediana', titulo: 'Desvío mediana', tipo: 'numero' },
        { campo: 'peor_retraso',   titulo: 'Peor retraso',  tipo: 'dias' },
        { campo: 'adelantados',    titulo: 'Se adelantaron', tipo: 'numero' }
      ]
    },

    // ── OTD-LOG-04 ── COMPUESTO: fact_envio ───────────────────────────
    // El «y período» del catálogo se resuelve con el filtro `agrupar` y no con
    // un informe aparte: es la misma medida vista por otro corte.
    {
      id: 'OTD-LOG-04',
      endpoint: 'dias-transito',
      fuente: 'compuesto',
      titulo: 'Días reales de tránsito',
      descripcion: 'Cuánto tarda de verdad un paquete desde que sale de bodega hasta la puerta '
                 + 'del cliente, sin importar qué fecha se prometió. Junto al promedio van la '
                 + 'mediana (el envío corriente) y el percentil 90 (el 10 % peor): la distancia '
                 + 'entre esas cifras es la conversación real sobre un transportista. Se puede '
                 + 'mirar por transportista, por mes o por zona de destino.',
      icono: 'timer',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay envíos que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select',
          valorInicial: 'transportista', opciones: [
          { valor: 'transportista', etiqueta: 'Transportista' },
          { valor: 'mes',           etiqueta: 'Mes' },
          { valor: 'zona',          etiqueta: 'Zona de envío' }
        ] },
        { param: 'desde', etiqueta: 'Despachado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Despachado hasta', tipo: 'fecha' },
        { param: 'zona', etiqueta: 'Zona de envío', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        FILTRO_TRANSPORTISTA
      ],
      columnas: [
        { campo: 'grupo',         titulo: 'Transportista / mes / zona', tipo: 'texto',
          recortar: 26 },
        { campo: 'envios',        titulo: 'Envíos',        tipo: 'numero' },
        { campo: 'medidos',       titulo: 'Entregados',    tipo: 'numero' },
        { campo: 'cobertura_pct', titulo: 'Cobertura',     tipo: 'porcentaje' },
        { campo: 'dias_promedio', titulo: 'Días (media)',  tipo: 'numero' },
        { campo: 'dias_mediana',  titulo: 'Días (mediana)', tipo: 'numero' },
        { campo: 'dias_p90',      titulo: 'Días (p90)',    tipo: 'numero' },
        { campo: 'dias_minimo',   titulo: 'Mínimo',        tipo: 'numero' },
        { campo: 'dias_maximo',   titulo: 'Máximo',        tipo: 'numero' },
        { campo: 'sin_llegar',    titulo: 'Nunca llegaron', tipo: 'chip',
          color: colorSiHay('sin_llegar') }
      ]
    },

    // ── OTD-LOG-05 ── COMPUESTO: fact_novedad_envio ───────────────────
    {
      id: 'OTD-LOG-05',
      endpoint: 'novedades',
      fuente: 'compuesto',
      titulo: 'Problemas de entrega',
      descripcion: 'Las incidencias de la última milla por tipo y por cómo terminaron: cuántas '
                 + 'ocurren, cuántos intentos toman y cuáles acaban con el paquete de vuelta en '
                 + 'el almacén, que es la venta perdida. Las que siguen abiertas aparecen como '
                 + '«Sin resolver» y NO se descartan: un tiempo medio calculado solo sobre las '
                 + 'cerradas mediría únicamente lo que ya terminó bien.',
      icono: 'report_problem',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO', 'SOPORTE'],
      sinPaginar: true,
      vacio: 'No hay novedades de entrega con esos filtros.',
      filtros: [
        { param: 'tipo', etiqueta: 'Tipo de problema', tipo: 'select', opciones: [
          { valor: '',                     etiqueta: 'Todos los tipos' },
          { valor: 'cliente_ausente',      etiqueta: 'Cliente ausente' },
          { valor: 'direccion_incorrecta', etiqueta: 'Dirección incorrecta' },
          { valor: 'cliente_rechazo',      etiqueta: 'Rechazo en la puerta' },
          { valor: 'zona_dificil_acceso',  etiqueta: 'Zona de difícil acceso' },
          { valor: 'dano_en_transito',     etiqueta: 'Daño en el camino' }
        ] },
        { param: 'accion', etiqueta: 'Desenlace', tipo: 'select', opciones: [
          { valor: '',                 etiqueta: 'Todos los desenlaces' },
          { valor: 'devuelto_almacen', etiqueta: 'Devuelto al almacén' },
          { valor: 'reprogramada',     etiqueta: 'Reprogramada' },
          { valor: 'sin_resolver',     etiqueta: 'Sin resolver (abierta)' }
        ] },
        { param: 'desde', etiqueta: 'Registrada desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Registrada hasta', tipo: 'fecha' },
        FILTRO_TRANSPORTISTA
      ],
      columnas: [
        { campo: 'tipo',              titulo: 'Tipo de problema', tipo: 'texto',
          etiqueta: v => TIPO_NOVEDAD[v] || v, recortar: 24 },
        { campo: 'accion',            titulo: 'Desenlace',   tipo: 'chip',
          color: colorDesenlace, etiqueta: v => ACCION_NOVEDAD[v] || v },
        { campo: 'novedades',         titulo: 'Casos',       tipo: 'numero' },
        { campo: 'envios',            titulo: 'Envíos',      tipo: 'numero' },
        { campo: 'resueltas',         titulo: 'Resueltas',   tipo: 'numero' },
        { campo: 'abiertas',          titulo: 'Abiertas',    tipo: 'chip',
          color: colorSiHay('abiertas') },
        { campo: 'intento_medio',     titulo: 'Intentos (media)', tipo: 'numero' },
        { campo: 'intento_max',       titulo: 'Máx. intentos',    tipo: 'numero' },
        { campo: 'en_tercer_intento', titulo: 'Llegaron al 3.º',  tipo: 'numero' },
        { campo: 'horas_medias',      titulo: 'Horas hasta resolver', tipo: 'numero' },
        { campo: 'horas_maximo',      titulo: 'Peor caso (horas)',    tipo: 'numero' }
      ]
    },

    // ── SERIE DEL COSTO DE ENVÍO ── COMPUESTO: fact_envio ─────────────
    // El SEGUNDO informe con dinero del departamento: sin DESPACHO ni BODEGA,
    // igual que OTD-LOG-11. Aquel da la foto por zona; éste, la evolución.
    {
      id: 'OTD-LOG-11 · serie',
      endpoint: 'costo-envio-mensual',
      fuente: 'compuesto',
      titulo: 'Evolución mensual del costo de envío',
      descripcion: 'Cómo se mueve el costo del transporte mes a mes, con su costo por kilo. '
                 + 'Responde «¿se está encareciendo?», que es distinto de «¿dónde nos cuesta '
                 + 'más caro?» — esa la contesta el informe de costo por zona. El costo medio '
                 + 'EXCLUYE los envíos sin tarifar y dice cuántos excluyó en cada mes.',
      icono: 'trending_up',
      roles: ['ADMIN', 'GERENTE'],
      sinPaginar: true,
      vacio: 'No hay envíos que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'desde', etiqueta: 'Despachado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Despachado hasta', tipo: 'fecha' },
        { param: 'zona', etiqueta: 'Zona de envío', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        FILTRO_TRANSPORTISTA
      ],
      columnas: [
        { campo: 'periodo',        titulo: 'Mes',            tipo: 'texto' },
        { campo: 'envios',         titulo: 'Envíos',         tipo: 'numero' },
        { campo: 'entregados',     titulo: 'Entregados',     tipo: 'numero' },
        { campo: 'costo_total',    titulo: 'Costo total',    tipo: 'moneda', monto: true },
        { campo: 'costo_medio',    titulo: 'Costo medio',    tipo: 'moneda', monto: true },
        { campo: 'costo_por_kg',   titulo: 'Costo por kilo', tipo: 'moneda', monto: true },
        { campo: 'peso_total',     titulo: 'Kilos',          tipo: 'numero' },
        { campo: 'transportistas', titulo: 'Transportistas', tipo: 'numero' },
        { campo: 'zonas',          titulo: 'Zonas',          tipo: 'numero' },
        { campo: 'sin_tarifar',    titulo: 'Sin tarifa',     tipo: 'chip',
          color: colorSiHay('sin_tarifar') }
      ]
    },

    // ── OTD-LOG-07 ── COMPUESTO: fact_devolucion (Fase 4) ─────────────
    {
      id: 'OTD-LOG-07',
      endpoint: 'ciclo-devolucion',
      fuente: 'compuesto',
      titulo: 'Días de ciclo de la devolución',
      descripcion: 'Cuánto tarda un RMA, tramo por tramo. CADA columna de días trae su '
                 + 'propio contador al lado: el ciclo hasta el cierre solo existe en las '
                 + 'devoluciones que llegaron a «cerrada» y el ciclo hasta el desenlace '
                 + 'añade las rechazadas, que también terminaron. Son promedios sobre '
                 + 'poblaciones distintas y no se comparan entre sí sin mirar el n.',
      icono: 'schedule',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay devoluciones con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',       etiqueta: 'Mes' },
          { valor: 'motivo', etiqueta: 'Motivo del cliente' },
          { valor: 'estado', etiqueta: 'Paso del ciclo' }
        ] },
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
        { campo: 'etiqueta',       titulo: 'Período / motivo', tipo: 'texto', recortar: 28 },
        { campo: 'devoluciones',   titulo: 'Devoluciones',  tipo: 'numero' },
        { campo: 'terminadas',     titulo: 'Con desenlace', tipo: 'numero' },
        { campo: 'cobertura_pct',  titulo: '% medible',     tipo: 'porcentaje' },
        { campo: 'n_desenlace',    titulo: 'n (desenlace)', tipo: 'numero' },
        { campo: 'dias_desenlace', titulo: 'Ciclo hasta el desenlace', tipo: 'dias' },
        { campo: 'mediana_desenlace', titulo: 'Mediana',    tipo: 'dias' },
        { campo: 'n_cierre',       titulo: 'n (cierre)',    tipo: 'numero' },
        { campo: 'dias_cierre',    titulo: 'Ciclo hasta el cierre', tipo: 'dias' },
        { campo: 'n_aprobacion',   titulo: 'n (aprob.)',    tipo: 'numero' },
        { campo: 'dias_aprobacion', titulo: 'Hasta aprobar', tipo: 'dias' },
        { campo: 'n_transito',     titulo: 'n (tránsito)',  tipo: 'numero' },
        { campo: 'dias_transito',  titulo: 'Tránsito de vuelta', tipo: 'dias' },
        { campo: 'n_inspeccion',   titulo: 'n (inspec.)',   tipo: 'numero' },
        { campo: 'dias_inspeccion', titulo: 'Hasta inspeccionar', tipo: 'dias' },
        { campo: 'n_reembolso',    titulo: 'n (reemb.)',    tipo: 'numero' },
        { campo: 'dias_reembolso', titulo: 'Hasta reembolsar', tipo: 'dias' },
        { campo: 'peor_caso',      titulo: 'Peor caso',     tipo: 'dias' }
      ]
    },

    // ── OTD-LOG-08 ── COMPUESTO: fact_devolucion_linea (Fase 4) ───────
    // BODEGA entra «en cantidades»: la consulta no selecciona ni un importe.
    {
      id: 'OTD-LOG-08',
      endpoint: 'motivos-devolucion',
      fuente: 'compuesto',
      titulo: 'Motivos de devolución y destino de la mercancía',
      descripcion: 'Por qué devuelven y qué pasa con lo devuelto. Solo lo APTO PARA '
                 + 'REVENTA vuelve al stock vendible; lo defectuoso va al pool de '
                 + 'devolución al proveedor y lo rechazado no genera reembolso. '
                 + '«Sin inspeccionar» no es un hueco: son las líneas cuya devolución '
                 + 'todavía no ha llegado a bodega.',
      icono: 'inventory_2',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE', 'ANALISTA', 'BODEGA'],
      sinPaginar: true,
      vacio: 'No hay líneas devueltas con esos filtros.',
      filtros: [
        { param: 'resultado', etiqueta: 'Resultado de inspección', tipo: 'select',
          opciones: [
            { valor: '',                 etiqueta: 'Todos los resultados' },
            { valor: 'apto_reventa',     etiqueta: 'Apto para reventa (reingresa)' },
            { valor: 'defectuoso',       etiqueta: 'Defectuoso (al proveedor)' },
            { valor: 'rechazado',        etiqueta: 'Rechazado (sin reembolso)' },
            { valor: 'sin_inspeccionar', etiqueta: 'Sin inspeccionar todavía' }
          ], ancho: 'ancho' },
        { param: 'desde', etiqueta: 'Solicitada desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Solicitada hasta', tipo: 'fecha' },
        { param: 'motivo', etiqueta: 'Motivo', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'motivo',              titulo: 'Motivo del cliente', tipo: 'texto',
          recortar: 30 },
        { campo: 'resultado',           titulo: 'Destino',      tipo: 'chip',
          color: colorInspeccion, etiqueta: v => RESULTADO_INSPECCION[v] || v },
        { campo: 'lineas',              titulo: 'Líneas',       tipo: 'numero' },
        { campo: 'unidades',            titulo: 'Unidades',     tipo: 'numero' },
        { campo: 'devoluciones',        titulo: 'Devoluciones', tipo: 'numero' },
        { campo: 'productos',           titulo: 'Productos',    tipo: 'numero' },
        { campo: 'uds_reingresadas',    titulo: 'Vuelven al stock', tipo: 'numero' },
        { campo: 'pct_reingreso',       titulo: '% recuperado', tipo: 'porcentaje' },
        { campo: 'sin_inspeccionar',    titulo: 'Pendientes',   tipo: 'numero' }
      ]
    },

    // ── OTD-LOG-09 ── COMPUESTO: fact_envio × fact_devolucion ─────────
    // El único informe que cruza dos tablas de hechos de FASES distintas.
    {
      id: 'OTD-LOG-09',
      endpoint: 'tasa-devolucion',
      fuente: 'compuesto',
      titulo: 'Tasa mensual de devolución sobre los envíos',
      descripcion: 'De cada 100 envíos despachados, cuántos acaban en devolución. '
                 + 'Numerador y denominador NO son la misma población: se cuentan las '
                 + 'devoluciones registradas EN el mes contra los envíos despachados EN '
                 + 'el mes, y una devolución de julio puede venir de un envío de mayo. Es '
                 + 'la medida de control operativa, no la calidad de lo enviado ese mes.',
      icono: 'percent',
      roles: ['ADMIN', 'GERENTE', 'DESPACHO', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay envíos ni devoluciones en el período elegido.',
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
        { campo: 'periodo',              titulo: 'Mes',           tipo: 'texto' },
        { campo: 'envios',               titulo: 'Envíos',        tipo: 'numero' },
        { campo: 'entregados',           titulo: 'Entregados',    tipo: 'numero' },
        { campo: 'devoluciones',         titulo: 'Devoluciones',  tipo: 'numero' },
        { campo: 'unidades_devueltas',   titulo: 'Unidades',      tipo: 'numero' },
        { campo: 'pct_sobre_envios',     titulo: '% sobre envíos', tipo: 'porcentaje' },
        { campo: 'pct_sobre_entregados', titulo: '% sobre entregados', tipo: 'porcentaje' }
      ]
    },

    // ── OTD-LOG-10 ── COMPUESTO: fact_devolucion (Fase 4) ─────────────
    // El TERCER informe con dinero del departamento: sin DESPACHO ni BODEGA.
    {
      id: 'OTD-LOG-10',
      endpoint: 'reembolsos',
      fuente: 'compuesto',
      titulo: 'Reembolsos pagados a clientes',
      descripcion: 'Cuánto dinero se devolvió, por qué vía y por qué motivo. El período '
                 + 'es el del PAGO del reembolso, no el de la solicitud. La columna «Con '
                 + 'asiento» dice cuántos tienen además su registro de tesorería: hay una '
                 + 'devolución legacy con monto y sin asiento, y las dos cifras se '
                 + 'muestran sin reconciliar.',
      icono: 'currency_exchange',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE'],
      sinPaginar: true,
      vacio: 'No se pagó ningún reembolso con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',       etiqueta: 'Mes del pago' },
          { valor: 'metodo', etiqueta: 'Vía de reembolso' },
          { valor: 'motivo', etiqueta: 'Motivo de la devolución' }
        ] },
        { param: 'metodo', etiqueta: 'Vía', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todas las vías' },
          { valor: 'efectivo',      etiqueta: 'Efectivo' },
          { valor: 'tarjeta',       etiqueta: 'Tarjeta' },
          { valor: 'transferencia', etiqueta: 'Transferencia' }
        ] },
        { param: 'desde', etiqueta: 'Pagado desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Pagado hasta', tipo: 'fecha' },
        { param: 'motivo', etiqueta: 'Motivo', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'etiqueta',           titulo: 'Mes / vía / motivo', tipo: 'texto',
          recortar: 30 },
        { campo: 'reembolsos',         titulo: 'Reembolsos',   tipo: 'numero' },
        { campo: 'monto',              titulo: 'Reembolsado',  tipo: 'moneda', monto: true },
        { campo: 'monto_medio',        titulo: 'Medio',        tipo: 'moneda', monto: true },
        { campo: 'mayor',              titulo: 'Mayor',        tipo: 'moneda', monto: true },
        { campo: 'mercancia_devuelta', titulo: 'Mercancía devuelta', tipo: 'moneda',
          monto: true },
        { campo: 'pct_sobre_devuelto', titulo: '% de lo devuelto', tipo: 'porcentaje' },
        { campo: 'con_asiento',        titulo: 'Con asiento',  tipo: 'numero' },
        { campo: 'sin_asiento',        titulo: 'Sin asiento',  tipo: 'chip',
          color: colorSiHay('sin_asiento') },
        { campo: 'clientes',           titulo: 'Clientes',     tipo: 'numero' },
        { campo: 'dias_hasta_pagar',   titulo: 'Días hasta pagar', tipo: 'dias' }
      ]
    }
  ]
};
