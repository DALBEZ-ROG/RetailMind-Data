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
import { ComprasService } from '../../../core/services/compras.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { OrdenCompraRow, OrdenCompraDetalle, StockRow } from '../../../core/models/operativo.model';

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
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
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

  ngOnInit(): void {
    // Solo ordenes aprobadas por Gerencia (confirmada) o con recepcion parcial:
    // el backend rechaza recibir una orden no aprobada (compuerta CU-O-12)
    this.compras.ordenes().subscribe(o =>
      this.ordenes = o.filter(x => ['confirmada', 'recibida_parcial'].includes(x.estado)));
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
        this.compras.ordenes().subscribe(o =>
          this.ordenes = o.filter(x =>
            ['confirmada', 'recibida_parcial'].includes(x.estado) || x.id === this.ordenId));
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar la recepción'), 'Cerrar', { duration: 5000 });
        this.cargarOrden(); // refresca cantidades ya recibidas
      }
    });
  }

  /** Consulta el inventario en la bodega de la orden para evidenciar que el stock subió. */
  private verificarStock(): void {
    if (!this.orden) return;
    this.stockDespues = [];
    const varianteIds = new Set(this.lineas.map(l => l.varianteId));
    this.referencias.stock().subscribe(rows => {
      this.stockDespues = rows.filter(r => varianteIds.has(r.producto_variante_id));
    });
  }
}
