import { ColumnaInforme, FiltroInforme, KpiInforme, TipoValor } from './informe.model';

/**
 * MOLDE de los TABLEROS DE DIRECCIÓN — nivel ESTRATÉGICO
 * (docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md §4).
 *
 * Contrato entre `TableroServiceBase` (backend, un sobre por tablero) y la
 * pantalla genérica `TableroComponent`. Un tablero NUEVO es un
 * `DefinicionTablero` más en el archivo de definiciones; no se escribe un
 * componente por tablero, igual que en el nivel táctico no se escribe uno por
 * informe.
 *
 * La diferencia con el molde táctico es el grano: allí el sobre trae UN
 * conjunto de filas, aquí trae VARIOS bloques de naturaleza distinta que se
 * leen juntos. Por eso `items` vive dentro de cada bloque y no en la raíz.
 */

/** Cómo se dibuja un bloque. La decide el backend; la pantalla la obedece. */
export type Visualizacion =
  | 'serie'            // líneas en el tiempo, una por serie
  | 'serie_apilada'    // barras apiladas al 100 %
  | 'barras'           // barras por categoría
  | 'barras_apiladas'  // barras apiladas por valor absoluto
  | 'areas_apiladas'   // igual que la apilada, con relleno
  | 'doble_eje'        // dos medidas con escalas distintas
  | 'curva_acumulada'  // Pareto: barra por elemento + curva del acumulado
  | 'embudo'           // pasos decrecientes con su tasa
  | 'dispersion'       // nube de puntos con cruz de corte
  | 'caja_bigotes'     // distribución: mínimo, cuartiles, mediana, máximo
  | 'matriz'           // mapa de calor de dos dimensiones
  | 'semaforo'         // tarjetas grandes con reparto
  | 'semaforo_tabla'   // solo tabla, con el estado como píldora de color
  | 'ranking'          // tabla ordenada
  | 'tabla';           // tabla sin más

/**
 * Un elemento del tablero, tal como lo devuelve el backend.
 *
 * `denominador` NO es opcional: es la regla que gobierna todo el nivel
 * estratégico (§2.3 del diseño). Una cifra sin su base no produce una pantalla
 * rara, produce una decisión.
 */
export interface BloqueTablero {
  id: string;
  titulo: string;
  visualizacion: Visualizacion;
  denominador: string;
  /** Cómo hay que LEER estas cifras. Se pinta encima del elemento. */
  salvedad?: string;
  /** Aviso de muestra débil: el bloque se pinta, pero no sostiene un juicio. */
  muestra?: string;
  items: Record<string, any>[];
  filas: number;
  /** Matriz margen × rotación: dónde va la cruz de los cuadrantes. */
  corteRotacion?: number;
  corteMargen?: number;
  /** Descuento: líneas y pedidos con el cupón sin prorratear (C1.2). */
  excepciones?: number;
  pedidosExcepcion?: number;
  [extra: string]: any;
}

/** Tarjeta de cabecera. `nota` lleva el denominador y no es decoración. */
export interface KpiTablero extends KpiInforme {
  nota?: string;
}

/** Sobre estándar de TODOS los tableros de dirección. */
export interface SobreTablero {
  tablero: string;
  titulo: string;
  decisiones: string[];
  periodo?: {
    desde?: string; hasta?: string;
    primerMesConDato?: string; ultimoMesConDato?: string; mesesConDato?: number;
  };
  kpis: KpiTablero[];
  bloques: BloqueTablero[];
  /** Salvedades del tablero entero (no de un bloque). */
  salvedades: string[];

  /** `false` cuando el almacén analítico no respondió. NO es un error. */
  analiticaDisponible: boolean;
  avisoAnalitica?: string;
  /** Marca de agua ya formateada: la carga MÁS REZAGADA de sus tablas. */
  datosAl?: string;
  fuente?: string;
  tablasFuente?: string[];

  /** T-3: 'completo' o 'posventa' (alcance recortado del rol SOPORTE). */
  alcance?: string;
  bloquesOmitidos?: { id: string; titulo: string; motivo: string }[];
  [extra: string]: any;
}

// ── Definición declarativa de la pantalla ──────────────────────────────

