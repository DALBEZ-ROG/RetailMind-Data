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
import { mensajeError } from '../../../core/services/api-error.util';
import {
  PedidoVentaRow, CatalogoRef, EnvioDetalle, SeguimientoRow
} from '../../../core/models/operativo.model';

@Component({
  selector: 'app-despachos',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './despachos.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class DespachosComponent implements OnInit {

  pedidos: PedidoVentaRow[] = [];
  pedidosEnTransito: PedidoVentaRow[] = [];
  transportistas: CatalogoRef[] = [];
  metodosEnvio: CatalogoRef[] = [];

  pedidoId: number | null = null;
  transportistaId: number | null = null;
  metodoEnvioId: number | null = null;
  observacion = '';

  // Entrega (despachado -> entregado)
  pedidoEntregaId: number | null = null;
  observacionEntrega = '';
  entregando = false;

  envio: EnvioDetalle | null = null;
  seguimiento: SeguimientoRow[] = [];
  procesando = false;

  constructor(private ventas: VentasService, private referencias: ReferenciasService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.cargarPedidos();
    this.referencias.transportistas().subscribe(t => this.transportistas = t);
    this.referencias.metodosEnvio().subscribe(m => this.metodosEnvio = m);
  }

  cargarPedidos(): void {
    this.ventas.pedidos().subscribe(p => {
      // Despachables: PAGADOS y con factura emitida (compuertas del backend)
      this.pedidos = p.filter(x =>
        ['pagado', 'en_preparacion'].includes(x.estado) && !!x.tiene_factura);
      // Entregables: en tránsito
      this.pedidosEnTransito = p.filter(x => x.estado === 'despachado');
    });
  }

  entregar(): void {
    if (!this.pedidoEntregaId) {
      this.snackBar.open('Selecciona el pedido a entregar', 'Cerrar', { duration: 3000 });
      return;
    }
    this.entregando = true;
    this.ventas.entregar(this.pedidoEntregaId, this.observacionEntrega).subscribe({
      next: p => {
        this.entregando = false;
        this.pedidoEntregaId = null;
        this.observacionEntrega = '';
        this.snackBar.open(`Pedido ${p.numero} ENTREGADO`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        if (p.envio) this.cargarSeguimiento(p.envio.id);
        this.cargarPedidos();
      },
      error: e => {
        this.entregando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar la entrega'), 'Cerrar', { duration: 5000 });
        this.cargarPedidos();
      }
    });
  }

  despachar(): void {
    if (!this.pedidoId || !this.transportistaId || !this.metodoEnvioId) {
      this.snackBar.open('Pedido, transportista y método de envío son requeridos', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.ventas.despachar(this.pedidoId, {
      transportistaId: this.transportistaId, metodoEnvioId: this.metodoEnvioId,
      observacion: this.observacion
    }).subscribe({
      next: envio => {
        this.procesando = false;
        this.envio = envio;
        this.snackBar.open(`Despachado — guía ${envio.numero_guia}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.pedidoId = null; // el pedido ya no es despachable
        this.cargarSeguimiento(envio.id);
        this.cargarPedidos();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo despachar el pedido'), 'Cerrar', { duration: 5000 });
        this.cargarPedidos(); // refleja el estado real si ya estaba despachado
      }
    });
  }

  cargarSeguimiento(envioId: number): void {
    this.ventas.seguimiento(envioId).subscribe(s => this.seguimiento = s);
  }
}
