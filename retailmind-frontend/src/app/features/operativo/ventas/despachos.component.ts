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
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  PedidoVentaRow, CatalogoRef, EnvioDetalle, SeguimientoRow, DetalleLogistico,
  NovedadesEnvioRes, NovedadEnvioRow
} from '../../../core/models/operativo.model';

/**
 * Despacho (script 39): solo pedidos PREPARADOS por bodega. Al seleccionar el
 * pedido se carga el detalle logístico completo (ítems, cliente, dirección,
 * transportista ASIGNADO por zona); despacho puede cambiar el transportista
 * (override registrado en la línea de tiempo), genera la guía y despacha.
 * Luego confirma la entrega. Compuerta backend: no se despacha sin preparar.
 */
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
  detalle: DetalleLogistico | null = null;
  cambiarTransportista = false;
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
              private snackBar: MatSnackBar, private confirmar: ConfirmService) {}

  ngOnInit(): void {
    this.cargarPedidos();
    this.referencias.transportistas().subscribe(t => this.transportistas = t);
    this.referencias.metodosEnvio().subscribe(m => this.metodosEnvio = m);
  }

  cargarPedidos(): void {
    this.ventas.pedidos().subscribe(p => {
      // Despachables: PREPARADOS por bodega (compuerta del backend)
      this.pedidos = p.filter(x => x.estado === 'preparado');
      // Entregables: en tránsito
      this.pedidosEnTransito = p.filter(x => x.estado === 'despachado');
    });
  }

  /** Al elegir el pedido carga su detalle logístico (ítems + asignación). */
  seleccionarPedido(): void {
    this.detalle = null;
    this.cambiarTransportista = false;
    this.transportistaId = null;
    this.metodoEnvioId = null;
    if (!this.pedidoId) return;
    this.ventas.detalleDespacho(this.pedidoId).subscribe({
      next: d => {
        this.detalle = d;
        this.transportistaId = d.transportista_id;
        this.metodoEnvioId = d.metodo_envio_id;
        // Sin asignación por zona (pedido legacy): despacho debe elegir
        this.cambiarTransportista = !d.transportista_id;
      },
      error: e => this.snackBar.open(
        mensajeError(e, 'No se pudo cargar el detalle del pedido'), 'Cerrar', { duration: 5000 })
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
    if (!this.pedidoId) {
      this.snackBar.open('Selecciona el pedido a despachar', 'Cerrar', { duration: 3500 });
      return;
    }
    if (this.cambiarTransportista && (!this.transportistaId || !this.metodoEnvioId)) {
      this.snackBar.open('Selecciona transportista y método de envío', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    // Sin override se despacha con el transportista ASIGNADO por zona
    const body = this.cambiarTransportista
      ? { transportistaId: this.transportistaId, metodoEnvioId: this.metodoEnvioId,
          observacion: this.observacion }
      : { observacion: this.observacion };
    this.ventas.despachar(this.pedidoId, body).subscribe({
      next: envio => {
        this.procesando = false;
        this.envio = envio;
        this.snackBar.open(`Despachado — guía ${envio.numero_guia}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.pedidoId = null; // el pedido ya no es despachable
        this.detalle = null;
        this.observacion = '';
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

  // ── Novedades / incidencias de envío (script 44) ─────────────────────
  // Sobre un pedido DESPACHADO: registrar la novedad (el envío queda
  // 'fallido') y resolverla: reprogramar (máx. 3 intentos) o devolver al
  // almacén (el pedido pasa a 'no_entregado'; el stock NO se reingresa aquí).

  tiposNovedad = [
    { codigo: 'cliente_ausente', nombre: 'Cliente ausente' },
    { codigo: 'direccion_incorrecta', nombre: 'Dirección incorrecta' },
    { codigo: 'cliente_rechazo', nombre: 'Cliente rechazó el paquete' },
    { codigo: 'zona_dificil_acceso', nombre: 'Zona de difícil acceso' },
    { codigo: 'dano_en_transito', nombre: 'Daño en tránsito' }
  ];

  pedidoNovedadId: number | null = null;
  novedadesInfo: NovedadesEnvioRes | null = null;
  tipoNovedad: string | null = null;
  descripcionNovedad = '';
  observacionResolucion = '';
  procesandoNovedad = false;

  get novedadAbierta(): NovedadEnvioRow | undefined {
    return this.novedadesInfo?.novedades.find(n => n.estado === 'abierta');
  }

  etiquetaNovedad(codigo: string): string {
    return this.tiposNovedad.find(t => t.codigo === codigo)?.nombre ?? codigo;
  }

  seleccionarPedidoNovedad(): void {
    this.novedadesInfo = null;
    this.tipoNovedad = null;
    this.descripcionNovedad = '';
    this.observacionResolucion = '';
    if (!this.pedidoNovedadId) return;
    this.ventas.novedadesPedido(this.pedidoNovedadId).subscribe({
      next: info => {
        this.novedadesInfo = info;
        if (info.envio) this.cargarSeguimiento(info.envio.id);
      },
      error: e => this.snackBar.open(
        mensajeError(e, 'No se pudieron cargar las novedades'), 'Cerrar', { duration: 5000 })
    });
  }

  registrarNovedad(): void {
    if (!this.novedadesInfo?.envio || !this.tipoNovedad) {
      this.snackBar.open('Selecciona el pedido y el tipo de novedad', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesandoNovedad = true;
    this.ventas.registrarNovedad(this.novedadesInfo.envio.id,
      { tipo: this.tipoNovedad, descripcion: this.descripcionNovedad }).subscribe({
      next: info => this.trasAccionNovedad(info, 'Novedad registrada — el envío queda con incidencia'),
      error: e => this.errorNovedad(e, 'No se pudo registrar la novedad')
    });
  }

  reprogramar(): void {
    const abierta = this.novedadAbierta;
    if (!abierta) return;
    this.procesandoNovedad = true;
    this.ventas.reprogramarNovedad(abierta.id, this.observacionResolucion).subscribe({
      next: info => this.trasAccionNovedad(info,
        `Entrega reprogramada — intento ${info.intentos} de ${info.max_intentos ?? 3}`),
      error: e => this.errorNovedad(e, 'No se pudo reprogramar la entrega')
    });
  }

  /**
   * Devolver al almacén es IRREVERSIBLE: `VentasService.devolverAlmacen` deja
   * el envío 'devuelto' y el pedido en 'no_entregado', que es TERMINAL (no
   * hay transición de salida en el servicio). Por eso confirma antes.
   */
  devolverAlmacen(): void {
    const abierta = this.novedadAbierta;
    if (!abierta) return;
    const pedido = this.pedidosEnTransito.find(p => p.id === this.pedidoNovedadId);
    this.confirmar.confirmar({
      titulo: 'Confirmar devolución al almacén',
      mensaje: `¿Devolver al almacén el envío del pedido `
             + `${pedido?.numero ?? '#' + this.pedidoNovedadId}?`,
      consecuencia:
        'El envío quedará «devuelto» y el pedido pasará a NO ENTREGADO, que es un estado '
        + 'terminal: ya no se podrá reprogramar la entrega ni entregarlo. El stock NO se '
        + 'reingresa aquí —eso lo decide la inspección física de bodega, como en la RMA— y el '
        + 'reembolso al cliente queda pendiente de gestionarse por ticket de soporte y '
        + 'gerencia. La alternativa reversible es «Reprogramar entrega».',
      textoAceptar: 'Devolver al almacén',
      tono: 'peligro'
    }).subscribe(ok => { if (ok) this.ejecutarDevolucionAlmacen(abierta.id); });
  }

  private ejecutarDevolucionAlmacen(novedadId: number): void {
    this.procesandoNovedad = true;
    this.ventas.devolverAlmacen(novedadId, this.observacionResolucion).subscribe({
      next: info => {
        this.trasAccionNovedad(info,
          'Envío devuelto al almacén — el pedido queda NO ENTREGADO (sin reingreso de stock)');
        this.pedidoNovedadId = null; // el pedido sale de "en tránsito"
      },
      error: e => this.errorNovedad(e, 'No se pudo devolver el envío al almacén')
    });
  }

  private trasAccionNovedad(info: NovedadesEnvioRes, mensaje: string): void {
    this.procesandoNovedad = false;
    this.novedadesInfo = info;
    this.tipoNovedad = null;
    this.descripcionNovedad = '';
    this.observacionResolucion = '';
    this.snackBar.open(mensaje, 'OK', { duration: 4000, panelClass: ['snack-success'] });
    if (info.envio) this.cargarSeguimiento(info.envio.id);
    this.cargarPedidos();
  }

  private errorNovedad(e: unknown, porDefecto: string): void {
    this.procesandoNovedad = false;
    this.snackBar.open(mensajeError(e, porDefecto), 'Cerrar', { duration: 5000 });
    this.seleccionarPedidoNovedad(); // refleja el estado real del envío
  }
}
