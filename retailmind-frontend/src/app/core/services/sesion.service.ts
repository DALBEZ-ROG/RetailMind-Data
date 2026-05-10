import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Sesion } from '../models/sesion.model';
import { PageModel } from '../models/page.model';

@Injectable({ providedIn: 'root' })
export class SesionService {
  private readonly base = `${environment.apiUrl}/api/sesiones`;

  constructor(private http: HttpClient) {}

  getAll(page: number, size: number): Observable<PageModel<Sesion>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size);
    return this.http.get<PageModel<Sesion>>(this.base, { params });
  }

  getById(id: string): Observable<Sesion> {
    return this.http.get<Sesion>(`${this.base}/${id}`);
  }

  getByUsuario(userId: string, page: number, size: number): Observable<PageModel<Sesion>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size);
    return this.http.get<PageModel<Sesion>>(`${this.base}/usuario/${userId}`, { params });
  }

  getByCanal(canalId: number, page: number, size: number): Observable<PageModel<Sesion>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size);
    return this.http.get<PageModel<Sesion>>(`${this.base}/canal/${canalId}`, { params });
  }
}
