import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { VentanaHoraria } from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class HorariosService {
  private readonly base = `${environment.apiUrl}/api/admin/horarios`;

  constructor(private http: HttpClient) {}

  listar(): Observable<VentanaHoraria[]> { return this.http.get<VentanaHoraria[]>(this.base); }

  crear(body: {
    rolGrupo: string; diaSemana: number; horaInicio: string; horaFin: string; activo: boolean;
  }): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(this.base, body);
  }

  editar(id: number, body: { horaInicio: string; horaFin: string; activo: boolean }): Observable<unknown> {
    return this.http.put(`${this.base}/${id}`, body);
  }
}
