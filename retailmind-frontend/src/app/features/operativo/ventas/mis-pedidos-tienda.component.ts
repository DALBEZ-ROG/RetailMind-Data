import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { VentasService } from '../../../core/services/ventas.service';
import { DevolucionesService } from '../../../core/services/devoluciones.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  CatalogoRef, DevolucionRow, DevolucionRma, ElegibilidadDevolucion,
  PedidoVentaRow, PedidoVentaDetalle, SeguimientoRow
} from '../../../core/models/operativo.model';

/**
 * CU-O-20: MIS PEDIDOS (rol CLIENTE). RLS (app.cliente_id) devuelve solo lo
 * suyo. Además del seguimiento, aquí NACE la devolución RMA: en un pedido
 * entregado (30 días de plazo) el cliente elige ítems + motivo; la solicitud
 * crea un ticket de soporte y el cliente sigue el estado y descarga la guía
 * de retorno (PDF) cuando soporte aprueba.
 */
@Component({
  selector: 'app-mis-pedidos-tienda',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, MatTableModule, MatIconModule,
    MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSnackBarModule, MatTooltipModule],
  templateUrl: './mis-pedidos-tienda.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class MisPedidosTiendaComponent implements OnInit {

  pedidos: PedidoVentaRow[] = [];
  detalle: PedidoVentaDetalle | null = null;
  seguimiento: SeguimientoRow[] = [];
  loading = true;

  // ── Devolución RMA del cliente ──
  devoluciones: DevolucionRow[] = [];
  devolucion: DevolucionRma | null = null;
  elegibilidad: ElegibilidadDevolucion | null = null;
  motivos: CatalogoRef[] = [];
  motivoCodigo: string | null = null;
  descripcionDevolucion = '';
  cantidades: Record<number, number> = {};
  solicitando = false;

  columnas = ['numero', 'fecha', 'estado', 'total', 'acciones'];
  columnasDevolucion = ['numero', 'pedido', 'motivo', 'estado', 'monto', 'acciones'];

  constructor(private ventas: VentasService, private rma: DevolucionesService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.cargar();
    this.cargarDevoluciones();
  }

  cargar(): void {
    this.loading = true;
    this.ventas.pedidos().subscribe({
      next: data => { this.pedidos = data; this.loading = false; },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudieron cargar tus pedidos'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  cargarDevoluciones(): void {
    this.rma.listar().subscribe(d => this.devoluciones = d);
  }

  verPedido(id: number): void {
    this.elegibilidad = null;
    this.ventas.pedido(id).subscribe({
      next: p => {
        this.detalle = p;
        this.seguimiento = [];
        // Seguimiento del envío del pedido (RLS: solo el suyo)
        if (p.envio) {
          this.ventas.seguimiento(p.envio.id).subscribe(s => this.seguimiento = s);
        }
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar el pedido'), 'Cerrar', { duration: 4000 })
    });
  }

  /** PDF de la factura del pedido (el RLS de la BD lo limita a SUS facturas). */
  verFacturaPdf(): void {
    if (!this.detalle?.factura) return;
    this.ventas.facturaPdf(this.detalle.factura.id).subscribe({
      next: blob => this.abrirBlob(blob),
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }

  // ── Solicitar devolución (nace aquí el RMA) ──────────────────────────

  /** El botón aparece en estados devolvibles; el backend confirma plazo/cupos. */
  get puedeDevolver(): boolean {
    return !!this.detalle && ['entregado', 'devuelto', 'despachado'].includes(this.detalle.estado);
  }

  prepararDevolucion(): void {
    if (!this.detalle) return;
    this.rma.elegibilidad(this.detalle.id).subscribe({
      next: e => {
        this.elegibilidad = e;
        this.cantidades = {};
        e.items.forEach(it => this.cantidades[it.pedido_detalle_id] = 0);
        this.motivoCodigo = null;
        this.descripcionDevolucion = '';
        if (!this.motivos.length) {
          this.rma.motivos().subscribe(m => this.motivos = m);
        }
        if (!e.elegible) {
          this.snackBar.open('Este pedido ya no es elegible para devolución', 'Cerrar', { duration: 4000 });
        }
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo evaluar la devolución'), 'Cerrar', { duration: 4000 })
    });
  }

  solicitarDevolucion(): void {
    if (!this.elegibilidad || this.solicitando) return;
    if (!this.motivoCodigo) {
      this.snackBar.open('Selecciona el motivo de la devolución', 'Cerrar', { duration: 3000 });
      return;
    }
    const items = this.elegibilidad.items
      .filter(it => (this.cantidades[it.pedido_detalle_id] || 0) > 0)
      .map(it => ({ pedidoDetalleId: it.pedido_detalle_id,
                    cantidad: this.cantidades[it.pedido_detalle_id] }));
    if (!items.length) {
      this.snackBar.open('Indica al menos una cantidad a devolver', 'Cerrar', { duration: 3000 });
      return;
    }
    this.solicitando = true;
    this.rma.solicitar({
      pedidoId: this.elegibilidad.pedido_id, motivoCodigo: this.motivoCodigo,
      descripcion: this.descripcionDevolucion, items
    }).subscribe({
      next: d => {
        this.solicitando = false;
        this.elegibilidad = null;
        this.devolucion = d;
        this.snackBar.open(`Devolución ${d.numero} solicitada — soporte la revisará (ticket ${d.ticket_numero})`,
          'OK', { duration: 5000, panelClass: ['snack-success'] });
        this.cargarDevoluciones();
      },
      error: e => {
        this.solicitando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo solicitar la devolución'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  verDevolucion(id: number): void {
    this.rma.detalle(id).subscribe({
      next: d => this.devolucion = d,
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar la devolución'), 'Cerrar', { duration: 4000 })
    });
  }

  /** Guía de retorno en PDF (existe cuando soporte aprueba). */
  verGuiaPdf(): void {
    if (!this.devolucion) return;
    this.rma.guiaPdf(this.devolucion.id).subscribe({
      next: blob => this.abrirBlob(blob),
      error: () => this.snackBar.open('La guía se genera cuando soporte aprueba la devolución', 'Cerrar', { duration: 4000 })
    });
  }

  chipDevolucion(estado: string): string {
    if (['aprobada', 'reembolsada', 'cerrada', 'inspeccionada'].includes(estado)) return 'ok';
    if (estado === 'rechazada') return 'error';
    return 'warn';
  }

  private abrirBlob(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }
}
