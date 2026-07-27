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
 * Los COMPUESTOS de Soporte (cumplimiento del SLA por período — SOP-02, tiempo
 * de resolución por categoría — SOP-03, satisfacción — SOP-06, tiempo por
 * agente — SOP-07, reapertura — SOP-08) NO están aquí: son de la fase
 * ETL → ClickHouse.
 *
 * Ningún informe de Soporte lleva dinero: no hay columnas `monto: true` ni
 * corte financiero que declarar. Los `roles` espejan SecurityConfig, que es
 * quien realmente decide.
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
    }
  ]
};
