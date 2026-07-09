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
import { mensajeError } from '../../../core/services/api-error.util';
import { BodegaRef, VarianteRef, KardexRow } from '../../../core/models/operativo.model';

/** CU-O-17: kardex de solo lectura (BODEGA/GERENTE/ADMIN/ANALISTA). */
@Component({
  selector: 'app-kardex',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './kardex.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class KardexComponent implements OnInit {

  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  movimientos: KardexRow[] = [];

  varianteId: number | null = null;
  bodegaId: number | null = null;
  loading = false;

  columnas = ['fecha', 'sku', 'bodega', 'tipo', 'entrada', 'salida', 'stock', 'referencia'];

  constructor(private inventario: InventarioService, private referencias: ReferenciasService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.referencias.bodegas().subscribe(b => this.bodegas = b);
    this.referencias.variantes().subscribe(v => this.variantes = v);
    this.consultar();
  }

  consultar(): void {
    this.loading = true;
    this.inventario.kardex(this.varianteId, this.bodegaId).subscribe({
      next: data => { this.movimientos = data; this.loading = false; },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo consultar el kardex'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  limpiar(): void {
    this.varianteId = null;
    this.bodegaId = null;
    this.consultar();
  }
}
