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
import { CampanaRow } from '../../../core/models/operativo.model';
import {
  CampanaDialogComponent, CampanaDialogData, CampanaDialogResultado
} from './campana-dialog.component';
import { vigenciaDe, FiltroVigencia } from './vigencia.util';

/**
 * Campañas de marketing, alineada al patrón (docs/PATRON_UI.md).
 *
 * ÚNICA de las nueve sin bandera `activo`: su ciclo de vida es el `estado`
 * (borrador → activa → pausada → finalizada) y «finalizada» es TERMINAL —el
 * backend rechaza cualquier cambio posterior—. Consecuencias:
 *
 *  · «Eliminar» = finalizar. Es la única forma de retirar una campaña de
 *    circulación, y NO se puede deshacer: el mensaje lo dice con todas las
 *    letras en vez de prometer una restauración que no existe.
 *  · Activar y pausar viven DENTRO de Modificar, como un campo más, para no
 *    añadir botones sueltos que romperían la regla 4.
 */
@Component({
  selector: 'app-campanas',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent],
  templateUrl: './campanas.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CampanasComponent implements OnInit {

  private todas: CampanaRow[] = [];
  campanas: CampanaRow[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroCanal = 'todos';
  filtroEstado = 'todos';
  filtroVigencia: FiltroVigencia = 'todos';

  readonly canales = ['email', 'redes', 'web', 'sms', 'mixto'];
  readonly estados = ['borrador', 'activa', 'pausada', 'finalizada'];

  filaSeleccionada: CampanaRow | null = null;

  columnas = ['nombre', 'canal', 'presupuesto', 'vigencia', 'banners', 'estado'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.marketing.campanas().subscribe({
      next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.campanas = this.todas.filter(c => {
      if (this.filtroEstado !== 'todos' && c.estado !== this.filtroEstado) return false;
      if (this.filtroCanal !== 'todos' && c.canal !== this.filtroCanal) return false;
      if (this.filtroVigencia !== 'todos'
          && vigenciaDe(c.fecha_inicio, c.fecha_fin) !== this.filtroVigencia) return false;
      if (!q) return true;
      return c.nombre.toLowerCase().includes(q)
          || (c.descripcion ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroCanal = 'todos';
    this.filtroEstado = 'todos';
    this.filtroVigencia = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.campanas.find(c => c.id === this.filaSeleccionada!.id) ?? null;
  }

  vigencia(c: CampanaRow): string { return vigenciaDe(c.fecha_inicio, c.fecha_fin); }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(c: CampanaRow): void { this.filaSeleccionada = c; }

  nuevaCampana(): void { this.abrirDialogo('nuevo'); }
  modificarCampana(): void { this.abrirDialogo('actualizar'); }
  verCampana(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const campana = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !campana) return;

    const data: CampanaDialogData = { campana, modo };
    this.dialog.open(CampanaDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: CampanaDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(campana!, res);
      });
  }

  private crear(res: CampanaDialogResultado): void {
    const { estado, ...body } = res;            // nace en 'borrador'
    this.marketing.crearCampana(body).subscribe({
      next: () => {
        this.snackBar.open('Campaña creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la campaña'),
        'Cerrar', { duration: 4000 })
    });
  }

  /** Cuerpo por el PUT y `estado` por su propio PATCH, solo si cambió. */
  private guardar(original: CampanaRow, res: CampanaDialogResultado): void {
    const { estado, ...cambios } = res;
    this.marketing.editarCampana(original.id, cambios).pipe(
      switchMap(() => estado === original.estado
        ? of(null)
        : this.marketing.estadoCampana(original.id, estado))
    ).subscribe({
      next: () => {
        this.snackBar.open('Campaña actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la campaña'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar = FINALIZAR, y es irreversible ─────────────────

  eliminarCampana(): void {
    const c = this.filaSeleccionada;
    if (!c || c.estado === 'finalizada') return;
    this.confirmar.eliminacion(
      `la campaña «${c.nombre}»`,
      'En marketing, eliminar una campaña es FINALIZARLA: pasa a estado «finalizada», ' +
      'deja de emitirse y NO SE PUEDE REACTIVAR — es un estado terminal. No se borra ' +
      `nada: su presupuesto, sus ${c.banners} banner/s y su histórico se conservan para ` +
      'los informes.'
    ).subscribe(ok => {
      if (!ok) return;
      this.marketing.estadoCampana(c.id, 'finalizada').subscribe({
        next: () => {
          this.snackBar.open(`Campaña «${c.nombre}» finalizada`, 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al finalizar la campaña'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada?.estado === 'finalizada'
      ? 'La campaña ya está finalizada: es un estado terminal y no admite cambios.'
      : '';
  }
}
