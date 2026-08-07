import { ColorChip, DefinicionInforme } from '../../../../core/models/informe.model';

/**
 * PREVISIÓN DE DEMANDA — fase E2 del nivel estratégico (§5.1 de
 * `docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`).
 *
 * Es el ÚNICO informe que aparece en dos departamentos: Gerencia lo usa para
 * fijar las metas del período (D-10.1) y Compras para el plan de compra
 * (D-11.1) y el nivel objetivo de stock (D-07.5). La declaración vive aquí una
 * sola vez y cada departamento la importa con SU lista de roles — que es lo
 * único que cambia, y espeja las dos líneas de `SecurityConfig`.
 *
 * Las cinco reglas de presentación de §5.1.9, y dónde se cumple cada una:
 *
 *   1. histórico y previsión en el MISMO gráfico → `graficoPrevision: true`,
 *      que la pantalla genérica pinta desde el campo `serie` del sobre;
 *   2. ningún número sin banda → las columnas «Mínimo» y «Máximo» van SIEMPRE
 *      pegadas a «Previstas», y las series sin previsión llegan con método
 *      `sin_prevision` para que la fila lo diga en vez de enseñar un cero;
 *   3. la muestra en la fila → columna «Meses hist.»;
 *   4. el error POR FILA → «MAPE» junto a «Vara (ingenuo)», porque un 24 % no
 *      significa nada sin saber qué sacaba el ingenuo en ESA serie;
 *   5. la banda se ensancha con el horizonte → lo produce el modelo y lo
 *      verifica su validación antes de publicar la tabla.
 *
 * Y las cinco limitaciones de §5.1.10 viajan en `salvedad`, que la pantalla
 * pinta ENCIMA de la tabla. La segunda es la que puede costar dinero: **se
 * prevé la venta, no la demanda**.
 */

/** Verde si el modelo bate a su línea base; ámbar si se publicó la base. */
function colorMetodo(fila: Record<string, any>): ColorChip {
  switch (fila['metodo']) {
    case 'descomposicion':        return 'ok';
    case 'top_down_categoria':    return 'info';
    case 'linea_base_estacional': return 'warn';
    default:                      return 'neutral';
  }
}

function etiquetaMetodo(valor: any): string {
  switch (valor) {
    case 'descomposicion':        return 'descomposición';
    case 'top_down_categoria':    return 'cuota de categoría';
    case 'linea_base_estacional': return 'línea base';
    case 'sin_prevision':         return 'sin previsión';
    default:                      return String(valor);
  }
}

/**
 * El MAPE se lee en semáforo contra su propia vara y no contra un umbral fijo:
 * un 45 % en una variante que vende 4 unidades al mes puede ser el mejor
 * resultado posible, y un 15 % es malo si el ingenuo sacaba 10 %.
 */
function colorMape(fila: Record<string, any>): ColorChip {
  const propio = Number(fila['mape_backtest']);
  const vara = Number(fila['mape_linea_base']);
  if (!vara) { return 'neutral'; }
  if (propio < vara * 0.8) { return 'ok'; }
  return propio <= vara ? 'info' : 'warn';
}

/**
 * Las categorías del catálogo sembrado. Es una lista cerrada porque el filtro
 * del backend compara por igualdad EXACTA: la categoría elegida decide qué
 * serie se dibuja en el gráfico, y una coincidencia parcial podría casar con
 * dos («Ropa» y «Ropa Mujer») y pintar una serie que no es la de la tabla.
 */
const CATEGORIAS = ['Abarrotes', 'Ropa', 'Deportes', 'Calzado', 'Hogar',
  'Electrónica', 'Belleza', 'Accesorios', 'Ropa Mujer', 'Ropa Hombre'];

