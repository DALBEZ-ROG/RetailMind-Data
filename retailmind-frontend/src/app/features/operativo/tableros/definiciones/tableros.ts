import { DefinicionTablero } from '../../../../core/models/tablero.model';

/**
 * DEFINICIONES DE LOS TABLEROS DE DIRECCIÓN — fase E1-A
 * (docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md §4).
 *
 * Un tablero es una entrada de este archivo. La pantalla genérica
 * `TableroComponent` no sabe nada de omnicanalidad ni de rotación: lee este
 * archivo y el sobre del backend, y pinta.
 *
 * Regla que gobierna las columnas: **todo lo que el backend calcula con un
 * denominador se muestra al lado de su denominador**. Por eso el embudo lleva
 * su base en una columna, los tickets llevan `base_tiempos` junto a las horas
 * medias, y ninguna tasa viaja sola.
 */

const DIRECCION = ['ADMIN', 'GERENTE', 'ANALISTA'] as const;

/**
 * El rango arranca VACÍO, y eso significa «todo el histórico».
 *
 * Antes traía `2025-01-01` y `2026-12-31` escritos a mano, y la intención era
 * exactamente ésta: cuando se escribieron, el histórico ERA ese rango. Al
 * crecer los datos a una década (2025-2034) esas dos constantes dejaron de
 * describir el histórico y pasaron a RECORTARLO, sin avisar: el tablero abría
 * mostrando el 20 % de la información con aspecto de estar completa. Un filtro
 * por defecto que caduca es peor que uno ausente, porque no falla — miente.
 *
 * Se dejan en blanco a propósito. El backend omite la condición cuando el
 * filtro no llega (`InformeServiceBase.fecha()` devuelve `null` y `Filtros.y()`
 * no añade el `WHERE`), así que el tablero sirve la serie entera y el usuario
 * acota si quiere. El sobre del backend sigue declarando `primerMesConDato` /
 * `ultimoMesConDato`, de modo que la pantalla dice qué periodo está viendo.
 *
 * Y no sale caro, que era la duda razonable: medido contra la década completa
 * frente a la ventana de dos años que había, los siete tableros NO se degradan
 * —T-2 pasa de 1.495 ms a 1.066 ms, T-3 de 1.185 a 678—. ClickHouse agrega por
 * mes sobre un almacén columnar: los meses de más apenas cuestan, y el recorte
 * no estaba ahorrando nada. La decisión no se toma por elegancia: se toma
 * porque el número dice que no hay que pagar por ella.
 */
const FILTRO_DESDE = {
  param: 'desde', etiqueta: 'Desde', tipo: 'fecha' as const, valorInicial: ''
};
const FILTRO_HASTA = {
  param: 'hasta', etiqueta: 'Hasta', tipo: 'fecha' as const, valorInicial: ''
};

