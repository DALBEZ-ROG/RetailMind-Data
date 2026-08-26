import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  TransferenciaRow, AjusteRow, AjusteResultado, KardexRow, StockRow,
  ExistenciaBodegaRow, PaginaExistencias
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

  /**
   * Existencias por variante. Todos los criterios viajan al SERVIDOR: son
   * 6.224 variantes y filtrar en el navegador exigiría descargarlas todas.
   * `estado` y `orden` son listas blancas del backend — un valor no previsto
   * devuelve 400 y no llega al SQL.
   */
  existencias(f: {
    q?: string; bodegaId?: number | null; estado?: string; orden?: string;
    page?: number; size?: number;
  }): Observable<PaginaExistencias> {
    let params = new HttpParams();
    if (f.q)                params = params.set('q', f.q);
    if (f.bodegaId != null) params = params.set('bodegaId', f.bodegaId);
    if (f.estado)           params = params.set('estado', f.estado);
    if (f.orden)            params = params.set('orden', f.orden);
    params = params.set('page', f.page ?? 0).set('size', f.size ?? 25);
    return this.http.get<PaginaExistencias>(`${this.base}/existencias`, { params });
  }

  existenciasPorBodega(varianteId: number): Observable<ExistenciaBodegaRow[]> {
    return this.http.get<ExistenciaBodegaRow[]>(
      `${this.base}/existencias/${varianteId}/bodegas`);
  }

  kardex(varianteId?: number | null, bodegaId?: number | null): Observable<KardexRow[]> {
    let params = new HttpParams();
    if (varianteId != null) params = params.set('varianteId', varianteId);
    if (bodegaId != null)   params = params.set('bodegaId', bodegaId);
    return this.http.get<KardexRow[]>(`${this.base}/kardex`, { params });
  }
}
