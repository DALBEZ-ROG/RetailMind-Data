import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { VentasService } from '../../../core/services/ventas.service';
import { PaginaServidor } from '../../../core/services/pagina-servidor.util';
import { mensajeError } from '../../../core/services/api-error.util';
import { PreparacionRow, DetalleLogistico } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

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
    MatPaginatorModule, MatSnackBarModule, MatFormFieldModule, MatInputModule,
    CampoTextoDirective
  ],
  templateUrl: './preparacion-pedidos.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PreparacionPedidosComponent implements OnInit {

  /**
   * La página que devuelve el servidor. Esta pantalla no tenía paginador
   * NINGUNO: pintaba las 26.551 filas de la cola y esperaba 27,5 s a que el
   * endpoint las calculara.
   */
  readonly pag = new PaginaServidor<PreparacionRow>();

  /**
   * Búsqueda por número de pedido o cliente, resuelta EN EL SERVIDOR.
   *
   * La cola va por fecha ASCENDENTE sobre 26.551 pedidos, así que un pedido
   * recién facturado entra por el final y bodega no lo encontraba salvo
   * paginando cientos de páginas.
   */
  q = '';
  private busqueda$ = new Subject<string>();

  columnas = ['numero', 'cliente', 'canal', 'items', 'transportista', 'estado', 'acciones'];
  columnasDetalle = ['sku', 'producto', 'cantidad'];

  detalle: DetalleLogistico | null = null;
  cargandoDetalle = false;
  procesando = false;

  constructor(private ventas: VentasService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.cargarCola();
    this.busqueda$.pipe(debounceTime(350), distinctUntilChanged()).subscribe(() => {
      // Cambia el conjunto: vuelve a la primera página y SÍ recuenta.
      this.pag.pagina = 0;
      this.cargarCola();
    });
  }

  alBuscar(): void { this.busqueda$.next(this.q); }

  limpiarBusqueda(): void { this.q = ''; this.alBuscar(); }

  /**
   * @param conTotal false al cambiar de página: el conjunto no ha cambiado y
   *                 el conteo cuesta 4,7 s bajo RLS.
   */
  cargarCola(conTotal = true): void {
    this.ventas.colaPreparacion({
      page: this.pag.pagina, size: this.pag.tam, conTotal, q: this.q.trim() || undefined
    }).subscribe({
      next: p => {
        this.pag.aplicar(p);
        // Preparar el último pedido de la página la deja vacía: retrocede.
        if (this.pag.ajustarTrasBorrado()) { this.cargarCola(conTotal); }
      },
      error: e => this.snackBar.open(
        mensajeError(e, 'No se pudo cargar la cola de preparación'), 'Cerrar', { duration: 5000 })
    });
  }

  alPaginar(e: PageEvent): void {
    this.pag.alPaginar(e);
    this.cargarCola(false);
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
