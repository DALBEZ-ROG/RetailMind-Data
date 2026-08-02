import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SobreTablero } from '../models/tablero.model';

/**
 * Cliente ÚNICO de los TABLEROS DE DIRECCIÓN (nivel estratégico).
 *
 * Convención de ruta: /api/tableros/{tablero}. Como todos los tableros
 * devuelven el mismo sobre ({tablero, kpis, bloques, salvedades, datosAl…}), no
 * hace falta un servicio por tablero: uno nuevo es una definición nueva.
 *
 * Va aparte de `InformesService` a propósito, y no es solo por la ruta: un
 * informe devuelve UN conjunto de filas paginado y un tablero devuelve VARIOS
 * bloques sin paginar. Meter los dos contratos en un servicio obligaría a que
 * el tipo de retorno fuera la unión de ambos, y la pantalla tendría que
 * comprobar cuál le tocó.
 *
 * Los filtros vacíos NO se envían: el backend los interpreta como «sin filtro».
 */
@Injectable({ providedIn: 'root' })
export class TablerosService {
  private readonly base = `${environment.apiUrl}/api/tableros`;

  constructor(private http: HttpClient) {}

  consultar(tablero: string,
            filtros: Record<string, string | number | null | undefined>):
            Observable<SobreTablero> {
    let params = new HttpParams();
    Object.entries(filtros).forEach(([clave, valor]) => {
      if (valor !== null && valor !== undefined && valor !== '') {
        params = params.set(clave, String(valor));
      }
    });
    return this.http.get<SobreTablero>(`${this.base}/${tablero}`, { params });
  }
}
