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
import { MarketingService } from '../../../core/services/marketing.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { SuscriptorRow } from '../../../core/models/operativo.model';
import {
  SuscriptorDialogComponent, SuscriptorDialogData, SuscriptorDialogResultado
} from './suscriptor-dialog.component';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';
type FiltroConfirmado = 'todos' | 'si' | 'no';

/**
 * Suscriptores del boletín, alineada al patrón (docs/PATRON_UI.md).
 *
 * LIMITACIÓN DECLARADA: el backend no expone edición del suscriptor —un
 * suscriptor ES su email—, así que Modificar solo gobierna el estado de la
 * suscripción (que es además la vía para REACTIVAR a quien se dio de baja).
 * Para cambiar la dirección hay que eliminar y dar de alta la nueva.
 */
@Component({
  selector: 'app-newsletter',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent],
  templateUrl: './newsletter.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class NewsletterComponent implements OnInit {

  private todos: SuscriptorRow[] = [];
  suscriptores: SuscriptorRow[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroEstado: FiltroEstado = 'todos';
  filtroConfirmado: FiltroConfirmado = 'todos';

  filaSeleccionada: SuscriptorRow | null = null;

  columnas = ['email', 'cliente', 'confirmado', 'suscripcion', 'baja', 'activo'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  get activos(): number { return this.todos.filter(s => s.activo).length; }

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.marketing.suscriptores().subscribe({
      next: data => { this.todos = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.suscriptores = this.todos.filter(s => {
      if (this.filtroEstado === 'activos' && !s.activo) return false;
      if (this.filtroEstado === 'eliminados' && s.activo) return false;
      if (this.filtroConfirmado === 'si' && !s.confirmado) return false;
      if (this.filtroConfirmado === 'no' && s.confirmado) return false;
      if (!q) return true;
      return s.email.toLowerCase().includes(q)
          || (s.cliente ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroEstado = 'todos';
    this.filtroConfirmado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.suscriptores.find(s => s.id === this.filaSeleccionada!.id) ?? null;
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(s: SuscriptorRow): void { this.filaSeleccionada = s; }

  nuevoSuscriptor(): void { this.abrirDialogo('nuevo'); }
  modificarSuscriptor(): void { this.abrirDialogo('actualizar'); }
  verSuscriptor(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const suscriptor = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !suscriptor) return;

    const data: SuscriptorDialogData = { suscriptor, modo };
    this.dialog.open(SuscriptorDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: SuscriptorDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(suscriptor!, res);
      });
  }

  private crear(res: SuscriptorDialogResultado): void {
    this.marketing.altaSuscriptor(res.email, null).subscribe({
      next: () => {
        this.snackBar.open('Suscriptor dado de alta', 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al dar de alta'),
        'Cerrar', { duration: 4000 })
    });
  }

  /**
   * Sin PUT que llamar: lo único modificable es el estado de la suscripción.
   * Si no cambió, no se molesta al backend.
   */
  private guardar(original: SuscriptorRow, res: SuscriptorDialogResultado): void {
    if (res.activo === original.activo) {
      this.snackBar.open('Sin cambios que guardar', 'OK', { duration: 2000 });
      return;
    }
    this.marketing.activarSuscriptor(original.id, res.activo).subscribe({
      next: () => {
        this.snackBar.open(res.activo ? 'Suscripción reactivada' : 'Suscriptor dado de baja',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la suscripción'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarSuscriptor(): void {
    const s = this.filaSeleccionada;
    if (!s || !s.activo) return;
    this.confirmar.eliminacion(
      `la suscripción de «${s.email}»`,
      'El suscriptor se da de baja y dejará de recibir el boletín. No se borra nada: ' +
      'su registro y su fecha de alta se conservan (queda la fecha de baja), y puedes ' +
      'reactivarlo marcando «Suscripción activa» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.marketing.activarSuscriptor(s.id, false).subscribe({
        next: () => {
          this.snackBar.open(`«${s.email}» dado de baja`, 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al dar de baja'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'El suscriptor ya está dado de baja. Reactívalo desde Modificar.'
      : '';
  }
}
