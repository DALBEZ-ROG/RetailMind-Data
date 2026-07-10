import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SoporteService } from '../../../core/services/soporte.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  TicketRow, TicketDetalle, MensajeTicketRow, CategoriaTicketRef,
  UsuarioSoporteRef, PedidoSoporteRef, ClienteRef
} from '../../../core/models/operativo.model';

/** Espeja las transiciones del backend (SoporteService.TRANSICIONES). */
const TRANSICIONES: Record<string, string[]> = {
  abierto: ['en_proceso', 'cerrado'],
  en_proceso: ['esperando_cliente', 'resuelto', 'cerrado'],
  esperando_cliente: ['en_proceso', 'resuelto', 'cerrado'],
  resuelto: ['en_proceso', 'cerrado'],
  cerrado: []
};

@Component({
  selector: 'app-tickets',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule,
    MatSnackBarModule, MatTooltipModule],
  templateUrl: './tickets.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class TicketsComponent implements OnInit {

  tickets: TicketRow[] = [];
  loading = true;

  showForm = false;
  form = this.formVacio();

  detalle: TicketDetalle | null = null;
  nuevoMensaje = '';
  esInterno = false;
  estadoSel = '';
  asignarSel: number | null = null;

  categoriasRef: CategoriaTicketRef[] = [];
  clientesRef: ClienteRef[] = [];
  usuariosRef: UsuarioSoporteRef[] = [];
  pedidosRef: PedidoSoporteRef[] = [];

  prioridades = ['baja', 'media', 'alta', 'urgente'];

  constructor(private soporte: SoporteService, private referencias: ReferenciasService,
              private auth: AuthService, private snackBar: MatSnackBar) {}

  get esCliente(): boolean { return this.auth.hasRole('CLIENTE'); }
  get esGestion(): boolean { return this.auth.hasRole('ADMIN') || this.auth.hasRole('GERENTE'); }

  get columnas(): string[] {
    return this.esCliente
      ? ['numero', 'categoria', 'prioridad', 'estado', 'mensajes', 'fecha', 'acciones']
      : ['numero', 'cliente', 'categoria', 'prioridad', 'estado', 'asignado', 'mensajes', 'fecha', 'acciones'];
  }

  /** Estados a los que puede pasar el ticket abierto en el detalle. */
  get estadosSiguientes(): string[] {
    return this.detalle ? (TRANSICIONES[this.detalle.estado] || []) : [];
  }

  ngOnInit(): void {
    this.cargar();
    this.soporte.categoriasRef().subscribe({ next: c => this.categoriasRef = c, error: () => {} });
    if (this.esGestion) {
      this.referencias.clientes().subscribe({ next: c => this.clientesRef = c, error: () => {} });
      this.soporte.usuariosRef().subscribe({ next: u => this.usuariosRef = u, error: () => {} });
    }
  }

  private formVacio() {
    return { clienteId: null as number | null, categoriaId: null as number | null,
             pedidoId: null as number | null, asunto: '', descripcion: '', prioridad: 'media' };
  }

  cargar(): void {
    this.loading = true;
    this.soporte.tickets().subscribe({
      next: data => { this.tickets = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.form = this.formVacio();
    this.showForm = true;
    this.pedidosRef = [];
    // El cliente elige entre SUS pedidos; el personal espera a elegir cliente
    if (this.esCliente) {
      this.soporte.pedidosRef().subscribe({ next: p => this.pedidosRef = p, error: () => {} });
    }
  }

  /** El personal cambió el cliente del ticket: recargar sus pedidos. */
  clienteCambiado(): void {
    this.form.pedidoId = null;
    this.pedidosRef = [];
    if (this.form.clienteId) {
      this.soporte.pedidosRef(this.form.clienteId).subscribe({
        next: p => this.pedidosRef = p, error: () => {}
      });
    }
  }

  guardar(): void {
    if (!this.form.asunto.trim()) {
      this.snackBar.open('El asunto es requerido', 'Cerrar', { duration: 3000 });
      return;
    }
    if (this.esGestion && !this.form.clienteId) {
      this.snackBar.open('Selecciona el cliente del ticket', 'Cerrar', { duration: 3000 });
      return;
    }
    this.soporte.crearTicket(this.form).subscribe({
      next: r => {
        this.snackBar.open(`Ticket ${r.numero} creado`, 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
        this.ver(r.id);
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear el ticket'),
        'Cerrar', { duration: 4000 })
    });
  }

  ver(id: number): void {
    this.soporte.ticket(id).subscribe({
      next: t => {
        this.detalle = t;
        this.estadoSel = '';
        this.asignarSel = t.asignado_usuario_id ?? null;
        this.nuevoMensaje = '';
        this.esInterno = false;
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar el ticket'),
        'Cerrar', { duration: 3000 })
    });
  }

  esPropio(m: MensajeTicketRow): boolean {
    return this.esCliente ? m.de_cliente : !m.de_cliente;
  }

  responder(): void {
    if (!this.detalle || !this.nuevoMensaje.trim()) return;
    this.soporte.responder(this.detalle.id, this.nuevoMensaje, this.esInterno).subscribe({
      next: () => {
        this.nuevoMensaje = '';
        this.esInterno = false;
        this.ver(this.detalle!.id);
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo enviar el mensaje'),
        'Cerrar', { duration: 4000 })
    });
  }

  cambiarEstado(): void {
    if (!this.detalle || !this.estadoSel) return;
    this.soporte.cambiarEstado(this.detalle.id, this.estadoSel).subscribe({
      next: () => {
        this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 });
        this.ver(this.detalle!.id);
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cambiar el estado'),
        'Cerrar', { duration: 4000 })
    });
  }

  asignar(): void {
    if (!this.detalle) return;
    this.soporte.asignar(this.detalle.id, this.asignarSel).subscribe({
      next: () => {
        this.snackBar.open(this.asignarSel ? 'Agente asignado' : 'Asignación retirada',
          'OK', { duration: 2000 });
        this.ver(this.detalle!.id);
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo asignar el agente'),
        'Cerrar', { duration: 4000 })
    });
  }

  claseEstado(estado: string): string {
    if (estado === 'cerrado') return 'error';
    if (estado === 'resuelto') return 'ok';
    return '';
  }
}
