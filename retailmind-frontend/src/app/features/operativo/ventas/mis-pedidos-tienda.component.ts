import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { VentasService } from '../../../core/services/ventas.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  PedidoVentaRow, PedidoVentaDetalle, SeguimientoRow
} from '../../../core/models/operativo.model';

/**
 * CU-O-20: MIS PEDIDOS (rol CLIENTE). Reutiliza los endpoints del ciclo de
 * venta: el RLS de la BD (app.cliente_id) devuelve solo los pedidos del
 * cliente autenticado, sin filtro adicional en la app. Solo lectura.
 */
@Component({
  selector: 'app-mis-pedidos-tienda',
  standalone: true,
  imports: [CommonModule, MatTableModule, MatIconModule, MatButtonModule,
    MatSnackBarModule, MatTooltipModule],
  templateUrl: './mis-pedidos-tienda.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class MisPedidosTiendaComponent implements OnInit {

  pedidos: PedidoVentaRow[] = [];
  detalle: PedidoVentaDetalle | null = null;
  seguimiento: SeguimientoRow[] = [];
  loading = true;

  columnas = ['numero', 'fecha', 'estado', 'total', 'acciones'];

  constructor(private ventas: VentasService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.cargar();
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

  verPedido(id: number): void {
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
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }
}
