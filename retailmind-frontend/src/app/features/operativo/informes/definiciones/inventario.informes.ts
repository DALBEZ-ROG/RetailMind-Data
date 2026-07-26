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
 * Los COMPUESTOS de Inventario (rotación por categoría en el tiempo —
 * OTD-INV-04, evolución mensual del capital almacenado — OTD-INV-09, acumulado
 * de mermas — OTD-INV-10) NO están aquí: son de la fase ETL → ClickHouse.
 *
 * SEGREGACIÓN FINANCIERA: BODEGA aparece en `roles` de los SEIS informes de
 * cantidades y NO en el de valorización (OTD-INV-07), que es el único con
 * dinero. Esto espeja SecurityConfig — que es quien realmente decide — y evita
 * disparar una petición que la API negaría con 403.
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
    }
  ]
};
