/**
 * MOLDE de los informes tácticos departamentales.
 *
 * Estos tipos son el contrato entre el backend (InformeServiceBase: un solo
 * sobre para todos los informes) y la pantalla genérica
 * `InformesDepartamentoComponent`. Añadir un informe NUEVO es declarar un
 * `DefinicionInforme` en el archivo de definiciones de su departamento — no se
 * escribe un componente por informe.
 *
 * Ver `docs/tactico/PATRON_INFORMES.md`.
 */

/** Cómo formatear un valor en la tabla o en las tarjetas de resumen. */
export type TipoValor =
  | 'texto'       // tal cual
  | 'numero'      // separador de miles
  | 'moneda'      // $ con 2 decimales
  | 'porcentaje'  // n %
  | 'fecha'       // dd/MM/yy
  | 'fechaHora'   // dd/MM/yy HH:mm
  | 'dias'        // "n días"
  | 'estrellas'   // calificación 1-5
  | 'booleano'    // Sí / No
  | 'chip'        // píldora de estado (usa `chip` para el color)
  /**
   * Micro-gráfico de barras dibujado EN LA CELDA a partir de un array de
   * números que envía el backend.
   *
   * Lo estrena OTD-VEN-19, y ahí no es adorno: la regla 2 de §5.2.9 del diseño
   * estratégico exige el *sparkline* de compras por mes EN LA FILA porque es lo
   * que permite distinguir de un golpe un hueco de una pendiente — y es la
   * defensa contra el artefacto de la rampa de cartera, que fue el único modo
   * de fallo capaz de invertir por completo esta lista.
   *
   * Se dibuja como SVG inline con `<title>` NATIVO y no con `matTooltip`: la
   * lección de la fase E1-A es que con centenares de directivas de tooltip
   * vivas el navegador deja de responder.
   */
  | 'sparkline';

/** Color de la píldora cuando `tipo: 'chip'`. */
export type ColorChip = 'ok' | 'warn' | 'error' | 'neutral' | 'info';

export interface ColumnaInforme {
  /** Clave snake_case tal como la devuelve el backend. */
  campo: string;
  titulo: string;
  tipo: TipoValor;
  /** Para `tipo: 'chip'`: decide el color a partir de la fila. */
  color?: (fila: Record<string, any>) => ColorChip;
  /** Etiqueta legible cuando el valor crudo es un código. */
  etiqueta?: (valor: any, fila: Record<string, any>) => string;
  /** Texto largo: se recorta en la celda y va completo en el tooltip. */
  recortar?: number;
  /** Columna de dinero: se oculta si el informe corre sin permiso financiero. */
  monto?: boolean;
}

/** Un filtro visible de la pantalla. `param` es el query param del endpoint. */
export interface FiltroInforme {
  param: string;
  etiqueta: string;
  tipo: 'select' | 'fecha' | 'numero' | 'texto' | 'periodo';
  /** Opciones de `select` ('' = sin filtro / todos). */
  opciones?: { valor: string; etiqueta: string }[];
  valorInicial?: string;
  /** `texto`: aplica con debounce en vez de al cambiar. */
  debounce?: boolean;
  ancho?: 'normal' | 'ancho';
}

export interface DefinicionInforme {
  /** Id del objetivo táctico, p. ej. 'OTD-VEN-01'. */
  id: string;
  /** Segmento final de la ruta REST: /api/informes/{depto}/{endpoint}. */
  endpoint: string;
  titulo: string;
  descripcion: string;
  icono: string;
  /** Roles que ven el informe en el selector; espeja SecurityConfig. */
  roles: readonly string[];
  filtros: FiltroInforme[];
  columnas: ColumnaInforme[];
  /** Mensaje del estado vacío, específico del informe. */
  vacio: string;
  /** El backend no pagina este informe (pocas filas por naturaleza). */
  sinPaginar?: boolean;
  /**
   * Pinta la barra «avance sobre la meta» con el KPI de porcentaje del sobre.
   * Solo para informes que de verdad miden contra una meta (OTD-VEN-15): un
   * porcentaje cualquiera —una tasa de resolución, una ocupación media— NO es
   * un avance, y presentarlo como tal miente.
   */
  barraAvance?: boolean;
  /**
   * Pinta el gráfico de serie histórica + previsión con su banda, a partir del
   * campo `serie` del sobre.
   *
   * Es opt-in por la misma razón que `barraAvance`: solo la previsión de
   * demanda (fase E2) envía una serie con puntos observados Y previstos, y la
   * regla 1 de §5.1.9 la exige precisamente ahí — «un número solo, sin la serie
   * que lo produjo, no es interpretable». Un informe cualquiera con una columna
   * mensual NO es una previsión y no debe heredar el trazo discontinuo, que
   * afirma que esos puntos no han ocurrido.
   */
  graficoPrevision?: boolean;
}

