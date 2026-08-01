import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Parte de una tarea concreta dentro de una corrida. */
export interface TareaCorrida {
  tarea: string;
  resultado: string;
  filas: number;
  segundos: number;
}

/** Lo que devuelve GET /api/dwh/estado. */
export interface EstadoDwh {
  analiticaDisponible: boolean;
  hayCorridas: boolean;
  enCurso: boolean;
  corridaId?: string;
  resultado?: string;
  exito?: boolean;
  inicio?: string;
  fin?: string;
  duracionSeg?: number;
  tareasTotales?: number;
  tareasCompletadas?: number;
  filas?: number;
  validacion?: string;
  validacionMensaje?: string;
  controles?: number;
  mensaje?: string;
  errores?: { tarea: string; resultado: string; mensaje: string }[];
  tareas?: TareaCorrida[];
  /** Misma marca «Datos al …» que enseñan los informes compuestos. */
  datosAl?: string;
}

/** Lo que devuelve POST /api/dwh/actualizar (202 Accepted). */
export interface DisparoDwh {
  corridaId: string;
  enCurso: boolean;
  mensaje: string;
}

/**
 * Actualización del data warehouse. Solo ADMIN y GERENTE: la ruta
 * `/api/dwh/**` lo exige en SecurityConfig, que es quien realmente decide.
 */
@Injectable({ providedIn: 'root' })
export class DwhService {
  private readonly base = `${environment.apiUrl}/api/dwh`;

  constructor(private http: HttpClient) {}

  actualizar(): Observable<DisparoDwh> {
    return this.http.post<DisparoDwh>(`${this.base}/actualizar`, {});
  }

  estado(): Observable<EstadoDwh> {
    return this.http.get<EstadoDwh>(`${this.base}/estado`);
  }
}
