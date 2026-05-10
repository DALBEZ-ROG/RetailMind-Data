export interface EtlResponse {
  success:           boolean;
  mensaje:           string;
  output:            string;
  duracionSegundos:  number;
}

export interface EstadoTabla {
  tabla:               string;
  totalRegistros:      number;
  ultimaActualizacion: string;
}

export interface CargaHistorial {
  semana:               number;
  fechaCarga:           string;
  registrosProcesados:  number;
  registrosNuevos:      number;
}