/** Departamento con sus informes. Un archivo de definiciones por departamento. */
export interface DefinicionDepartamento {
  /** Segmento de la ruta REST y de la URL de la app: 'ventas', 'compras'… */
  departamento: string;
  titulo: string;
  descripcion: string;
  icono: string;
  informes: DefinicionInforme[];
}

/** Tarjeta de resumen que el backend adjunta al sobre. */
export interface KpiInforme {
  etiqueta: string;
  valor: any;
  tipo: TipoValor;
}

/** Sobre estándar de TODOS los informes tácticos. */
export interface SobreInforme {
  items: Record<string, any>[];
  total: number;
  page: number;
  size: number;
  resumen?: KpiInforme[];
  /** VEN-02: 'propio' cuando el backend recortó el informe al vendedor. */
  alcance?: string;
  /**
   * Texto del recorte, cuando el informe quiere decirlo con sus palabras. Sin
   * él, la pantalla usa el de VEN-02 («tus propias ventas»), que en OTD-VEN-19
   * sería falso: allí el recorte es por CARTERA, no por autoría de la venta.
   */
  avisoAlcance?: string;
  /**
   * Coletilla que se pinta junto al título del informe.
   *
   * La estrena OTD-VEN-19 para cumplir la regla 5 de §5.2.9: la fecha ancla va
   * EN EL TÍTULO. La recencia de la alerta se mide contra la última compra
   * registrada en el almacén y no contra el reloj, así que una pantalla sin esa
   * fecha se lee como si fuera de hoy — y si el pipeline se detuvo, no lo es.
   */
  sufijoTitulo?: string;

  // ── Solo en los informes COMPUESTOS (fuente ClickHouse) ────────────────
  /**
   * `false` cuando el almacén analítico no respondió. El sobre llega vacío y
   * con `avisoAnalitica`; NO es un error y no rompe la aplicación — es la
   * degradación declarada: con Docker apagado todo el sistema funciona y solo
   * la analítica se cae, con aviso.
   */
  analiticaDisponible?: boolean;
  avisoAnalitica?: string;
  /**
   * Marca de agua: momento de la última carga del ETL, ya formateado como
   * texto. Un informe analítico puede llevar hasta 24 h de desfase, y uno que
   * no dice de cuándo es su dato miente por omisión.
   */
  datosAl?: string;
  /** Origen del dato, p. ej. 'ClickHouse · retailmind_dwh'. */
  fuente?: string;
  /**
   * SALVEDAD METODOLÓGICA del informe: una limitación de cómo está calculado
   * el dato que hay que leer ANTES que la tabla, no en la documentación.
   *
   * Hoy la envía OTD-INV-09 (y OTD-INV-10 en su modo valorizado): el inventario
   * de meses pasados se valoriza con el costo VIGENTE porque el sistema no
   * guarda costo histórico, así que la serie es «unidades de cada mes a precio
   * de hoy» y NO «lo que valía la bodega aquel mes». Presentarla sin decirlo
   * sería falso, y decirlo solo en el diseño no cuenta: el que lee la pantalla
   * no lee el diseño.
   */
  salvedad?: string;
  /** OTD-INV-10: si el rol recibió además las columnas de valorización. */
  conValorizacion?: boolean;

  [extra: string]: any;
}
