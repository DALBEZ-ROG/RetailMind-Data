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
import { MatPaginatorModule } from '@angular/material/paginator';
import { PaginaLocal } from '../../../core/services/pagina-local.util';
import { ComprasService } from '../../../core/services/compras.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { AuthService } from '../../../core/services/auth.service';
import { SelectBuscableComponent, OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ProveedorRef, BodegaRef, VarianteRef, OrdenCompraRow, OrdenCompraDetalle
} from '../../../core/models/operativo.model';

interface LineaOrden { varianteId: number | null; cantidad: number; precioUnitario: number; }

@Component({
  selector: 'app-ordenes-compra',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    MatPaginatorModule, SelectBuscableComponent],
  templateUrl: './ordenes-compra.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class OrdenesCompraComponent implements OnInit {

  ordenes: OrdenCompraRow[] = [];
  /** La PÁGINA que se pinta. Sin esto el DOM recibía las 134.588 órdenes. */
  readonly pag = new PaginaLocal<OrdenCompraRow>();
  proveedores: ProveedorRef[] = [];
  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  variantesOpc: OpcionBuscable[] = [];
  loading = true;

  showForm = false;
  proveedorId: number | null = null;
  bodegaId: number | null = null;
  /** Fecha de entrega prometida por el proveedor (OTD-COM-05); opcional. */
  fechaEntregaEsperada: string | null = null;
  observacion = '';
  lineas: LineaOrden[] = [{ varianteId: null, cantidad: 1, precioUnitario: 0 }];

  ordenCreada: OrdenCompraDetalle | null = null;
  detalleOrden: OrdenCompraDetalle | null = null;
  procesando = false;

  constructor(private compras: ComprasService, private referencias: ReferenciasService,
              private auth: AuthService, private snackBar: MatSnackBar,
              private confirmar: ConfirmService) {}

  /** El botón Aprobar solo aplica a GERENTE/ADMIN (espeja SecurityConfig). */
  get puedeAprobar(): boolean {
    const user = this.auth.getCurrentUser();
    return !!user && ['ADMIN', 'GERENTE'].includes(user.rol);
  }

  /** Segregación financiera: BODEGA consulta órdenes para RECIBIR, sin montos. */
  get esBodega(): boolean {
    return this.auth.hasRole('BODEGA');
  }

  // Las columnas dependen del ROL, que no cambia durante la sesión, así que se
  // resuelven UNA vez y se guardan. Devolviéndolas a pelo, el getter entregaba
  // un array NUEVO en cada ciclo de detección de cambios y `MatTable` rehacía
  // las celdas sin que nada hubiera cambiado (trampa §8.6 de `PATRON_UI.md`).
  private _columnas?: string[];
  private _columnasDetalle?: string[];

  get columnas(): string[] {
    return this._columnas ??= this.esBodega
      ? ['numero', 'proveedor', 'bodega', 'estado', 'acciones']
      : ['numero', 'proveedor', 'bodega', 'estado', 'total', 'acciones'];
  }

  get columnasDetalle(): string[] {
    return this._columnasDetalle ??= this.esBodega
      ? ['sku', 'producto', 'cantidad', 'recibida']
      : ['sku', 'producto', 'cantidad', 'recibida', 'precio', 'subtotal'];
  }

  ngOnInit(): void {
    this.cargarOrdenes();
    this.referencias.proveedores().subscribe(p => this.proveedores = p);
    this.referencias.bodegas().subscribe(b => this.bodegas = b);
    this.referencias.variantes().subscribe(v => {
      this.variantes = v;
      this.variantesOpc = v.map(x =>
        ({ id: x.id, texto: `${x.sku} — ${x.producto} (costo $${Number(x.costo ?? 0).toFixed(2)})` }));
    });
  }

  cargarOrdenes(): void {
    this.loading = true;
    this.compras.ordenes().subscribe({
      next: data => { this.ordenes = data; this.pag.fijar(data); this.loading = false; },
      error: () => this.loading = false
    });
  }

  agregarLinea(): void { this.lineas.push({ varianteId: null, cantidad: 1, precioUnitario: 0 }); }
  quitarLinea(i: number): void { this.lineas.splice(i, 1); }

  alSeleccionarVariante(linea: LineaOrden): void {
    const v = this.variantes.find(x => x.id === linea.varianteId);
    if (v && v.costo != null) linea.precioUnitario = Number(v.costo);
  }

  get totalEstimado(): number {
    return this.lineas.reduce((acc, l) =>
      acc + (l.cantidad || 0) * (l.precioUnitario || 0), 0) * 1.15;
  }

  emitirOrden(): void {
    const items = this.lineas
      .filter(l => l.varianteId != null && l.cantidad > 0 && l.precioUnitario > 0)
      .map(l => ({ varianteId: l.varianteId!, cantidad: l.cantidad, precioUnitario: l.precioUnitario }));
    if (!this.proveedorId || !this.bodegaId || !items.length) {
      this.snackBar.open('Proveedor, bodega y al menos una línea válida son requeridos', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.compras.emitirOrden({
      proveedorId: this.proveedorId, bodegaId: this.bodegaId,
      fechaEntregaEsperada: this.fechaEntregaEsperada || null,
      observacion: this.observacion, items
    }).subscribe({
      next: orden => {
        this.procesando = false;
        this.ordenCreada = orden;
        this.snackBar.open(`Orden ${orden.numero} emitida — total ${orden.total}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.proveedorId = null; this.bodegaId = null; this.observacion = '';
        this.fechaEntregaEsperada = null;
        this.lineas = [{ varianteId: null, cantidad: 1, precioUnitario: 0 }];
        this.cargarOrdenes();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo emitir la orden'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  verOrden(id: number): void {
    this.compras.orden(id).subscribe({
      next: o => this.detalleOrden = o,
      error: () => this.snackBar.open('No se pudo cargar la orden', 'Cerrar', { duration: 3000 })
    });
  }

  /**
   * Aprobar es IRREVERSIBLE: `ComprasService.aprobarOrden` deja la orden en
   * 'confirmada' (no existe un estado 'aprobada' en el check de la tabla) y
   * `/api/compras` no expone ningún endpoint que devuelva la orden a
   * 'enviada'. Por eso confirma antes, y el mensaje dice qué se abre.
   */
  aprobarOrden(o: OrdenCompraRow): void {
    this.confirmar.confirmar({
      titulo: 'Confirmar aprobación de la orden',
      mensaje: `¿Aprobar la orden ${o.numero} del proveedor ${o.proveedor}?`,
      consecuencia:
        'La orden pasará de «enviada» a «confirmada» y quedará habilitada para recibir '
        + 'mercancía y, tras la recepción completa, para facturarse. La aprobación NO se '
        + 'puede deshacer: el sistema no ofrece ninguna acción que devuelva la orden a '
        + '«enviada».',
      textoAceptar: 'Aprobar la orden',
      tono: 'peligro'
    }).subscribe(ok => { if (ok) this.ejecutarAprobacion(o); });
  }

  private ejecutarAprobacion(o: OrdenCompraRow): void {
    this.procesando = true;
    this.compras.aprobarOrden(o.id).subscribe({
      next: r => {
        this.procesando = false;
        this.snackBar.open(`Orden ${r.numero} aprobada (${r.estadoAnterior} → ${r.estado})`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.cargarOrdenes();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo aprobar la orden'), 'Cerrar', { duration: 5000 });
      }
    });
  }
}
