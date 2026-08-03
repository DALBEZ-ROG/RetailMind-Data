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
import { OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { PromocionRow, PromocionDetalle } from '../../../core/models/operativo.model';
import {
  PromocionDialogComponent, PromocionDialogData, PromocionDialogResultado
} from './promocion-dialog.component';
import {
  PromocionProductoDialogComponent, PromocionProductoDialogData
} from './promocion-producto-dialog.component';
import { vigenciaDe, FiltroVigencia } from './vigencia.util';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';
type ProductoDePromocion = PromocionDetalle['productos'][number];

/**
 * Promociones, alineada al patrón (docs/PATRON_UI.md).
 *
 * Dos grillas y dos barras de acciones: la promoción (baja LÓGICA) y sus
 * productos asociados (borrado FÍSICO — ver `eliminarProducto`).
 */
@Component({
  selector: 'app-promociones',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatDialogModule, AccionesRegistroComponent],
  templateUrl: './promociones.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PromocionesComponent implements OnInit {

  private todas: PromocionRow[] = [];
  promociones: PromocionRow[] = [];
  productosOpc: OpcionBuscable[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroTipo = 'todos';
  filtroVigencia: FiltroVigencia = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  readonly tiposDescuento = ['porcentaje', 'monto_fijo'];

  filaSeleccionada: PromocionRow | null = null;
  /** Detalle de la promoción marcada, con sus productos. */
  seleccionada: PromocionDetalle | null = null;
  productoSeleccionado: ProductoDePromocion | null = null;

  columnas = ['nombre', 'tipo', 'valor', 'vigencia', 'productos', 'activo'];
  readonly columnasProducto = ['producto', 'estado'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void {
    this.cargar();
    this.marketing.productosRef().subscribe(p => {
      this.productosOpc = p.map(x => ({ id: x.id, texto: x.nombre }));
    });
  }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.marketing.promociones().subscribe({
      next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
      error: () => this.loading = false
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.promociones = this.todas.filter(p => {
      if (this.filtroEstado === 'activos' && !p.activo) return false;
      if (this.filtroEstado === 'eliminados' && p.activo) return false;
      if (this.filtroTipo !== 'todos' && p.tipo_descuento !== this.filtroTipo) return false;
      if (this.filtroVigencia !== 'todos'
          && vigenciaDe(p.fecha_inicio, p.fecha_fin) !== this.filtroVigencia) return false;
      if (!q) return true;
      return p.nombre.toLowerCase().includes(q)
          || (p.descripcion ?? '').toLowerCase().includes(q);
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
    this.filaSeleccionada = this.promociones.find(p => p.id === this.filaSeleccionada!.id) ?? null;
    if (!this.filaSeleccionada) { this.seleccionada = null; this.productoSeleccionado = null; }
  }

  vigencia(p: PromocionRow): string { return vigenciaDe(p.fecha_inicio, p.fecha_fin); }

  // ── Regla 2: selección + las cuatro opciones (promoción) ─────────────

  seleccionarFila(p: PromocionRow): void {
    this.filaSeleccionada = p;
    this.productoSeleccionado = null;
    this.cargarDetalle(p.id);
  }

  private cargarDetalle(id: number): void {
    this.marketing.promocion(id).subscribe({
      next: p => this.seleccionada = p,
      error: () => this.snackBar.open('No se pudo cargar la promoción', 'Cerrar', { duration: 3000 })
    });
  }

  cerrarDetalle(): void {
    this.seleccionada = null;
    this.filaSeleccionada = null;
    this.productoSeleccionado = null;
  }

  nuevaPromocion(): void { this.abrirDialogo('nuevo'); }
  modificarPromocion(): void { this.abrirDialogo('actualizar'); }
  verPromocion(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const promocion = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !promocion) return;

    const data: PromocionDialogData = { promocion, modo };
    this.dialog.open(PromocionDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: PromocionDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(promocion!, res);
      });
  }

  private crear(res: PromocionDialogResultado): void {
    const { activo, ...body } = res;
    this.marketing.crearPromocion(body).subscribe({
      next: () => {
        this.snackBar.open('Promoción creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la promoción'),
        'Cerrar', { duration: 4000 })
    });
  }

  private guardar(original: PromocionRow, res: PromocionDialogResultado): void {
    const { activo, ...cambios } = res;
    this.marketing.editarPromocion(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.marketing.activarPromocion(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Promoción actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
        if (this.seleccionada?.id === original.id) this.cargarDetalle(original.id);
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la promoción'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar la promoción = baja LÓGICA ─────────────────────

  eliminarPromocion(): void {
    const p = this.filaSeleccionada;
    if (!p || !p.activo) return;
    this.confirmar.eliminacion(
      `la promoción «${p.nombre}»`,
      `La promoción dejará de aplicarse a sus ${p.productos} producto/s en los pedidos ` +
      'nuevos. No se borra nada: los pedidos que ya la aplicaron conservan su ' +
      'descuento, y puedes restaurarla marcando «Activa» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.marketing.activarPromocion(p.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Promoción «${p.nombre}» eliminada`, 'OK', { duration: 3000 });
          this.cargar();
          if (this.seleccionada?.id === p.id) this.cargarDetalle(p.id);
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar la promoción'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'La promoción ya está eliminada (inactiva). Restáurala desde Modificar.'
      : '';
  }

  // ── Productos de la promoción: aquí el borrado SÍ es físico ──────────

  seleccionarProducto(pp: ProductoDePromocion): void { this.productoSeleccionado = pp; }

  asociarProducto(): void {
    if (!this.seleccionada) return;
    const promocionId = this.seleccionada.id;
    const yaAsociados = new Set(this.seleccionada.productos.map(p => p.producto_id));
    const data: PromocionProductoDialogData = {
      promocionNombre: this.seleccionada.nombre,
      opciones: this.productosOpc.filter(o => !yaAsociados.has(o.id))
    };
    this.dialog.open(PromocionProductoDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((productoId: number | undefined) => {
        if (productoId == null) return;
        this.marketing.asociarProducto(promocionId, productoId).subscribe({
          next: () => {
            this.snackBar.open('Producto asociado', 'OK', { duration: 2000, panelClass: ['snack-success'] });
            this.cargarDetalle(promocionId);
            this.cargar();
          },
          error: e => this.snackBar.open(mensajeError(e, 'Error al asociar el producto'),
            'Cerrar', { duration: 4000 })
        });
      });
  }

  /**
   * OJO: esto NO es una baja lógica. `quitarProducto` es un DELETE real sobre
   * `promocion_producto`: la fila desaparece de la BD y no hay bandera que
   * devolver. Por eso el mensaje avisa de que no se puede deshacer y no
   * promete ninguna restauración desde Modificar.
   */
  eliminarProducto(): void {
    const pp = this.productoSeleccionado;
    const promocionId = this.seleccionada?.id;
    if (!pp || promocionId == null) return;
    this.confirmar.eliminacion(
      `«${pp.producto}» de esta promoción`,
      'Se borra la asociación entre el producto y la promoción: ESTA ACCIÓN NO SE ' +
      'PUEDE DESHACER, y para revertirla habría que volver a asociarlo a mano. ' +
      'El producto en sí no se toca y los pedidos que ya aplicaron el descuento lo ' +
      'conservan; solo deja de aplicarse en los pedidos nuevos.'
    ).subscribe(ok => {
      if (!ok) return;
      this.marketing.quitarProducto(promocionId, pp.producto_id).subscribe({
        next: () => {
          this.snackBar.open(`«${pp.producto}» quitado de la promoción`, 'OK', { duration: 3000 });
          this.productoSeleccionado = null;
          this.cargarDetalle(promocionId);
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al quitar el producto'),
          'Cerrar', { duration: 4000 })
      });
    });
  }
}
