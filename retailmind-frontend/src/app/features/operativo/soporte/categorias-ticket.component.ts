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
import { SoporteService } from '../../../core/services/soporte.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { CategoriaTicketRow } from '../../../core/models/operativo.model';
import {
  CategoriaTicketDialogComponent, CategoriaTicketDialogData, CategoriaTicketDialogResultado
} from './categoria-ticket-dialog.component';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';

/**
 * Categorías de ticket, alineada al patrón (docs/PATRON_UI.md).
 * «Eliminar» es la BAJA LÓGICA de siempre (PATCH .../activo con false): la
 * categoría deja de ofrecerse al abrir un ticket nuevo, pero los tickets y
 * las FAQ que ya la usan siguen apuntando a ella.
 */
@Component({
  selector: 'app-categorias-ticket',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent],
  templateUrl: './categorias-ticket.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CategoriasTicketComponent implements OnInit {

  private todas: CategoriaTicketRow[] = [];
  categorias: CategoriaTicketRow[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroPrioridad = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  readonly prioridades = ['baja', 'media', 'alta', 'urgente'];

  filaSeleccionada: CategoriaTicketRow | null = null;

  columnas = ['nombre', 'prioridad', 'uso', 'activo'];

  constructor(private soporte: SoporteService, private snackBar: MatSnackBar,
              private dialog: MatDialog, private confirmar: ConfirmService) {}

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.soporte.categorias().subscribe({
      next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.categorias = this.todas.filter(c => {
      if (this.filtroEstado === 'activos' && !c.activo) return false;
      if (this.filtroEstado === 'eliminados' && c.activo) return false;
      if (this.filtroPrioridad !== 'todos' && c.prioridad_defecto !== this.filtroPrioridad) return false;
      if (!q) return true;
      return c.nombre.toLowerCase().includes(q)
          || (c.descripcion ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroPrioridad = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.categorias.find(c => c.id === this.filaSeleccionada!.id) ?? null;
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(c: CategoriaTicketRow): void { this.filaSeleccionada = c; }

  nuevaCategoria(): void { this.abrirDialogo('nuevo'); }
  modificarCategoria(): void { this.abrirDialogo('actualizar'); }
  verCategoria(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const categoria = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !categoria) return;

    const data: CategoriaTicketDialogData = { categoria, modo };
    this.dialog.open(CategoriaTicketDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: CategoriaTicketDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(categoria!, res);
      });
  }

  private crear(res: CategoriaTicketDialogResultado): void {
    const { activo, ...body } = res;
    this.soporte.crearCategoria(body).subscribe({
      next: () => {
        this.snackBar.open('Categoría creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la categoría'),
        'Cerrar', { duration: 4000 })
    });
  }

  private guardar(original: CategoriaTicketRow, res: CategoriaTicketDialogResultado): void {
    const { activo, ...cambios } = res;
    this.soporte.editarCategoria(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.soporte.activarCategoria(original.id, activo))
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
    this.confirmar.eliminacion(
      `la categoría «${c.nombre}»`,
      'La categoría dejará de ofrecerse al abrir un ticket nuevo. No se borra nada: ' +
      `los ${c.tickets} ticket/s y las ${c.faqs} FAQ que ya la usan la conservan —con ` +
      'su prioridad automática—, y puedes restaurarla marcando «Activa» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.soporte.activarCategoria(c.id, false).subscribe({
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
