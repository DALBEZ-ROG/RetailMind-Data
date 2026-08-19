import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Red logística: bodegas, transportistas, métodos, zonas y tarifas de envío.
 *
 * Cierra el defecto D-09: estas cinco tablas sostienen el ciclo de venta —sin
 * bodega no hay pedido, sin zona ni tarifa el checkout no encuentra
 * transportista— y no tenían ninguna pantalla ni endpoint de escritura: solo
 * se poblaban con los scripts de siembra.
 */
@Injectable({ providedIn: 'root' })
export class RedLogisticaService {

  private base = `${environment.apiUrl}/api/admin/red`;

  constructor(private http: HttpClient) {}

  referencias(): Observable<any> { return this.http.get(`${this.base}/referencias`); }

  bodegas(): Observable<any[]> { return this.http.get<any[]>(`${this.base}/bodegas`); }
  crearBodega(b: any): Observable<any> { return this.http.post(`${this.base}/bodegas`, b); }
  editarBodega(id: number, b: any): Observable<any> {
    return this.http.put(`${this.base}/bodegas/${id}`, b);
  }
  activarBodega(id: number, activo: boolean): Observable<any> {
    return this.http.patch(`${this.base}/bodegas/${id}/activo`, { activo });
  }

  transportistas(): Observable<any[]> { return this.http.get<any[]>(`${this.base}/transportistas`); }
  crearTransportista(t: any): Observable<any> { return this.http.post(`${this.base}/transportistas`, t); }
  editarTransportista(id: number, t: any): Observable<any> {
    return this.http.put(`${this.base}/transportistas/${id}`, t);
  }
  activarTransportista(id: number, activo: boolean): Observable<any> {
    return this.http.patch(`${this.base}/transportistas/${id}/activo`, { activo });
  }

  metodos(): Observable<any[]> { return this.http.get<any[]>(`${this.base}/metodos`); }
  crearMetodo(m: any): Observable<any> { return this.http.post(`${this.base}/metodos`, m); }
  editarMetodo(id: number, m: any): Observable<any> {
    return this.http.put(`${this.base}/metodos/${id}`, m);
  }
  activarMetodo(id: number, activo: boolean): Observable<any> {
    return this.http.patch(`${this.base}/metodos/${id}/activo`, { activo });
  }

  zonas(): Observable<any[]> { return this.http.get<any[]>(`${this.base}/zonas`); }
  crearZona(z: any): Observable<any> { return this.http.post(`${this.base}/zonas`, z); }
  editarZona(id: number, z: any): Observable<any> {
    return this.http.put(`${this.base}/zonas/${id}`, z);
  }
  activarZona(id: number, activo: boolean): Observable<any> {
    return this.http.patch(`${this.base}/zonas/${id}/activo`, { activo });
  }

  tarifas(): Observable<any[]> { return this.http.get<any[]>(`${this.base}/tarifas`); }
  crearTarifa(t: any): Observable<any> { return this.http.post(`${this.base}/tarifas`, t); }
  editarTarifa(id: number, t: any): Observable<any> {
    return this.http.put(`${this.base}/tarifas/${id}`, t);
  }
  activarTarifa(id: number, activo: boolean): Observable<any> {
    return this.http.patch(`${this.base}/tarifas/${id}/activo`, { activo });
  }
}
