import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { UsuarioAdminRow, RolRef } from '../models/operativo.model';

/**
 * Gestión de usuarios del back-office (`/admin-usuarios`, solo ADMIN).
 * La contraseña solo viaja en el ALTA; ni se lee ni se modifica después.
 */
@Injectable({ providedIn: 'root' })
export class UsuariosAdminService {
  private readonly base = `${environment.apiUrl}/api/auth`;

  constructor(private http: HttpClient) {}

  usuarios(): Observable<UsuarioAdminRow[]> {
    return this.http.get<UsuarioAdminRow[]>(`${this.base}/usuarios`);
  }
  roles(): Observable<RolRef[]> { return this.http.get<RolRef[]>(`${this.base}/roles`); }

  crear(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/register`, body);
  }
  editar(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/usuarios/${id}`, body);
  }
  /** Baja/alta LÓGICA: lo que la pantalla presenta como «Eliminar». */
  activar(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/usuarios/${id}/activo`, { activo });
  }
}
