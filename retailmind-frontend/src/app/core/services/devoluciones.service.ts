import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CatalogoRef, DevolucionRow, DevolucionRma, ElegibilidadDevolucion,
  ItemDevolucionReq
} from '../models/operativo.model';

/** RMA / logística inversa: un método por transición (/api/devoluciones). */
@Injectable({ providedIn: 'root' })
export class DevolucionesService {
  private readonly base = `${environment.apiUrl}/api/devoluciones`;

  constructor(private http: HttpClient) {}

  listar(estado?: string): Observable<DevolucionRow[]> {
    const params = estado ? new HttpParams().set('estado', estado) : undefined;
    return this.http.get<DevolucionRow[]>(this.base, { params });
  }
  detalle(id: number): Observable<DevolucionRma> {
    return this.http.get<DevolucionRma>(`${this.base}/${id}`);
  }
  motivos(): Observable<CatalogoRef[]> {
    return this.http.get<CatalogoRef[]>(`${this.base}/motivos-ref`);
  }
  transportistas(): Observable<CatalogoRef[]> {
    return this.http.get<CatalogoRef[]>(`${this.base}/transportistas-ref`);
  }
  elegibilidad(pedidoId: number): Observable<ElegibilidadDevolucion> {
    return this.http.get<ElegibilidadDevolucion>(`${this.base}/pedido/${pedidoId}/elegibilidad`);
  }
  guiaPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/${id}/guia-pdf`, { responseType: 'blob' });
  }

  solicitar(body: { pedidoId: number; motivoCodigo: string; descripcion?: string;
                    items: ItemDevolucionReq[] }): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(this.base, body);
  }
  revision(id: number): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/revision`, {});
  }
  aprobar(id: number, transportistaId?: number | null): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/aprobar`, { transportistaId });
  }
  rechazar(id: number, motivo: string): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/rechazar`, { motivo });
  }
  transito(id: number, observacion?: string): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/transito`, { observacion });
  }
  recepcion(id: number, observacion?: string): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/recepcion`, { observacion });
  }
  inspeccionar(id: number, items: { devolucionDetalleId: number; resultado: string;
                                    nota?: string }[]): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/inspeccion`, { items });
  }
  reembolsar(id: number, metodo: string, referencia?: string): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/reembolso`, { metodo, referencia });
  }
  cerrar(id: number): Observable<DevolucionRma> {
    return this.http.post<DevolucionRma>(`${this.base}/${id}/cerrar`, {});
  }
}
