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
import { MetasService } from '../../../core/services/metas.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { MetaVentaRow } from '../../../core/models/operativo.model';
import {
  MetaDialogComponent, MetaDialogData, MetaDialogResultado
} from './meta-dialog.component';

const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio',
  'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

type FiltroEstado = 'todos' | 'vigentes' | 'eliminadas';

/**
 * Metas de venta por período (OTD-VEN-15, script 48), alineada al patrón
 * (docs/PATRON_UI.md). ADMIN/GERENTE fijan y editan; VENDEDOR/ANALISTA solo
 * leen el avance — para ellos la barra de acciones se queda en «Ver».
 *
 * «Eliminar» es la BAJA LÓGICA de siempre (`PATCH .../activo` con false): la
 * meta deja de contar como objetivo del período pero no se borra.
 */
@Component({
  selector: 'app-metas-venta',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    MatDialogModule, AccionesRegistroComponent],
  templateUrl: './metas-venta.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class MetasVentaComponent implements OnInit {

  private todas: MetaVentaRow[] = [];
  metas: MetaVentaRow[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroAnio: number | 'todos' = 'todos';
  filtroMes: number | 'todos' = 'todos';
  filtroDepartamento = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  /** Años presentes en los datos: no se ofrece un criterio que no exista. */
  anios: number[] = [];

  // Espeja el CHECK de meta_venta.departamento
  departamentos = ['general', 'ventas', 'compras', 'inventario',
    'logistica', 'soporte', 'marketing'];
  meses = MESES;

  filaSeleccionada: MetaVentaRow | null = null;

  columnas = ['periodo', 'departamento', 'meta', 'avance', 'fijada', 'activo'];

  constructor(private metasSrv: MetasService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  /** Fijar/editar metas: gerencia (espeja SecurityConfig y los GRANTs). */
  get esGestion(): boolean {
    return this.auth.hasRole('ADMIN') || this.auth.hasRole('GERENTE');
  }

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.metasSrv.metas().subscribe({
      next: data => {
        this.todas = data;
        this.anios = [...new Set(data.map(m => m.anio))].sort((a, b) => b - a);
        this.aplicarFiltros();
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  aplicarFiltros(): void {
    this.metas = this.todas.filter(m => {
      if (this.filtroEstado === 'vigentes' && !m.activo) return false;
      if (this.filtroEstado === 'eliminadas' && m.activo) return false;
      if (this.filtroAnio !== 'todos' && m.anio !== this.filtroAnio) return false;
      if (this.filtroMes !== 'todos' && m.mes !== this.filtroMes) return false;
      if (this.filtroDepartamento !== 'todos'
          && m.departamento !== this.filtroDepartamento) return false;
      return true;
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroAnio = 'todos';
    this.filtroMes = 'todos';
    this.filtroDepartamento = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.metas.find(m => m.id === this.filaSeleccionada!.id) ?? null;
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(m: MetaVentaRow): void { this.filaSeleccionada = m; }

  nuevaMeta(): void { this.abrirDialogo('nuevo'); }
  modificarMeta(): void { this.abrirDialogo('actualizar'); }
  verMeta(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const meta = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !meta) return;

    const data: MetaDialogData = {
      meta, modo, meses: this.meses, departamentos: this.departamentos
    };
    this.dialog.open(MetaDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: MetaDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(meta!, res);
      });
  }

  private crear(res: MetaDialogResultado): void {
    const { activo, ...body } = res;
    this.metasSrv.crear(body).subscribe({
      next: () => {
        this.snackBar.open('Meta creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la meta'),
        'Cerrar', { duration: 4000 })
    });
  }

  /** `activo` viaja por su propio endpoint; el PUT solo lleva los datos. */
  private guardar(original: MetaVentaRow, res: MetaDialogResultado): void {
    const { activo, ...cambios } = res;
    this.metasSrv.editar(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.metasSrv.activar(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Meta actualizada', 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la meta'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarMeta(): void {
    const m = this.filaSeleccionada;
    if (!m || !m.activo) return;
    this.confirmar.eliminacion(
      `la meta de «${m.departamento}» de ${this.nombreMes(m.mes)} ${m.anio}`,
      'El período dejará de tener objetivo: el informe «Venta contra la meta del mes» ' +
      'y el tablero de gerencia dejarán de comparar contra esta cifra. No se borra nada: ' +
      'lo facturado del período no se toca y puedes restaurarla marcando «Vigente» ' +
      'desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.metasSrv.activar(m.id, false).subscribe({
        next: () => {
          this.snackBar.open('Meta eliminada', 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar la meta'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'La meta ya está eliminada (inactiva). Restáurala desde Modificar.'
      : '';
  }

  nombreMes(mes: number): string { return MESES[mes - 1] || String(mes); }

  /** % de cumplimiento (solo metas con venta_real calculada). */
  cumplimiento(m: MetaVentaRow): number | null {
    if (m.venta_real === null || m.venta_real === undefined || !m.monto_meta) return null;
    return Math.round((m.venta_real / m.monto_meta) * 100);
  }
}
