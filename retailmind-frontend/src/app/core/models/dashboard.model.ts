export interface GrupoConteo {
  nombre: string;
  total: number;
}

export interface DashboardResumen {
  totalSesiones:          number;
  totalUsuarios:          number;
  totalConversiones:      number;
  tasaConversion:         number;
  totalAbandonos:         number;
  totalEventos:           number;
  semanasCargadas:        number;
  sesionesPorCanal:       GrupoConteo[];
  sesionesPorRegion:      GrupoConteo[];
  sesionesPorDispositivo: GrupoConteo[];
}

/** Un punto de la serie temporal: una semana cargada de `fact_eventos`. */
export interface PuntoSemanal {
  semana:       number;
  eventos:      number;
  sesiones:     number;
  conversiones: number;
  tasa:         number;
  /** `false` cuando la semana no llega al mínimo de sesiones para que su tasa signifique algo. */
  medible:      boolean;
}

/**
 * Series y desgloses de `GET /api/dashboard/series`.
 *
 * Los desgloses miden EVENTOS y no sesiones: `session_id` no identifica una
 * sesión en esta tabla (el 87 % toca 2-3 canales), así que contar sesiones por
 * canal suma el 231 % de las que existen. Detalle en `DashboardSeriesService`.
 */
export interface DashboardSeries {
  disponible:             boolean;
  error?:                 string;
  semanal:                PuntoSemanal[];
  acciones:               GrupoConteo[];
  duracion:               GrupoConteo[];
  eventosPorCanal:        GrupoConteo[];
  eventosPorDispositivo:  GrupoConteo[];
  eventosPorRegion:       GrupoConteo[];
  minSesionesTasa:        number;
}
