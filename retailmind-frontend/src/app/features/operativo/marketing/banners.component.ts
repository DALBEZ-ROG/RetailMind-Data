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
import { BannerRow, CampanaRow } from '../../../core/models/operativo.model';
import {
  BannerDialogComponent, BannerDialogData, BannerDialogResultado
} from './banner-dialog.component';
import { vigenciaDe, FiltroVigencia } from './vigencia.util';

import { CampoTextoDirective } from '../../../core/validacion';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';

/**
 * Banners de la tienda, alineada al patrón (docs/PATRON_UI.md).
 * «Eliminar» es la BAJA LÓGICA de siempre (PATCH .../activo con false).
 */
@Component({
  selector: 'app-banners',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent,
    CampoTextoDirective
  ],
  templateUrl: './banners.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class BannersComponent implements OnInit {

  private todos: BannerRow[] = [];
  banners: BannerRow[] = [];
  campanas: CampanaRow[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroPosicion = 'todos';
  filtroCampanaId: number | null | 'todos' = 'todos';
  filtroVigencia: FiltroVigencia = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  readonly posiciones = ['home_principal', 'home_secundario', 'categoria', 'checkout'];

  filaSeleccionada: BannerRow | null = null;

  columnas = ['titulo', 'posicion', 'orden', 'campana', 'vigencia', 'activo'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void {
    this.cargar();
    this.marketing.campanas().subscribe(c => this.campanas = c);
  }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.marketing.banners().subscribe({
      next: data => { this.todos = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.banners = this.todos.filter(b => {
      if (this.filtroEstado === 'activos' && !b.activo) return false;
      if (this.filtroEstado === 'eliminados' && b.activo) return false;
      if (this.filtroPosicion !== 'todos' && b.posicion !== this.filtroPosicion) return false;
      if (this.filtroCampanaId !== 'todos' && b.campana_id !== this.filtroCampanaId) return false;
      if (this.filtroVigencia !== 'todos'
          && vigenciaDe(b.fecha_inicio, b.fecha_fin) !== this.filtroVigencia) return false;
      if (!q) return true;
      return b.titulo.toLowerCase().includes(q)
          || (b.campana ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroPosicion = 'todos';
    this.filtroCampanaId = 'todos';
    this.filtroVigencia = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.banners.find(b => b.id === this.filaSeleccionada!.id) ?? null;
  }

  vigencia(b: BannerRow): string { return vigenciaDe(b.fecha_inicio, b.fecha_fin); }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(b: BannerRow): void { this.filaSeleccionada = b; }

  nuevoBanner(): void { this.abrirDialogo('nuevo'); }
  modificarBanner(): void { this.abrirDialogo('actualizar'); }
  verBanner(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const banner = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !banner) return;

    const data: BannerDialogData = { banner, campanas: this.campanas, modo };
    this.dialog.open(BannerDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: BannerDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(banner!, res);
      });
  }

  private crear(res: BannerDialogResultado): void {
    const { activo, ...body } = res;
    this.marketing.crearBanner(body).subscribe({
      next: () => {
        this.snackBar.open('Banner creado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear el banner'),
        'Cerrar', { duration: 4000 })
    });
  }

  private guardar(original: BannerRow, res: BannerDialogResultado): void {
    const { activo, ...cambios } = res;
    this.marketing.editarBanner(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.marketing.activarBanner(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Banner actualizado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar el banner'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarBanner(): void {
    const b = this.filaSeleccionada;
    if (!b || !b.activo) return;
    this.confirmar.eliminacion(
      `el banner «${b.titulo}»`,
      `El banner dejará de mostrarse en la tienda (posición «${b.posicion}»). No se ` +
      'borra nada: la pieza y su vínculo con la campaña se conservan, y puedes ' +
      'restaurarlo marcando «Activo» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.marketing.activarBanner(b.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Banner «${b.titulo}» eliminado`, 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar el banner'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'El banner ya está eliminado (inactivo). Restáuralo desde Modificar.'
      : '';
  }
}
