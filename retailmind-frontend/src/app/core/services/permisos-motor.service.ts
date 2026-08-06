import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  MapaSeguridad, PermisosPage, PoliticaRls, ObjetoAdministrable, CambioPermiso,
  RolPersonalizado, ResultadoRol
} from '../models/seguridad.model';

/**
 * Mapa de la seguridad del motor y administración de privilegios (script 86).
 * Solo ADMIN: los seis endpoints están enumerados uno a uno en SecurityConfig.
 */
@Injectable({ providedIn: 'root' })
export class PermisosMotorService {
  private readonly base = `${environment.apiUrl}/api/seguridad`;

  constructor(private http: HttpClient) {}

  mapa(): Observable<MapaSeguridad> {
    return this.http.get<MapaSeguridad>(`${this.base}/mapa`);
  }

  permisos(f: { rol?: string; tabla?: string; privilegio?: string; tipo?: string }):
      Observable<PermisosPage> {
    let params = new HttpParams();
    if (f.rol)        params = params.set('rol', f.rol);
    if (f.tabla)      params = params.set('tabla', f.tabla);
    if (f.privilegio) params = params.set('privilegio', f.privilegio);
    if (f.tipo)       params = params.set('tipo', f.tipo);
    return this.http.get<PermisosPage>(`${this.base}/permisos`, { params });
  }

  politicas(f: { tabla?: string; rol?: string }): Observable<PoliticaRls[]> {
    let params = new HttpParams();
    if (f.tabla) params = params.set('tabla', f.tabla);
    if (f.rol)   params = params.set('rol', f.rol);
    return this.http.get<PoliticaRls[]>(`${this.base}/politicas`, { params });
  }

  objetos(): Observable<ObjetoAdministrable[]> {
    return this.http.get<ObjetoAdministrable[]>(`${this.base}/objetos`);
  }

  conceder(b: { rol: string; tabla: string; columna?: string | null; privilegio: string }):
      Observable<CambioPermiso> {
    return this.http.post<CambioPermiso>(`${this.base}/permisos/conceder`, b);
  }

  revocar(b: { rol: string; tabla: string; columna?: string | null; privilegio: string }):
      Observable<CambioPermiso> {
    return this.http.post<CambioPermiso>(`${this.base}/permisos/revocar`, b);
  }

  // ── Roles propios (script 87) ────────────────────────────────────────────

  rolesPersonalizados(): Observable<RolPersonalizado[]> {
    return this.http.get<RolPersonalizado[]>(`${this.base}/roles-personalizados`);
  }

  crearRol(b: { codigo: string; nombre: string; rolBase: string | null }):
      Observable<ResultadoRol> {
    return this.http.post<ResultadoRol>(`${this.base}/roles-personalizados`, b);
  }

  eliminarRol(codigo: string): Observable<ResultadoRol> {
    return this.http.delete<ResultadoRol>(
      `${this.base}/roles-personalizados/${encodeURIComponent(codigo)}`);
  }
}
