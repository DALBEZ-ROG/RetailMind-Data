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
import { MatTooltipModule } from '@angular/material/tooltip';
import { InventarioService } from '../../../core/services/inventario.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { SelectBuscableComponent, OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  BodegaRef, VarianteRef, StockRow, AjusteRow, AjusteResultado
} from '../../../core/models/operativo.model';

/** CU-O-16: ajuste manual de stock por conteo físico o merma (BODEGA/ADMIN). */
@Component({
  selector: 'app-ajustes',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    SelectBuscableComponent],
  templateUrl: './ajustes.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class AjustesComponent implements OnInit {

  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  variantesOpc: OpcionBuscable[] = [];
  ajustes: AjusteRow[] = [];

  varianteId: number | null = null;
  bodegaId: number | null = null;
  tipo: 'entrada' | 'salida' = 'entrada';
  cantidad = 1;
  motivo = '';

  stockVariante: StockRow[] = [];
  resultado: AjusteResultado | null = null;
  procesando = false;

  anulando: AjusteRow | null = null;
  motivoAnulacion = '';

  constructor(private inventario: InventarioService, private referencias: ReferenciasService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.referencias.bodegas().subscribe(b => this.bodegas = b);
    this.referencias.variantes().subscribe(v => {
      this.variantes = v;
      this.variantesOpc = v.map(x => ({ id: x.id, texto: `${x.sku} — ${x.producto}` }));
    });
    this.cargarAjustes();
  }

  cargarAjustes(): void {
    this.inventario.ajustes().subscribe(a => this.ajustes = a);
  }

  consultarStock(): void {
    if (!this.varianteId) { this.stockVariante = []; return; }
    this.referencias.stock(this.varianteId).subscribe(s => this.stockVariante = s);
  }

  registrarAjuste(): void {
    if (!this.varianteId || !this.bodegaId || this.cantidad <= 0) {
      this.snackBar.open('Variante, bodega y cantidad (> 0) son requeridas', 'Cerrar', { duration: 3500 });
      return;
    }
    if (!this.motivo.trim()) {
      this.snackBar.open('El motivo del ajuste es obligatorio', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.inventario.registrarAjuste({
      varianteId: this.varianteId, bodegaId: this.bodegaId, tipo: this.tipo,
      cantidad: this.cantidad, motivo: this.motivo.trim()
    }).subscribe({
      next: res => {
        this.procesando = false;
        this.resultado = res;
        this.snackBar.open(`Ajuste #${res.id} aplicado — stock resultante ${res.stockResultante}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.motivo = '';
        this.consultarStock();
        this.cargarAjustes();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar el ajuste'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  anularAjuste(): void {
    if (!this.anulando) return;
    if (!this.motivoAnulacion.trim()) {
      this.snackBar.open('El motivo de la anulación es obligatorio', 'Cerrar', { duration: 3500 });
      return;
    }
    this.inventario.anularAjuste(this.anulando.id, this.motivoAnulacion.trim()).subscribe({
      next: () => {
        this.snackBar.open(`Ajuste #${this.anulando!.id} anulado — stock revertido`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.anulando = null;
        this.motivoAnulacion = '';
        this.consultarStock();
        this.cargarAjustes();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo anular el ajuste'),
        'Cerrar', { duration: 5000 })
    });
  }
}
