import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { VentasService } from '../../../core/services/ventas.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { PedidoVentaRow, FacturaVenta } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-facturas-venta',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './facturas-venta.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class FacturasVentaComponent implements OnInit {

  pedidos: PedidoVentaRow[] = [];
  pedidoId: number | null = null;
  factura: FacturaVenta | null = null;
  procesando = false;

  constructor(private ventas: VentasService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.ventas.pedidos().subscribe(p => this.pedidos = p);
  }

  emitirFactura(): void {
    if (!this.pedidoId) {
      this.snackBar.open('Selecciona el pedido a facturar', 'Cerrar', { duration: 3000 });
      return;
    }
    this.procesando = true;
    this.ventas.emitirFactura(this.pedidoId).subscribe({
      next: f => {
        this.procesando = false;
        this.factura = f;
        this.snackBar.open(`Factura ${f.numero} emitida — total ${f.total}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.ventas.pedidos().subscribe(p => this.pedidos = p);
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo emitir la factura'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  /** Descarga el PDF autenticado como blob y lo abre en una pestaña nueva. */
  verPdf(facturaId: number): void {
    this.ventas.facturaPdf(facturaId).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }
}
