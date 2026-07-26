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
  | 'chip';       // píldora de estado (usa `chip` para el color)

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
  [extra: string]: any;
}
