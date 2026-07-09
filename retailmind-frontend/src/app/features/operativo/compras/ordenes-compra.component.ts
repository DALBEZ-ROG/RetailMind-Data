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
import { ComprasService } from '../../../core/services/compras.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ProveedorRef, BodegaRef, VarianteRef, OrdenCompraRow, OrdenCompraDetalle
} from '../../../core/models/operativo.model';

interface LineaOrden { varianteId: number | null; cantidad: number; precioUnitario: number; }

@Component({
  selector: 'app-ordenes-compra',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './ordenes-compra.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class OrdenesCompraComponent implements OnInit {

  ordenes: OrdenCompraRow[] = [];
  proveedores: ProveedorRef[] = [];
  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  loading = true;

  showForm = false;
  proveedorId: number | null = null;
  bodegaId: number | null = null;
  observacion = '';
  lineas: LineaOrden[] = [{ varianteId: null, cantidad: 1, precioUnitario: 0 }];

  ordenCreada: OrdenCompraDetalle | null = null;
  detalleOrden: OrdenCompraDetalle | null = null;
  procesando = false;

  columnas = ['numero', 'proveedor', 'bodega', 'estado', 'total', 'acciones'];

  constructor(private compras: ComprasService, private referencias: ReferenciasService,
              private auth: AuthService, private snackBar: MatSnackBar) {}

  /** El botón Aprobar solo aplica a GERENTE/ADMIN (espeja SecurityConfig). */
  get puedeAprobar(): boolean {
    const user = this.auth.getCurrentUser();
    return !!user && ['ADMIN', 'GERENTE'].includes(user.rol);
  }

  ngOnInit(): void {
    this.cargarOrdenes();
    this.referencias.proveedores().subscribe(p => this.proveedores = p);
    this.referencias.bodegas().subscribe(b => this.bodegas = b);
    this.referencias.variantes().subscribe(v => this.variantes = v);
  }

  cargarOrdenes(): void {
    this.loading = true;
    this.compras.ordenes().subscribe({
      next: data => { this.ordenes = data; this.loading = false; },
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
      observacion: this.observacion, items
    }).subscribe({
      next: orden => {
        this.procesando = false;
        this.ordenCreada = orden;
        this.snackBar.open(`Orden ${orden.numero} emitida — total ${orden.total}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.proveedorId = null; this.bodegaId = null; this.observacion = '';
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

  aprobarOrden(o: OrdenCompraRow): void {
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
