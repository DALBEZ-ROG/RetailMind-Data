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
import { CategoriaAdmin } from '../../../core/models/operativo.model';
import {
  CategoriaDialogComponent, CategoriaDialogData, CategoriaDialogResultado
} from './categoria-dialog.component';

import { CampoTextoDirective } from '../../../core/validacion';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';

/**
 * Categorías del catálogo, alineada al patrón (docs/PATRON_UI.md).
 * «Eliminar» es la BAJA LÓGICA de siempre (PATCH .../activo con false).
 */
@Component({
  selector: 'app-categorias-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent,
    CampoTextoDirective
  ],
  templateUrl: './categorias-admin.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CategoriasAdminComponent implements OnInit {

  private todas: CategoriaAdmin[] = [];
  categorias: CategoriaAdmin[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroPadreId: number | null | 'todos' = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  filaSeleccionada: CategoriaAdmin | null = null;

  columnas = ['nombre', 'slug', 'padre', 'descripcion', 'activo'];

  constructor(private catalogo: CatalogoAdminService, private snackBar: MatSnackBar,
              private dialog: MatDialog, private confirmar: ConfirmService) {}

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.catalogo.categorias().subscribe({
      next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  /** Filtrado en cliente: el árbol entero llega en una sola llamada. */
  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.categorias = this.todas.filter(c => {
      if (this.filtroEstado === 'activos' && !c.activo) return false;
      if (this.filtroEstado === 'eliminados' && c.activo) return false;
      if (this.filtroPadreId !== 'todos' && c.categoria_padre_id !== this.filtroPadreId) return false;
      if (!q) return true;
      return c.nombre.toLowerCase().includes(q)
          || c.slug.toLowerCase().includes(q)
          || (c.descripcion ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroPadreId = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.categorias.find(c => c.id === this.filaSeleccionada!.id) ?? null;
  }

  /** El nombre del padre se resuelve sobre TODAS, no sobre lo filtrado. */
  nombrePadre(c: CategoriaAdmin): string {
    if (c.categoria_padre_id == null) return '—';
    return this.todas.find(x => x.id === c.categoria_padre_id)?.nombre || '—';
  }

  /** Raíces: las candidatas naturales para filtrar por rama. */
  get raices(): CategoriaAdmin[] {
    return this.todas.filter(c => c.categoria_padre_id == null);
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(c: CategoriaAdmin): void { this.filaSeleccionada = c; }

  nuevaCategoria(): void { this.abrirDialogo('nuevo'); }
  modificarCategoria(): void { this.abrirDialogo('actualizar'); }
  verCategoria(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const categoria = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !categoria) return;

    const data: CategoriaDialogData = {
      categoria, modo,
      // Sin la propia categoría: no puede ser su propio padre.
      padresPosibles: this.todas.filter(c => c.id !== categoria?.id)
    };
    this.dialog.open(CategoriaDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: CategoriaDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(categoria!, res);
      });
  }

  private crear(res: CategoriaDialogResultado): void {
    const { activo, ...body } = res;
    this.catalogo.crearCategoria(body).subscribe({
      next: () => {
        this.snackBar.open('Categoría creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la categoría'),
        'Cerrar', { duration: 4000 })
    });
  }

  /** El endpoint de edición NO cambia el padre: solo nombre/slug/descripción. */
  private guardar(original: CategoriaAdmin, res: CategoriaDialogResultado): void {
    const { activo, padreId, ...cambios } = res;
    this.catalogo.editarCategoria(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.catalogo.activarCategoria(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Categoría actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la categoría'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarCategoria(): void {
    const c = this.filaSeleccionada;
    if (!c || !c.activo) return;
    const hijas = this.todas.filter(x => x.categoria_padre_id === c.id && x.activo).length;
    this.confirmar.eliminacion(
      `la categoría «${c.nombre}»`,
      'La categoría dejará de ofrecerse para clasificar productos y desaparecerá del ' +
      'filtro de la tienda. No se borra nada: los productos que ya la llevan la ' +
      'conservan, y puedes restaurarla marcando «Activa» desde Modificar.' +
      (hijas > 0
        ? ` Ojo: cuelgan de ella ${hijas} subcategoría${hijas > 1 ? 's' : ''} activa${hijas > 1 ? 's' : ''}, que NO se eliminan.`
        : '')
    ).subscribe(ok => {
      if (!ok) return;
      this.catalogo.activarCategoria(c.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Categoría «${c.nombre}» eliminada`, 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar la categoría'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'La categoría ya está eliminada (inactiva). Restáurala desde Modificar.'
      : '';
  }
}
