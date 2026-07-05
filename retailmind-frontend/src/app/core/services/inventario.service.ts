import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TransferenciaRow } from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class InventarioService {
  private readonly base = `${environment.apiUrl}/api/inventario`;

  constructor(private http: HttpClient) {}

  transferir(body: {
    varianteId: number; bodegaOrigenId: number; bodegaDestinoId: number;
    cantidad: number; observacion?: string;
  }): Observable<TransferenciaRow> {
    return this.http.post<TransferenciaRow>(`${this.base}/transferencias`, body);
  }

  transferencias(): Observable<TransferenciaRow[]> {
    return this.http.get<TransferenciaRow[]>(`${this.base}/transferencias`);
  }
}
