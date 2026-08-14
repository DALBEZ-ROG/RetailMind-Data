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
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { VentasService } from '../../../core/services/ventas.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { PedidoVentaRow, FacturaVenta, FacturaVentaRow } from '../../../core/models/operativo.model';
import { CodigoLegiblePipe } from '../../../core/pipes/etiquetas.pipe';

@Component({
  selector: 'app-facturas-venta',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatPaginatorModule, CodigoLegiblePipe],
  templateUrl: './facturas-venta.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class FacturasVentaComponent implements OnInit {

  // Emisión: solo pedidos PAGADOS sin factura (compuerta del backend)
  pedidos: PedidoVentaRow[] = [];

  /**
   * Cuántos facturables se ofrecen en el desplegable. El servidor no entrega
   * más de 200 por página, y meter 15.172 opciones en un `mat-select` sería
   * cambiar un problema de memoria por otro de render.
   */
  readonly TOPE_SELECTOR = 200;

  pedidoId: number | null = null;
  factura: FacturaVenta | null = null;
  procesando = false;

  // Listado de facturas emitidas (búsqueda + paginación server-side)
  facturas: FacturaVentaRow[] = [];
  total = 0;
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
    this.cargarPedidos();
    this.cargarFacturas();
    this.busqueda$.pipe(debounceTime(350), distinctUntilChanged()).subscribe(() => {
      this.pagina = 0;
      this.cargarFacturas();
    });
  }

  /**
   * Selector de pedidos FACTURABLES.
   *
   * El filtro (estado en pagado/en_preparacion/despachado/entregado y sin
   * factura) lo aplica ahora el SERVIDOR con `facturables=true`: es la misma
   * regla, movida de sitio. Aplicarla aquí sobre una página exigiría antes
   * descargar los 2.999.993 pedidos —que es lo que tumbaba el backend— y, con
   * el listado paginado, filtrar solo la página visible habría dejado el
   * selector casi siempre VACÍO sin dar ningún error: los 15.172 facturables
   * no están en las primeras 200 filas de tres millones.
   */
  cargarPedidos(): void {
    // Sin conteo: contar los facturables exige recorrer los 2.999.993 pedidos
    // con un NOT EXISTS contra `factura_venta`, y bajo RLS eso son ~9 s para
    // un número que aquí solo serviría de rótulo. El selector se declara como
    // «los N más recientes» y no promete un total.
    this.ventas.pedidos({ facturables: true, size: this.TOPE_SELECTOR, conTotal: false })
      .subscribe(pg => this.pedidos = pg.items);
  }

  cargarFacturas(): void {
    this.loading = true;
    this.ventas.facturas(this.q, this.pagina, this.tamPagina).subscribe({
      next: pg => {
        this.facturas = pg.items;
        this.total = pg.total;
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
        this.cargarPedidos();
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
