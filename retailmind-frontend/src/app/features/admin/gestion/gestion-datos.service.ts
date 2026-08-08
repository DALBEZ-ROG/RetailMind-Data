import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class GestionDatosService {
  private readonly base = `${environment.apiUrl}/api/gestion`;

  constructor(private http: HttpClient) {}

  // ── Fact Eventos ───────────────────────────────────────────────────────────
  getFactEventos(page: number, size: number, semana?: number): Observable<any> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (semana) params = params.set('semana', semana);
    return this.http.get(`${this.base}/fact-eventos`, { params });
  }

  // SOLO LECTURA: `fact_eventos` no tiene update ni delete (deuda A-3,
  // 2026-08-07). `event_pk` no identifica una fila —52-139 eventos comparten
  // cada valor—, así que las dos operaciones actuaban sobre un centenar de
  // filas creyendo actuar sobre una. Los endpoints tampoco existen ya en el
  // backend. Ver `GestionDatosService` (Java) para el motivo completo.

  // ── Dimensiones genéricas ──────────────────────────────────────────────────
  getDimension(tabla: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/${tabla}`);
  }

  createDimension(tabla: string, body: any): Observable<any> {
    return this.http.post(`${this.base}/${tabla}`, body);
  }

  updateDimension(tabla: string, id: number, body: any): Observable<any> {
    return this.http.put(`${this.base}/${tabla}/${id}`, body);
  }

  deleteDimension(tabla: string, id: number | string): Observable<any> {
    return this.http.delete(`${this.base}/${tabla}/${id}`);
  }

  // ── Productos ──────────────────────────────────────────────────────────────
  getProductos(page: number, size: number): Observable<any> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get(`${this.base}/dim-producto`, { params });
  }

  createProducto(body: any): Observable<any> {
    return this.http.post(`${this.base}/dim-producto`, body);
  }

  updateProducto(id: string, body: any): Observable<any> {
    return this.http.put(`${this.base}/dim-producto/${id}`, body);
  }

  deleteProducto(id: string): Observable<any> {
    return this.http.delete(`${this.base}/dim-producto/${id}`);
  }

  // ── Usuarios ───────────────────────────────────────────────────────────────
  getUsuarios(page: number, size: number): Observable<any> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get(`${this.base}/dim-usuario`, { params });
  }

  deleteUsuario(id: string): Observable<any> {
    return this.http.delete(`${this.base}/dim-usuario/${id}`);
  }
}
