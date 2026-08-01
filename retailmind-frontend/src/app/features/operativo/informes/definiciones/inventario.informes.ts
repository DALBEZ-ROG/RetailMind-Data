import { ColorChip, DefinicionDepartamento, FiltroInforme } from '../../../../core/models/informe.model';

/**
 * INFORMES TÁCTICOS DE INVENTARIO / BODEGA — los siete objetivos SIMPLES del
 * catálogo (`docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §5).
 *
 * Este archivo es TODO lo que hay que escribir para la pantalla: la
 * `InformesDepartamentoComponent` genérica pinta los filtros, la tabla, el
 * resumen y la paginación a partir de estas declaraciones. Ni un componente,
 * ni un servicio, ni un estilo nuevo (ver `docs/tactico/PATRON_INFORMES.md`).
 *
 * Desde la Fase 3B del pipeline ETL (2026-07-31) están también los TRES
 * objetivos COMPUESTOS del departamento, que se sirven desde ClickHouse
 * (`retailmind_dwh`) en vez de PostgreSQL: OTD-INV-04 (rotación por
 * categoría), OTD-INV-09 (capital inmovilizado mes a mes) y OTD-INV-10
 * (mermas y sobrantes por motivo). Se declaran EXACTAMENTE igual que los
 * simples —el sobre es el mismo— y la pantalla genérica añade sola lo que
 * traen de más: la marca de agua «Datos al …», el aviso de degradación si el
 * almacén analítico no responde, y la SALVEDAD metodológica de OTD-INV-09.
 *
 * SEGREGACIÓN FINANCIERA: BODEGA aparece en `roles` de los informes de
 * cantidades y NO en los DOS con dinero — OTD-INV-07 (valor del inventario) y
 * OTD-INV-09 (capital inmovilizado). OTD-INV-10 es el caso MIXTO: BODEGA lo ve
 * en cantidades y las columnas `monto: true` llegan vacías porque el backend
 * ni siquiera las selecciona para su rol. Esto espeja SecurityConfig — que es
 * quien realmente decide — y evita disparar una petición que la API negaría
 * con 403.
 */

/** Bodegas activas del sistema (`bodega.codigo`). El backend filtra por código. */
const FILTRO_BODEGA: FiltroInforme = {
  param: 'bodega', etiqueta: 'Bodega', tipo: 'select', opciones: [
    { valor: '',        etiqueta: 'Todas las bodegas' },
    { valor: 'BOD-01',  etiqueta: 'Bodega Central Quevedo' },
    { valor: 'BOD-02',  etiqueta: 'Bodega Norte' }
  ]
};

/** Buscador de producto: aplica mientras se escribe (debounce del componente). */
const FILTRO_PRODUCTO: FiltroInforme = {
  param: 'buscar', etiqueta: 'SKU o nombre del producto', tipo: 'texto',
  debounce: true, ancho: 'ancho'
};

