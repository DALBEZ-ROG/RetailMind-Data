import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ItemOrdenReq, ItemRecepcionReq, OrdenCompraRow, OrdenCompraDetalle,
  FacturaCompra, CuentaPorPagarRow
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class ComprasService {
  private readonly base = `${environment.apiUrl}/api/compras`;

  constructor(private http: HttpClient) {}

  emitirOrden(body: {
    proveedorId: number; bodegaId: number; monedaId?: number | null;
    fechaEntregaEsperada?: string | null; observacion?: string; items: ItemOrdenReq[];
  }): Observable<OrdenCompraDetalle> {
    return this.http.post<OrdenCompraDetalle>(`${this.base}/ordenes`, body);
  }

  ordenes(): Observable<OrdenCompraRow[]> { return this.http.get<OrdenCompraRow[]>(`${this.base}/ordenes`); }

  /** CU-O-12: aprobar orden emitida (enviada → confirmada). Solo GERENTE/ADMIN. */
  aprobarOrden(id: number):
      Observable<{ id: number; numero: string; estadoAnterior: string; estado: string }> {
    return this.http.post<any>(`${this.base}/ordenes/${id}/aprobar`, {});
  }
  orden(id: number): Observable<OrdenCompraDetalle> {
    return this.http.get<OrdenCompraDetalle>(`${this.base}/ordenes/${id}`);
  }

  registrarRecepcion(ordenId: number, body: { observacion?: string; items: ItemRecepcionReq[] }):
      Observable<{ id: number; numero: string; ordenCompraId: number; estadoOrden: string }> {
    return this.http.post<any>(`${this.base}/ordenes/${ordenId}/recepciones`, body);
  }

  /** El número de factura lo genera el backend; no se envía. */
  registrarFactura(ordenId: number): Observable<FacturaCompra> {
    return this.http.post<FacturaCompra>(`${this.base}/ordenes/${ordenId}/facturas`, {});
  }
  factura(id: number): Observable<FacturaCompra> {
    return this.http.get<FacturaCompra>(`${this.base}/facturas/${id}`);
  }
  facturaPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/facturas/${id}/pdf`, { responseType: 'blob' });
  }

  cuentasPorPagar(): Observable<CuentaPorPagarRow[]> {
    return this.http.get<CuentaPorPagarRow[]>(`${this.base}/cuentas-por-pagar`);
  }
  registrarPago(cuentaId: number, body: { monto: number; metodoPagoId: number; referencia?: string }):
      Observable<{ pagoId: number; saldoPendiente: number; estadoCuenta: string }> {
    return this.http.post<any>(`${this.base}/cuentas-por-pagar/${cuentaId}/pagos`, body);
  }
}
