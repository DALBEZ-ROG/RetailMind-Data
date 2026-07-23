import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  TransferenciaRow, AjusteRow, AjusteResultado, KardexRow, StockRow
} from '../models/operativo.model';

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

  registrarAjuste(body: {
    varianteId: number; bodegaId: number; tipo: 'entrada' | 'salida';
    cantidad: number; motivo: string;
  }): Observable<AjusteResultado> {
    return this.http.post<AjusteResultado>(`${this.base}/ajustes`, body);
  }

  ajustes(): Observable<AjusteRow[]> {
    return this.http.get<AjusteRow[]>(`${this.base}/ajustes`);
  }

  anularAjuste(id: number, motivo: string): Observable<{ id: number; estado: string }> {
    return this.http.post<{ id: number; estado: string }>(`${this.base}/ajustes/${id}/anular`, { motivo });
  }

  /** OTD-INV-08: fija stock mínimo y máximo de una variante en una bodega. */
  actualizarNiveles(body: {
    varianteId: number; bodegaId: number; stockMinimo: number; stockMaximo: number | null;
  }): Observable<StockRow> {
    return this.http.put<StockRow>(`${this.base}/niveles`, body);
  }

  kardex(varianteId?: number | null, bodegaId?: number | null): Observable<KardexRow[]> {
    let params = new HttpParams();
    if (varianteId != null) params = params.set('varianteId', varianteId);
    if (bodegaId != null)   params = params.set('bodegaId', bodegaId);
    return this.http.get<KardexRow[]>(`${this.base}/kardex`, { params });
  }
}