/**
 * Cómo se pinta un bloque concreto: qué campo va en cada eje y qué columnas
 * lleva su tabla. El backend decide QUÉ es el bloque; esto decide cómo se ve.
 */
export interface PresentacionBloque {
  /** Debe coincidir con `BloqueTablero.id`. */
  id: string;
  /** Campo del eje horizontal (normalmente 'periodo'). */
  ejeX?: string;
  /** Campo que separa las series de una serie múltiple o apilada. */
  serie?: string;
  /** Campo de la medida principal. */
  valor?: string;
  valorTipo?: TipoValor;
  valorEtiqueta?: string;
  /** Segunda medida, para `doble_eje`. */
  valor2?: string;
  valor2Tipo?: TipoValor;
  valor2Etiqueta?: string;
  /** `dispersion`: ejes, etiqueta del punto y campo que da el color. */
  x?: string;
  y?: string;
  xEtiqueta?: string;
  yEtiqueta?: string;
  punto?: string;
  grupo?: string;
  /** `curva_acumulada`: campo del % acumulado. */
  acumulado?: string;
  /**
   * `matriz`: qué campo va en las filas y cuál en las columnas del mapa de
   * calor. La intensidad la da `valor`.
   */
  fila?: string;
  columna?: string;
  /**
   * `caja_bigotes`: nombres de los campos de la distribución. Por convención
   * el backend los emite como `minimo`, `q1`, `mediana`, `q3`, `maximo` y
   * `media`; se declaran igualmente para que un bloque pueda usar otros.
   */
  caja?: { minimo: string; q1: string; mediana: string; q3: string;
           maximo: string; media?: string };
  /** Columnas de la tabla que acompaña SIEMPRE al gráfico. */
  columnas: ColumnaInforme[];
  /**
   * Cuántas filas se pintan en la tabla y en el gráfico de un ranking. El
   * bloque llega entero desde el backend; recortar es decisión de pantalla y
   * el pie de la tabla dice cuántas hay en total.
   */
  topFilas?: number;
  /** Texto de ayuda propio de la pantalla (no sustituye a la salvedad). */
  ayuda?: string;
}

/**
 * Elemento que NO sale del almacén y se resuelve con una segunda llamada a un
 * informe SIMPLE de PostgreSQL ya construido (R-7 del diseño: carritos
 * abandonados en T-1, sobre-stock del presente en T-2).
 *
 * Tiene una propiedad que conviene decir en voz alta: **sobrevive a ClickHouse
 * apagado**, porque su fuente es la base transaccional.
 */
export interface BloqueExterno {
  id: string;
  titulo: string;
  /** Segmento de /api/informes/{departamento}/{endpoint}. */
  departamento: string;
  endpoint: string;
  /** Filtros fijos que se envían al informe. */
  filtros: Record<string, string | number>;
  columnas: ColumnaInforme[];
  /** Por qué este bloque no está en el almacén. Se pinta en la tarjeta. */
  nota: string;
  /** Roles que pueden consultar ESE informe (espeja SecurityConfig). */
  roles: readonly string[];
  topFilas?: number;
  vacio: string;
}

/** Respuesta de un bloque externo, ya normalizada por la pantalla. */
export interface EstadoExterno {
  filas: Record<string, any>[];
  total: number;
  /**
   * Tarjetas de cabecera del informe que sirve el bloque. Se pintan DENTRO de
   * su tarjeta y no arriba con las del tablero: son de otra fuente y de otra
   * base, y mezclarlas invitaria a sumarlas con las del almacen.
   */
  resumen: KpiInforme[];
  cargando: boolean;
  error: string;
}

export interface DefinicionTablero {
  /** Código del tablero: 'T-1', 'T-2', 'T-3'. */
  id: string;
  /** Segmento de la ruta REST y de la URL de la app. */
  clave: string;
  titulo: string;
  objetivo: string;
  descripcion: string;
  icono: string;
  /** Roles que ven el tablero; espeja SecurityConfig, que es quien decide. */
  roles: readonly string[];
  filtros: FiltroInforme[];
  bloques: PresentacionBloque[];
  externos?: BloqueExterno[];
}
