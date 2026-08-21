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
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, map } from 'rxjs/operators';
import { VentasService } from '../../../core/services/ventas.service';
import { SelectBuscableComponent } from '../../../core/components/select-buscable/select-buscable.component';
import { mensajeError } from '../../../core/services/api-error.util';
import { FacturaVenta, FacturaVentaRow } from '../../../core/models/operativo.model';
import { CodigoLegiblePipe } from '../../../core/pipes/etiquetas.pipe';

import { CampoTextoDirective } from '../../../core/validacion';

@Component({
  selector: 'app-facturas-venta',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatPaginatorModule, SelectBuscableComponent, CodigoLegiblePipe,
    CampoTextoDirective
  ],
  templateUrl: './facturas-venta.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class FacturasVentaComponent implements OnInit {

  /**
   * Buscador de pedidos FACTURABLES, resuelto EN EL SERVIDOR.
   *
   * Antes era un `mat-select` con los 50 facturables más recientes por id, y
   * ahí no se podía TECLEAR nada. Con 15.172 facturables entre 2.999.993
   * pedidos —y con los recién creados al final del orden por id, porque su id
   * sale de la secuencia (4.341) y los sembrados llegan a 2.100.119.001— el
   * pedido que uno quiere facturar prácticamente nunca estaba en esa lista.
   * Ahora se escribe el número (o el cliente) y el filtro se aplica al conjunto
   * completo, con la MISMA compuerta `facturables=true` del backend.
   */
  buscarPedidoFacturable = (q: string) =>
    this.ventas.pedidos({ facturables: true, q, size: this.TOPE_SELECTOR, conTotal: false })
      .pipe(map(pg => pg.items.map(p => ({
        id: p.id,
        // El monto puede no venir (roles logísticos no leen `pedido.total`):
        // en ese caso se omite en vez de pintar «$NaN».
        texto: p.total != null
          ? `${p.numero} — ${p.cliente} ($${Number(p.total).toFixed(2)})`
          : `${p.numero} — ${p.cliente}`
      }))));

  /** Tope de coincidencias por consulta: es un buscador, no un volcado. */
  readonly TOPE_SELECTOR = 50;

  pedidoId: number | null = null;
  factura: FacturaVenta | null = null;
  procesando = false;

  // Listado de facturas emitidas (búsqueda + paginación server-side)
  facturas: FacturaVentaRow[] = [];
  total = 0;

  /** Ver `pedidos-venta`: el conteo de 2.855.378 facturas viene acotado. */
  totalEsMinimo = false;

  get etiquetaTotal(): string {
    const n = this.total.toLocaleString('es-EC');
    return this.totalEsMinimo ? `más de ${n}` : n;
  }
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];
  q = '';
  private busqueda$ = new Subject<string>();
  loading = true;

  detalle: FacturaVenta | null = null;

  columnas = ['numero', 'pedido', 'cliente', 'fecha', 'total', 'estado', 'acciones'];

  constructor(private ventas: VentasService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.cargarFacturas();
    this.busqueda$.pipe(debounceTime(350), distinctUntilChanged()).subscribe(() => {
      this.pagina = 0;
      this.cargarFacturas();
    });
  }

  cargarFacturas(): void {
    this.loading = true;
    this.ventas.facturas(this.q, this.pagina, this.tamPagina).subscribe({
      next: pg => {
        this.facturas = pg.items;
        this.total = pg.total;
        this.totalEsMinimo = !!pg.totalEsMinimo;
        this.loading = false;
      },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudieron cargar las facturas'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  alBuscar(): void { this.busqueda$.next(this.q); }

  alPaginar(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
    this.cargarFacturas();
  }

  emitirFactura(): void {
    if (!this.pedidoId) {
      this.snackBar.open('Selecciona el pedido a facturar', 'Cerrar', { duration: 3000 });
      return;
    }
    this.procesando = true;
    this.ventas.emitirFactura(this.pedidoId).subscribe({
      next: f => {
        this.procesando = false;
        this.factura = f;
        this.pedidoId = null;
        this.snackBar.open(`Factura ${f.numero} emitida — total ${f.total}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.cargarFacturas();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo emitir la factura'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  verDetalle(id: number): void {
    this.ventas.factura(id).subscribe({
      next: f => this.detalle = f,
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar la factura'), 'Cerrar', { duration: 4000 })
    });
  }

  /** Descarga el PDF autenticado como blob y lo abre en una pestaña nueva. */
  verPdf(facturaId: number): void {
    this.ventas.facturaPdf(facturaId).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }
}