/** Rango del período, común a los tres informes COMPUESTOS. */
const FILTRO_DESDE: FiltroInforme = { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' };
const FILTRO_HASTA: FiltroInforme = { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' };

/** Rojo cuando la variante está agotada; ámbar mientras solo esté por debajo. */
function colorFaltante(fila: Record<string, any>): ColorChip {
  return Number(fila['stock_actual']) === 0 ? 'error' : 'warn';
}

/** Gravedad del sobre-stock por cuánto excede el tope. */
function colorExceso(fila: Record<string, any>): ColorChip {
  return Number(fila['ocupacion_pct']) >= 200 ? 'error' : 'warn';
}

/** El signo del movimiento manda: entra (verde) o sale (rojo). */
function colorMovimiento(fila: Record<string, any>): ColorChip {
  return Number(fila['cantidad_con_signo']) >= 0 ? 'ok' : 'error';
}

function colorAjuste(fila: Record<string, any>): ColorChip {
  if (fila['estado'] === 'anulado') { return 'neutral'; }
  return Number(fila['unidades_netas']) < 0 ? 'error' : 'ok';
}

// ── Colores de los informes COMPUESTOS (Fase 3B del ETL) ────────────────

/**
 * Rotación: rojo cuando la categoría está PARADA (cero ventas en el período),
 * que es media respuesta de OTD-INV-04 y la fila que hay que mirar primero.
 * Ámbar por debajo de media vuelta, verde a partir de ahí.
 */
function colorRotacion(fila: Record<string, any>): ColorChip {
  const r = Number(fila['rotacion_veces']);
  if (!r) { return 'error'; }
  return r < 0.5 ? 'warn' : 'ok';
}

/**
 * Variación del capital inmovilizado. NO se pinta el crecimiento de verde:
 * que la bodega acumule más capital no es bueno ni malo por sí mismo, y
 * colorearlo tomaría partido. Azul sube, neutro estable, ámbar baja fuerte.
 */
function colorVariacion(fila: Record<string, any>): ColorChip {
  const v = fila['variacion_pct'];
  if (v === null || v === undefined) { return 'neutral'; }
  const n = Number(v);
  if (n > 5) { return 'info'; }
  if (n < -5) { return 'warn'; }
  return 'neutral';
}

/** Un ajuste anulado no cuenta: su contramovimiento ya lo dejó en cero. */
function colorEstadoAjuste(fila: Record<string, any>): ColorChip {
  return fila['estado'] === 'anulado' ? 'neutral' : 'info';
}

/** Neto del motivo: perdió (rojo), ganó (verde) o quedó en nada (neutro). */
function colorNetoAjuste(fila: Record<string, any>): ColorChip {
  const n = Number(fila['unidades_netas']);
  if (n < 0) { return 'error'; }
  return n > 0 ? 'ok' : 'neutral';
}

function colorTransferencia(fila: Record<string, any>): ColorChip {
  switch (fila['estado']) {
    case 'recibida':    return 'ok';
    case 'en_transito': return 'info';
    case 'cancelada':   return 'error';
    default:            return 'warn';   // pendiente: aún no salió de origen
  }
}

const ESTADO_TRANSFERENCIA: Record<string, string> = {
  pendiente:   'Pendiente de envío',
  en_transito: 'En camino',
  recibida:    'Recibida',
  cancelada:   'Cancelada'
};

const TIPO_AJUSTE: Record<string, string> = {
  positivo: 'Sobrante (suma)',
  negativo: 'Merma (resta)',
  conteo:   'Conteo físico'
};

/** Origen del movimiento de kardex (`movimiento_inventario.referencia_tipo`). */
const ORIGEN_MOVIMIENTO: Record<string, string> = {
  pedido:              'Pedido de venta',
  recepcion_mercancia: 'Recepción de compra',
  inventario_inicial:  'Apertura de inventario',
  transferencia_bodega: 'Transferencia',
  ajuste_inventario:   'Ajuste',
  devolucion:          'Devolución de cliente',
  devolucion_proveedor: 'Devolución a proveedor',
  item_defectuoso:     'Producto defectuoso'
};

export const INFORMES_INVENTARIO: DefinicionDepartamento = {
  departamento: 'inventario',
  titulo: 'Informes de Inventario',
  descripcion: 'Control de existencias, reposición, movimientos y capital almacenado',
  icono: 'warehouse',
  informes: [

    // ── OTD-INV-01 ────────────────────────────────────────────────────
    {
      id: 'OTD-INV-01',
      endpoint: 'bajo-minimo',
      titulo: 'Productos bajo mínimo',
      descripcion: 'Variantes que cayeron por debajo de su tope mínimo, con el faltante '
                 + 'que hay que reponer en cada bodega.',
      icono: 'production_quantity_limits',
      roles: ['ADMIN', 'GERENTE', 'BODEGA', 'COMPRAS'],
      vacio: 'Ninguna variante con mínimo definido está por debajo de su tope.',
      filtros: [FILTRO_BODEGA, FILTRO_PRODUCTO],
      columnas: [
        { campo: 'sku',              titulo: 'SKU',          tipo: 'texto' },
        { campo: 'producto',         titulo: 'Producto',     tipo: 'texto', recortar: 30 },
        { campo: 'bodega',           titulo: 'Bodega',       tipo: 'texto', recortar: 22 },
        { campo: 'stock_actual',     titulo: 'Existencia',   tipo: 'chip', color: colorFaltante },
        { campo: 'stock_reservado',  titulo: 'Apartado',     tipo: 'numero' },
        { campo: 'stock_disponible', titulo: 'Disponible',   tipo: 'numero' },
        { campo: 'stock_minimo',     titulo: 'Mínimo',       tipo: 'numero' },
        { campo: 'faltante',         titulo: 'Faltante',     tipo: 'numero' },
        { campo: 'cobertura_pct',    titulo: 'Cobertura',    tipo: 'porcentaje' }
      ]
    },

    // ── OTD-INV-02 ────────────────────────────────────────────────────
    {
      id: 'OTD-INV-02',
      endpoint: 'stock-bodega',
      titulo: 'Stock actual por bodega',
      descripcion: 'Existencias de cada variante en cada almacén, separando lo apartado '
                 + 'para pedidos de lo realmente disponible.',
      icono: 'warehouse',
      roles: ['ADMIN', 'GERENTE', 'BODEGA', 'COMPRAS', 'VENDEDOR'],
      vacio: 'Ninguna variante coincide con los filtros elegidos.',
      filtros: [
        FILTRO_BODEGA,
        { param: 'situacion', etiqueta: 'Situación', tipo: 'select', opciones: [
          { valor: '',            etiqueta: 'Todas' },
          { valor: 'con_stock',   etiqueta: 'Con existencias' },
          { valor: 'sin_stock',   etiqueta: 'Agotadas' },
          { valor: 'con_reserva', etiqueta: 'Con unidades apartadas' }
        ] },
        FILTRO_PRODUCTO
      ],
      columnas: [
        { campo: 'sku',              titulo: 'SKU',        tipo: 'texto' },
        { campo: 'producto',         titulo: 'Producto',   tipo: 'texto', recortar: 32 },
        { campo: 'bodega',           titulo: 'Bodega',     tipo: 'texto', recortar: 22 },
        { campo: 'stock_actual',     titulo: 'Existencia', tipo: 'numero' },
        { campo: 'stock_reservado',  titulo: 'Apartado',   tipo: 'numero' },
        { campo: 'stock_disponible', titulo: 'Disponible', tipo: 'numero' },
        { campo: 'stock_minimo',     titulo: 'Mínimo',     tipo: 'numero' },
        { campo: 'stock_maximo',     titulo: 'Máximo',     tipo: 'numero' },
        { campo: 'fecha_actualizacion', titulo: 'Últ. movimiento', tipo: 'fecha' }
      ]
    },

    // ── OTD-INV-03 ────────────────────────────────────────────────────
    {
      id: 'OTD-INV-03',
      endpoint: 'kardex',
      titulo: 'Kardex de un producto',
      descripcion: 'Historial de entradas y salidas: qué se movió, cuándo, por qué y con '
                 + 'qué saldo quedó. Escribe el SKU o el nombre para seguir un producto.',
      icono: 'receipt_long',
      roles: ['ADMIN', 'GERENTE', 'BODEGA'],
      vacio: 'No hay movimientos de kardex con esos filtros.',
      filtros: [
        FILTRO_PRODUCTO,
        FILTRO_BODEGA,
        // `naturaleza` es la FAMILIA que declara tipo_movimiento, no el signo:
        // 'salida_ajuste' es de familia «ajuste» aunque reste. Las etiquetas lo
        // dicen para que nadie combine «Salidas» + «Salida por ajuste» y crea
        // que el informe está roto cuando devuelve 0 filas.
        { param: 'naturaleza', etiqueta: 'Familia', tipo: 'select', opciones: [
          { valor: '',              etiqueta: 'Todas las familias' },
          { valor: 'entrada',       etiqueta: 'Compras y devoluciones (+)' },
          { valor: 'salida',        etiqueta: 'Ventas y devoluciones a proveedor (−)' },
          { valor: 'ajuste',        etiqueta: 'Ajustes (±)' },
          { valor: 'transferencia', etiqueta: 'Transferencias (±)' }
        ] },
        { param: 'tipo', etiqueta: 'Tipo de movimiento', tipo: 'select', opciones: [
          { valor: '',                             etiqueta: 'Todos los tipos' },
          { valor: 'entrada_compra',               etiqueta: 'Entrada por compra' },
          { valor: 'entrada_ajuste',               etiqueta: 'Entrada por ajuste' },
          { valor: 'entrada_transferencia',        etiqueta: 'Entrada por transferencia' },
          { valor: 'entrada_devolucion_cliente',   etiqueta: 'Entrada por devolución de cliente' },
          { valor: 'entrada_reposicion_proveedor', etiqueta: 'Entrada por reposición de proveedor' },
          { valor: 'salida_venta',                 etiqueta: 'Salida por venta' },
          { valor: 'salida_ajuste',                etiqueta: 'Salida por ajuste' },
          { valor: 'salida_transferencia',         etiqueta: 'Salida por transferencia' },
          { valor: 'salida_devolucion_proveedor',  etiqueta: 'Salida por devolución a proveedor' }
        ] },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'fecha_creacion',     titulo: 'Fecha',      tipo: 'fechaHora' },
        { campo: 'sku',                titulo: 'SKU',        tipo: 'texto' },
        { campo: 'producto',           titulo: 'Producto',   tipo: 'texto', recortar: 26 },
        { campo: 'bodega',             titulo: 'Bodega',     tipo: 'texto', recortar: 20 },
        { campo: 'tipo',               titulo: 'Movimiento', tipo: 'texto', recortar: 26 },
        { campo: 'cantidad_con_signo', titulo: 'Cantidad',   tipo: 'chip', color: colorMovimiento },
        { campo: 'stock_anterior',     titulo: 'Saldo antes', tipo: 'numero' },
        { campo: 'stock_nuevo',        titulo: 'Saldo después', tipo: 'numero' },
        { campo: 'referencia_tipo',    titulo: 'Origen',     tipo: 'texto',
          etiqueta: v => ORIGEN_MOVIMIENTO[v] || v },
        { campo: 'referencia_id',      titulo: 'Documento',  tipo: 'texto' }
      ]
    },

    // ── OTD-INV-05 ────────────────────────────────────────────────────
    {
      id: 'OTD-INV-05',
      endpoint: 'ajustes',
      titulo: 'Ajustes de inventario',
      descripcion: 'Mercancía perdida o sobrante detectada en los ajustes, con su motivo '
                 + 'y el impacto real en existencias. Un ajuste anulado queda en neto 0.',
      icono: 'tune',
      roles: ['ADMIN', 'GERENTE', 'BODEGA'],
      vacio: 'No hay ajustes de inventario con esos filtros.',
      filtros: [
        { param: 'tipo', etiqueta: 'Tipo', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los tipos' },
          { valor: 'negativo', etiqueta: 'Merma (resta)' },
          { valor: 'positivo', etiqueta: 'Sobrante (suma)' },
          { valor: 'conteo',   etiqueta: 'Conteo físico' }
        ] },
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los estados' },
          { valor: 'aplicado', etiqueta: 'Aplicado' },
          { valor: 'anulado',  etiqueta: 'Anulado' },
          { valor: 'borrador', etiqueta: 'Borrador' }
        ] },
        FILTRO_BODEGA,
        // El motivo es texto libre en la BD (lo escribe quien hace el ajuste):
        // por eso se busca, no se elige de una lista cerrada que mentiría.
        { param: 'motivo', etiqueta: 'Buscar en el motivo', tipo: 'texto',
          debounce: true, ancho: 'ancho' },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'fecha_aplicacion', titulo: 'Aplicado',    tipo: 'fecha' },
        { campo: 'tipo',             titulo: 'Tipo',        tipo: 'texto',
          etiqueta: v => TIPO_AJUSTE[v] || v },
        { campo: 'estado',           titulo: 'Estado',      tipo: 'chip',
          color: f => f['estado'] === 'anulado' ? 'neutral' : 'ok' },
        { campo: 'bodega',           titulo: 'Bodega',      tipo: 'texto', recortar: 22 },
        { campo: 'skus',             titulo: 'SKU',         tipo: 'texto', recortar: 24 },
        { campo: 'unidades_netas',   titulo: 'Impacto neto', tipo: 'chip', color: colorAjuste },
        { campo: 'motivo',           titulo: 'Motivo',      tipo: 'texto', recortar: 48 },
        { campo: 'responsable',      titulo: 'Responsable', tipo: 'texto', recortar: 22 }
      ]
    },

    // ── OTD-INV-06 ────────────────────────────────────────────────────
    {
      id: 'OTD-INV-06',
      endpoint: 'transferencias',
      titulo: 'Transferencias entre bodegas',
      descripcion: 'Traslados de mercancía: cuáles van en camino y cuáles ya se '
                 + 'recibieron. Una transferencia pendiente aún no movió stock.',
      icono: 'swap_horiz',
      roles: ['ADMIN', 'GERENTE', 'BODEGA'],
      vacio: 'No hay transferencias con ese estado.',
      filtros: [
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [
          { valor: '',            etiqueta: 'Todos los estados' },
          { valor: 'pendiente',   etiqueta: 'Pendiente de envío' },
          { valor: 'en_transito', etiqueta: 'En camino' },
          { valor: 'recibida',    etiqueta: 'Recibida' },
          { valor: 'cancelada',   etiqueta: 'Cancelada' }
        ] },
        { ...FILTRO_BODEGA, etiqueta: 'Bodega (origen o destino)' },
        { param: 'desde', etiqueta: 'Desde', tipo: 'fecha' },
        { param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' }
      ],
      columnas: [
        { campo: 'fecha_creacion',  titulo: 'Solicitada',  tipo: 'fecha' },
        { campo: 'estado',          titulo: 'Estado',      tipo: 'chip', color: colorTransferencia,
          etiqueta: v => ESTADO_TRANSFERENCIA[v] || v },
        { campo: 'origen',          titulo: 'Origen',      tipo: 'texto', recortar: 22 },
        { campo: 'destino',         titulo: 'Destino',     tipo: 'texto', recortar: 22 },
        { campo: 'skus',            titulo: 'SKU',         tipo: 'texto', recortar: 24 },
        { campo: 'unidades',        titulo: 'Unidades',    tipo: 'numero' },
        { campo: 'fecha_envio',     titulo: 'Enviada',     tipo: 'fecha' },
        { campo: 'fecha_recepcion', titulo: 'Recibida',    tipo: 'fecha' },
        { campo: 'dias_transito',   titulo: 'En tránsito', tipo: 'dias' },
        { campo: 'solicitante',     titulo: 'Solicitante', tipo: 'texto', recortar: 22 }
      ]
    },

    // ── OTD-INV-07 ────────────────────────────────────────────────────
    // ÚNICO informe de Inventario con DINERO: sin BODEGA ni DESPACHO.
    {
      id: 'OTD-INV-07',
      endpoint: 'valor-inventario',
      titulo: 'Valor del inventario por categoría',
      descripcion: 'Cuánto dinero hay parado en mercancía almacenada, por categoría y '
                 + 'bodega, valorizado al costo vigente de cada variante.',
      icono: 'savings',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay mercancía almacenada que coincida con los filtros.',
      filtros: [
        FILTRO_BODEGA,
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'categoria',        titulo: 'Categoría',   tipo: 'texto', recortar: 26 },
        { campo: 'bodega',           titulo: 'Bodega',      tipo: 'texto', recortar: 22 },
        { campo: 'variantes',        titulo: 'Variantes',   tipo: 'numero' },
        { campo: 'unidades',         titulo: 'Unidades',    tipo: 'numero' },
        { campo: 'valor_costo',      titulo: 'Valor a costo', tipo: 'moneda', monto: true },
        { campo: 'valor_venta',      titulo: 'Valor a venta', tipo: 'moneda', monto: true },
        { campo: 'margen_potencial', titulo: 'Margen potencial', tipo: 'moneda', monto: true },
        { campo: 'margen_pct',       titulo: 'Margen',      tipo: 'porcentaje' }
      ]
    },

    // ── OTD-INV-08 ────────────────────────────────────────────────────
    {
      id: 'OTD-INV-08',
      endpoint: 'sobre-stock',
      titulo: 'Sobre-stock',
      descripcion: 'Variantes por encima del tope máximo deseado, con el exceso que está '
                 + 'ocupando espacio y capital en bodega.',
      icono: 'inventory',
      roles: ['ADMIN', 'GERENTE', 'BODEGA', 'COMPRAS'],
      vacio: 'Ninguna variante con tope definido supera su máximo.',
      filtros: [FILTRO_BODEGA, FILTRO_PRODUCTO],
      columnas: [
        { campo: 'sku',             titulo: 'SKU',        tipo: 'texto' },
        { campo: 'producto',        titulo: 'Producto',   tipo: 'texto', recortar: 30 },
        { campo: 'bodega',          titulo: 'Bodega',     tipo: 'texto', recortar: 22 },
        { campo: 'stock_actual',    titulo: 'Existencia', tipo: 'chip', color: colorExceso },
        { campo: 'stock_reservado', titulo: 'Apartado',   tipo: 'numero' },
        { campo: 'stock_maximo',    titulo: 'Máximo',     tipo: 'numero' },
        { campo: 'exceso',          titulo: 'Exceso',     tipo: 'numero' },
        { campo: 'ocupacion_pct',   titulo: 'Ocupación',  tipo: 'porcentaje' }
      ]
    },

    // ══════════════════════════════════════════════════════════════════
    // INFORMES COMPUESTOS — fuente ClickHouse (`retailmind_dwh`)
    // Fase 3B del pipeline: fact_movimiento_inventario + fact_stock_mensual.
    //
    // Se distinguen de los simples en tres cosas que la pantalla ya sabe
    // pintar sola: marca de agua «Datos al …», degradación con aviso si el
    // almacén no responde, y —OTD-INV-09— la SALVEDAD metodológica.
    // ══════════════════════════════════════════════════════════════════

    // ── OTD-INV-04 ────────────────────────────────────────────────────
    // Sin dinero: BODEGA entra. El backend tampoco selecciona importes.
    {
      id: 'OTD-INV-04',
      endpoint: 'rotacion',
      titulo: 'Rotación por categoría',
      descripcion: 'Qué categorías rotan y cuáles se quedan paradas. La rotación son las '
                 + 'unidades VENDIDAS del período divididas por el stock promedio mensual; '
                 + 'las transferencias entre bodegas y los ajustes no cuentan como rotación.',
      icono: 'autorenew',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA', 'BODEGA'],
      sinPaginar: true,
      vacio: 'No hay mercancía almacenada en el período y los filtros elegidos.',
      filtros: [
        FILTRO_DESDE, FILTRO_HASTA, FILTRO_BODEGA,
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'categoria',             titulo: 'Categoría',   tipo: 'texto', recortar: 26 },
        { campo: 'rotacion_veces',        titulo: 'Rotación',    tipo: 'chip',
          color: colorRotacion,
          etiqueta: (v: any) => `${Number(v).toFixed(2)} ×` },
        { campo: 'dias_cobertura',        titulo: 'Cobertura',   tipo: 'dias' },
        { campo: 'unidades_vendidas',     titulo: 'Uds. vendidas', tipo: 'numero' },
        { campo: 'stock_promedio',        titulo: 'Stock promedio', tipo: 'numero' },
        { campo: 'stock_final',           titulo: 'Stock al cierre', tipo: 'numero' },
        { campo: 'posiciones',            titulo: 'Variantes',   tipo: 'numero' },
        // La diferencia con `unidades_vendidas` son transferencias y ajustes:
        // se muestra para que el criterio del numerador sea comprobable y no
        // haya que creerse la descripción.
        { campo: 'unidades_salida_total', titulo: 'Salidas totales', tipo: 'numero' },
        { campo: 'meses',                 titulo: 'Meses',       tipo: 'numero' }
      ]
    },

    // ── OTD-INV-09 ────────────────────────────────────────────────────
    // DINERO de principio a fin: sin BODEGA, igual que OTD-INV-07.
    // El sobre trae `salvedad` y la pantalla la pinta encima de la tabla.
    {
      id: 'OTD-INV-09',
      endpoint: 'capital-inmovilizado',
      titulo: 'Capital inmovilizado mes a mes',
      descripcion: 'Cómo evoluciona el dinero parado en mercancía almacenada, mes a mes, '
                 + 'para saber si la bodega se está llenando o vaciando de capital. '
                 + 'Valorizado a costo VIGENTE: lee la advertencia antes de la tabla.',
      icono: 'trending_up',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA'],
      sinPaginar: true,
      vacio: 'No hay stock reconstruido en el período y los filtros elegidos.',
      filtros: [
        FILTRO_DESDE, FILTRO_HASTA, FILTRO_BODEGA,
        { param: 'categoria', etiqueta: 'Categoría', tipo: 'texto',
          debounce: true, ancho: 'ancho' }
      ],
      columnas: [
        { campo: 'mes',                       titulo: 'Mes',        tipo: 'texto' },
        { campo: 'valor_cierre',              titulo: 'Capital al cierre',
          tipo: 'moneda', monto: true },
        { campo: 'variacion_valor',           titulo: 'Variación',  tipo: 'moneda',
          monto: true },
        { campo: 'variacion_pct',             titulo: 'Variación %', tipo: 'chip',
          color: colorVariacion,
          etiqueta: (v: any) => (v === null || v === undefined)
            ? '—' : `${Number(v) > 0 ? '+' : ''}${Number(v).toFixed(2)} %` },
        { campo: 'unidades',                  titulo: 'Unidades',   tipo: 'numero' },
        { campo: 'posiciones',                titulo: 'Posiciones', tipo: 'numero' },
        // Cuánta parte del capital de ese mes es mercancía que no se movió.
        { campo: 'posiciones_sin_movimiento', titulo: 'Quietas',    tipo: 'numero' },
        { campo: 'unidades_entradas',         titulo: 'Entradas',   tipo: 'numero' },
        { campo: 'unidades_salidas',          titulo: 'Salidas',    tipo: 'numero' }
      ]
    },

    // ── OTD-INV-10 ────────────────────────────────────────────────────
    // MIXTO: BODEGA ve las cantidades; las columnas de valor solo llegan a
    // ADMIN y GERENTE (el backend ni siquiera las selecciona para el resto,
    // y las columnas `monto: true` se ocultan solas cuando no vienen).
    {
      id: 'OTD-INV-10',
      endpoint: 'mermas',
      titulo: 'Mermas y sobrantes por motivo',
      descripcion: 'Mercancía perdida y sobrante acumulada por período y motivo, para '
                 + 'atacar las causas de la pérdida. Solo ajustes de inventario reales: '
                 + 'la apertura del almacén no es un sobrante.',
      icono: 'report_problem',
      roles: ['ADMIN', 'GERENTE', 'ANALISTA', 'BODEGA'],
      sinPaginar: true,
      vacio: 'No se registraron ajustes de inventario en el período elegido.',
      filtros: [
        FILTRO_DESDE, FILTRO_HASTA, FILTRO_BODEGA,
        { param: 'tipo', etiqueta: 'Tipo de ajuste', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos los tipos' },
          { valor: 'negativo', etiqueta: 'Negativo (merma)' },
          { valor: 'positivo', etiqueta: 'Positivo (sobrante)' },
          { valor: 'conteo',   etiqueta: 'Conteo físico' }
        ] },
        { param: 'estado', etiqueta: 'Estado', tipo: 'select', opciones: [
          { valor: '',         etiqueta: 'Todos' },
          { valor: 'aplicado', etiqueta: 'Aplicado' },
          { valor: 'anulado',  etiqueta: 'Anulado' }
        ] }
      ],
      columnas: [
        { campo: 'motivo',            titulo: 'Motivo',     tipo: 'texto', recortar: 40 },
        { campo: 'tipo',              titulo: 'Tipo',       tipo: 'texto' },
        { campo: 'estado',            titulo: 'Estado',     tipo: 'chip',
          color: colorEstadoAjuste },
        { campo: 'movimientos',       titulo: 'Movs.',      tipo: 'numero' },
        { campo: 'productos',         titulo: 'Productos',  tipo: 'numero' },
        { campo: 'unidades_merma',    titulo: 'Uds. perdidas',  tipo: 'numero' },
        { campo: 'unidades_sobrante', titulo: 'Uds. sobrantes', tipo: 'numero' },
        { campo: 'unidades_netas',    titulo: 'Neto',       tipo: 'chip',
          color: colorNetoAjuste },
        { campo: 'valor_merma',       titulo: 'Valor perdido', tipo: 'moneda', monto: true },
        { campo: 'valor_neto',        titulo: 'Valor neto',    tipo: 'moneda', monto: true },
        { campo: 'primera',           titulo: 'Primero',    tipo: 'fecha' },
        { campo: 'ultima',            titulo: 'Último',     tipo: 'fecha' }
      ]
    }
  ]
};
