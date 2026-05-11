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
