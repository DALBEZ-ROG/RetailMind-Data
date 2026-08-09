import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface InicializacionResponse {
  success: boolean;
  mensaje: string;
  output: string;
  duracionSegundos: number;
  registrosCargados: number;
}

/** Conteo real de una de las 13 tablas auxiliares de la base legada. */
export interface DimensionEstado {
  tabla: string;
  filas: number;
}

/** Foto del estado de la capa legada. Cada campo sale de una consulta real. */
export interface EstadoLegado {
  base: string;
  clickhouseConectado: boolean;
  clickhouseVersion: string | null;
  errorConexion: string | null;
  factEventos: number;
  factEventosConDatos: boolean;
  semanasDistintas: number;
  dimensiones: DimensionEstado[];
  dimensionesConDatos: number;
  dimensionesTotales: number;
  parquetExiste: boolean;
  parquetRuta: string;
  parquetBytes: number;
  parquetFecha: number;
}

/** Una semana de `fact_eventos` con su conteo real y su procedencia. */
export interface SemanaEstado {
  semana: number;
  filas: number;
  eventosTienda: number;
  estado: 'ocupada' | 'tienda';
  motivo: string;
}

export interface SemanasEstado {
  disponible: boolean;
  error?: string;
  semanas: SemanaEstado[];
  totalRegistros: number;
  semanasCargadas: number;
  eventosTienda: number;
  libres: number[];
  proximaLibre: number | null;
}

/**
 * Capa legada de ClickHouse (base `retailmind`).
 *
 * NO REINTRODUCIR `resetSistema()`, `cargaCompleta()`, `cargarClickhouse()` ni
 * `extraerPocketbase()`: sus endpoints se suprimieron el 2026-08-08 porque
 * disparaban `DROP TABLE` y `TRUNCATE` sobre `fact_eventos` y sus dimensiones,
 * 2.823.245 filas irreproducibles. El motivo completo esta en el javadoc de
 * `InicializacionController`.
 */
@Injectable({ providedIn: 'root' })
export class InicializacionService {
  private readonly base = `${environment.apiUrl}/api/init`;

  constructor(private http: HttpClient) {}

  /** Estado REAL: conexion, filas, dimensiones y parquet en disco. */
  estado(): Observable<EstadoLegado> {
    return this.http.get<EstadoLegado>(`${this.base}/estado`);
  }

  /** Semanas con su conteo real (`GROUP BY semana`) y cuales quedan libres. */
  semanas(): Observable<SemanasEstado> {
    return this.http.get<SemanasEstado>(`${this.base}/semanas`);
  }

  /** Diagnostico de solo lectura: vuelca la salida cruda del verificador. */
  verificarClickhouse(): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/verificar-clickhouse`, {});
  }

  generarSemana(semana: number): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/generar-semana?semana=${semana}`, {});
  }
}
