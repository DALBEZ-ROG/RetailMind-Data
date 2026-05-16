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

@Injectable({ providedIn: 'root' })
export class InicializacionService {
  private readonly base = `${environment.apiUrl}/api/init`;

  constructor(private http: HttpClient) {}

  extraerPocketbase(): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/extraer-pocketbase`, {});
  }

  cargarClickhouse(): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/cargar-clickhouse`, {});
  }

  verificarClickhouse(): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/verificar-clickhouse`, {});
  }

  resetSistema(): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/reset-sistema`, {});
  }

  cargaCompleta(): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/carga-completa`, {});
  }

  generarSemana(semana: number): Observable<InicializacionResponse> {
    return this.http.post<InicializacionResponse>(`${this.base}/generar-semana?semana=${semana}`, {});
  }
}
