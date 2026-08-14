import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatPaginatorModule } from '@angular/material/paginator';
import { PaginaLocal } from '../../../core/services/pagina-local.util';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of } from 'rxjs';
import { SoporteService } from '../../../core/services/soporte.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  TicketRow, TicketDetalle, MensajeTicketRow, CategoriaTicketRef,
  UsuarioSoporteRef, PedidoSoporteRef, ProductoTicketRef, ClienteRef
} from '../../../core/models/operativo.model';
import { CodigoLegiblePipe } from '../../../core/pipes/etiquetas.pipe';

/** Espeja las transiciones del backend (SoporteService.TRANSICIONES). */
const TRANSICIONES: Record<string, string[]> = {
  abierto: ['en_proceso', 'cerrado'],
  en_proceso: ['esperando_cliente', 'resuelto', 'cerrado'],
  esperando_cliente: ['en_proceso', 'resuelto', 'cerrado'],
  resuelto: ['en_proceso', 'cerrado'],
  cerrado: []
};

/**
 * Tickets de soporte.
 *  - CLIENTE: crea (categoría + descripción, SIN prioridad: es automática),
 *    ve los suyos (RLS) y conversa con "Equipo de soporte".
 *  - SOPORTE (9º rol): bandeja con SLA y filtros; toma tickets, responde,
 *    cambia estado y prioridad.
 *  - ADMIN/GERENTE: además crean en nombre del cliente y asignan agentes.
 */
