import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SobreInforme } from '../models/informe.model';

/**
 * Cliente ÚNICO de los informes tácticos: sirve a los seis departamentos.
 *
 * Convención de ruta: /api/informes/{departamento}/{endpoint}. Como todos los
 * informes devuelven el mismo sobre ({items, total, page, size, resumen}), no
 * hace falta un servicio por departamento — solo una definición nueva.
 *
 * Los filtros vacíos NO se envían: el backend los interpreta como «sin
 * filtro» y así la URL queda legible en las herramientas de red.
 */
@Injectable({ providedIn: 'root' })
export class InformesService {
  private readonly base = `${environment.apiUrl}/api/informes`;

  constructor(private http: HttpClient) {}

  consultar(departamento: string, endpoint: string,
            filtros: Record<string, string | number | null | undefined>): Observable<SobreInforme> {
    let params = new HttpParams();
    Object.entries(filtros).forEach(([clave, valor]) => {
      if (valor !== null && valor !== undefined && valor !== '') {
        params = params.set(clave, String(valor));
      }
    });
    return this.http.get<SobreInforme>(`${this.base}/${departamento}/${endpoint}`, { params });
  }
}
