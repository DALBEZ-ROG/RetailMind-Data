import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LogAccesoPage } from '../models/operativo.model';

/** Intentos de acceso al sistema (OTD-GER-09, script 53): solo Admin/Gerencia. */
@Injectable({ providedIn: 'root' })
export class AccesosService {
  private readonly base = `${environment.apiUrl}/api/seguridad/accesos`;

  constructor(private http: HttpClient) {}

  consultar(f: { desde?: string; hasta?: string; resultado?: string; email?: string;
                 page: number; size: number; }): Observable<LogAccesoPage> {
    let params = new HttpParams()
      .set('page', f.page).set('size', f.size);
    if (f.desde)     params = params.set('desde', f.desde);
    if (f.hasta)     params = params.set('hasta', f.hasta);
    if (f.resultado) params = params.set('resultado', f.resultado);
    if (f.email)     params = params.set('email', f.email);
    return this.http.get<LogAccesoPage>(this.base, { params });
  }
}
