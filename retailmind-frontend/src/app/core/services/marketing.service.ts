import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CuponRow, UsoCuponRow, PromocionRow, PromocionDetalle, ProductoRef,
  CampanaRow, BannerRow, SuscriptorRow
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class MarketingService {
  private readonly base = `${environment.apiUrl}/api/marketing`;

  constructor(private http: HttpClient) {}

  // Cupones
  cupones(): Observable<CuponRow[]> { return this.http.get<CuponRow[]>(`${this.base}/cupones`); }
  usosCupon(id: number): Observable<UsoCuponRow[]> {
    return this.http.get<UsoCuponRow[]>(`${this.base}/cupones/${id}/usos`);
  }
  crearCupon(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/cupones`, body);
  }
  editarCupon(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/cupones/${id}`, body);
  }
  activarCupon(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/cupones/${id}/activo`, { activo });
  }

  // Promociones
  promociones(): Observable<PromocionRow[]> {
    return this.http.get<PromocionRow[]>(`${this.base}/promociones`);
  }
  promocion(id: number): Observable<PromocionDetalle> {
    return this.http.get<PromocionDetalle>(`${this.base}/promociones/${id}`);
  }
  crearPromocion(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/promociones`, body);
  }
  editarPromocion(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/promociones/${id}`, body);
  }
  activarPromocion(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/promociones/${id}/activo`, { activo });
  }
  asociarProducto(promocionId: number, productoId: number): Observable<unknown> {
    return this.http.post(`${this.base}/promociones/${promocionId}/productos`, { productoId });
  }
  quitarProducto(promocionId: number, productoId: number): Observable<unknown> {
    return this.http.delete(`${this.base}/promociones/${promocionId}/productos/${productoId}`);
  }
  productosRef(): Observable<ProductoRef[]> {
    return this.http.get<ProductoRef[]>(`${this.base}/productos-ref`);
  }

  // Campañas
  campanas(): Observable<CampanaRow[]> { return this.http.get<CampanaRow[]>(`${this.base}/campanas`); }
  crearCampana(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/campanas`, body);
  }
  editarCampana(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/campanas/${id}`, body);
  }
  estadoCampana(id: number, estado: string): Observable<unknown> {
    return this.http.patch(`${this.base}/campanas/${id}/estado`, { estado });
  }

  // Banners
  banners(): Observable<BannerRow[]> { return this.http.get<BannerRow[]>(`${this.base}/banners`); }
  crearBanner(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/banners`, body);
  }
  editarBanner(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/banners/${id}`, body);
  }
  activarBanner(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/banners/${id}/activo`, { activo });
  }

  // Newsletter
  suscriptores(): Observable<SuscriptorRow[]> {
    return this.http.get<SuscriptorRow[]>(`${this.base}/newsletter`);
  }
  altaSuscriptor(email: string, clienteId: number | null): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/newsletter`, { email, clienteId });
  }
  activarSuscriptor(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/newsletter/${id}/activo`, { activo });
  }
}
