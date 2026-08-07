import { ColorChip, DefinicionDepartamento } from '../../../../core/models/informe.model';

/**
 * INFORMES TÁCTICOS DE SOPORTE — los tres objetivos del catálogo
 * (`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §8) que se resuelven con una
 * consulta directa a PostgreSQL.
 *
 * Este archivo es TODO lo que hay que escribir para la pantalla: la
 * `InformesDepartamentoComponent` genérica pinta los filtros, la tabla, el
 * resumen y la paginación a partir de estas declaraciones. Ni un componente,
 * ni un servicio, ni un estilo nuevo (ver `docs/tactico/PATRON_INFORMES.md`).
 *
 * Conviven aquí los TRES simples (PostgreSQL) y los CINCO compuestos de la
 * Fase 4 del pipeline (ClickHouse): SOP-02 cumplimiento del plazo prometido,
 * SOP-03 tiempo de resolución por categoría, SOP-06 primera respuesta, SOP-07
 * tiempo por agente y SOP-08 productos problemáticos. Para la pantalla genérica
 * son iguales —mismo sobre, mismos filtros declarativos— y la única diferencia
 * visible es la marca de agua «Datos al …» que traen los compuestos, más la
 * `salvedad` que cuatro de ellos escriben encima de la tabla.
 *
 * NINGUNO de los ocho lleva dinero: no hay columnas `monto: true` ni corte
 * financiero que declarar. Los `roles` espejan SecurityConfig —que es quien
 * realmente decide— con dos AMPLIACIONES de la Fase 4: SOP-03 suma al ANALISTA
 * y SOP-08 a COMPRAS, porque el ranking de productos problemáticos existe para
 * que Compras vaya a revisar el producto con su proveedor.
 */

const ESTADO_TICKET: Record<string, string> = {
  abierto:           'Abierto',
  en_proceso:        'En proceso',
  esperando_cliente: 'Esperando al cliente',
  resuelto:          'Resuelto',
  cerrado:           'Cerrado'
};

/** Lo rojo es lo que se salió del plazo; lo verde, lo que ya está cerrado. */
function colorEstadoTicket(fila: Record<string, any>): ColorChip {
  if (fila['vencido']) { return 'error'; }
  switch (fila['estado']) {
    case 'resuelto':
    case 'cerrado':          return 'ok';
    case 'esperando_cliente': return 'neutral';
    case 'en_proceso':       return 'info';
    default:                 return 'warn';
  }
}

const PRIORIDAD: Record<string, string> = {
  urgente: 'Urgente',
  alta:    'Alta',
  media:   'Media',
  baja:    'Baja'
};

/** La prioridad la pone el sistema desde la categoría (script 37), no el cliente. */
function colorPrioridad(fila: Record<string, any>): ColorChip {
  switch (fila['prioridad']) {
    case 'urgente': return 'error';
    case 'alta':    return 'warn';
    case 'media':   return 'info';
    default:        return 'neutral';
  }
}

