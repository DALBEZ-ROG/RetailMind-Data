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
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { InventarioService } from '../../../core/services/inventario.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { SelectBuscableComponent, OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { mensajeError } from '../../../core/services/api-error.util';
import { BodegaRef, VarianteRef, KardexRow } from '../../../core/models/operativo.model';

/** CU-O-17: kardex de solo lectura (BODEGA/GERENTE/ADMIN/ANALISTA). */
@Component({
  selector: 'app-kardex',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatPaginatorModule, SelectBuscableComponent],
  templateUrl: './kardex.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class KardexComponent implements OnInit {

  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  variantesOpc: OpcionBuscable[] = [];
  movimientos: KardexRow[] = [];

  varianteId: number | null = null;
  bodegaId: number | null = null;
  loading = false;

  // Paginación client-side (el backend limita a 500 movimientos)
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];

  columnas = ['fecha', 'sku', 'bodega', 'tipo', 'entrada', 'salida', 'stock', 'referencia'];

  constructor(private inventario: InventarioService, private referencias: ReferenciasService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.referencias.bodegas().subscribe(b => this.bodegas = b);
    this.referencias.variantes().subscribe(v => {
      this.variantes = v;
      this.variantesOpc = v.map(x => ({ id: x.id, texto: `${x.sku} — ${x.producto}` }));
    });
    this.consultar();
  }

  consultar(): void {
    this.loading = true;
    this.inventario.kardex(this.varianteId, this.bodegaId).subscribe({
      next: data => {
        this.movimientos = data; this.pagina = 0; this.recortarPagina(); this.loading = false;
      },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo consultar el kardex'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  /**
   * La página visible. Campo y no getter: desde un getter el array se
   * reconstruía en cada ciclo de detección de cambios y `MatTable` rehacía las
   * celdas sin que nada hubiera cambiado (trampa §8.6 de `PATRON_UI.md`).
   */
  movimientosPagina: KardexRow[] = [];

  private recortarPagina(): void {
    const inicio = this.pagina * this.tamPagina;
    this.movimientosPagina = this.movimientos.slice(inicio, inicio + this.tamPagina);
  }

  alPaginar(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
    this.recortarPagina();
  }

  limpiar(): void {
    this.varianteId = null;
    this.bodegaId = null;
    this.consultar();
  }
}