export function informePrevisionDemanda(roles: readonly string[]): DefinicionInforme {
  return {
    id: 'OTD-GER-13',
    endpoint: 'prevision-demanda',
    fuente: 'compuesto',
    titulo: 'Previsión de demanda (3 meses)',
    descripcion: 'Unidades esperadas para los próximos tres meses, con su banda del '
               + '80 %, en tres niveles: total, categoría y variante. Cada fila lleva '
               + 'SU muestra («Meses hist.») y SU error medido en el backtest («MAPE»), '
               + 'junto a lo que sacaba el ingenuo estacional en esa misma serie. Lee '
               + 'la salvedad antes que la tabla: se prevé la VENTA, no la demanda.',
    icono: 'insights',
    roles,
    vacio: 'No hay previsión publicada con esos filtros. Si el modelo no superó su '
         + 'backtest, la tabla conserva la de la corrida anterior.',
    graficoPrevision: true,
    filtros: [
      { param: 'nivel', etiqueta: 'Nivel', tipo: 'select', valorInicial: 'categoria',
        opciones: [
          { valor: 'total',     etiqueta: 'Total de la tienda' },
          { valor: 'categoria', etiqueta: 'Por categoría' },
          { valor: 'variante',  etiqueta: 'Por producto (solo con 12+ meses)' }
        ] },
      { param: 'categoria', etiqueta: 'Categoría', tipo: 'select', ancho: 'ancho',
        opciones: [{ valor: '', etiqueta: 'Todas' }]
          .concat(CATEGORIAS.map(c => ({ valor: c, etiqueta: c }))) },
      { param: 'horizonte', etiqueta: 'Horizonte', tipo: 'select',
        opciones: [
          { valor: '',  etiqueta: 'Los 3 meses' },
          { valor: '1', etiqueta: '1.er mes' },
          { valor: '2', etiqueta: '2.º mes' },
          { valor: '3', etiqueta: '3.er mes' }
        ] },
      { param: 'buscar', etiqueta: 'Producto o SKU (nivel variante)', tipo: 'texto',
        debounce: true, ancho: 'ancho' }
    ],
    columnas: [
      { campo: 'periodo',            titulo: 'Mes',        tipo: 'texto' },
      { campo: 'serie',              titulo: 'Serie',      tipo: 'texto', recortar: 30 },
      { campo: 'sku',                titulo: 'SKU',        tipo: 'texto' },
      { campo: 'horizonte',          titulo: 'Horiz.',     tipo: 'numero' },
      // Los meses que separan la previsión del último dato con que se ENTRENÓ,
      // que no son los que la separan del último mes observado si ése quedó
      // fuera por estar incompleto. Es la explicación de por qué la banda del
      // primer mes es más ancha de lo que su horizonte 1 haría esperar.
      { campo: 'horizonte_efectivo', titulo: 'Meses desde el ajuste', tipo: 'numero' },
      // Regla 2: la previsión NUNCA viaja sola. Las dos columnas de banda van
      // pegadas a la cifra, no al final de la tabla.
      { campo: 'unidades_previstas', titulo: 'Previstas',  tipo: 'numero' },
      { campo: 'limite_inferior',    titulo: 'Mínimo 80 %', tipo: 'numero' },
      { campo: 'limite_superior',    titulo: 'Máximo 80 %', tipo: 'numero' },
      // Regla 3: la muestra en la fila, no en una nota al pie.
      { campo: 'meses_historia',     titulo: 'Meses hist.', tipo: 'numero' },
      { campo: 'observaciones_mes',  titulo: 'Obs. de ese mes', tipo: 'chip',
        color: f => Number(f['observaciones_mes']) >= 2 ? 'ok'
                  : Number(f['observaciones_mes']) === 1 ? 'warn' : 'neutral' },
      // Regla 4: el error POR FILA, y al lado la vara que tenía que batir.
      { campo: 'mape_backtest',      titulo: 'MAPE',       tipo: 'chip',
        color: colorMape,
        etiqueta: (v, f) => f['metodo'] === 'sin_prevision'
          ? '—' : Number(v).toLocaleString('es-EC', { maximumFractionDigits: 1 }) + ' %' },
      { campo: 'mape_linea_base',    titulo: 'Vara (ingenuo)', tipo: 'porcentaje' },
      { campo: 'mae_backtest',       titulo: 'MAE (uds.)', tipo: 'numero' },
      { campo: 'metodo',             titulo: 'Método',     tipo: 'chip',
        color: colorMetodo, etiqueta: etiquetaMetodo },
      { campo: 'demanda_censurada',  titulo: 'Censurada',  tipo: 'chip',
        color: f => Number(f['demanda_censurada']) ? 'warn' : 'ok',
        etiqueta: v => Number(v) ? 'sí — hubo quiebre' : 'no' },
      { campo: 'unidades_mismo_mes_anio_previo', titulo: 'Mismo mes año ant.',
        tipo: 'numero' }
    ]
  };
}
