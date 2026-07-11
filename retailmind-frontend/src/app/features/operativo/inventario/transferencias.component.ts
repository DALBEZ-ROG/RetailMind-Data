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
import { InventarioService } from '../../../core/services/inventario.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { SelectBuscableComponent, OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  BodegaRef, VarianteRef, StockRow, TransferenciaRow
} from '../../../core/models/operativo.model';

@Component({
  selector: 'app-transferencias',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    SelectBuscableComponent],
  templateUrl: './transferencias.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class TransferenciasComponent implements OnInit {

  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  variantesOpc: OpcionBuscable[] = [];
  transferencias: TransferenciaRow[] = [];

  varianteId: number | null = null;
  bodegaOrigenId: number | null = null;
  bodegaDestinoId: number | null = null;
  cantidad = 1;
  observacion = '';

  stockVariante: StockRow[] = [];
  resultado: TransferenciaRow | null = null;
  procesando = false;

  constructor(private inventario: InventarioService, private referencias: ReferenciasService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.referencias.bodegas().subscribe(b => this.bodegas = b);
    this.referencias.variantes().subscribe(v => {
      this.variantes = v;
      this.variantesOpc = v.map(x => ({ id: x.id, texto: `${x.sku} — ${x.producto}` }));
    });
    this.cargarTransferencias();
  }

  cargarTransferencias(): void {
    this.inventario.transferencias().subscribe(t => this.transferencias = t);
  }

  consultarStock(): void {
    if (!this.varianteId) { this.stockVariante = []; return; }
    this.referencias.stock(this.varianteId).subscribe(s => this.stockVariante = s);
  }

  transferir(): void {
    if (!this.varianteId || !this.bodegaOrigenId || !this.bodegaDestinoId || this.cantidad <= 0) {
      this.snackBar.open('Variante, bodegas y cantidad (> 0) son requeridas', 'Cerrar', { duration: 3500 });
      return;
    }
    if (this.bodegaOrigenId === this.bodegaDestinoId) {
      this.snackBar.open('La bodega origen y destino deben ser distintas', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.inventario.transferir({
      varianteId: this.varianteId, bodegaOrigenId: this.bodegaOrigenId,
      bodegaDestinoId: this.bodegaDestinoId, cantidad: this.cantidad,
      observacion: this.observacion
    }).subscribe({
      next: res => {
        this.procesando = false;
        this.resultado = res;
        this.snackBar.open('Transferencia realizada', 'OK', { duration: 3000, panelClass: ['snack-success'] });
        this.consultarStock();
        this.cargarTransferencias();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo realizar la transferencia'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  claves(obj: TransferenciaRow): string[] {
    return Object.keys(obj).filter(k => typeof obj[k] !== 'object');
  }
}
