import { ColorChip, DefinicionDepartamento } from '../../../../core/models/informe.model';
import { informePrevisionDemanda } from './prevision.informe';

/**
 * INFORMES TÁCTICOS DE GERENCIA / DIRECCIÓN — los cinco objetivos del catálogo
 * (`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §9) que se resuelven con una
 * consulta directa a PostgreSQL.
 *
 * Este archivo es TODO lo que hay que escribir para la pantalla: la
 * `InformesDepartamentoComponent` genérica pinta los filtros, la tabla, el
 * resumen y la paginación a partir de estas declaraciones. Ni un componente,
 * ni un servicio, ni un estilo nuevo (ver `docs/tactico/PATRON_INFORMES.md`).
 *
 * Conviven aquí los cinco SIMPLES y los seis COMPUESTOS (ClickHouse): balanza
 * mensual (GER-02) y descuento por cupón (GER-05) desde la Fase 2, y con la
 * Fase 4 la ganancia por categoría (GER-03), el margen producto a producto
 * (GER-10), el descuento total entregado (GER-11) y el efecto de las
 * promociones (GER-07). Los seis suman al ANALISTA, que en los simples no
 * participa.
 *
 * GER-03 y GER-10 arrastran la SALVEDAD del costo vigente —el sistema no
 * guarda histórico de costos— y GER-07 la de MUESTRA DÉBIL. Las tres viajan en
 * el sobre y la pantalla genérica las pinta encima de la tabla: quien lee la
 * pantalla no lee el diseño.
 *
 * DATOS SENSIBLES DE SEGURIDAD: OTD-GER-08 (auditoría) y OTD-GER-09 (intentos
 * de acceso) declaran `roles: ['ADMIN', 'GERENTE']`, el corte más estricto del
 * sistema. Esto espeja SecurityConfig —que es quien realmente decide— y evita
 * ofrecer en la pantalla un informe que la API negaría con 403.
 */

/** Los cuatro bloques de la foto del día, con su color de lectura. */
function colorBloque(fila: Record<string, any>): ColorChip {
  switch (fila['bloque']) {
    case 'Día sin movimiento':   return 'warn';
    case 'Pedidos del día':      return 'info';
    case 'Cobros del día':       return 'ok';
    case 'Facturación del día':  return 'neutral';
    default:                     return Number(fila['cantidad']) > 0 ? 'warn' : 'ok';
  }
}

const SITUACION_CUPON: Record<string, string> = {
  vigente:    'Vigente',
  programado: 'Programado',
  vencido:    'Vencido',
  agotado:    'Sin usos disponibles',
  inactivo:   'Desactivado'
};

function colorSituacionCupon(fila: Record<string, any>): ColorChip {
  switch (fila['situacion']) {
    case 'vigente':    return Number(fila['dias_para_vencer']) <= 7
                            && fila['dias_para_vencer'] !== null ? 'warn' : 'ok';
    case 'programado': return 'info';
    case 'agotado':    return 'warn';
    default:           return 'neutral';
  }
}

const TIPO_DESCUENTO: Record<string, string> = {
  porcentaje:   'Porcentaje',
  monto_fijo:   'Monto fijo',
  envio_gratis: 'Envío gratis'
};

const TIPO_MARKETING: Record<string, string> = {
  promocion: 'Promoción',
  campana:   'Campaña',
  banner:    'Banner'
};

const VIGENCIA: Record<string, string> = {
  vigente:    'Vigente hoy',
  programado: 'Programado',
  finalizado: 'Finalizado',
  inactivo:   'Desactivado'
};

function colorVigencia(fila: Record<string, any>): ColorChip {
  switch (fila['vigencia']) {
    case 'vigente':    return 'ok';
    case 'programado': return 'info';
    case 'finalizado': return 'neutral';
    default:           return 'warn';
  }
}

function colorAccion(fila: Record<string, any>): ColorChip {
  switch (fila['accion']) {
    case 'INSERT': return 'ok';
    case 'UPDATE': return 'info';
    case 'DELETE': return 'error';
    default:       return 'neutral';
  }
}

const MOTIVO_FALLO: Record<string, string> = {
  password_incorrecto: 'Contraseña incorrecta',
  email_no_registrado: 'Correo no registrado',
  fuera_horario:       'Fuera de su horario',
  usuario_inactivo:    'Usuario desactivado'
};

