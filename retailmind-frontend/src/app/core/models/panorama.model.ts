/**
 * PANORAMA DEL NEGOCIO — la foto de conjunto del comercio.
 *
 * El sobre reutiliza la forma de un tablero (kpis + bloques + marca de agua +
 * degradación) porque la pantalla se lee igual, pero añade `almacen`: la franja
 * que dice si lo de arriba se puede creer.
 */

export interface KpiPanorama {
  etiqueta: string;
  valor: number | string | null;
  /** moneda | numero | porcentaje — decide el formateo, no el color. */
  tipo: string;
  /** El denominador de la cifra. Obligatorio en el backend, nunca vacío. */
  nota: string;
}

export interface BloquePanorama {
  id: string;
  titulo: string;
  /** serie | barras | ranking */
  visualizacion: string;
  denominador: string;
  items: Record<string, any>[];
  filas: number;
  /** Cómo hay que leer estas cifras. Se pinta ENCIMA del gráfico. */
  salvedad?: string;
}

/** Estado del pipeline. Sin esto, seis gráficos bonitos no prueban nada. */
export interface EstadoAlmacen {
  ultimaCorrida: string;
  validacion: string;
  mensajeValidacion: string;
  tablasPublicadas: number;
  filasPublicadas: number;
  tablasConFallo: number;
}

export interface Panorama {
  tablero: string;
  titulo: string;
  kpis: KpiPanorama[];
  bloques: BloquePanorama[];
  salvedades: string[];
  almacen?: EstadoAlmacen;
  analiticaDisponible: boolean;
  avisoAnalitica?: string;
  fuente?: string;
  /** Ya viene FORMATEADA como texto: una fecha serializada la corre un día. */
  datosAl?: string;
  tablasFuente?: string[];
}
