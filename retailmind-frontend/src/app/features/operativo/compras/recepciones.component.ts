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
import { forkJoin } from 'rxjs';
import { ComprasService } from '../../../core/services/compras.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { OrdenCompraRow, OrdenCompraDetalle, StockRow } from '../../../core/models/operativo.model';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

interface LineaRecepcion {
  detalleId: number; sku: string; producto: string; varianteId: number;
  pedida: number; yaRecibida: number; aRecibir: number;
  /** Rechazo en puerta: NO entra a stock; queda pendiente de devolución a proveedor. */
  rechazada: number; motivoRechazo: string;
}

@Component({
  selector: 'app-recepciones',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    CampoNumeroDirective, CampoTextoDirective
  ],
  templateUrl: './recepciones.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class RecepcionesComponent implements OnInit {

  readonly columnas = ['sku', 'producto', 'pedida', 'recibida', 'aRecibir', 'rechazada', 'motivoRechazo'];

  ordenes: OrdenCompraRow[] = [];
  ordenId: number | null = null;
  orden: OrdenCompraDetalle | null = null;
  lineas: LineaRecepcion[] = [];
  observacion = '';

  resultado: { numero: string; estadoOrden: string } | null = null;
  stockDespues: StockRow[] = [];
  procesando = false;

  constructor(private compras: ComprasService, private referencias: ReferenciasService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.cargarRecibibles(); }

  /**
   * Solo órdenes aprobadas por Gerencia (confirmada) o con recepción parcial:
   * el backend rechaza recibir una orden no aprobada (compuerta CU-O-12).
   *
   * **El filtro se movió a SQL** (`recibibles=true`). Antes se descargaban las
   * 134.588 órdenes y se filtraban aquí; al paginar el endpoint ese `filter`
   * habría mirado solo las 25 primeras y el selector habría salido VACÍO sin
   * error alguno: las 79 recibibles son las de id más bajo y el listado va por
   * `id DESC`. Se piden 200 (el tope de `Paginacion`) y `total` dice cuántas
   * hay de verdad.
   *
   * @param incluirOrdenId conserva en el selector la orden recién recibida
   *                       aunque ya no cumpla el predicado — es el
   *                       `|| x.id === this.ordenId` de antes, en SQL.
   */
  totalRecibibles = 0;

  private cargarRecibibles(incluirOrdenId?: number | null): void {
    this.compras.ordenes({ recibibles: true, incluirOrdenId, size: 200 }).subscribe(pg => {
      this.ordenes = pg.items;
      this.totalRecibibles = pg.total;
    });
  }

  cargarOrden(): void {
    if (!this.ordenId) return;
    this.resultado = null;
    this.stockDespues = [];
    this.compras.orden(this.ordenId).subscribe({
      next: o => {
        this.orden = o;
        this.lineas = o.detalles.map(d => ({
          detalleId: d.id, sku: d.sku, producto: d.producto,
          varianteId: d.producto_variante_id,
          pedida: d.cantidad, yaRecibida: d.cantidad_recibida,
          aRecibir: Math.max(d.cantidad - d.cantidad_recibida, 0),
          rechazada: 0, motivoRechazo: ''
        }));
      },
      error: () => this.snackBar.open('No se pudo cargar la orden', 'Cerrar', { duration: 3000 })
    });
  }

  registrar(): void {
    if (!this.orden) return;
    const items = this.lineas
      .filter(l => l.aRecibir > 0)
      .map(l => ({
        ordenCompraDetalleId: l.detalleId, cantidadRecibida: l.aRecibir,
        // Rechazo en puerta: no entra a stock; cae al pool de defectuosos
        // pendientes de devolución a proveedor (backend, script 45)
        cantidadRechazada: l.rechazada > 0 ? l.rechazada : undefined,
        motivoRechazo: l.rechazada > 0 && l.motivoRechazo ? l.motivoRechazo : undefined
      }));
    if (!items.length) {
      this.snackBar.open('Indica al menos una cantidad a recibir mayor que 0', 'Cerrar', { duration: 3500 });
      return;
    }
    const excedida = this.lineas.find(l => l.aRecibir > l.pedida - l.yaRecibida);
    if (excedida) {
      this.snackBar.open(`No puedes recibir ${excedida.aRecibir} de ${excedida.sku}: solo quedan ${excedida.pedida - excedida.yaRecibida} pendientes`, 'Cerrar', { duration: 4500 });
      return;
    }
    this.procesando = true;
    this.compras.registrarRecepcion(this.orden.id, { observacion: this.observacion, items }).subscribe({
      next: res => {
        this.procesando = false;
        this.resultado = { numero: res.numero, estadoOrden: res.estadoOrden };
        this.snackBar.open(`Recepción ${res.numero} confirmada — orden ${res.estadoOrden}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.verificarStock();
        this.cargarOrden();
        this.cargarRecibibles(this.ordenId);
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar la recepción'), 'Cerrar', { duration: 5000 });
        this.cargarOrden(); // refresca cantidades ya recibidas
      }
    });
  }

  /**
   * Consulta el inventario para evidenciar que el stock subió.
   *
   * **El filtro se movió a SQL.** Antes se pedía `/api/referencias/stock` SIN
   * argumentos —las 11.406 posiciones del inventario, 2,17 MB— y se filtraba
   * aquí por las variantes de la recepción. El endpoint ya aceptaba
   * `varianteId`, así que ahora se pide una consulta por variante de la orden
   * (unas pocas líneas) y no llega ni una fila de más.
   */
  private verificarStock(): void {
    if (!this.orden) return;
    this.stockDespues = [];
    const varianteIds = [...new Set(this.lineas.map(l => l.varianteId))];
    if (!varianteIds.length) return;
    forkJoin(varianteIds.map(id => this.referencias.stock(id)))
      .subscribe(listas => this.stockDespues = listas.flat());
  }
}