export const INFORMES_GERENCIA: DefinicionDepartamento = {
  departamento: 'gerencia',
  titulo: 'Informes de Gerencia',
  descripcion: 'Foto del día, vigencias de marketing y el rastro de lo que pasa en el sistema',
  icono: 'flag',

  // ── PRESENTACIÓN (2026-08-16): el piloto de Ventas, ya validado ────────
  // 12 informes en la columna, 9 indicadores como mucho (GER-05 y GER-11 →
  // 3+3+3) y 5 filtros. Comparte con Compras el único informe con GRÁFICO
  // —OTD-GER-13, la previsión de demanda—, cuyo SVG se reescala solo dentro
  // de la columna de contenido.
  selectorVertical: true,
  kpiVidrio: true,

  informes: [

    // ── OTD-GER-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-GER-01',
      endpoint: 'foto-dia',
      fuente: 'simple',
      titulo: 'Foto del día',
      descripcion: 'Qué se pidió, qué se cobró y qué está esperando una decisión. Sin fecha '
                 + 'se consulta hoy; los pendientes son SIEMPRE al momento, no de ese día.',
      icono: 'today',
      roles: ['ADMIN', 'GERENTE'],
      sinPaginar: true,
      // El seed llega hasta el 22/07/2026 en pedidos y el 23/07/2026 en cobros:
      // un día posterior sale vacío y eso es correcto. El resumen trae siempre
      // «Último día con pedidos» para saber a dónde mover el filtro.
      vacio: 'Sin actividad registrada para esta fecha. Elige un día con movimiento — el '
           + 'resumen indica cuál fue el último día con pedidos.',
      filtros: [
        { param: 'fecha', etiqueta: 'Día a consultar (por defecto, hoy)', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'bloque',   titulo: 'Bloque',   tipo: 'chip', color: colorBloque },
        { campo: 'concepto', titulo: 'Concepto', tipo: 'texto', recortar: 42 },
        { campo: 'cantidad', titulo: 'Cantidad', tipo: 'numero' },
        { campo: 'monto',    titulo: 'Monto',    tipo: 'moneda', monto: true },
        { campo: 'nota',     titulo: 'Lectura',  tipo: 'texto', recortar: 46 }
      ]
    },

    // ── OTD-GER-04 ────────────────────────────────────────────────────
    {
      id: 'OTD-GER-04',
      endpoint: 'cupones',
      fuente: 'simple',
      titulo: 'Cupones y usos restantes',
      descripcion: 'Qué cupones están sueltos ahí fuera, cuántos usos les quedan y cuándo '
                 + 'dejan de valer. La situación se recalcula con las mismas reglas del checkout.',
      icono: 'confirmation_number',
      roles: ['ADMIN', 'GERENTE'],
      vacio: 'No hay cupones que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'situacion', etiqueta: 'Situación', tipo: 'select', valorInicial: 'vigente',
          opciones: [
            { valor: 'vigente',    etiqueta: 'Vigentes hoy' },
            { valor: '',           etiqueta: 'Todos' },
            { valor: 'programado', etiqueta: 'Programados' },
            { valor: 'vencido',    etiqueta: 'Vencidos' },
            { valor: 'agotado',    etiqueta: 'Sin usos disponibles' },
            { valor: 'inactivo',   etiqueta: 'Desactivados' }
          ] },
        { param: 'tipo', etiqueta: 'Tipo de descuento', tipo: 'select', opciones: [
          { valor: '',             etiqueta: 'Todos los tipos' },
          { valor: 'porcentaje',   etiqueta: 'Porcentaje' },
          { valor: 'monto_fijo',   etiqueta: 'Monto fijo' },
          { valor: 'envio_gratis', etiqueta: 'Envío gratis' }
        ] },
        { param: 'buscar', etiqueta: 'Código o descripción', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'codigo',              titulo: 'Código',       tipo: 'texto' },
        { campo: 'situacion',           titulo: 'Situación',    tipo: 'chip',
          color: colorSituacionCupon, etiqueta: v => SITUACION_CUPON[v] || v },
        { campo: 'tipo_descuento',      titulo: 'Tipo',         tipo: 'texto',
          etiqueta: v => TIPO_DESCUENTO[v] || v },
        { campo: 'valor',               titulo: 'Valor',        tipo: 'numero' },
        { campo: 'monto_minimo_pedido', titulo: 'Compra mínima', tipo: 'moneda', monto: true },
        { campo: 'usos_actuales',       titulo: 'Usados',       tipo: 'numero' },
        { campo: 'usos_maximos',        titulo: 'Tope',         tipo: 'numero' },
        { campo: 'usos_restantes',      titulo: 'Restantes',    tipo: 'numero' },
        { campo: 'usos_por_cliente',    titulo: 'Por cliente',  tipo: 'numero' },
        { campo: 'fecha_inicio',        titulo: 'Desde',        tipo: 'fecha' },
        { campo: 'fecha_fin',           titulo: 'Hasta',        tipo: 'fecha' },
        { campo: 'dias_para_vencer',    titulo: 'Vence en',     tipo: 'dias' }
      ]
    },

    // ── OTD-GER-06 ────────────────────────────────────────────────────
    {
      id: 'OTD-GER-06',
      endpoint: 'marketing',
      fuente: 'simple',
      titulo: 'Marketing vigente',
      descripcion: 'Qué tenemos en la calle hoy: promociones con los productos que abarcan, '
                 + 'campañas y banners, todos medidos con la misma vara de vigencia.',
      icono: 'campaign',
      roles: ['ADMIN', 'GERENTE'],
      vacio: 'No hay acciones de marketing que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'vigencia', etiqueta: 'Vigencia', tipo: 'select', valorInicial: 'vigente',
          opciones: [
            { valor: 'vigente',    etiqueta: 'Vigentes hoy' },
            { valor: '',           etiqueta: 'Todas' },
            { valor: 'programado', etiqueta: 'Programadas' },
            { valor: 'finalizado', etiqueta: 'Finalizadas' },
            { valor: 'inactivo',   etiqueta: 'Desactivadas' }
          ] },
        { param: 'tipo', etiqueta: 'Tipo de pieza', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Promociones, campañas y banners' },
          { valor: 'promocion', etiqueta: 'Solo promociones' },
          { valor: 'campana',   etiqueta: 'Solo campañas' },
          { valor: 'banner',    etiqueta: 'Solo banners' }
        ] },
        { param: 'buscar', etiqueta: 'Nombre o detalle', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'tipo',           titulo: 'Pieza',      tipo: 'chip',
          color: f => f['tipo'] === 'promocion' ? 'info'
                    : f['tipo'] === 'campana' ? 'warn' : 'neutral',
          etiqueta: v => TIPO_MARKETING[v] || v },
        { campo: 'nombre',         titulo: 'Nombre',     tipo: 'texto', recortar: 32 },
        { campo: 'vigencia',       titulo: 'Vigencia',   tipo: 'chip',
          color: colorVigencia, etiqueta: v => VIGENCIA[v] || v },
        { campo: 'fecha_inicio',   titulo: 'Desde',      tipo: 'fecha' },
        { campo: 'fecha_fin',      titulo: 'Hasta',      tipo: 'fecha' },
        { campo: 'dias_restantes', titulo: 'Le quedan',  tipo: 'dias' },
        { campo: 'tipo_descuento', titulo: 'Descuento',  tipo: 'texto',
          etiqueta: v => TIPO_DESCUENTO[v] || v },
        { campo: 'valor',          titulo: 'Valor',      tipo: 'numero' },
        { campo: 'alcance',        titulo: 'Alcance',    tipo: 'numero' },
        { campo: 'alcance_nota',   titulo: 'Alcance de', tipo: 'texto', recortar: 22 },
        { campo: 'canal',          titulo: 'Canal',      tipo: 'texto', recortar: 16 },
        { campo: 'presupuesto',    titulo: 'Presupuesto', tipo: 'moneda', monto: true },
        { campo: 'detalle',        titulo: 'Detalle',    tipo: 'texto', recortar: 34 }
      ]
    },

    // ── OTD-GER-08 ────────────────────────────────────────────────────
    // SENSIBLE: auditoría del sistema. Solo ADMIN y GERENTE.
    {
      id: 'OTD-GER-08',
      endpoint: 'auditoria',
      fuente: 'simple',
      titulo: 'Auditoría del sistema',
      descripcion: 'Quién hizo qué: aprobaciones, despachos, registros de factura y '
                 + 'moderaciones, con autor, fecha y el antes/después del cambio.',
      icono: 'policy',
      roles: ['ADMIN', 'GERENTE'],
      vacio: 'No hay acciones auditadas que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'usuario', etiqueta: 'Autor (nombre o correo)', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'tabla', etiqueta: 'Registro afectado', tipo: 'select', opciones: [
          { valor: '',                    etiqueta: 'Todas las tablas auditadas' },
          { valor: 'pedido',              etiqueta: 'Pedidos de venta' },
          { valor: 'envio',               etiqueta: 'Envíos' },
          { valor: 'orden_compra',        etiqueta: 'Órdenes de compra' },
          { valor: 'factura_compra',      etiqueta: 'Facturas de compra' },
          { valor: 'resena',              etiqueta: 'Reseñas' },
          { valor: 'pregunta_producto',   etiqueta: 'Preguntas de producto' },
          { valor: 'novedad_envio',       etiqueta: 'Novedades de envío' },
          { valor: 'devolucion_proveedor', etiqueta: 'Devoluciones a proveedor' },
          { valor: 'item_defectuoso',     etiqueta: 'Ítems defectuosos' },
          { valor: 'producto_proveedor',  etiqueta: 'Catálogo proveedor-producto' },
          // Seguridad del motor (scripts 86 y 87). Esta lista tiene que espejar
          // TABLAS_AUDITADAS del backend: una entidad auditada que falte aquí se
          // ve en el listado pero no se puede aislar, y pedirla da 400.
          { valor: 'pg_privilegio',       etiqueta: 'Privilegios del motor (GRANT/REVOKE)' },
          { valor: 'rol_personalizado',   etiqueta: 'Roles propios (alta y baja)' }
        ] },
        { param: 'accion', etiqueta: 'Acción', tipo: 'select', opciones: [
          { valor: '',       etiqueta: 'Todas las acciones' },
          { valor: 'insert', etiqueta: 'Alta' },
          { valor: 'update', etiqueta: 'Modificación' },
          { valor: 'delete', etiqueta: 'Baja' }
        ] },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'fecha_creacion', titulo: 'Cuándo',   tipo: 'fechaHora' },
        { campo: 'autor',          titulo: 'Quién',    tipo: 'texto', recortar: 24 },
        { campo: 'correo',         titulo: 'Correo',   tipo: 'texto', recortar: 26 },
        { campo: 'accion',         titulo: 'Acción',   tipo: 'chip', color: colorAccion },
        { campo: 'tabla',          titulo: 'Registro', tipo: 'texto', recortar: 22 },
        { campo: 'registro_id',    titulo: 'Nº',       tipo: 'texto' },
        { campo: 'antes',          titulo: 'Antes',    tipo: 'texto', recortar: 40 },
        { campo: 'despues',        titulo: 'Después',  tipo: 'texto', recortar: 40 },
        { campo: 'ip',             titulo: 'IP',       tipo: 'texto', recortar: 18 }
      ]
    },

    // ── OTD-GER-09 ────────────────────────────────────────────────────
    // SENSIBLE: intentos de acceso. Solo ADMIN y GERENTE.
    {
      id: 'OTD-GER-09',
      endpoint: 'accesos',
      fuente: 'simple',
      titulo: 'Intentos de acceso',
      descripcion: 'Quién entró y quién no pudo, desde qué IP y por qué. «Fuera de su horario» '
                 + 'no es un error de credenciales: es la ventana del grupo bloqueando el login.',
      icono: 'login',
      roles: ['ADMIN', 'GERENTE'],
      vacio: 'No hay intentos de acceso que coincidan con los filtros elegidos.',
      filtros: [
        { param: 'resultado', etiqueta: 'Resultado', tipo: 'select', opciones: [
          { valor: '',                    etiqueta: 'Todos los intentos' },
          { valor: 'fallido',             etiqueta: 'Solo fallidos' },
          { valor: 'exitoso',             etiqueta: 'Solo exitosos' },
          { valor: 'password_incorrecto', etiqueta: 'Fallo: contraseña incorrecta' },
          { valor: 'email_no_registrado', etiqueta: 'Fallo: correo no registrado' },
          { valor: 'fuera_horario',       etiqueta: 'Fallo: fuera de su horario' },
          { valor: 'usuario_inactivo',    etiqueta: 'Fallo: usuario desactivado' }
        ] },
        { param: 'correo', etiqueta: 'Correo intentado', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'fecha_creacion',   titulo: 'Cuándo',   tipo: 'fechaHora' },
        { campo: 'exitoso',          titulo: 'Resultado', tipo: 'chip',
          color: f => f['exitoso'] ? 'ok' : 'error',
          etiqueta: v => v ? 'Entró' : 'Rechazado' },
        { campo: 'email_intentado',  titulo: 'Correo',   tipo: 'texto', recortar: 30 },
        { campo: 'usuario',          titulo: 'Usuario',  tipo: 'texto', recortar: 24 },
        { campo: 'motivo_fallo',     titulo: 'Motivo',   tipo: 'texto',
          etiqueta: v => MOTIVO_FALLO[v] || v },
        { campo: 'ip',               titulo: 'IP',       tipo: 'texto', recortar: 22 },
        { campo: 'user_agent',       titulo: 'Navegador', tipo: 'texto', recortar: 34 }
      ]
    },

    // ── OTD-GER-02 ── COMPUESTO: la fuente es ClickHouse ──────────────
    {
      id: 'OTD-GER-02',
      endpoint: 'balanza',
      fuente: 'compuesto',
      titulo: 'Balanza mensual: cobros contra pagos',
      descripcion: 'El dinero que ENTRA por cobros de cliente contra el que SALE hacia '
                 + 'proveedores, mes a mes. Mide CAJA —movimiento real de dinero—, no lo '
                 + 'devengado: la mitad devengada del lado de compras llega con las órdenes '
                 + 'de compra en la siguiente fase del almacén. El saldo sale muy negativo y '
                 + 'no es un error: el abastecimiento del período pagó mercancía que todavía '
                 + 'no se ha vendido. Los cobros rechazados van en su propia columna y nunca '
                 + 'se suman al ingreso.',
      icono: 'balance',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'No hubo movimiento de dinero en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'mes',              titulo: 'Mes',     tipo: 'texto' },
        { campo: 'cobrado',          titulo: 'Cobrado', tipo: 'moneda', monto: true },
        { campo: 'cobros',           titulo: 'Cobros',  tipo: 'numero' },
        { campo: 'pagado_proveedor', titulo: 'Pagado a proveedores', tipo: 'moneda',
          monto: true },
        { campo: 'pagos',            titulo: 'Pagos',   tipo: 'numero' },
        { campo: 'saldo',            titulo: 'Saldo',   tipo: 'moneda', monto: true },
        { campo: 'cobertura_pct',    titulo: 'Cobertura del pago', tipo: 'porcentaje' },
        { campo: 'cobros_fallidos',  titulo: 'Rechazados', tipo: 'numero' },
        { campo: 'monto_fallido',    titulo: 'Monto rechazado', tipo: 'moneda', monto: true },
        { campo: 'tasa_rechazo_pct', titulo: '% de rechazo',    tipo: 'porcentaje' },
        { campo: 'pagos_a_tiempo',   titulo: 'Pagos puntuales', tipo: 'numero' },
        { campo: 'puntualidad_pct',  titulo: '% puntualidad',   tipo: 'porcentaje' }
      ]
    },

    // ── OTD-GER-05 ── COMPUESTO: la fuente es ClickHouse ──────────────
    // Par histórico de OTD-GER-04 (arriba, PostgreSQL): aquél da la foto de
    // vigencia de hoy; éste, el costo real del canje mes a mes.
    {
      id: 'OTD-GER-05',
      endpoint: 'descuento-cupones',
      fuente: 'compuesto',
      titulo: 'Descuento otorgado por cupón',
      descripcion: 'Qué cupones canjearon los clientes y cuánto le costaron al negocio, mes a '
                 + 'mes. «Descuento canjeado» es lo que registró el canje y «Descuento en el '
                 + 'pedido» lo que la cabecera del pedido acabó descontando: casi siempre '
                 + 'coinciden, y las dos columnas están para que cualquier diferencia se vea. '
                 + 'El porcentaje se mide sobre la venta que trajo ese cupón —de cada 100 '
                 + 'dólares vendidos con él, cuántos costó—, no sobre la venta total del mes. '
                 + 'Excluye los pedidos cancelados.',
      icono: 'local_activity',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'No hubo canjes de cupón en el período elegido.',
      filtros: [
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'cupon', etiqueta: 'Código del cupón', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'mes',      titulo: 'Mes',   tipo: 'texto' },
        { campo: 'cupon',    titulo: 'Cupón', tipo: 'chip', color: () => 'info' },
        { campo: 'usos',     titulo: 'Canjes',   tipo: 'numero' },
        { campo: 'clientes', titulo: 'Clientes', tipo: 'numero' },
        { campo: 'descuento_canjeado',  titulo: 'Descuento canjeado', tipo: 'moneda',
          monto: true },
        { campo: 'descuento_en_pedido', titulo: 'Descuento en el pedido', tipo: 'moneda',
          monto: true },
        { campo: 'venta_asociada',      titulo: 'Venta con cupón', tipo: 'moneda',
          monto: true },
        { campo: 'descuento_sobre_venta_pct', titulo: '% sobre esa venta',
          tipo: 'porcentaje' },
        { campo: 'descuento_promedio',  titulo: 'Descuento medio', tipo: 'moneda',
          monto: true },
        { campo: 'ticket_promedio',     titulo: 'Ticket medio',    tipo: 'moneda',
          monto: true },
        { campo: 'participacion_mes_pct', titulo: '% del mes', tipo: 'porcentaje' }
      ]
    },

    // ══ FASE 4 · margen, descuento y efecto de las promociones ════════
    // Los cuatro llevan DINERO y ninguno lo respalda el motor: el corte lo
    // hace la ruta (ADMIN/GERENTE/ANALISTA), enumerada por nombre.

    // ── OTD-GER-03 ── COMPUESTO: fact_venta_linea ─────────────────────
    {
      id: 'OTD-GER-03',
      endpoint: 'margen-categoria',
      fuente: 'compuesto',
      titulo: 'Ganancia por categoría',
      descripcion: 'Qué categorías dejan más ganancia, sobre la venta NETA: ya lleva '
                 + 'descontadas las dos capas de descuento y excluido el IVA. El grano de '
                 + 'origen es la LÍNEA, porque la categoría es del producto y no del '
                 + 'pedido. El margen se calcula contra el costo VIGENTE — el sistema no '
                 + 'guarda histórico de costos.',
      icono: 'savings',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay ventas con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',      etiqueta: 'Categoría' },
          { valor: 'mes',   etiqueta: 'Evolución mensual' },
          { valor: 'marca', etiqueta: 'Marca' }
        ] },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'etiqueta',            titulo: 'Categoría / período', tipo: 'texto',
          recortar: 26 },
        { campo: 'pedidos',             titulo: 'Pedidos',      tipo: 'numero' },
        { campo: 'lineas',              titulo: 'Líneas',       tipo: 'numero' },
        { campo: 'unidades',            titulo: 'Unidades',     tipo: 'numero' },
        { campo: 'productos',           titulo: 'Productos',    tipo: 'numero' },
        { campo: 'venta_bruta',         titulo: 'Venta bruta',  tipo: 'moneda', monto: true },
        { campo: 'descuentos',          titulo: 'Descuentos',   tipo: 'moneda', monto: true },
        { campo: 'descuento_pct',       titulo: '% descuento',  tipo: 'porcentaje' },
        { campo: 'venta',               titulo: 'Venta neta',   tipo: 'moneda', monto: true },
        { campo: 'costo',               titulo: 'Costo',        tipo: 'moneda', monto: true },
        { campo: 'ganancia',            titulo: 'Ganancia',     tipo: 'moneda', monto: true },
        { campo: 'margen_pct',          titulo: 'Margen',       tipo: 'porcentaje' },
        { campo: 'ganancia_por_unidad', titulo: 'Ganancia / ud.', tipo: 'moneda',
          monto: true }
      ]
    },

    // ── OTD-GER-10 ── COMPUESTO: fact_venta_linea ─────────────────────
    {
      id: 'OTD-GER-10',
      endpoint: 'margen-producto',
      fuente: 'compuesto',
      titulo: 'Margen producto por producto',
      descripcion: 'La vista fina de la ganancia, con buscador por nombre o SKU. Solo '
                 + 'aparecen los productos CON venta en el período: uno sin ventas no '
                 + 'tiene margen realizado, y ponerlo con cero lo dejaría al final del '
                 + 'ranking como si vendiera sin ganancia. Misma salvedad de costo '
                 + 'vigente que la vista por categoría.',
      icono: 'price_check',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'Ningún producto vendió con esos filtros.',
      filtros: [
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'buscar', etiqueta: 'Producto o SKU', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'producto_nombre',      titulo: 'Producto',    tipo: 'texto', recortar: 32 },
        { campo: 'sku',                  titulo: 'SKU',         tipo: 'texto', recortar: 14 },
        { campo: 'categoria',            titulo: 'Categoría',   tipo: 'texto', recortar: 16 },
        { campo: 'unidades',             titulo: 'Unidades',    tipo: 'numero' },
        { campo: 'pedidos',              titulo: 'Pedidos',     tipo: 'numero' },
        { campo: 'venta',                titulo: 'Venta neta',  tipo: 'moneda', monto: true },
        { campo: 'descuentos',           titulo: 'Descuentos',  tipo: 'moneda', monto: true },
        { campo: 'costo',                titulo: 'Costo',       tipo: 'moneda', monto: true },
        { campo: 'ganancia',             titulo: 'Ganancia',    tipo: 'moneda', monto: true },
        { campo: 'margen_pct',           titulo: 'Margen',      tipo: 'porcentaje' },
        { campo: 'precio_medio',         titulo: 'Precio medio', tipo: 'moneda',
          monto: true },
        { campo: 'ganancia_por_unidad',  titulo: 'Ganancia / ud.', tipo: 'moneda',
          monto: true },
        { campo: 'lineas_con_promocion', titulo: 'Líneas en promo', tipo: 'numero' }
      ]
    },

    // ── OTD-GER-11 ── COMPUESTO: fact_venta_linea ─────────────────────
    {
      id: 'OTD-GER-11',
      endpoint: 'descuento-total',
      fuente: 'compuesto',
      titulo: 'Descuento total entregado',
      descripcion: 'Las DOS capas del descuento —promoción por línea y cupón prorrateado— '
                 + 'separadas y sumadas. Se deciden en sitios distintos: la promo la pone '
                 + 'Marketing y el cupón lo elige el cliente. Ojo al KPI «Ingreso '
                 + 'perdido»: cada dólar descontado cuesta 1,15 porque el IVA se '
                 + 'recalcula sobre la base rebajada.',
      icono: 'local_offer',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'No se entregó descuento con esos filtros.',
      filtros: [
        { param: 'agrupar', etiqueta: 'Ver por', tipo: 'select', opciones: [
          { valor: '',          etiqueta: 'Mes' },
          { valor: 'producto',  etiqueta: 'Producto' },
          { valor: 'categoria', etiqueta: 'Categoría' }
        ] },
        { param: 'canal', etiqueta: 'Canal', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los canales' },
          { valor: 'web',      etiqueta: 'Tienda en línea' },
          { valor: 'tienda',   etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ] },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' },
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'etiqueta',             titulo: 'Mes / producto', tipo: 'texto',
          recortar: 32 },
        { campo: 'pedidos',              titulo: 'Pedidos',     tipo: 'numero' },
        { campo: 'lineas',               titulo: 'Líneas',      tipo: 'numero' },
        { campo: 'unidades',             titulo: 'Unidades',    tipo: 'numero' },
        { campo: 'venta_bruta',          titulo: 'Venta bruta', tipo: 'moneda', monto: true },
        { campo: 'promocion',            titulo: 'Por promoción', tipo: 'moneda',
          monto: true },
        { campo: 'cupon',                titulo: 'Por cupón',   tipo: 'moneda', monto: true },
        { campo: 'descuento',            titulo: 'Descuento total', tipo: 'moneda',
          monto: true },
        { campo: 'descuento_pct',        titulo: '% sobre la venta', tipo: 'porcentaje' },
        { campo: 'venta',                titulo: 'Venta neta',  tipo: 'moneda', monto: true },
        { campo: 'ganancia',             titulo: 'Ganancia',    tipo: 'moneda', monto: true },
        { campo: 'margen_pct',           titulo: 'Margen',      tipo: 'porcentaje' },
        { campo: 'lineas_con_promocion', titulo: 'Líneas con promo', tipo: 'numero' },
        { campo: 'lineas_con_cupon',     titulo: 'Líneas con cupón', tipo: 'numero' },
        { campo: 'excepciones',          titulo: 'Sin factura', tipo: 'chip',
          color: f => Number(f['excepciones']) > 0 ? 'warn' : 'neutral' }
      ]
    },

    // ── OTD-GER-07 ── COMPUESTO: dim_promocion_producto × ventas ──────
    // MUESTRA DÉBIL DECLARADA (catálogo: REQUIERE VOLUMEN). El orden es por
    // VOLUMEN y no por la variación, y las dos columnas de «Líneas» van
    // antes que el porcentaje a propósito.
    {
      id: 'OTD-GER-07',
      endpoint: 'efecto-promociones',
      fuente: 'compuesto',
      titulo: 'Efecto de las promociones: antes vs durante',
      descripcion: 'Unidades por DÍA del producto antes de su promoción y durante ella. '
                 + 'MUESTRA DÉBIL: muchas filas se calculan sobre una o dos ventas, así '
                 + 'que «Líneas durante» y «Líneas antes» son el denominador y hay que '
                 + 'mirarlos ANTES que la variación. La tabla se ordena por volumen y no '
                 + 'por la variación, precisamente para no poner arriba los casos que no '
                 + 'se sostienen.',
      icono: 'campaign',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      vacio: 'No hay pares promoción-producto con esos filtros.',
      filtros: [
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'buscar', etiqueta: 'Producto', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'producto',             titulo: 'Producto',    tipo: 'texto', recortar: 28 },
        { campo: 'promocion',            titulo: 'Promoción',   tipo: 'texto', recortar: 24 },
        { campo: 'categoria',            titulo: 'Categoría',   tipo: 'texto', recortar: 16 },
        { campo: 'inicio',               titulo: 'Desde',       tipo: 'fecha' },
        { campo: 'fin',                  titulo: 'Hasta',       tipo: 'fecha' },
        { campo: 'dias_ventana',         titulo: 'Días de promo', tipo: 'numero' },
        { campo: 'lineas_durante',       titulo: 'Líneas durante (n)', tipo: 'chip',
          color: f => Number(f['lineas_durante']) >= 5 ? 'ok'
                    : Number(f['lineas_durante']) > 0 ? 'warn' : 'error' },
        { campo: 'lineas_antes',         titulo: 'Líneas antes (n)', tipo: 'numero' },
        { campo: 'lineas_con_descuento', titulo: 'Con descuento', tipo: 'numero' },
        { campo: 'uds_dia_durante',      titulo: 'Uds./día durante', tipo: 'numero' },
        { campo: 'uds_dia_antes',        titulo: 'Uds./día antes', tipo: 'numero' },
        { campo: 'variacion_pct',        titulo: 'Variación',   tipo: 'porcentaje' },
        { campo: 'unidades_durante',     titulo: 'Uds. durante', tipo: 'numero' },
        { campo: 'unidades_antes',       titulo: 'Uds. antes',  tipo: 'numero' },
        { campo: 'venta_durante',        titulo: 'Venta durante', tipo: 'moneda',
          monto: true },
        { campo: 'descuento_aplicado',   titulo: 'Descuento',   tipo: 'moneda', monto: true }
      ]
    },

    // ── OTD-GER-13 ── PREDICTIVO: fact_prevision_demanda (fase E2, §5.1) ──
    // El único informe del catálogo que NO describe lo que pasó sino lo que se
    // espera, y el único que aparece en DOS departamentos. Se declara una sola
    // vez en `prevision.informe.ts` y Compras importa la misma función: mismo
    // dato, mismos KPI, misma salvedad, y lo único que cambia es el reparto de
    // roles, que espeja SecurityConfig.
    // Aquí sirve a D-10.1: la previsión con la que se fijan las metas del
    // próximo período.
    informePrevisionDemanda(['ADMIN', 'GERENTE', 'ANALISTA'])
  ]
};
