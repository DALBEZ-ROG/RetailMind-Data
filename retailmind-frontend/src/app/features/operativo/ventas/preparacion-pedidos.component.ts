import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { VentasService } from '../../../core/services/ventas.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { PreparacionRow, DetalleLogistico } from '../../../core/models/operativo.model';

/**
 * Preparación de pedidos (picking/empaque) — pantalla de BODEGA (script 39).
 * Cola: pedidos FACTURADOS (por tomar) y EN PREPARACIÓN (picking en curso).
 * Bodega ve el detalle (ítems, cantidades, cliente, dirección, transportista
 * asignado), inicia la preparación y la marca PREPARADO; recién ahí el pedido
 * pasa a la bandeja de despacho. Compuertas en backend: no se prepara un
 * pedido sin factura ni se despacha uno sin preparar.
 */
@Component({
  selector: 'app-preparacion-pedidos',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatSnackBarModule],
  templateUrl: './preparacion-pedidos.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PreparacionPedidosComponent implements OnInit {

  cola: PreparacionRow[] = [];
  columnas = ['numero', 'cliente', 'canal', 'items', 'transportista', 'estado', 'acciones'];
  columnasDetalle = ['sku', 'producto', 'cantidad'];

  detalle: DetalleLogistico | null = null;
  cargandoDetalle = false;
  procesando = false;

  constructor(private ventas: VentasService, private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.cargarCola(); }

  cargarCola(): void {
    this.ventas.colaPreparacion().subscribe({
      next: c => this.cola = c,
      error: e => this.snackBar.open(
        mensajeError(e, 'No se pudo cargar la cola de preparación'), 'Cerrar', { duration: 5000 })
    });
  }

  verDetalle(pedidoId: number): void {
    this.cargandoDetalle = true;
    this.ventas.detallePreparacion(pedidoId).subscribe({
      next: d => { this.detalle = d; this.cargandoDetalle = false; },
      error: e => {
        this.cargandoDetalle = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar el detalle'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  iniciar(pedidoId: number): void {
    this.procesando = true;
    this.ventas.iniciarPreparacion(pedidoId).subscribe({
      next: d => {
        this.procesando = false;
        this.detalle = d;
        this.snackBar.open(`Picking iniciado para ${d.numero}`, 'OK',
          { duration: 3000, panelClass: ['snack-success'] });
        this.cargarCola();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo iniciar la preparación'), 'Cerrar', { duration: 5000 });
        this.cargarCola();
      }
    });
  }

  marcarPreparado(pedidoId: number): void {
    this.procesando = true;
    this.ventas.marcarPreparado(pedidoId).subscribe({
      next: d => {
        this.procesando = false;
        this.detalle = d;
        this.snackBar.open(`Pedido ${d.numero} PREPARADO — pasa a despacho`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.cargarCola();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo marcar el pedido como preparado'), 'Cerrar', { duration: 5000 });
        this.cargarCola();
      }
    });
  }

  cerrarDetalle(): void { this.detalle = null; }
}