export const TABLEROS: DefinicionTablero[] = [

  // ══════════════════════════════════════════════════════════════════════
  // T-1 · OMNICANAL — OE-06 · D-06.1, D-06.2, D-06.3
  // ══════════════════════════════════════════════════════════════════════
  {
    id: 'T-1',
    clave: 'omnicanal',
    titulo: 'Tablero Omnicanal',
    objetivo: 'OE-06 · Consolidación de la Experiencia Omnicanal',
    descripcion: 'Dónde se refuerza y de dónde se retira capacidad por canal, en cuál de los '
               + 'puntos de caída del recorrido se invierte, y qué medios de cobro se '
               + 'sostienen o se renegocian.',
    icono: 'hub',
    roles: DIRECCION,
    filtros: [
      FILTRO_DESDE, FILTRO_HASTA,
      {
        param: 'canal', etiqueta: 'Canal', tipo: 'select', valorInicial: '',
        opciones: [
          { valor: '', etiqueta: 'Los tres canales' },
          { valor: 'web', etiqueta: 'Tienda en línea' },
          { valor: 'tienda', etiqueta: 'Mostrador' },
          { valor: 'telefono', etiqueta: 'Teléfono' }
        ]
      },
      { param: 'categoria', etiqueta: 'Categoría de producto', tipo: 'texto', debounce: true }
    ],
    bloques: [
      {
        id: 'participacion_canal',
        ejeX: 'periodo', serie: 'canal', valor: 'participacion_pct', valorTipo: 'porcentaje',
        valorEtiqueta: 'Participación de la venta',
        ayuda: 'Cada barra suma 100 % dentro de SU mes: la lectura es el reparto, no el '
             + 'volumen. El volumen absoluto está en la columna de venta de la tabla.',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'canal', titulo: 'Canal', tipo: 'texto' },
          { campo: 'pedidos', titulo: 'Pedidos', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'venta', titulo: 'Venta', tipo: 'moneda', monto: true },
          { campo: 'participacion_pct', titulo: 'Participación', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'ticket_canal',
        ejeX: 'periodo', serie: 'canal', valor: 'ticket_promedio', valorTipo: 'moneda',
        valorEtiqueta: 'Ticket promedio',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'canal', titulo: 'Canal', tipo: 'texto' },
          { campo: 'pedidos', titulo: 'Pedidos', tipo: 'numero' },
          { campo: 'ticket_promedio', titulo: 'Ticket', tipo: 'moneda', monto: true },
          { campo: 'unidades_por_pedido', titulo: 'Uds./pedido', tipo: 'numero' },
          { campo: 'lineas_por_pedido', titulo: 'Líneas/pedido', tipo: 'numero' }
        ]
      },
      {
        id: 'cliente_omnicanal',
        ejeX: 'segmento', valor: 'clientes', valorTipo: 'numero',
        columnas: [
          { campo: 'segmento', titulo: 'Segmento', tipo: 'texto', recortar: 40 },
          { campo: 'clientes', titulo: 'Clientes', tipo: 'numero' },
          { campo: 'clientes_pct', titulo: '% de la cartera activa', tipo: 'porcentaje' },
          { campo: 'pedidos', titulo: 'Pedidos', tipo: 'numero' },
          { campo: 'venta', titulo: 'Venta', tipo: 'moneda', monto: true }
        ]
      },
      {
        id: 'embudo',
        ejeX: 'paso', valor: 'pedidos', valorTipo: 'numero',
        columnas: [
          { campo: 'paso', titulo: 'Paso', tipo: 'texto' },
          { campo: 'pedidos', titulo: 'Pedidos', tipo: 'numero' },
          { campo: 'denominador', titulo: 'Base del paso', tipo: 'numero' },
          { campo: 'tasa_paso_pct', titulo: 'Conversión del paso', tipo: 'porcentaje' },
          { campo: 'perdidos', titulo: 'No pasan', tipo: 'numero' },
          { campo: 'tasa_origen_pct', titulo: '% del origen', tipo: 'porcentaje' },
          { campo: 'nota', titulo: 'Qué hay detrás', tipo: 'texto', recortar: 70 }
        ]
      },
      {
        id: 'cobros_fallidos',
        ejeX: 'periodo', serie: 'motivo', valor: 'intentos', valorTipo: 'numero',
        valorEtiqueta: 'Intentos rechazados',
        columnas: [
          { campo: 'periodo', titulo: 'Mes del intento', tipo: 'texto' },
          { campo: 'motivo', titulo: 'Motivo', tipo: 'texto' },
          { campo: 'intentos', titulo: 'Rechazos', tipo: 'numero' },
          { campo: 'intentos_del_mes', titulo: 'Intentos del mes', tipo: 'numero' },
          { campo: 'pct_del_mes', titulo: '% de los intentos', tipo: 'porcentaje' },
          { campo: 'monto', titulo: 'Monto no cobrado', tipo: 'moneda', monto: true }
        ]
      },
      {
        id: 'mezcla_pago',
        ejeX: 'periodo', serie: 'forma_pago', valor: 'participacion_pct',
        valorTipo: 'porcentaje', valorEtiqueta: 'Participación del cobro',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'forma_pago', titulo: 'Forma de pago', tipo: 'texto' },
          { campo: 'cobros', titulo: 'Cobros', tipo: 'numero' },
          { campo: 'monto', titulo: 'Monto', tipo: 'moneda', monto: true },
          { campo: 'participacion_pct', titulo: 'Participación', tipo: 'porcentaje' }
        ]
      }
    ],
    externos: [
      {
        id: 'carritos_abandonados',
        titulo: 'Carrito abandonado',
        departamento: 'ventas',
        endpoint: 'carritos-abandonados',
        filtros: { estado: 'abandonado', diasMinimos: 0, page: 0, size: 25 },
        // Los roles del informe SIMPLE, que NO son los del tablero: el
        // ANALISTA no está en OTD-VEN-08 y la pantalla no le dispara la
        // llamada para no ensuciar la consola con un 403 que la API daría
        // igual.
        roles: ['ADMIN', 'GERENTE'],
        nota: 'Este bloque NO sale del almacén analítico: el almacén no tiene grano de '
            + 'carrito y el diseño decidió no crear una tabla para 290 filas. Se sirve del '
            + 'informe OTD-VEN-08 sobre PostgreSQL, que ya existía. Efecto colateral: es el '
            + 'único bloque de este tablero que sigue vivo con el almacén apagado.',
        vacio: 'No hay carritos abandonados.',
        columnas: [
          { campo: 'dias_inactivo', titulo: 'Inactivo', tipo: 'dias' },
          { campo: 'ultima_actividad', titulo: 'Última actividad', tipo: 'fecha' },
          { campo: 'cliente', titulo: 'Cliente', tipo: 'texto', recortar: 26 },
          { campo: 'lineas', titulo: 'Líneas', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'contenido', titulo: 'Contenido', tipo: 'texto', recortar: 40 },
          { campo: 'valor', titulo: 'Valor', tipo: 'moneda', monto: true }
        ]
      }
    ]
  },

  // ══════════════════════════════════════════════════════════════════════
  // T-2 · RENTABILIDAD Y ROTACIÓN — OE-07 · D-07.1 … D-07.4
  // ══════════════════════════════════════════════════════════════════════
  {
    id: 'T-2',
    clave: 'rentabilidad',
    titulo: 'Tablero de Rentabilidad y Rotación',
    objetivo: 'OE-07 · Rentabilidad por Volumen y Rotación',
    descripcion: 'Qué se descontinúa, dónde se mueve el precio, cuánto descuento se autoriza '
               + 'y qué capital parado se libera. Es el tablero de las palancas que la '
               + 'dirección controla directamente.',
    icono: 'trending_up',
    roles: DIRECCION,
    filtros: [
      FILTRO_DESDE, FILTRO_HASTA,
      {
        param: 'categoria', etiqueta: 'Categoría', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['margen_categoria'], campo: 'categoria',
                      todos: 'Todas las categorías' }
      },
      {
        param: 'marca', etiqueta: 'Marca', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['matriz_margen_rotacion', 'producto_hueso'], campo: 'marca',
                      todos: 'Todas las marcas' }
      },
      // Sigue siendo de escritura: NINGÚN bloque de este tablero trae la bodega
      // (el sobre-stock que sí la lleva es un bloque de PostgreSQL y se pide
      // solo al abrir su tarjeta). Ver §8.7 de `docs/PATRON_UI.md`.
      { param: 'bodega', etiqueta: 'Bodega (solo stock)', tipo: 'texto', debounce: true }
    ],
    bloques: [
      {
        id: 'margen_categoria',
        ejeX: 'periodo', serie: 'categoria', valor: 'margen', valorTipo: 'moneda',
        valorEtiqueta: 'Margen',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'venta_neta', titulo: 'Venta neta', tipo: 'moneda', monto: true },
          { campo: 'costo', titulo: 'Costo', tipo: 'moneda', monto: true },
          { campo: 'margen', titulo: 'Margen', tipo: 'moneda', monto: true },
          { campo: 'margen_pct', titulo: 'Margen %', tipo: 'porcentaje' },
          { campo: 'participacion_pct', titulo: '% del mes', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'matriz_margen_rotacion',
        x: 'rotacion', y: 'margen_pct', punto: 'producto', grupo: 'cuadrante',
        xEtiqueta: 'Rotación (uds. vendidas / stock medio)',
        yEtiqueta: 'Margen %',
        topFilas: 40,
        ayuda: 'Arriba a la derecha (estrella) se sostiene; abajo a la izquierda (hueso) es '
             + 'la candidata a descontinuar. La cruz son las MEDIANAS del conjunto filtrado, '
             + 'no un umbral del negocio: se mueve con el filtro.',
        columnas: [
          { campo: 'cuadrante', titulo: 'Cuadrante', tipo: 'chip',
            color: f => f['cuadrante'] === 'estrella' ? 'ok'
                      : f['cuadrante'] === 'hueso' ? 'error'
                      : f['cuadrante'] === 'sin_stock' ? 'neutral' : 'warn' },
          { campo: 'sku', titulo: 'SKU', tipo: 'texto' },
          { campo: 'producto', titulo: 'Producto', tipo: 'texto', recortar: 26 },
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'unidades', titulo: 'Uds. vendidas', tipo: 'numero' },
          { campo: 'stock_medio', titulo: 'Stock medio', tipo: 'numero' },
          { campo: 'rotacion', titulo: 'Rotación', tipo: 'numero' },
          { campo: 'margen_pct', titulo: 'Margen %', tipo: 'porcentaje' },
          { campo: 'venta_neta', titulo: 'Venta neta', tipo: 'moneda', monto: true }
        ]
      },
      {
        id: 'producto_hueso',
        ejeX: 'producto', valor: 'capital_retenido', valorTipo: 'moneda',
        topFilas: 25,
        columnas: [
          { campo: 'sku', titulo: 'SKU', tipo: 'texto' },
          { campo: 'producto', titulo: 'Producto', tipo: 'texto', recortar: 28 },
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'stock_actual', titulo: 'Existencia', tipo: 'numero' },
          { campo: 'costo', titulo: 'Costo unitario', tipo: 'moneda', monto: true },
          { campo: 'capital_retenido', titulo: 'Capital retenido', tipo: 'moneda',
            monto: true },
          { campo: 'ultima_venta', titulo: 'Última venta', tipo: 'texto',
            etiqueta: v => v ? String(v) : 'Nunca' },
          { campo: 'dias_sin_venta', titulo: 'Días sin venta', tipo: 'dias' }
        ]
      },
      {
        id: 'descuento_mes',
        ejeX: 'periodo', serie: 'categoria', valor: 'descuento_total', valorTipo: 'moneda',
        valorEtiqueta: 'Descuento entregado',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'venta_bruta', titulo: 'Venta bruta', tipo: 'moneda', monto: true },
          { campo: 'descuento_promocion', titulo: 'Promoción', tipo: 'moneda', monto: true },
          { campo: 'descuento_cupon', titulo: 'Cupón', tipo: 'moneda', monto: true },
          { campo: 'descuento_total', titulo: 'Total', tipo: 'moneda', monto: true },
          { campo: 'descuento_pct', titulo: '% sobre bruta', tipo: 'porcentaje' },
          { campo: 'lineas_excepcion', titulo: 'Líneas sin prorratear', tipo: 'numero' }
        ]
      },
      {
        id: 'descuento_vs_margen',
        ejeX: 'periodo',
        valor: 'descuento_pct', valorTipo: 'porcentaje', valorEtiqueta: '% de descuento',
        valor2: 'margen_pct', valor2Tipo: 'porcentaje', valor2Etiqueta: '% de margen',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'pedidos', titulo: 'Pedidos', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'venta_bruta', titulo: 'Venta bruta', tipo: 'moneda', monto: true },
          { campo: 'descuento', titulo: 'Descuento', tipo: 'moneda', monto: true },
          { campo: 'descuento_pct', titulo: 'Descuento %', tipo: 'porcentaje' },
          { campo: 'venta_neta', titulo: 'Venta neta', tipo: 'moneda', monto: true },
          { campo: 'margen', titulo: 'Margen', tipo: 'moneda', monto: true },
          { campo: 'margen_pct', titulo: 'Margen %', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'capital_mensual',
        ejeX: 'periodo', valor: 'capital', valorTipo: 'moneda',
        valorEtiqueta: 'Capital inmovilizado',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'posiciones', titulo: 'Posiciones', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'entradas', titulo: 'Entradas', tipo: 'numero' },
          { campo: 'salidas', titulo: 'Salidas', tipo: 'numero' },
          { campo: 'capital', titulo: 'Capital al cierre', tipo: 'moneda', monto: true },
          { campo: 'variacion_pct', titulo: 'Variación', tipo: 'porcentaje' }
        ]
      }
    ],
    externos: [
      {
        id: 'sobre_stock',
        titulo: 'Sobre-stock del presente',
        departamento: 'inventario',
        endpoint: 'sobre-stock',
        filtros: { page: 0, size: 25 },
        roles: ['ADMIN', 'GERENTE'],
        nota: 'Este bloque NO sale del almacén analítico: `fact_stock_mensual` guarda el '
            + 'cierre de cada mes pero no lleva mínimo ni máximo. Los topes son del presente '
            + 'y viven en la base transaccional, así que el sobre-stock se pide al informe '
            + 'OTD-INV-08 sobre PostgreSQL. Sigue vivo con el almacén apagado.',
        vacio: 'Ninguna variante con tope definido supera su máximo.',
        columnas: [
          { campo: 'sku', titulo: 'SKU', tipo: 'texto' },
          { campo: 'producto', titulo: 'Producto', tipo: 'texto', recortar: 28 },
          { campo: 'bodega', titulo: 'Bodega', tipo: 'texto', recortar: 20 },
          { campo: 'stock_actual', titulo: 'Existencia', tipo: 'numero' },
          { campo: 'stock_maximo', titulo: 'Máximo', tipo: 'numero' },
          { campo: 'exceso', titulo: 'Exceso', tipo: 'numero' },
          { campo: 'ocupacion_pct', titulo: 'Ocupación', tipo: 'porcentaje' }
        ]
      }
    ]
  },

  // ══════════════════════════════════════════════════════════════════════
  // T-3 · CLIENTE Y POSVENTA — OE-08 · D-08.2, D-08.3, D-08.4
  // ══════════════════════════════════════════════════════════════════════
  {
    id: 'T-3',
    clave: 'cliente-posventa',
    titulo: 'Tablero de Cliente y Posventa',
    objetivo: 'OE-08 · Fidelización y Retención de Clientes',
    descripcion: 'A quién se le reconoce la condición de preferente, qué causa de reclamo se '
               + 'ataca y qué productos se retiran o se renegocian por devolución y '
               + 'calificación.',
    icono: 'diversity_3',
    // SOPORTE entra, pero solo al bloque de tickets y devoluciones: el backend
    // no ejecuta los demás y el sobre declara cuáles omitió.
    roles: ['ADMIN', 'GERENTE', 'ANALISTA', 'SOPORTE'],
    filtros: [
      FILTRO_DESDE, FILTRO_HASTA,
      // OJO: los DOS se llaman «categoría» y los dos viven en un campo llamado
      // `categoria`, pero son dominios DISTINTOS —producto (Abarrotes, Belleza…)
      // y ticket (Facturación, Envíos…)—. Por eso cada uno enumera SUS bloques:
      // mezclarlos ofrecería «Facturación» como categoría de producto, que
      // devuelve cero filas sin dar error.
      {
        param: 'categoria', etiqueta: 'Categoría de producto', tipo: 'select', valorInicial: '',
        opcionesDe: {
          bloques: ['devolucion_producto', 'calificacion_producto', 'reclama_y_devuelve'],
          campo: 'categoria', todos: 'Todas las categorías'
        }
      },
      {
        param: 'categoriaTicket', etiqueta: 'Categoría de ticket', tipo: 'select',
        valorInicial: '',
        opcionesDe: { bloques: ['tickets_categoria'], campo: 'categoria',
                      todos: 'Todas las categorías de ticket' }
      }
    ],
    bloques: [
      {
        id: 'pareto_clientes',
        ejeX: 'cliente', valor: 'venta', valorTipo: 'moneda',
        acumulado: 'acumulado_pct',
        topFilas: 25,
        ayuda: 'El corte que mires sobre la curva ES la decisión: hasta dónde llega la '
             + 'condición de cliente preferente. Las barras son la venta de cada cliente y '
             + 'la línea el acumulado.',
        columnas: [
          { campo: 'ranking', titulo: '#', tipo: 'numero' },
          { campo: 'cliente', titulo: 'Cliente', tipo: 'texto', recortar: 28 },
          { campo: 'ciudad', titulo: 'Ciudad', tipo: 'texto' },
          { campo: 'pedidos', titulo: 'Pedidos', tipo: 'numero' },
          { campo: 'venta', titulo: 'Venta', tipo: 'moneda', monto: true },
          { campo: 'ticket_medio', titulo: 'Ticket medio', tipo: 'moneda', monto: true },
          { campo: 'venta_pct', titulo: '% del ingreso', tipo: 'porcentaje' },
          { campo: 'acumulado_pct', titulo: 'Acumulado', tipo: 'porcentaje' },
          { campo: 'clientes_pct', titulo: '% de clientes', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'nuevo_recurrente',
        ejeX: 'periodo', valor: 'venta_nuevos', valorTipo: 'moneda',
        valorEtiqueta: 'Venta de clientes nuevos',
        valor2: 'venta_recurrentes', valor2Tipo: 'moneda',
        valor2Etiqueta: 'Venta de clientes recurrentes',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'clientes_nuevos', titulo: 'Clientes nuevos', tipo: 'numero' },
          { campo: 'clientes_recurrentes', titulo: 'Recurrentes', tipo: 'numero' },
          { campo: 'clientes_nuevos_pct', titulo: '% nuevos', tipo: 'porcentaje' },
          { campo: 'pedidos_nuevos', titulo: 'Pedidos de nuevos', tipo: 'numero' },
          { campo: 'venta_nuevos', titulo: 'Venta de nuevos', tipo: 'moneda', monto: true },
          { campo: 'venta_recurrentes', titulo: 'Venta de recurrentes', tipo: 'moneda',
            monto: true },
          { campo: 'venta_nuevos_pct', titulo: '% de la venta', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'tickets_categoria',
        ejeX: 'categoria', valor: 'tickets', valorTipo: 'numero',
        valorEtiqueta: 'Tickets',
        valor2: 'horas_mediana', valor2Tipo: 'numero',
        valor2Etiqueta: 'Horas hasta el cierre (mediana)',
        ayuda: 'El volumen se cuenta sobre TODOS los tickets del período; el tiempo solo '
             + 'sobre los cerrados, que son muchos menos. Mira siempre la columna «Base de '
             + 'los tiempos» antes de comparar dos categorías.',
        columnas: [
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'tickets', titulo: 'Tickets', tipo: 'numero' },
          { campo: 'vivos', titulo: 'Vivos', tipo: 'numero' },
          { campo: 'resueltos', titulo: 'Resueltos (sin cerrar)', tipo: 'numero' },
          { campo: 'cerrados', titulo: 'Cerrados', tipo: 'numero' },
          { campo: 'base_tiempos', titulo: 'Base de los tiempos', tipo: 'numero' },
          { campo: 'horas_mediana', titulo: 'Horas (mediana)', tipo: 'numero' },
          { campo: 'horas_media', titulo: 'Horas (media)', tipo: 'numero' },
          { campo: 'horas_p90', titulo: 'Horas (p90)', tipo: 'numero' },
          { campo: 'sla_cumplido', titulo: 'En plazo', tipo: 'numero' },
          { campo: 'sla_pct', titulo: '% en plazo', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'devolucion_producto',
        ejeX: 'producto', serie: 'motivo', valor: 'unidades', valorTipo: 'numero',
        valorEtiqueta: 'Unidades devueltas',
        topFilas: 25,
        columnas: [
          { campo: 'sku', titulo: 'SKU', tipo: 'texto' },
          { campo: 'producto', titulo: 'Producto', tipo: 'texto', recortar: 26 },
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'motivo', titulo: 'Motivo', tipo: 'texto', recortar: 30 },
          { campo: 'lineas', titulo: 'Líneas', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'unidades_reingresadas', titulo: 'Vuelven al stock', tipo: 'numero' },
          { campo: 'reingreso_pct', titulo: '% recuperado', tipo: 'porcentaje' },
          { campo: 'monto', titulo: 'Monto', tipo: 'moneda', monto: true }
        ]
      },
      {
        id: 'calificacion_producto',
        ejeX: 'producto', valor: 'negativas', valorTipo: 'numero',
        valorEtiqueta: 'Reseñas negativas (1-2 estrellas)',
        topFilas: 25,
        columnas: [
          { campo: 'producto', titulo: 'Producto', tipo: 'texto', recortar: 28 },
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'resenas', titulo: 'Reseñas', tipo: 'numero' },
          { campo: 'nota_media', titulo: 'Nota media', tipo: 'numero' },
          { campo: 'negativas', titulo: 'Negativas', tipo: 'numero' },
          { campo: 'positivas', titulo: 'Positivas', tipo: 'numero' },
          { campo: 'verificadas', titulo: 'Compra verificada', tipo: 'numero' },
          { campo: 'pendientes', titulo: 'Sin moderar', tipo: 'numero' }
        ]
      },
      {
        id: 'reclama_y_devuelve',
        x: 'unidades', y: 'tickets', punto: 'producto', grupo: 'categoria',
        xEtiqueta: 'Unidades devueltas', yEtiqueta: 'Reclamos abiertos',
        topFilas: 30,
        ayuda: 'Arriba a la derecha paga dos veces: el reembolso y la reputación. Es la '
             + 'decisión D-08.4 en una sola vista.',
        columnas: [
          { campo: 'sku', titulo: 'SKU', tipo: 'texto' },
          { campo: 'producto', titulo: 'Producto', tipo: 'texto', recortar: 26 },
          { campo: 'categoria', titulo: 'Categoría', tipo: 'texto' },
          { campo: 'tickets', titulo: 'Reclamos', tipo: 'numero' },
          { campo: 'tickets_cerrados', titulo: 'Cerrados', tipo: 'numero' },
          { campo: 'devoluciones', titulo: 'Devoluciones', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'unidades_reingresadas', titulo: 'Vuelven al stock', tipo: 'numero' },
          { campo: 'monto', titulo: 'Monto', tipo: 'moneda', monto: true }
        ]
      }
    ]
  },

  // ══════════════════════════════════════════════════════════════════════
  // T-4 · OPERACIÓN Y ÚLTIMA MILLA — OE-09 · D-09.1, D-09.2, D-09.4
  //
  // SIN DINERO. Es el único tablero que Despacho y Bodega pueden abrir, y
  // ninguna de sus columnas es un importe: envíos, días, horas y unidades.
  // ══════════════════════════════════════════════════════════════════════
  {
    id: 'T-4',
    clave: 'operacion',
    titulo: 'Tablero de Operación y Última Milla',
    objetivo: 'OE-09 · Eficiencia Operativa',
    descripcion: 'Con qué transportistas se renueva contrato, qué etapa del ciclo recibe '
               + 'refuerzo y dónde se ataca la pérdida física. Todo en envíos, días y '
               + 'unidades: sin una sola cifra de dinero.',
    icono: 'local_shipping',
    roles: ['ADMIN', 'GERENTE', 'ANALISTA', 'DESPACHO', 'BODEGA'],
    filtros: [
      FILTRO_DESDE, FILTRO_HASTA,
      {
        param: 'transportista', etiqueta: 'Transportista', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['cumplimiento_promesa', 'dias_transito'], campo: 'transportista',
                      todos: 'Todos los transportistas' }
      },
      {
        param: 'zona', etiqueta: 'Zona de envío', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['dias_transito'], campo: 'zona', todos: 'Todas las zonas' }
      },
      // De escritura todavía: la merma se agrupa por MOTIVO, no por bodega, así
      // que ningún bloque de este tablero trae el nombre de la bodega.
      { param: 'bodega', etiqueta: 'Bodega (merma y origen)', tipo: 'texto', debounce: true }
    ],
    bloques: [
      {
        id: 'cumplimiento_promesa',
        ejeX: 'transportista', valor: 'a_tiempo_pct', valorTipo: 'porcentaje',
        valorEtiqueta: 'Entregas dentro de la fecha prometida',
        ayuda: 'Compara siempre el porcentaje con la columna «medibles»: un transportista '
             + 'con pocos envíos medibles no es comparable con uno que tiene dos mil.',
        columnas: [
          { campo: 'transportista', titulo: 'Transportista', tipo: 'texto', recortar: 24 },
          { campo: 'envios', titulo: 'Envíos', tipo: 'numero' },
          { campo: 'con_promesa', titulo: 'Medibles', tipo: 'numero' },
          { campo: 'sin_promesa', titulo: 'No medibles', tipo: 'numero' },
          { campo: 'a_tiempo', titulo: 'A tiempo', tipo: 'numero' },
          { campo: 'tarde', titulo: 'Tarde', tipo: 'numero' },
          { campo: 'a_tiempo_pct', titulo: '% a tiempo', tipo: 'porcentaje' },
          { campo: 'retraso_medio', titulo: 'Retraso medio', tipo: 'dias' }
        ]
      },
      {
        id: 'dias_transito',
        ejeX: 'transportista', serie: 'zona',
        caja: { minimo: 'minimo', q1: 'q1', mediana: 'mediana', q3: 'q3',
                maximo: 'maximo', media: 'media' },
        ayuda: 'La CAJA es el 50 % central de los envíos y la línea gruesa la mediana; el '
             + 'punto hueco es la media. Dos transportistas con la misma media pero cajas '
             + 'distintas no son lo mismo: uno entrega siempre igual y el otro es una '
             + 'lotería, y renovar con el segundo es comprar variabilidad.',
        columnas: [
          { campo: 'transportista', titulo: 'Transportista', tipo: 'texto', recortar: 22 },
          { campo: 'zona', titulo: 'Zona', tipo: 'texto', recortar: 22 },
          { campo: 'medidos', titulo: 'Envíos medidos', tipo: 'numero' },
          { campo: 'minimo', titulo: 'Mínimo', tipo: 'dias' },
          { campo: 'q1', titulo: 'Q1', tipo: 'dias' },
          { campo: 'mediana', titulo: 'Mediana', tipo: 'dias' },
          { campo: 'q3', titulo: 'Q3', tipo: 'dias' },
          { campo: 'maximo', titulo: 'Máximo', tipo: 'dias' },
          { campo: 'media', titulo: 'Media', tipo: 'dias' }
        ]
      },
      {
        id: 'tiempo_etapa',
        ejeX: 'etapa', valor: 'dias_mediana', valorTipo: 'numero',
        valorEtiqueta: 'Días hasta el siguiente hito (mediana)',
        ayuda: 'Las cuatro filas NO se suman: son cuatro medias sobre cuatro conjuntos '
             + 'distintos. Mira siempre «pedidos medidos» antes de comparar dos etapas.',
        columnas: [
          { campo: 'etapa', titulo: 'Etapa', tipo: 'texto', recortar: 38 },
          { campo: 'pedidos_medidos', titulo: 'Pedidos medidos', tipo: 'numero' },
          { campo: 'dias_mediana', titulo: 'Mediana', tipo: 'dias' },
          { campo: 'dias_media', titulo: 'Media', tipo: 'dias' },
          { campo: 'dias_p90', titulo: 'p90', tipo: 'dias' },
          { campo: 'horas_media', titulo: 'Horas (media)', tipo: 'numero' }
        ]
      },
      {
        id: 'incidencias',
        fila: 'tipo', columna: 'desenlace', valor: 'novedades', valorTipo: 'numero',
        ayuda: 'La columna «sin resolver» es la accionable: son las incidencias que hoy '
             + 'están bloqueando una entrega.',
        columnas: [
          { campo: 'tipo', titulo: 'Tipo de incidencia', tipo: 'texto', recortar: 24 },
          { campo: 'desenlace', titulo: 'Desenlace', tipo: 'chip',
            color: f => f['desenlace'] === 'reprogramada' ? 'ok'
                      : f['desenlace'] === 'devuelto_almacen' ? 'error' : 'warn' },
          { campo: 'novedades', titulo: 'Incidencias', tipo: 'numero' },
          { campo: 'envios', titulo: 'Envíos', tipo: 'numero' },
          { campo: 'resueltas', titulo: 'Resueltas', tipo: 'numero' },
          { campo: 'horas_resolucion', titulo: 'Horas hasta resolver', tipo: 'numero' },
          { campo: 'intento_maximo', titulo: 'Intentos (máx.)', tipo: 'numero' }
        ]
      },
      {
        id: 'merma_motivo',
        ejeX: 'motivo', valor: 'unidades_perdidas', valorTipo: 'numero',
        valorEtiqueta: 'Unidades perdidas',
        topFilas: 20,
        columnas: [
          { campo: 'motivo', titulo: 'Motivo', tipo: 'texto', recortar: 44 },
          { campo: 'tipo', titulo: 'Tipo', tipo: 'chip',
            color: f => f['tipo'] === 'negativo' ? 'error'
                      : f['tipo'] === 'positivo' ? 'ok' : 'info' },
          { campo: 'movimientos', titulo: 'Ajustes', tipo: 'numero' },
          { campo: 'unidades_perdidas', titulo: 'Perdidas', tipo: 'numero' },
          { campo: 'unidades_sobrantes', titulo: 'Sobrantes', tipo: 'numero' },
          { campo: 'neto', titulo: 'Neto', tipo: 'numero' },
          { campo: 'variantes', titulo: 'Variantes', tipo: 'numero' }
        ]
      },
      {
        id: 'retorno_almacen',
        ejeX: 'paso', valor: 'pedidos', valorTipo: 'numero',
        columnas: [
          { campo: 'paso', titulo: 'Paso', tipo: 'texto', recortar: 36 },
          { campo: 'pedidos', titulo: 'Cantidad', tipo: 'numero' },
          { campo: 'denominador', titulo: 'Base del paso', tipo: 'numero' },
          { campo: 'tasa_paso_pct', titulo: 'Del paso anterior', tipo: 'porcentaje' },
          { campo: 'perdidos', titulo: 'No pasan', tipo: 'numero' },
          { campo: 'nota', titulo: 'Qué hay detrás', tipo: 'texto', recortar: 74 }
        ]
      }
    ]
  },

  // ══════════════════════════════════════════════════════════════════════
  // T-5 · COSTO DE LA OPERACIÓN — OE-09 · D-09.3
  // ══════════════════════════════════════════════════════════════════════
  {
    id: 'T-5',
    clave: 'costo-operacion',
    titulo: 'Tablero de Costo de la Operación',
    objetivo: 'OE-09 · Eficiencia Operativa',
    descripcion: 'Qué zonas se re-tarifan, cuáles se subsidian a propósito y cuáles dejan '
               + 'de subsidiarse. Es el gemelo CON dinero del tablero de Operación, y por '
               + 'eso Despacho y Bodega quedan fuera.',
    icono: 'payments',
    roles: DIRECCION,
    filtros: [
      FILTRO_DESDE, FILTRO_HASTA,
      {
        param: 'zona', etiqueta: 'Zona de envío', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['costo_zona_mes', 'costo_por_kg'], campo: 'zona',
                      todos: 'Todas las zonas' }
      },
      {
        param: 'transportista', etiqueta: 'Transportista', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['costo_por_kg'], campo: 'transportista',
                      todos: 'Todos los transportistas' }
      }
    ],
    bloques: [
      {
        id: 'costo_zona_mes',
        ejeX: 'periodo', serie: 'zona', valor: 'costo', valorTipo: 'moneda',
        valorEtiqueta: 'Costo del envío',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'zona', titulo: 'Zona', tipo: 'texto', recortar: 22 },
          { campo: 'envios', titulo: 'Envíos', tipo: 'numero' },
          { campo: 'kilos', titulo: 'Kilos', tipo: 'numero' },
          { campo: 'costo', titulo: 'Costo', tipo: 'moneda', monto: true },
          { campo: 'costo_medio', titulo: 'Costo medio', tipo: 'moneda', monto: true },
          { campo: 'participacion_pct', titulo: '% del mes', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'costo_por_kg',
        fila: 'transportista', columna: 'zona', valor: 'costo_por_kg', valorTipo: 'moneda',
        ayuda: 'Una casilla en gris no es una casilla barata: es una casilla sin envíos. '
             + 'Y en los envíos sin tarifar el costo por kilo no existe, no vale cero.',
        columnas: [
          { campo: 'transportista', titulo: 'Transportista', tipo: 'texto', recortar: 22 },
          { campo: 'zona', titulo: 'Zona', tipo: 'texto', recortar: 22 },
          { campo: 'envios', titulo: 'Envíos', tipo: 'numero' },
          { campo: 'tarifados', titulo: 'Tarifados', tipo: 'numero' },
          { campo: 'sin_tarifa', titulo: 'Sin tarifar', tipo: 'numero' },
          { campo: 'costo_por_kg', titulo: '$/kg medio', tipo: 'moneda', monto: true },
          { campo: 'mediana_por_kg', titulo: '$/kg mediana', tipo: 'moneda', monto: true },
          { campo: 'kilos', titulo: 'Kilos', tipo: 'numero' },
          { campo: 'costo', titulo: 'Costo', tipo: 'moneda', monto: true }
        ]
      },
      {
        id: 'reembolsos',
        ejeX: 'via', serie: 'motivo', valor: 'reembolsado', valorTipo: 'moneda',
        valorEtiqueta: 'Reembolsado',
        columnas: [
          { campo: 'via', titulo: 'Vía del reembolso', tipo: 'texto' },
          { campo: 'motivo', titulo: 'Motivo', tipo: 'texto', recortar: 30 },
          { campo: 'devoluciones', titulo: 'Devoluciones', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'reembolsado', titulo: 'Reembolsado', tipo: 'moneda', monto: true },
          { campo: 'sin_asiento', titulo: 'Sin asiento', tipo: 'numero' },
          { campo: 'diferencia', titulo: 'Diferencia', tipo: 'moneda', monto: true }
        ]
      },
      {
        id: 'costo_sobre_venta',
        ejeX: 'periodo',
        valor: 'costo', valorTipo: 'moneda', valorEtiqueta: 'Costo de envío',
        valor2: 'costo_sobre_venta_pct', valor2Tipo: 'porcentaje',
        valor2Etiqueta: '% sobre la venta',
        columnas: [
          { campo: 'periodo', titulo: 'Mes', tipo: 'texto' },
          { campo: 'envios', titulo: 'Envíos', tipo: 'numero' },
          { campo: 'costo', titulo: 'Costo de envío', tipo: 'moneda', monto: true },
          { campo: 'costo_medio', titulo: 'Costo medio', tipo: 'moneda', monto: true },
          { campo: 'venta', titulo: 'Venta del mes', tipo: 'moneda', monto: true },
          { campo: 'costo_sobre_venta_pct', titulo: '% sobre la venta', tipo: 'porcentaje' }
        ]
      }
    ]
  },

  // ══════════════════════════════════════════════════════════════════════
  // T-6 · ABASTECIMIENTO — OE-11 · D-11.2, D-11.3, D-11.4
  // ══════════════════════════════════════════════════════════════════════
  {
    id: 'T-6',
    clave: 'abastecimiento',
    titulo: 'Tablero de Abastecimiento',
    objetivo: 'OE-11 · Excelencia en la Cadena de Abastecimiento',
    descripcion: 'A qué proveedor se concentra la compra y a cuál se le retira, qué '
               + 'condiciones de pago se renegocian y qué reclamo de calidad se escala. Es '
               + 'el centro de costo dominante del negocio.',
    icono: 'inventory_2',
    roles: ['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA'],
    filtros: [
      FILTRO_DESDE, FILTRO_HASTA,
      // La fuente es `ficha_proveedor` y NO `defectuosos`: ese último guarda el
      // nombre CORTO («El Costeno») mientras el filtro compara contra la razón
      // social completa. Medido: con el nombre corto los 8 bloques bajan de 92
      // filas a 1 — sin error y sin aviso.
      {
        param: 'proveedor', etiqueta: 'Proveedor', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['ficha_proveedor'], campo: 'proveedor',
                      todos: 'Todos los proveedores' }
      },
      {
        param: 'categoria', etiqueta: 'Categoría', tipo: 'select', valorInicial: '',
        opcionesDe: { bloques: ['evolucion_costo'], campo: 'categoria',
                      todos: 'Todas las categorías' }
      },
      {
        param: 'alcance', etiqueta: 'Alcance de la entrega', tipo: 'select',
        valorInicial: 'entregadas',
        opciones: [
          { valor: 'entregadas', etiqueta: 'Solo entregadas (recomendado)' },
          { valor: 'en_camino', etiqueta: 'Solo las que vienen de camino' },
          { valor: 'canceladas', etiqueta: 'Solo canceladas' },
          { valor: 'todas', etiqueta: 'Todas (mezcla responsabilidades)' }
        ]
      }
    ],
    bloques: [
      {
        id: 'gasto_proveedor_mes',
        ejeX: 'periodo', serie: 'proveedor', valor: 'gasto', valorTipo: 'moneda',
        valorEtiqueta: 'Gasto facturado',
        columnas: [
          { campo: 'periodo', titulo: 'Mes de la factura', tipo: 'texto' },
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 30 },
          { campo: 'facturas', titulo: 'Facturas', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'gasto', titulo: 'Gasto', tipo: 'moneda', monto: true },
          { campo: 'participacion_pct', titulo: '% del mes', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'ficha_proveedor',
        ejeX: 'proveedor', valor: 'gasto', valorTipo: 'moneda',
        valorEtiqueta: 'Gasto facturado',
        ayuda: 'Los cuatro ejes de la decisión: cuánto cuesta, cuánto tarda, si cumple lo '
             + 'que promete y con qué calidad entrega. Un desvío NEGATIVO en el plazo '
             + 'significa que llegó antes de lo prometido.',
        columnas: [
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 30 },
          { campo: 'ordenes', titulo: 'Órdenes', tipo: 'numero' },
          { campo: 'gasto', titulo: 'Gasto', tipo: 'moneda', monto: true },
          { campo: 'ciclo_medio', titulo: 'Ciclo', tipo: 'dias' },
          { campo: 'desvio_medio', titulo: 'Desvío del plazo', tipo: 'dias' },
          { campo: 'cumplimiento_pct', titulo: '% cumplimiento', tipo: 'porcentaje' },
          { campo: 'con_promesa', titulo: 'Órdenes medibles', tipo: 'numero' },
          { campo: 'unidades_recibidas', titulo: 'Uds. recibidas', tipo: 'numero' },
          { campo: 'rechazo_pct', titulo: '% rechazo', tipo: 'porcentaje' },
          { campo: 'precio_medio', titulo: 'Precio medio', tipo: 'moneda', monto: true }
        ]
      },
      {
        id: 'entregas_incompletas',
        ejeX: 'proveedor', valor: 'unidades_faltantes', valorTipo: 'numero',
        valorEtiqueta: 'Unidades servidas de menos',
        ayuda: 'El alcance por defecto es «solo entregadas» a propósito: con «todas», un '
             + 'proveedor cuyas órdenes canceló Compras aparece como el peor del catálogo.',
        columnas: [
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 30 },
          { campo: 'ordenes', titulo: 'Órdenes', tipo: 'numero' },
          { campo: 'lineas', titulo: 'Líneas', tipo: 'numero' },
          { campo: 'lineas_cortas', titulo: 'Líneas cortas', tipo: 'numero' },
          { campo: 'unidades_pedidas', titulo: 'Pedidas', tipo: 'numero' },
          { campo: 'unidades_recibidas', titulo: 'Recibidas', tipo: 'numero' },
          { campo: 'unidades_faltantes', titulo: 'Faltantes', tipo: 'numero' },
          { campo: 'servido_pct', titulo: '% servido', tipo: 'porcentaje' }
        ]
      },
      {
        id: 'rechazo_puerta',
        fila: 'proveedor', columna: 'motivo', valor: 'unidades_rechazadas',
        valorTipo: 'numero',
        columnas: [
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 28 },
          { campo: 'motivo', titulo: 'Motivo del rechazo', tipo: 'texto', recortar: 32 },
          { campo: 'lineas', titulo: 'Líneas', tipo: 'numero' },
          { campo: 'unidades_rechazadas', titulo: 'Rechazadas', tipo: 'numero' },
          { campo: 'unidades_recibidas', titulo: 'Recibidas', tipo: 'numero' },
          { campo: 'rechazo_pct', titulo: '% sobre lo que llegó', tipo: 'porcentaje' },
          { campo: 'variantes', titulo: 'Variantes', tipo: 'numero' }
        ]
      },
      {
        id: 'evolucion_costo',
        ejeX: 'sku', valor: 'variacion_pct', valorTipo: 'porcentaje',
        valorEtiqueta: 'Variación del precio de compra',
        topFilas: 25,
        columnas: [
          { campo: 'sku', titulo: 'SKU', tipo: 'texto' },
          { campo: 'producto', titulo: 'Producto', tipo: 'texto', recortar: 24 },
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 24 },
          { campo: 'compras', titulo: 'Compras', tipo: 'numero' },
          { campo: 'primera_compra', titulo: 'Primera', tipo: 'texto' },
          { campo: 'ultima_compra', titulo: 'Última', tipo: 'texto' },
          { campo: 'precio_inicial', titulo: 'Precio inicial', tipo: 'moneda', monto: true },
          { campo: 'precio_final', titulo: 'Precio final', tipo: 'moneda', monto: true },
          { campo: 'variacion_pct', titulo: 'Variación', tipo: 'porcentaje' },
          { campo: 'subidas', titulo: 'Subidas', tipo: 'numero' },
          { campo: 'bajadas', titulo: 'Bajadas', tipo: 'numero' }
        ]
      },
      {
        id: 'cxp_vencimientos',
        ejeX: 'proveedor', valor: 'saldo', valorTipo: 'moneda',
        valorEtiqueta: 'Saldo pendiente',
        topFilas: 20,
        columnas: [
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 28 },
          { campo: 'estado', titulo: 'Situación', tipo: 'chip',
            color: f => f['estado'] === 'vencida' ? 'error'
                      : f['estado'] === 'parcial' ? 'warn' : 'info' },
          { campo: 'documentos', titulo: 'Documentos', tipo: 'numero' },
          { campo: 'monto_original', titulo: 'Original', tipo: 'moneda', monto: true },
          { campo: 'pagado', titulo: 'Pagado', tipo: 'moneda', monto: true },
          { campo: 'saldo', titulo: 'Saldo', tipo: 'moneda', monto: true },
          { campo: 'vence_primero', titulo: 'Vence primero', tipo: 'texto' },
          { campo: 'vencidos', titulo: 'Ya vencidos', tipo: 'numero' }
        ]
      },
      {
        id: 'puntualidad_pago',
        ejeX: 'proveedor', valor: 'a_tiempo_pct', valorTipo: 'porcentaje',
        valorEtiqueta: 'Pagos dentro del vencimiento',
        valor2: 'desvio_medio', valor2Tipo: 'numero',
        valor2Etiqueta: 'Desvío medio en días',
        ayuda: 'Un desvío negativo significa que se pagó ANTES de vencer, y eso tampoco es '
             + 'gratis: adelantar el pago regala liquidez que el negocio necesita.',
        columnas: [
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 30 },
          { campo: 'pagos', titulo: 'Pagos', tipo: 'numero' },
          { campo: 'pagado', titulo: 'Pagado', tipo: 'moneda', monto: true },
          { campo: 'a_tiempo', titulo: 'A tiempo', tipo: 'numero' },
          { campo: 'tarde', titulo: 'Tarde', tipo: 'numero' },
          { campo: 'a_tiempo_pct', titulo: '% a tiempo', tipo: 'porcentaje' },
          { campo: 'desvio_medio', titulo: 'Desvío medio', tipo: 'dias' },
          { campo: 'peor_retraso', titulo: 'Peor retraso', tipo: 'dias' }
        ]
      },
      {
        id: 'defectuosos',
        ejeX: 'proveedor', valor: 'costo_en_juego', valorTipo: 'moneda',
        valorEtiqueta: 'Costo en juego',
        columnas: [
          { campo: 'proveedor', titulo: 'Proveedor', tipo: 'texto', recortar: 28 },
          { campo: 'origen', titulo: 'Origen', tipo: 'chip',
            color: f => f['origen'] === 'rma' ? 'warn' : 'info',
            etiqueta: v => v === 'rma' ? 'Devolución de cliente' : 'Recepción de compra' },
          { campo: 'resolucion', titulo: 'Resolución', tipo: 'chip',
            color: f => f['resolucion'] === 'sin_resolver' ? 'error' : 'ok' },
          { campo: 'items', titulo: 'Ítems', tipo: 'numero' },
          { campo: 'unidades', titulo: 'Unidades', tipo: 'numero' },
          { campo: 'costo_en_juego', titulo: 'Costo en juego', tipo: 'moneda', monto: true },
          { campo: 'recuperado', titulo: 'Recuperado', tipo: 'moneda', monto: true },
          { campo: 'recuperado_pct', titulo: '% recuperado', tipo: 'porcentaje' },
          { campo: 'dias_resolucion', titulo: 'Días', tipo: 'dias' }
        ]
      }
    ]
  },

  // ══════════════════════════════════════════════════════════════════════
  // T-7 · GOBIERNO DEL DATO — OE-10 · D-10.2, D-10.3 · DATO SENSIBLE
  // ══════════════════════════════════════════════════════════════════════
  {
    id: 'T-7',
    clave: 'gobierno-dato',
    titulo: 'Tablero de Gobierno del Dato',
    objetivo: 'OE-10 · Liderazgo en Decisiones Basadas en Datos',
    descripcion: 'Si la información con la que se está decidiendo es confiable, y qué '
               + 'privilegios de acceso se revocan o se refuerzan. Es el único riesgo del '
               + 'sistema que no se detecta mirando ninguna otra pantalla.',
    icono: 'verified_user',
    roles: ['ADMIN', 'GERENTE'],
    filtros: [
      {
        param: 'corridas', etiqueta: 'Ejecuciones en el histórico', tipo: 'select',
        valorInicial: '10',
        opciones: [
          { valor: '10', etiqueta: 'Las 10 últimas' },
          { valor: '25', etiqueta: 'Las 25 últimas' },
          { valor: '50', etiqueta: 'Las 50 últimas' }
        ]
      }
    ],
    bloques: [
      {
        id: 'salud_corrida',
        ejeX: 'tarea', valor: 'filas', valorTipo: 'numero',
        ayuda: 'Si el control de validación no está en «éxito», ninguna cifra de los otros '
             + 'seis tableros puede darse por buena hasta revisarlo.',
        columnas: [
          { campo: 'tarea', titulo: 'Tarea', tipo: 'texto', recortar: 30 },
          { campo: 'tipo', titulo: 'Tipo', tipo: 'chip',
            color: f => f['tipo'] === 'tabla' ? 'info'
                      : f['tipo'] === 'control' ? 'warn' : 'neutral' },
          { campo: 'resultado', titulo: 'Resultado', tipo: 'chip',
            color: f => f['resultado'] === 'exito' ? 'ok'
                      : f['resultado'] === 'en_curso' ? 'info'
                      : f['resultado'] === 'omitido' ? 'neutral' : 'error' },
          { campo: 'filas', titulo: 'Filas escritas', tipo: 'numero' },
          { campo: 'excepciones', titulo: 'Excepciones', tipo: 'numero' },
          { campo: 'duracion_seg', titulo: 'Segundos', tipo: 'numero' },
          { campo: 'comenzo', titulo: 'Comenzó', tipo: 'texto' },
          { campo: 'mensaje', titulo: 'Mensaje', tipo: 'texto', recortar: 50 }
        ]
      },
      {
        id: 'historico_corridas',
        ejeX: 'cuando', valor: 'filas', valorTipo: 'numero',
        valorEtiqueta: 'Filas publicadas',
        valor2: 'duracion_seg', valor2Tipo: 'numero', valor2Etiqueta: 'Duración (s)',
        columnas: [
          { campo: 'cuando', titulo: 'Ejecución', tipo: 'texto' },
          { campo: 'resultado_corrida', titulo: 'Resultado', tipo: 'chip',
            color: f => f['resultado_corrida'] === 'exito' ? 'ok'
                      : f['resultado_corrida'] === 'en_curso' ? 'info' : 'error' },
          { campo: 'tareas', titulo: 'Tablas', tipo: 'numero' },
          { campo: 'publicadas', titulo: 'Publicadas', tipo: 'numero' },
          { campo: 'fallidas', titulo: 'Fallidas', tipo: 'numero' },
          { campo: 'excepciones', titulo: 'Excepciones', tipo: 'numero' },
          { campo: 'filas', titulo: 'Filas', tipo: 'numero' },
          { campo: 'duracion_seg', titulo: 'Segundos', tipo: 'numero' }
        ]
      },
      {
        id: 'antiguedad_dato',
        ejeX: 'tabla', valor: 'horas', valorTipo: 'numero',
        valorEtiqueta: 'Horas desde la carga',
        columnas: [
          { campo: 'tabla', titulo: 'Tabla', tipo: 'texto', recortar: 30 },
          { campo: 'cargada', titulo: 'Cargada', tipo: 'texto',
            etiqueta: (v, f) => v ? String(v)
                                  : (f['generada'] ? 'Generada en el almacén' : '—') },
          { campo: 'horas', titulo: 'Antigüedad', tipo: 'numero',
            etiqueta: (v, f) => v === null || v === undefined
              ? (f['generada'] ? 'No aplica' : '—')
              : `${Number(v).toLocaleString('es-EC')} h` },
          { campo: 'filas', titulo: 'Filas', tipo: 'numero' }
        ]
      }
    ],
    externos: [
      {
        id: 'auditoria',
        titulo: 'Acciones auditadas: quién hizo qué',
        departamento: 'gerencia',
        endpoint: 'auditoria',
        filtros: { page: 0, size: 25 },
        roles: ['ADMIN', 'GERENTE'],
        nota: 'Este bloque NO sale del almacén analítico: la auditoría es una consulta '
            + 'filtrada, no un barrido agregado, y llevarla al almacén sería crear una '
            + 'tabla para no ganar nada. Se sirve del informe OTD-GER-08 sobre PostgreSQL. '
            + 'OJO con su permiso: aquí el corte lo hace la RUTA y no el motor, porque el '
            + 'rol de analista SÍ puede leer la tabla de auditoría en la base de datos.',
        vacio: 'No hay acciones auditadas.',
        topFilas: 15,
        columnas: [
          { campo: 'fecha_creacion', titulo: 'Cuándo', tipo: 'fechaHora' },
          { campo: 'autor', titulo: 'Quién', tipo: 'texto', recortar: 22 },
          { campo: 'accion', titulo: 'Acción', tipo: 'chip',
            color: f => f['accion'] === 'delete' ? 'error'
                      : f['accion'] === 'insert' ? 'ok' : 'info' },
          { campo: 'tabla', titulo: 'Registro', tipo: 'texto', recortar: 20 },
          { campo: 'registro_id', titulo: 'Nº', tipo: 'texto' },
          { campo: 'ip', titulo: 'IP', tipo: 'texto', recortar: 16 }
        ]
      },
      {
        id: 'accesos',
        titulo: 'Intentos de acceso al sistema',
        departamento: 'gerencia',
        endpoint: 'accesos',
        filtros: { resultado: 'fallido', page: 0, size: 25 },
        roles: ['ADMIN', 'GERENTE'],
        nota: 'Tampoco sale del almacén: se sirve del informe OTD-GER-09 sobre PostgreSQL. '
            + 'Aquí el motor SÍ respalda la ruta —solo administración y gerencia leen la '
            + 'tabla de accesos— y llega filtrado a los intentos FALLIDOS, que son los '
            + 'accionables. «Fuera de su horario» no es un error de credenciales: es la '
            + 'ventana del grupo bloqueando el login.',
        vacio: 'No hay intentos de acceso fallidos registrados.',
        topFilas: 15,
        columnas: [
          { campo: 'fecha_creacion', titulo: 'Cuándo', tipo: 'fechaHora' },
          { campo: 'email_intentado', titulo: 'Correo', tipo: 'texto', recortar: 26 },
          { campo: 'motivo_fallo', titulo: 'Motivo', tipo: 'texto', recortar: 24 },
          { campo: 'ip', titulo: 'IP', tipo: 'texto', recortar: 18 },
          { campo: 'user_agent', titulo: 'Navegador', tipo: 'texto', recortar: 30 }
        ]
      }
    ]
  }
];


export function definicionTablero(clave: string): DefinicionTablero | undefined {
  return TABLEROS.find(t => t.clave === clave);
}