export const INFORMES_SOPORTE: DefinicionDepartamento = {
  departamento: 'soporte',
  titulo: 'Informes de Soporte',
  descripcion: 'Bandeja de tickets, causas por categoría y reparto del trabajo del equipo',
  icono: 'support_agent',
  informes: [

    // ── OTD-SOP-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-SOP-01',
      endpoint: 'bandeja',
      fuente: 'simple',
      titulo: 'Bandeja de tickets',
      descripcion: 'Qué hay sobre la mesa: estado, urgencia, categoría y quién lo atiende. '
                 + 'Primero lo que ya pasó su fecha límite.',
      icono: 'inbox',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE'],
      vacio: 'No hay tickets que coincidan con los filtros elegidos.',
      filtros: [
        // «Pendientes» no es un estado de la tabla: agrupa los tres en los que
        // el ticket sigue vivo, y es la pregunta con la que se abre la bandeja.
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', valorInicial: 'pendientes',
          opciones: [
            { valor: 'pendientes',        etiqueta: 'Sin resolver (bandeja)' },
            { valor: '',                  etiqueta: 'Todos los estados' },
            { valor: 'abierto',           etiqueta: 'Abierto' },
            { valor: 'en_proceso',        etiqueta: 'En proceso' },
            { valor: 'esperando_cliente', etiqueta: 'Esperando al cliente' },
            { valor: 'resuelto',          etiqueta: 'Resuelto' },
            { valor: 'cerrado',           etiqueta: 'Cerrado' }
          ] },
        { param: 'prioridad', etiqueta: 'Urgencia', tipo: 'select', opciones: [
          { valor: '',        etiqueta: 'Todas las urgencias' },
          { valor: 'urgente', etiqueta: 'Urgente (2 h)' },
          { valor: 'alta',    etiqueta: 'Alta (4 h)' },
          { valor: 'media',   etiqueta: 'Media (24 h)' },
          { valor: 'baja',    etiqueta: 'Baja (72 h)' }
        ] },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'agente', etiqueta: 'Agente asignado', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'buscar', etiqueta: 'Nº de ticket, asunto o cliente', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'numero',         titulo: 'Ticket',    tipo: 'texto' },
        { campo: 'estado',         titulo: 'Estado',    tipo: 'chip',
          color: colorEstadoTicket, etiqueta: v => ESTADO_TICKET[v] || v },
        { campo: 'prioridad',      titulo: 'Urgencia',  tipo: 'chip',
          color: colorPrioridad, etiqueta: v => PRIORIDAD[v] || v },
        { campo: 'categoria',      titulo: 'Categoría', tipo: 'texto', recortar: 22 },
        { campo: 'asunto',         titulo: 'Asunto',    tipo: 'texto', recortar: 34 },
        { campo: 'cliente',        titulo: 'Cliente',   tipo: 'texto', recortar: 24 },
        { campo: 'agente',         titulo: 'Atiende',   tipo: 'texto', recortar: 22 },
        { campo: 'fecha_creacion', titulo: 'Creado',    tipo: 'fecha' },
        { campo: 'fecha_limite',   titulo: 'Vence',     tipo: 'fechaHora' },
        { campo: 'vencido',        titulo: 'Plazo',     tipo: 'chip',
          color: f => f['vencido'] ? 'error' : (f['vivo'] ? 'ok' : 'neutral'),
          etiqueta: (v, f) => v ? 'VENCIDO' : (f['vivo'] ? 'En plazo' : 'Cerrado') },
        { campo: 'dias_abierto',   titulo: 'Antigüedad', tipo: 'dias' }
      ]
    },

    // ── OTD-SOP-04 ────────────────────────────────────────────────────
    {
      id: 'OTD-SOP-04',
      endpoint: 'por-categoria',
      fuente: 'simple',
      titulo: 'Tickets por categoría',
      descripcion: 'De qué se queja la gente, para atacar la causa y no el síntoma. '
                 + 'Una categoría en cero también informa: ese frente está tranquilo.',
      icono: 'donut_small',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE'],
      sinPaginar: true,
      vacio: 'No hay categorías de ticket configuradas.',
      filtros: [
        { param: 'desde', etiqueta: 'Creados desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Creados hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'categoria',            titulo: 'Categoría',     tipo: 'texto', recortar: 24 },
        { campo: 'tickets',              titulo: 'Tickets',       tipo: 'numero' },
        { campo: 'porcentaje',           titulo: 'Del total',     tipo: 'porcentaje' },
        { campo: 'sin_resolver',         titulo: 'Sin resolver',  tipo: 'numero' },
        { campo: 'resueltos',            titulo: 'Resueltos',     tipo: 'numero' },
        { campo: 'criticos',             titulo: 'Urgentes/altos', tipo: 'numero' },
        { campo: 'vencidos',             titulo: 'Fuera de plazo', tipo: 'numero' },
        { campo: 'dias_promedio_abierto', titulo: 'Antigüedad media', tipo: 'dias' },
        { campo: 'prioridad_defecto',    titulo: 'Urgencia por defecto', tipo: 'chip',
          color: f => f['prioridad_defecto'] === 'alta' ? 'warn'
                    : f['prioridad_defecto'] === 'urgente' ? 'error'
                    : f['prioridad_defecto'] === 'media' ? 'info' : 'neutral',
          etiqueta: v => PRIORIDAD[v] || v }
      ]
    },

    // ── OTD-SOP-05 ────────────────────────────────────────────────────
    {
      id: 'OTD-SOP-05',
      endpoint: 'por-agente',
      fuente: 'simple',
      titulo: 'Carga por agente',
      descripcion: 'Cómo está repartido el trabajo del equipo. La cola «(sin asignar)» sale '
                 + 'primero: es la que nadie tomó.',
      icono: 'groups',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE'],
      sinPaginar: true,
      vacio: 'No hay tickets creados en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Creados desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Creados hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'agente',            titulo: 'Agente',        tipo: 'texto', recortar: 26 },
        // El backend nunca manda el rol vacío (SIN_DUENO / SIN_ROL): un null se
        // pintaría como «—» sin pasar por esta etiqueta. Amarillo = alguien de
        // fuera del equipo de soporte cargando tickets.
        { campo: 'rol',               titulo: 'Rol',           tipo: 'chip',
          color: f => f['cola_sin_dueno'] ? 'error'
                    : f['rol'] === 'SOPORTE' ? 'info' : 'warn',
          etiqueta: v => v === 'SIN_DUENO' ? 'Sin dueño'
                       : v === 'SIN_ROL' ? 'Sin rol asignado' : v },
        { campo: 'asignados',         titulo: 'Asignados',     tipo: 'numero' },
        { campo: 'abiertos',          titulo: 'Abiertos',      tipo: 'numero' },
        { campo: 'en_proceso',        titulo: 'En proceso',    tipo: 'numero' },
        { campo: 'esperando_cliente', titulo: 'Esperando',     tipo: 'numero' },
        { campo: 'resueltos',         titulo: 'Resueltos',     tipo: 'numero' },
        { campo: 'vencidos',          titulo: 'Fuera de plazo', tipo: 'numero' },
        { campo: 'criticos_vivos',    titulo: 'Críticos vivos', tipo: 'numero' },
        { campo: 'tasa_resolucion',   titulo: 'Tasa de cierre', tipo: 'porcentaje' },
        { campo: 'email',             titulo: 'Correo',        tipo: 'texto', recortar: 28 }
      ]
    },

    // ══ FASE 4 · los cinco COMPUESTOS (fuente: ClickHouse) ════════════
    // Ninguno lleva dinero: son tiempos, conteos y veredictos. Dos amplían
    // el reparto del departamento — SOP-03 suma al ANALISTA y SOP-08 a
    // COMPRAS— y así está escrito en SecurityConfig.

    // ── OTD-SOP-02 ────────────────────────────────────────────────────
    {
      id: 'OTD-SOP-02',
      endpoint: 'cumplimiento-sla',
      fuente: 'compuesto',
      titulo: 'Cumplimiento del tiempo prometido',
      descripcion: 'La base se parte en CUATRO y no en un porcentaje sobre el total: de '
                 + 'un ticket abierto no se sabe si cumplirá, y contarlo como '
                 + 'incumplimiento sería falso. El porcentaje se calcula solo sobre los '
                 + 'CERRADOS; la columna «Abiertos y vencidos» es la accionable, y se '
                 + 'recalcula en el momento de mirar la pantalla.',
      icono: 'timer',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE'],
      sinPaginar: true,
      vacio: 'No hay tickets con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Urgencia' },
          { valor: 'categoria', etiqueta: 'Categoría' },
          { valor: 'mes',       etiqueta: 'Evolución mensual' },
          { valor: 'agente',    etiqueta: 'Agente' }
        ] },
        { param: 'prioridad', etiqueta: 'Urgencia', tipo: 'select', opciones: [
          { valor: '',        etiqueta: 'Todas' },
          { valor: 'urgente', etiqueta: 'Urgente (2 h)' },
          { valor: 'alta',    etiqueta: 'Alta (4 h)' },
          { valor: 'media',   etiqueta: 'Media (24 h)' },
          { valor: 'baja',    etiqueta: 'Baja (72 h)' }
        ] },
        { param: 'desde', etiqueta: 'Abierto desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Abierto hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'etiqueta',          titulo: 'Urgencia / grupo', tipo: 'texto',
          etiqueta: v => PRIORIDAD[v] || v, recortar: 26 },
        { campo: 'tickets',           titulo: 'Tickets',       tipo: 'numero' },
        { campo: 'horas_prometidas',  titulo: 'Plazo (h)',     tipo: 'numero' },
        { campo: 'cerrados',          titulo: 'Cerrados (base)', tipo: 'numero' },
        { campo: 'cerrados_a_tiempo', titulo: 'A tiempo',      tipo: 'numero' },
        { campo: 'cerrados_tarde',    titulo: 'Tarde',         tipo: 'chip',
          color: f => Number(f['cerrados_tarde']) > 0 ? 'warn' : 'neutral' },
        { campo: 'abiertos_en_plazo', titulo: 'Abiertos en plazo', tipo: 'numero' },
        { campo: 'abiertos_vencidos', titulo: 'Abiertos VENCIDOS', tipo: 'chip',
          color: f => Number(f['abiertos_vencidos']) > 0 ? 'error' : 'ok' },
        { campo: 'pct_cumplimiento',  titulo: '% (solo cerrados)', tipo: 'porcentaje' },
        { campo: 'horas_resolucion',  titulo: 'Horas al cerrar', tipo: 'numero' }
      ]
    },

    // ── OTD-SOP-03 ────────────────────────────────────────────────────
    {
      id: 'OTD-SOP-03',
      endpoint: 'tiempo-resolucion',
      fuente: 'compuesto',
      titulo: 'Tiempo de resolución por tipo de problema',
      descripcion: 'Horas entre la apertura y el CIERRE, por categoría. La base son los '
                 + 'tickets con cierre y no los que están en estado «resuelto»: resolver '
                 + 'no cierra —el cliente todavía puede responder y reabrir— y no hay '
                 + 'instante que restar. La columna «Cerrados» es el denominador.',
      icono: 'hourglass_bottom',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay tickets con esos filtros.',
      filtros: [
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [
          { valor: '',                  etiqueta: 'Todos los estados' },
          { valor: 'abierto',           etiqueta: 'Abierto' },
          { valor: 'en_proceso',        etiqueta: 'En proceso' },
          { valor: 'esperando_cliente', etiqueta: 'Esperando al cliente' },
          { valor: 'resuelto',          etiqueta: 'Resuelto (sin cerrar)' },
          { valor: 'cerrado',           etiqueta: 'Cerrado' }
        ] },
        { param: 'prioridad', etiqueta: 'Urgencia', tipo: 'select', opciones: [
          { valor: '',        etiqueta: 'Todas' },
          { valor: 'urgente', etiqueta: 'Urgente' },
          { valor: 'alta',    etiqueta: 'Alta' },
          { valor: 'media',   etiqueta: 'Media' },
          { valor: 'baja',    etiqueta: 'Baja' }
        ] },
        { param: 'desde', etiqueta: 'Abierto desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Abierto hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'categoria',            titulo: 'Categoría',    tipo: 'texto', recortar: 26 },
        { campo: 'tickets',              titulo: 'Tickets',      tipo: 'numero' },
        { campo: 'resueltos_por_estado', titulo: 'Resueltos o cerrados', tipo: 'numero' },
        { campo: 'cerrados',             titulo: 'Con cierre (base)', tipo: 'numero' },
        { campo: 'cobertura_pct',        titulo: '% medible',    tipo: 'porcentaje' },
        { campo: 'horas_promedio',       titulo: 'Horas (media)', tipo: 'numero' },
        { campo: 'horas_mediana',        titulo: 'Mediana',      tipo: 'numero' },
        { campo: 'horas_p90',            titulo: 'P90',          tipo: 'numero' },
        { campo: 'horas_maximo',         titulo: 'Peor caso',    tipo: 'numero' },
        { campo: 'dias_promedio',        titulo: 'Días (media)', tipo: 'dias' },
        { campo: 'a_tiempo',             titulo: 'A tiempo',     tipo: 'numero' },
        { campo: 'tarde',                titulo: 'Tarde',        tipo: 'numero' }
      ]
    },

    // ── OTD-SOP-06 ────────────────────────────────────────────────────
    {
      id: 'OTD-SOP-06',
      endpoint: 'primera-respuesta',
      fuente: 'compuesto',
      titulo: 'Horas hasta la primera respuesta',
      descripcion: 'Cuánto tarda el equipo en contestar por primera vez. «Primera '
                 + 'respuesta» = primer mensaje del equipo VISIBLE para el cliente; una '
                 + 'nota interna no cuenta aunque llegue antes. La otra definición va en '
                 + 'las dos últimas columnas para que la diferencia sea un dato y no una '
                 + 'nota al pie. Los tickets SIN respuesta también se muestran.',
      icono: 'quickreply',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE'],
      sinPaginar: true,
      vacio: 'No hay tickets con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Urgencia' },
          { valor: 'mes',       etiqueta: 'Evolución mensual' },
          { valor: 'categoria', etiqueta: 'Categoría' },
          { valor: 'agente',    etiqueta: 'Agente' }
        ] },
        { param: 'prioridad', etiqueta: 'Urgencia', tipo: 'select', opciones: [
          { valor: '',        etiqueta: 'Todas' },
          { valor: 'urgente', etiqueta: 'Urgente (2 h)' },
          { valor: 'alta',    etiqueta: 'Alta (4 h)' },
          { valor: 'media',   etiqueta: 'Media (24 h)' },
          { valor: 'baja',    etiqueta: 'Baja (72 h)' }
        ] },
        { param: 'desde', etiqueta: 'Abierto desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Abierto hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'etiqueta',            titulo: 'Urgencia / grupo', tipo: 'texto',
          etiqueta: v => PRIORIDAD[v] || v, recortar: 26 },
        { campo: 'tickets',             titulo: 'Tickets',      tipo: 'numero' },
        { campo: 'horas_prometidas',    titulo: 'Plazo (h)',    tipo: 'numero' },
        { campo: 'con_respuesta',       titulo: 'Con respuesta', tipo: 'numero' },
        { campo: 'sin_respuesta',       titulo: 'SIN respuesta', tipo: 'chip',
          color: f => Number(f['sin_respuesta']) > 0 ? 'error' : 'ok' },
        { campo: 'cobertura_pct',       titulo: '% respondidos', tipo: 'porcentaje' },
        { campo: 'horas_promedio',      titulo: 'Horas (media)', tipo: 'numero' },
        { campo: 'horas_mediana',       titulo: 'Mediana',      tipo: 'numero' },
        { campo: 'horas_p90',           titulo: 'P90',          tipo: 'numero' },
        { campo: 'con_mensaje_equipo',  titulo: 'Base con notas internas', tipo: 'numero' },
        { campo: 'horas_incl_internas', titulo: 'Horas con notas internas', tipo: 'numero' },
        { campo: 'solo_notas_internas', titulo: 'Solo notas internas', tipo: 'numero' }
      ]
    },

    // ── OTD-SOP-07 ────────────────────────────────────────────────────
    // La ruta es /tiempos-agente y NO /por-agente: el simple con ese nombre
    // sigue vivo y responde otra pregunta (la carga viva de la bandeja).
    {
      id: 'OTD-SOP-07',
      endpoint: 'tiempos-agente',
      fuente: 'compuesto',
      titulo: 'Tiempo de resolución por agente',
      descripcion: 'Carga y tiempos de cada persona del equipo. La columna «Cerrados» es '
                 + 'el denominador y va antes que las horas: con dos o tres casos, la '
                 + 'media de un agente no distingue nada. La fila «(sin asignar)» son los '
                 + 'tickets que nadie ha tomado, y se muestra a propósito.',
      icono: 'support_agent',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE'],
      sinPaginar: true,
      vacio: 'No hay tickets con esos filtros.',
      filtros: [
        { param: 'prioridad', etiqueta: 'Urgencia', tipo: 'select', opciones: [
          { valor: '',        etiqueta: 'Todas' },
          { valor: 'urgente', etiqueta: 'Urgente' },
          { valor: 'alta',    etiqueta: 'Alta' },
          { valor: 'media',   etiqueta: 'Media' },
          { valor: 'baja',    etiqueta: 'Baja' }
        ] },
        { param: 'desde', etiqueta: 'Abierto desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Abierto hasta', tipo: 'fecha' },
        { param: 'agente', etiqueta: 'Agente', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'agente',           titulo: 'Agente',        tipo: 'texto', recortar: 26 },
        { campo: 'tickets',          titulo: 'Asignados',     tipo: 'numero' },
        { campo: 'cerrados',         titulo: 'Cerrados (base)', tipo: 'numero' },
        { campo: 'vivos',            titulo: 'En su bandeja', tipo: 'numero' },
        { campo: 'vencidos',         titulo: 'Vencidos',      tipo: 'chip',
          color: f => Number(f['vencidos']) > 0 ? 'error' : 'ok' },
        { campo: 'cobertura_pct',    titulo: '% medible',     tipo: 'porcentaje' },
        { campo: 'horas_promedio',   titulo: 'Horas (media)', tipo: 'numero' },
        { campo: 'horas_mediana',    titulo: 'Mediana',       tipo: 'numero' },
        { campo: 'horas_maximo',     titulo: 'Peor caso',     tipo: 'numero' },
        { campo: 'horas_respuesta',  titulo: '1.ª respuesta (h)', tipo: 'numero' },
        { campo: 'a_tiempo',         titulo: 'A tiempo',      tipo: 'numero' },
        { campo: 'tarde',            titulo: 'Tarde',         tipo: 'numero' },
        { campo: 'pct_cumplimiento', titulo: '% cumplimiento', tipo: 'porcentaje' },
        { campo: 'categorias',       titulo: 'Categorías',    tipo: 'numero' }
      ]
    },

    // ── OTD-SOP-08 ── cruza fact_ticket con fact_devolucion_linea ─────
    // Suma COMPRAS: el ranking existe para que revise el producto con su
    // proveedor. Sin una sola columna de dinero.
    {
      id: 'OTD-SOP-08',
      endpoint: 'productos-reclamados',
      fuente: 'compuesto',
      titulo: 'Productos que más problemas generan',
      descripcion: 'Reclamos y devoluciones por producto, en un solo ranking. Solo entra '
                 + 'lo que se puede atribuir a un producto: los tickets sin producto '
                 + '—consultas de facturación, envío o cuenta— quedan FUERA y se cuentan '
                 + 'en el resumen. No se reparten entre los demás: hacerlo inventaría un '
                 + 'culpable.',
      icono: 'report',
      roles: ['ADMIN', 'GERENTE', 'SOPORTE', 'COMPRAS'],
      vacio: 'Ningún producto acumula reclamos ni devoluciones con esos filtros.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'buscar', etiqueta: 'Producto', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'producto_nombre',   titulo: 'Producto',      tipo: 'texto', recortar: 34 },
        { campo: 'categoria',         titulo: 'Categoría',     tipo: 'texto', recortar: 18 },
        { campo: 'incidencias',       titulo: 'Incidencias',   tipo: 'numero' },
        { campo: 'reclamos',          titulo: 'Reclamos',      tipo: 'numero' },
        { campo: 'reclamos_vencidos', titulo: 'Reclamos vencidos', tipo: 'chip',
          color: f => Number(f['reclamos_vencidos']) > 0 ? 'error' : 'neutral' },
        { campo: 'devoluciones',      titulo: 'Devoluciones',  tipo: 'numero' },
        { campo: 'uds_devueltas',     titulo: 'Uds. devueltas', tipo: 'numero' },
        { campo: 'uds_defectuosas',   titulo: 'Uds. defectuosas', tipo: 'chip',
          color: f => Number(f['uds_defectuosas']) > 0 ? 'warn' : 'neutral' }
      ]
    }
  ]
};
