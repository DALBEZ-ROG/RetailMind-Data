import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Usuario } from '../models/usuario.model';
import { PageModel } from '../models/page.model';

@Injectable({ providedIn: 'root' })
export class UsuarioService {
  private readonly base = `${environment.apiUrl}/api/usuarios`;

  constructor(private http: HttpClient) {}

  getAll(page: number, size: number): Observable<PageModel<Usuario>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size);
    return this.http.get<PageModel<Usuario>>(this.base, { params });
  }

  getById(id: string): Observable<Usuario> {
    return this.http.get<Usuario>(`${this.base}/${id}`);
  }
}
