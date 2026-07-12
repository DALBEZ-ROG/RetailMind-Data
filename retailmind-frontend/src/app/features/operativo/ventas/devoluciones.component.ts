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
import { VentasService } from '../../../core/services/ventas.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { NavPermissionsService } from '../../../core/navigation/nav-permissions.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  PedidoVentaRow, PedidoVentaDetalle, BodegaRef, CatalogoRef,
  DevolucionDetalle, StockRow
} from '../../../core/models/operativo.model';

interface LineaDevolucion {
  detalleId: number; sku: string; producto: string;
  compradas: number; devolver: number; estadoProducto: string; accion: string;
}

@Component({
  selector: 'app-devoluciones',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './devoluciones.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class DevolucionesComponent implements OnInit {

  pedidos: PedidoVentaRow[] = [];
  bodegas: BodegaRef[] = [];
  motivos: CatalogoRef[] = [];

  pedidoId: number | null = null;
  pedido: PedidoVentaDetalle | null = null;
  lineas: LineaDevolucion[] = [];

  motivoCodigo: string | null = null;
  bodegaId: number | null = null;
  descripcion = '';

  devolucion: DevolucionDetalle | null = null;
  stockDespues: StockRow[] = [];
  procesando = false;

  constructor(private ventas: VentasService, private referencias: ReferenciasService,
              private nav: NavPermissionsService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    // Devolvibles: entregados (o con devolución parcial previa) — compuerta del backend
    this.ventas.pedidos().subscribe(p =>
      this.pedidos = p.filter(x => ['entregado', 'devuelto'].includes(x.estado)));
    // VENDEDOR/DESPACHO no tienen SELECT sobre bodega ni motivo_devolucion en
    // la BD: no se disparan esas peticiones (evita 403 en consola).
    if (this.nav.canDato('refBodegas')) {
      this.referencias.bodegas().subscribe(b => this.bodegas = b);
    }
    if (this.nav.canDato('refMotivosDevolucion')) {
      this.referencias.motivosDevolucion().subscribe(m => this.motivos = m);
    }
  }

  cargarPedido(): void {
    if (!this.pedidoId) return;
    this.devolucion = null;
    this.stockDespues = [];
    this.ventas.pedido(this.pedidoId).subscribe({
      next: p => {
        this.pedido = p;
        this.lineas = p.detalles.map(d => ({
          detalleId: d.id, sku: d.sku, producto: d.nombre_producto,
          compradas: d.cantidad, devolver: 0, estadoProducto: 'nuevo', accion: 'reembolso'
        }));
      },
      error: () => this.snackBar.open('No se pudo cargar el pedido', 'Cerrar', { duration: 3000 })
    });
  }

  procesar(): void {
    if (!this.pedido || !this.motivoCodigo || !this.bodegaId) {
      this.snackBar.open('Pedido, motivo y bodega de reingreso son requeridos', 'Cerrar', { duration: 3500 });
      return;
    }
    const items = this.lineas
      .filter(l => l.devolver > 0)
      .map(l => ({
        pedidoDetalleId: l.detalleId, cantidad: l.devolver,
        estadoProducto: l.estadoProducto, accion: l.accion
      }));
    if (!items.length) {
      this.snackBar.open('Indica al menos una cantidad a devolver mayor que 0', 'Cerrar', { duration: 3500 });
      return;
    }
    const excedida = this.lineas.find(l => l.devolver > l.compradas);
    if (excedida) {
      this.snackBar.open(`No puedes devolver ${excedida.devolver} de ${excedida.sku}: solo se compraron ${excedida.compradas}`, 'Cerrar', { duration: 4500 });
      return;
    }
    this.procesando = true;
    this.ventas.procesarDevolucion(this.pedido.id, {
      motivoCodigo: this.motivoCodigo, bodegaId: this.bodegaId,
      descripcion: this.descripcion, items
    }).subscribe({
      next: dev => {
        this.procesando = false;
        this.devolucion = dev;
        this.snackBar.open(`Devolución ${dev.numero} procesada — stock reingresado`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.verificarStock();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo procesar la devolución'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  /** Evidencia del reingreso: stock en la bodega elegida. */
  private verificarStock(): void {
    if (!this.bodegaId) return;
    const skus = new Set(this.lineas.filter(l => l.devolver > 0).map(l => l.sku));
    this.referencias.stock(undefined, this.bodegaId).subscribe(rows =>
      this.stockDespues = rows.filter(r => skus.has(r.sku)));
  }
}