@Component({
  selector: 'app-tickets',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatAutocompleteModule,
    MatCheckboxModule, MatSnackBarModule, MatTooltipModule, MatButtonToggleModule,
    MatPaginatorModule, CodigoLegiblePipe],
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
  prioridadSel = '';

  categoriasRef: CategoriaTicketRef[] = [];
  clientesRef: ClienteRef[] = [];
  usuariosRef: UsuarioSoporteRef[] = [];
  pedidosRef: PedidoSoporteRef[] = [];

  // Buscador del producto del reclamo (script 50): el catálogo tiene ~1.221
  // variantes, así que se busca en servidor con debounce (nunca lista completa)
  productosRef: ProductoTicketRef[] = [];
  productoBusqueda: string | ProductoTicketRef = '';
  private buscarProducto$ = new Subject<string>();

  prioridades = ['baja', 'media', 'alta', 'urgente'];
  estadosFiltro = ['abierto', 'en_proceso', 'esperando_cliente', 'resuelto', 'cerrado'];

  // Filtros de la bandeja (solo personal)
  filtroBandeja: 'todos' | 'sin_asignar' | 'mios' = 'todos';
  filtroEstado = '';
  filtroCategoria = '';
  filtroPrioridad = '';

  constructor(private soporte: SoporteService, private referencias: ReferenciasService,
              private auth: AuthService, private snackBar: MatSnackBar) {}

  get esCliente(): boolean { return this.auth.hasRole('CLIENTE'); }
  get esSoporte(): boolean { return this.auth.hasRole('SOPORTE'); }
  get esGestion(): boolean { return this.auth.hasRole('ADMIN') || this.auth.hasRole('GERENTE'); }
  /** Puede atender: responder, cambiar estado. */
  get esStaff(): boolean { return this.esGestion || this.esSoporte; }
  /** Tomar ticket / cambiar prioridad: agente de soporte y admin. */
  get puedeTomar(): boolean { return this.esSoporte || this.auth.hasRole('ADMIN'); }
  /** Asignar a terceros: gestión (el agente se auto-asigna con "tomar"). */
  get puedeAsignar(): boolean { return this.esGestion; }

  // El ROL no cambia durante la sesión: las columnas se resuelven una vez. Como
  // getter devolvía un array nuevo por ciclo de detección de cambios (§8.6).
  private _columnas?: string[];
  get columnas(): string[] {
    return this._columnas ??= this.esCliente
      ? ['numero', 'categoria', 'estado', 'mensajes', 'fecha', 'acciones']
      : ['numero', 'cliente', 'categoria', 'prioridad', 'sla', 'estado', 'asignado', 'acciones'];
  }

  /** Estados a los que puede pasar el ticket abierto en el detalle. */
  get estadosSiguientes(): string[] {
    return this.detalle ? (TRANSICIONES[this.detalle.estado] || []) : [];
  }

  /**
   * Bandeja con los filtros aplicados (client-side sobre la lista cargada).
   *
   * Es un CAMPO y se recalcula en `aplicarFiltros()`. Como getter recorría los
   * 179.851 tickets en CADA ciclo de detección de cambios y devolvía un array
   * nuevo, así que `MatTable` rehacía la tabla entera una y otra vez: es la
   * trampa §8.6 de `PATRON_UI.md` sobre el listado más grande del sistema.
   */
  ticketsFiltrados: TicketRow[] = [];

  /** La página que se pinta; el resto de la bandeja no llega al DOM. */
  readonly pag = new PaginaLocal<TicketRow>();

  /** Categorías presentes, recalculadas al cargar y no por ciclo. */
  categoriasEnBandeja: string[] = [];

  aplicarFiltros(): void {
    this.ticketsFiltrados = this.tickets.filter(t =>
      (this.filtroBandeja === 'todos'
        || (this.filtroBandeja === 'sin_asignar' && !t.asignado_usuario_id)
        || (this.filtroBandeja === 'mios' && t.asignado_a_mi))
      && (!this.filtroEstado || t.estado === this.filtroEstado)
      && (!this.filtroCategoria || t.categoria === this.filtroCategoria)
      && (!this.filtroPrioridad || t.prioridad === this.filtroPrioridad));
    this.pag.fijar(this.ticketsFiltrados);
  }

  ngOnInit(): void {
    this.cargar();
    this.soporte.categoriasRef().subscribe({ next: c => this.categoriasRef = c, error: () => {} });
    if (this.esGestion) {
      this.referencias.clientes().subscribe({ next: c => this.clientesRef = c, error: () => {} });
      this.soporte.usuariosRef().subscribe({ next: u => this.usuariosRef = u, error: () => {} });
    }
    this.buscarProducto$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => q.trim().length >= 2 ? this.soporte.productosRef(q.trim()) : of([]))
    ).subscribe({ next: p => this.productosRef = p, error: () => this.productosRef = [] });
  }

  private formVacio() {
    // Sin prioridad: la asigna el backend según la categoría elegida
    return { clienteId: null as number | null, categoriaId: null as number | null,
             pedidoId: null as number | null,
             productoVarianteId: null as number | null, asunto: '', descripcion: '' };
  }

  cargar(): void {
    this.loading = true;
    this.soporte.tickets().subscribe({
      next: data => {
        this.tickets = data;
        this.categoriasEnBandeja =
          [...new Set(data.map(t => t.categoria).filter((c): c is string => !!c))].sort();
        this.aplicarFiltros();
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.form = this.formVacio();
    this.showForm = true;
    this.pedidosRef = [];
    this.productosRef = [];
    this.productoBusqueda = '';
    // El cliente elige entre SUS pedidos; el personal espera a elegir cliente
    if (this.esCliente) {
      this.soporte.pedidosRef().subscribe({ next: p => this.pedidosRef = p, error: () => {} });
    }
  }

  // ── Buscador del producto del reclamo (script 50) ────────────────────

  buscarProducto(texto: string | ProductoTicketRef): void {
    // Mientras se escribe llega texto; al elegir una opción llega el objeto
    if (typeof texto === 'string') {
      this.form.productoVarianteId = null;
      this.buscarProducto$.next(texto);
    }
  }

  productoElegido(p: ProductoTicketRef): void {
    this.form.productoVarianteId = p.id;
  }

  limpiarProducto(): void {
    this.form.productoVarianteId = null;
    this.productoBusqueda = '';
    this.productosRef = [];
  }

  /** Cómo se muestra la opción elegida en el input del autocomplete. */
  nombreProducto = (p: ProductoTicketRef | string | null): string =>
    typeof p === 'object' && p ? `${p.nombre} (${p.sku})` : (p || '');

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
        this.snackBar.open(`Ticket ${r.numero} creado (prioridad ${(r as any).prioridad})`,
          'OK', { duration: 3000, panelClass: ['snack-success'] });
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
        this.prioridadSel = t.prioridad;
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

  tomar(id: number): void {
    this.soporte.tomar(id).subscribe({
      next: r => {
        this.snackBar.open('Ticket tomado: quedó asignado a ti (' + r.estado + ')',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        if (this.detalle?.id === id) this.ver(id);
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo tomar el ticket'),
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

  cambiarPrioridad(): void {
    if (!this.detalle || !this.prioridadSel || this.prioridadSel === this.detalle.prioridad) return;
    this.soporte.cambiarPrioridad(this.detalle.id, this.prioridadSel).subscribe({
      next: () => {
        this.snackBar.open('Prioridad actualizada a ' + this.prioridadSel, 'OK', { duration: 2000 });
        this.ver(this.detalle!.id);
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cambiar la prioridad'),
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

  /**
   * Texto del SLA: "vence en 3 h" / "vence en 25 min" / "VENCIDO" / "—".
   * Lee ticket_soporte.fecha_limite (columna persistida, script 49), ya no un
   * cálculo al vuelo del cliente.
   */
  slaTexto(t: { fecha_limite?: string; sla_vencido?: boolean; estado: string }): string {
    if (['resuelto', 'cerrado'].includes(t.estado) || !t.fecha_limite) return '—';
    if (t.sla_vencido) return 'VENCIDO';
    const min = Math.round((new Date(t.fecha_limite).getTime() - Date.now()) / 60000);
    if (min >= 60) {
      const h = Math.floor(min / 60);
      return h >= 48 ? `vence en ${Math.floor(h / 24)} d` : `vence en ${h} h`;
    }
    return `vence en ${Math.max(min, 1)} min`;
  }
}
