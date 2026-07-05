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
import { ComprasService } from '../../../core/services/compras.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { OrdenCompraRow, FacturaCompra, CuentaPorPagarRow } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-facturas-compra',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './facturas-compra.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class FacturasCompraComponent implements OnInit {

  ordenes: OrdenCompraRow[] = [];
  cuentas: CuentaPorPagarRow[] = [];

  ordenId: number | null = null;
  factura: FacturaCompra | null = null;
  procesando = false;

  constructor(private compras: ComprasService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.compras.ordenes().subscribe(o => this.ordenes = o);
    this.cargarCuentas();
  }

  cargarCuentas(): void {
    this.compras.cuentasPorPagar().subscribe(c => this.cuentas = c);
  }

  registrarFactura(): void {
    if (!this.ordenId) {
      this.snackBar.open('Selecciona la orden de compra a facturar', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.compras.registrarFactura(this.ordenId).subscribe({
      next: f => {
        this.procesando = false;
        this.factura = f;
        this.snackBar.open(`Factura ${f.numero_factura} registrada — total ${f.total}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.cargarCuentas();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar la factura'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  /**
   * El PDF exige el Bearer token, así que se descarga por HttpClient como blob
   * y se abre con una object URL (un href directo no llevaría el token).
   */
  verPdf(facturaId: number): void {
    this.compras.facturaPdf(facturaId).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }
}
