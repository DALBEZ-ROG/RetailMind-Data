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
import { MarketingService } from '../../../core/services/marketing.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { CuponRow, UsoCuponRow } from '../../../core/models/operativo.model';
import {
  CuponDialogComponent, CuponDialogData, CuponDialogResultado
} from './cupon-dialog.component';
import { vigenciaDe, FiltroVigencia } from './vigencia.util';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';

/**
 * Cupones de descuento, alineada al patrón (docs/PATRON_UI.md).
 * «Eliminar» es la BAJA LÓGICA de siempre (PATCH .../activo con false): el
 * cupón deja de poder canjearse, pero los canjes ya hechos se conservan.
 */
@Component({
  selector: 'app-cupones',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent],
  templateUrl: './cupones.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CuponesComponent implements OnInit {

  private todos: CuponRow[] = [];
  cupones: CuponRow[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroTipo = 'todos';
  filtroVigencia: FiltroVigencia = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  readonly tiposDescuento = ['porcentaje', 'monto_fijo', 'envio_gratis'];

  filaSeleccionada: CuponRow | null = null;

  /** Panel de solo lectura: los canjes del cupón seleccionado. */
  usosDe: CuponRow | null = null;
  usos: UsoCuponRow[] = [];

  columnas = ['codigo', 'tipo', 'valor', 'vigencia', 'usos', 'activo'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  /** Solo ADMIN escribe; GERENTE entra a consultar (espeja SecurityConfig). */
  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.marketing.cupones().subscribe({
      next: data => { this.todos = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.cupones = this.todos.filter(c => {
      if (this.filtroEstado === 'activos' && !c.activo) return false;
      if (this.filtroEstado === 'eliminados' && c.activo) return false;
      if (this.filtroTipo !== 'todos' && c.tipo_descuento !== this.filtroTipo) return false;
      if (this.filtroVigencia !== 'todos'
          && vigenciaDe(c.fecha_inicio, c.fecha_fin) !== this.filtroVigencia) return false;
      if (!q) return true;
      return c.codigo.toLowerCase().includes(q)
          || (c.descripcion ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroTipo = 'todos';
    this.filtroVigencia = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.cupones.find(c => c.id === this.filaSeleccionada!.id) ?? null;
    if (!this.filaSeleccionada) { this.usosDe = null; this.usos = []; }
  }

  vigencia(c: CuponRow): string { return vigenciaDe(c.fecha_inicio, c.fecha_fin); }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  /** Al seleccionar se traen sus canjes: es el detalle del cupón. */
  seleccionarFila(c: CuponRow): void {
    this.filaSeleccionada = c;
    this.usosDe = c;
    this.marketing.usosCupon(c.id).subscribe({
      next: u => this.usos = u,
      error: () => this.usos = []
    });
  }

  nuevoCupon(): void { this.abrirDialogo('nuevo'); }
  modificarCupon(): void { this.abrirDialogo('actualizar'); }
  verCupon(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const cupon = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !cupon) return;

    const data: CuponDialogData = { cupon, modo };
    this.dialog.open(CuponDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: CuponDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(cupon!, res);
      });
  }

  private crear(res: CuponDialogResultado): void {
    const { activo, ...body } = res;
    this.marketing.crearCupon(body).subscribe({
      next: () => {
        this.snackBar.open('Cupón creado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear el cupón'),
        'Cerrar', { duration: 4000 })
    });
  }

  private guardar(original: CuponRow, res: CuponDialogResultado): void {
    const { activo, ...cambios } = res;
    this.marketing.editarCupon(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.marketing.activarCupon(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Cupón actualizado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar el cupón'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarCupon(): void {
    const c = this.filaSeleccionada;
    if (!c || !c.activo) return;
    this.confirmar.eliminacion(
      `el cupón «${c.codigo}»`,
      'El cupón dejará de poder canjearse en el checkout de inmediato. No se borra ' +
      `nada: los ${c.usos_actuales} canje/s ya registrados y los descuentos que ` +
      'aplicaron se conservan, y puedes restaurarlo marcando «Activo» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.marketing.activarCupon(c.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Cupón «${c.codigo}» eliminado`, 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar el cupón'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'El cupón ya está eliminado (inactivo). Restáuralo desde Modificar.'
      : '';
  }
}
