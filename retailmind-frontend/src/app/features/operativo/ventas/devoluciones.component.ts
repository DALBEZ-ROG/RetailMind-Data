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
import { Observable } from 'rxjs';
import { DevolucionesService } from '../../../core/services/devoluciones.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  CatalogoRef, DevolucionRow, DevolucionRma
} from '../../../core/models/operativo.model';
import { CodigoLegiblePipe } from '../../../core/pipes/etiquetas.pipe';

/**
 * Tablero RMA / logística inversa. Una sola pantalla para todo el pipeline;
 * cada rol ve SOLO sus acciones (el backend + la BD son la fuente de verdad):
 *   SOPORTE  revisar / aprobar (guía de retorno) / rechazar / cerrar
 *   DESPACHO en tránsito / recibida
 *   BODEGA   recibida / inspección por ítem (único reingreso de stock)
 *   GERENTE  reembolso
 *   ADMIN    todas · VENDEDOR solo consulta
 */
@Component({
  selector: 'app-devoluciones',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule, CodigoLegiblePipe],
  templateUrl: './devoluciones.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class DevolucionesComponent implements OnInit {

  readonly estados = ['solicitada', 'en_revision', 'aprobada', 'rechazada', 'en_transito',
    'recibida', 'inspeccionada', 'reembolsada', 'cerrada'];
  readonly metodosReembolso = ['transferencia', 'tarjeta', 'credito_tienda', 'efectivo'];

  devoluciones: DevolucionRow[] = [];
  filtroEstado: string | null = null;
  detalle: DevolucionRma | null = null;
  transportistas: CatalogoRef[] = [];
  loading = true;
  procesando = false;

  // Formularios de las transiciones
  transportistaId: number | null = null;
  motivoRechazo = '';
  observacion = '';
  inspeccion: Record<number, { resultado: string | null; nota: string }> = {};
  metodoReembolso: string | null = null;
  referenciaReembolso = '';

  constructor(private rma: DevolucionesService, private auth: AuthService,
              private snackBar: MatSnackBar, private confirmar: ConfirmService) {}

  // Acciones visibles por rol (espeja SecurityConfig; el backend decide)
  get esAdmin(): boolean    { return this.auth.hasRole('ADMIN'); }
  get esSoporte(): boolean  { return this.esAdmin || this.auth.hasRole('SOPORTE'); }
  get esDespacho(): boolean { return this.esAdmin || this.auth.hasRole('DESPACHO'); }
  get esBodega(): boolean   { return this.esAdmin || this.auth.hasRole('BODEGA'); }
  get esGerente(): boolean  { return this.esAdmin || this.auth.hasRole('GERENTE'); }

  /** Segregación financiera: BODEGA/DESPACHO puros no ven montos ni precios. */
  get veMontos(): boolean {
    return !(this.auth.hasRole('BODEGA') || this.auth.hasRole('DESPACHO'));
  }

  get columnas(): string[] {
    return this.veMontos
      ? ['numero', 'pedido', 'cliente', 'motivo', 'monto', 'estado', 'fecha', 'acciones']
      : ['numero', 'pedido', 'cliente', 'motivo', 'estado', 'fecha', 'acciones'];
  }

  get columnasItems(): string[] {
    return this.veMontos
      ? ['sku', 'producto', 'cantidad', 'precio', 'inspeccion']
      : ['sku', 'producto', 'cantidad', 'inspeccion'];
  }

  ngOnInit(): void {
    this.cargar();
    if (this.esSoporte) {
      this.rma.transportistas().subscribe(t => this.transportistas = t);
    }
  }

  cargar(): void {
    this.loading = true;
    this.rma.listar(this.filtroEstado || undefined).subscribe({
      next: d => { this.devoluciones = d; this.loading = false; },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudieron cargar las devoluciones'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  ver(id: number): void {
    this.rma.detalle(id).subscribe({
      next: d => {
        this.detalle = d;
        this.motivoRechazo = '';
        this.observacion = '';
        this.metodoReembolso = null;
        this.referenciaReembolso = '';
        this.inspeccion = {};
        d.detalles.forEach(it => this.inspeccion[it.id] = { resultado: null, nota: '' });
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar la devolución'), 'Cerrar', { duration: 4000 })
    });
  }

  /** true cuando BODEGA puede capturar el resultado por ítem. */
  get enInspeccion(): boolean {
    return !!this.detalle && this.detalle.estado === 'recibida';
  }

  /** Monto que pagaría el reembolso: ítems apto_reventa + defectuoso. */
  get montoReembolsable(): number {
    if (!this.detalle) return 0;
    return this.detalle.detalles
      .filter(d => ['apto_reventa', 'defectuoso'].includes(d.resultado_inspeccion || ''))
      .reduce((acc, d) => acc + (d.precio_unitario ?? 0) * d.cantidad, 0);
  }

  revision(): void  { this.transicion(this.rma.revision(this.detalle!.id), 'Revisión iniciada'); }

  aprobar(): void {
    this.transicion(this.rma.aprobar(this.detalle!.id, this.transportistaId),
      'Devolución APROBADA — guía de retorno generada');
  }

  /**
   * Rechazar es IRREVERSIBLE: `DevolucionService.rechazar` deja la devolución
   * en 'rechazada', estado TERMINAL (ninguna transición lo admite como
   * origen), y marca el ticket como resuelto. Por eso confirma antes.
   */
  rechazar(): void {
    if (!this.motivoRechazo.trim()) {
      this.snackBar.open('Indica el motivo del rechazo', 'Cerrar', { duration: 3000 });
      return;
    }
    const d = this.detalle!;
    this.confirmar.confirmar({
      titulo: 'Confirmar rechazo de la devolución',
      mensaje: `¿Rechazar la devolución ${d.numero} del pedido ${d.numero_pedido}?`,
      consecuencia:
        '«Rechazada» es un estado terminal: la devolución no admite ninguna transición '
        + 'posterior, no se recibe mercancía y no se reembolsa nada. El ticket de soporte '
        + 'asociado queda RESUELTO y el cliente recibe en él el motivo que escribiste; si '
        + 'responde, el ticket se reabre. El cliente conserva el producto y puede volver a '
        + 'solicitar la devolución mientras siga dentro del plazo de 30 días.',
      textoAceptar: 'Rechazar la devolución',
      tono: 'peligro'
    }).subscribe(ok => {
      if (ok) {
        this.transicion(this.rma.rechazar(d.id, this.motivoRechazo), 'Devolución RECHAZADA');
      }
    });
  }

  transito(): void  { this.transicion(this.rma.transito(this.detalle!.id, this.observacion), 'Paquete EN TRÁNSITO'); }
  recepcion(): void { this.transicion(this.rma.recepcion(this.detalle!.id, this.observacion), 'Paquete RECIBIDO en almacén'); }

  inspeccionar(): void {
    const items = Object.entries(this.inspeccion).map(([id, v]) => ({
      devolucionDetalleId: +id, resultado: v.resultado as string, nota: v.nota
    }));
    if (items.some(i => !i.resultado)) {
      this.snackBar.open('Registra el resultado de TODOS los ítems', 'Cerrar', { duration: 3500 });
      return;
    }
    this.transicion(this.rma.inspeccionar(this.detalle!.id, items),
      'Inspección registrada — stock apto reingresado');
  }

  reembolsar(): void {
    if (!this.metodoReembolso) {
      this.snackBar.open('Selecciona el método del reembolso', 'Cerrar', { duration: 3000 });
      return;
    }
    this.transicion(this.rma.reembolsar(this.detalle!.id, this.metodoReembolso, this.referenciaReembolso),
      'Reembolso procesado');
  }

  cerrar(): void { this.transicion(this.rma.cerrar(this.detalle!.id), 'Devolución CERRADA — ticket resuelto'); }

  verGuiaPdf(): void {
    if (!this.detalle) return;
    this.rma.guiaPdf(this.detalle.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('No se pudo generar el PDF de la guía', 'Cerrar', { duration: 3000 })
    });
  }

  private transicion(peticion: Observable<DevolucionRma>, exito: string): void {
    if (!this.detalle || this.procesando) return;
    this.procesando = true;
    peticion.subscribe({
      next: d => {
        this.procesando = false;
        this.detalle = d;
        d.detalles.forEach(it => this.inspeccion[it.id] ??= { resultado: null, nota: '' });
        this.snackBar.open(exito, 'OK', { duration: 3500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo aplicar la transición'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  chipEstado(estado: string): string {
    if (['aprobada', 'reembolsada', 'cerrada', 'inspeccionada'].includes(estado)) return 'ok';
    if (estado === 'rechazada') return 'error';
    return 'warn';
  }
}
