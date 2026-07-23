import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { MetaVentaRow } from '../models/operativo.model';

/** Metas de venta por período (OTD-VEN-15): fija Gerencia, leen vendedor/analista. */
@Injectable({ providedIn: 'root' })
export class MetasService {
  private readonly base = `${environment.apiUrl}/api/gerencia/metas`;

  constructor(private http: HttpClient) {}

  metas(): Observable<MetaVentaRow[]> { return this.http.get<MetaVentaRow[]>(this.base); }
  crear(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(this.base, body);
  }
  editar(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/${id}`, body);
  }
  activar(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/${id}/activo`, { activo });
  }
}
