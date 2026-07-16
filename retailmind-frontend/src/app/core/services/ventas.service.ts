import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { HttpParams } from '@angular/common/http';
import {
  ItemPedidoReq, PedidoVentaRow, PedidoVentaDetalle,
  FacturaVenta, PaginaFacturasVenta, PagoClienteRes, EnvioDetalle,
  SeguimientoRow
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

  /** Cobro del pedido: compuerta previa a facturar/despachar (monto null = saldo). */
  registrarPago(pedidoId: number, body: {
    metodoPagoId: number; monto?: number | null; referencia?: string;
  }): Observable<PagoClienteRes> {
    return this.http.post<PagoClienteRes>(`${this.base}/pedidos/${pedidoId}/pagos`, body);
  }

  emitirFactura(pedidoId: number): Observable<FacturaVenta> {
    return this.http.post<FacturaVenta>(`${this.base}/pedidos/${pedidoId}/factura`, {});
  }
  facturas(q: string, page: number, size: number): Observable<PaginaFacturasVenta> {
    const params = new HttpParams().set('q', q).set('page', page).set('size', size);
    return this.http.get<PaginaFacturasVenta>(`${this.base}/facturas`, { params });
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
  /** Marca la entrega del pedido despachado (cierra la logística). */
  entregar(pedidoId: number, observacion?: string): Observable<PedidoVentaDetalle> {
    return this.http.post<PedidoVentaDetalle>(
      `${this.base}/pedidos/${pedidoId}/entrega`, { observacion });
  }
  envio(id: number): Observable<EnvioDetalle> {
    return this.http.get<EnvioDetalle>(`${this.base}/envios/${id}`);
  }
  seguimiento(envioId: number): Observable<SeguimientoRow[]> {
    return this.http.get<SeguimientoRow[]>(`${this.base}/envios/${envioId}/seguimiento`);
  }

  // Devoluciones (RMA / logística inversa): core/services/devoluciones.service.ts
}
