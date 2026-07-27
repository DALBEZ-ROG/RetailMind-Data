import { ColorChip, DefinicionDepartamento } from '../../../../core/models/informe.model';

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
 * Los COMPUESTOS de Gerencia (balanza mensual — GER-02, ganancia por categoría
 * — GER-03, descuento por cupón — GER-05, efecto de las promociones — GER-07,
 * margen por período — GER-10, costo de los descuentos — GER-11) NO están
 * aquí: son de la fase ETL → ClickHouse.
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
  informes: [

    // ── OTD-GER-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-GER-01',
      endpoint: 'foto-dia',
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
          { valor: 'producto_proveedor',  etiqueta: 'Catálogo proveedor-producto' }
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
    }
  ]
};
