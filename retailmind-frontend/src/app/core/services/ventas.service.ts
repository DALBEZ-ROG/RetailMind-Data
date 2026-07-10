import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ItemPedidoReq, ItemDevolucionReq, PedidoVentaRow, PedidoVentaDetalle,
  FacturaVenta, EnvioDetalle, SeguimientoRow, DevolucionDetalle
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class VentasService {
  private readonly base = `${environment.apiUrl}/api/ventas`;

  constructor(private http: HttpClient) {}

  crearPedido(body: { clienteId: number; bodegaId: number; canal: string; items: ItemPedidoReq[] }):
      Observable<PedidoVentaDetalle> {
    return this.http.post<PedidoVentaDetalle>(`${this.base}/pedidos`, body);
  }
  pedidos(): Observable<PedidoVentaRow[]> { return this.http.get<PedidoVentaRow[]>(`${this.base}/pedidos`); }
  pedido(id: number): Observable<PedidoVentaDetalle> {
    return this.http.get<PedidoVentaDetalle>(`${this.base}/pedidos/${id}`);
  }
  crearNota(pedidoId: number, nota: string, esVisibleCliente: boolean): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/pedidos/${pedidoId}/notas`,
      { nota, esVisibleCliente });
  }

  emitirFactura(pedidoId: number): Observable<FacturaVenta> {
    return this.http.post<FacturaVenta>(`${this.base}/pedidos/${pedidoId}/factura`, {});
  }
  factura(id: number): Observable<FacturaVenta> {
    return this.http.get<FacturaVenta>(`${this.base}/facturas/${id}`);
  }
  facturaPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/facturas/${id}/pdf`, { responseType: 'blob' });
  }

  despachar(pedidoId: number, body: {
    transportistaId: number; metodoEnvioId: number; bodegaId?: number | null; observacion?: string;
  }): Observable<EnvioDetalle> {
    return this.http.post<EnvioDetalle>(`${this.base}/pedidos/${pedidoId}/despacho`, body);
  }
  envio(id: number): Observable<EnvioDetalle> {
    return this.http.get<EnvioDetalle>(`${this.base}/envios/${id}`);
  }
  seguimiento(envioId: number): Observable<SeguimientoRow[]> {
    return this.http.get<SeguimientoRow[]>(`${this.base}/envios/${envioId}/seguimiento`);
  }

  procesarDevolucion(pedidoId: number, body: {
    motivoCodigo: string; bodegaId: number; descripcion?: string; items: ItemDevolucionReq[];
  }): Observable<DevolucionDetalle> {
    return this.http.post<DevolucionDetalle>(`${this.base}/pedidos/${pedidoId}/devolucion`, body);
  }
  devolucion(id: number): Observable<DevolucionDetalle> {
    return this.http.get<DevolucionDetalle>(`${this.base}/devoluciones/${id}`);
  }
}
