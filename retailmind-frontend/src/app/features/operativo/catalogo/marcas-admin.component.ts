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
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { of, switchMap } from 'rxjs';
import { CatalogoAdminService } from '../../../core/services/catalogo-admin.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { MarcaAdmin } from '../../../core/models/operativo.model';
import {
  MarcaDialogComponent, MarcaDialogData, MarcaDialogResultado
} from './marca-dialog.component';

import { CampoTextoDirective } from '../../../core/validacion';

/** Estado por el que se puede filtrar la grilla. */
type FiltroEstado = 'todos' | 'activos' | 'eliminados';

/**
 * Marcas del catálogo, alineada al patrón de interfaz (docs/PATRON_UI.md).
 * «Eliminar» es la BAJA LÓGICA de siempre (PATCH .../activo con false): la
 * marca deja de estar disponible pero los productos que la referencian no se
 * tocan. Se restaura marcando «Activa» en Modo Actualizar.
 */
@Component({
  selector: 'app-marcas-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent,
    CampoTextoDirective
  ],
  templateUrl: './marcas-admin.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class MarcasAdminComponent implements OnInit {

  /** Todas las marcas tal como llegan del backend. */
  private todas: MarcaAdmin[] = [];
  /** Lo que se pinta: `todas` pasado por los criterios de búsqueda. */
  marcas: MarcaAdmin[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroEstado: FiltroEstado = 'todos';

  filaSeleccionada: MarcaAdmin | null = null;

  columnas = ['nombre', 'slug', 'descripcion', 'activo'];

  constructor(private catalogo: CatalogoAdminService, private snackBar: MatSnackBar,
              private dialog: MatDialog, private confirmar: ConfirmService) {}

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.catalogo.marcas().subscribe({
      next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  /**
   * Filtrado en cliente: `GET /marcas` devuelve el catálogo entero de marcas
   * (decenas, no miles) y no hay endpoint de búsqueda. Se recalcula a mano y
   * no en un getter, para no crear un array nuevo en cada ciclo de detección
   * de cambios —`mat-table` lo repintaría entero—.
   */
  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.marcas = this.todas.filter(m => {
      if (this.filtroEstado === 'activos' && !m.activo) return false;
      if (this.filtroEstado === 'eliminados' && m.activo) return false;
      if (!q) return true;
      return m.nombre.toLowerCase().includes(q)
          || m.slug.toLowerCase().includes(q)
          || (m.descripcion ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  /** La fila marcada apunta a un objeto viejo tras recargar o refiltrar. */
  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.marcas.find(m => m.id === this.filaSeleccionada!.id) ?? null;
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(m: MarcaAdmin): void { this.filaSeleccionada = m; }

  nuevaMarca(): void { this.abrirDialogo('nuevo'); }
  modificarMarca(): void { this.abrirDialogo('actualizar'); }
  verMarca(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const marca = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !marca) return;

    const data: MarcaDialogData = { marca, modo };
    this.dialog.open(MarcaDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: MarcaDialogResultado | undefined) => {
        if (!res) return;                      // Cancelar, Esc o Modo Consulta
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(marca!, res);
      });
  }

  private crear(res: MarcaDialogResultado): void {
    const { activo, ...body } = res;           // 'activo' no es del alta
    this.catalogo.crearMarca(body).subscribe({
      next: () => {
        this.snackBar.open('Marca creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la marca'),
        'Cerrar', { duration: 4000 })
    });
  }

  /** Cuerpo por el PUT y `activo` por su propio PATCH, solo si cambió. */
  private guardar(original: MarcaAdmin, res: MarcaDialogResultado): void {
    const { activo, ...cambios } = res;
    this.catalogo.editarMarca(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.catalogo.activarMarca(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Marca actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la marca'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarMarca(): void {
    const m = this.filaSeleccionada;
    if (!m || !m.activo) return;
    this.confirmar.eliminacion(
      `la marca «${m.nombre}»`,
      'La marca dejará de ofrecerse para clasificar productos y desaparecerá del ' +
      'filtro de marca de la tienda. No se borra nada: los productos que ya la ' +
      'llevan la conservan, y puedes restaurarla marcando «Activa» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.catalogo.activarMarca(m.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Marca «${m.nombre}» eliminada`, 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar la marca'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'La marca ya está eliminada (inactiva). Restáurala desde Modificar.'
      : '';
  }
}
