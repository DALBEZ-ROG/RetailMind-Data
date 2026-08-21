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
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { PaginaServidor } from '../../../core/services/pagina-servidor.util';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of, map } from 'rxjs';
import { SoporteService } from '../../../core/services/soporte.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  TicketRow, TicketDetalle, MensajeTicketRow, CategoriaTicketRef,
  UsuarioSoporteRef, PedidoSoporteRef, ProductoTicketRef
} from '../../../core/models/operativo.model';
import { SelectBuscableComponent } from '../../../core/components/select-buscable/select-buscable.component';
import { CodigoLegiblePipe } from '../../../core/pipes/etiquetas.pipe';

import { CampoTextoDirective } from '../../../core/validacion';

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
    MatPaginatorModule, SelectBuscableComponent, CodigoLegiblePipe,
    CampoTextoDirective
  ],
  templateUrl: './tickets.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class TicketsComponent implements OnInit {

  loading = true;

  /**
   * ¿Hay algún filtro puesto? Decide el mensaje del estado vacío. Antes se
   * miraba `tickets.length`, que era el listado COMPLETO en memoria; con la
   * página del servidor ese dato ya no existe y usarlo diría «no hay tickets»
   * cuando lo que pasa es que el filtro no casa con ninguno.
   */
  get hayFiltro(): boolean {
    return this.filtroBandeja !== 'todos' || !!this.filtroEstado
        || !!this.filtroCategoria || !!this.filtroPrioridad;
  }

  showForm = false;
  form = this.formVacio();

  detalle: TicketDetalle | null = null;
  nuevoMensaje = '';
  esInterno = false;
  estadoSel = '';
  asignarSel: number | null = null;
  prioridadSel = '';

  categoriasRef: CategoriaTicketRef[] = [];
  usuariosRef: UsuarioSoporteRef[] = [];

  /**
   * Buscador de cliente EN SERVIDOR (tope 50 filas por consulta).
   *
   * Esta pantalla se descargaba los 50.072 clientes (4,03 MB) y los pintaba
   * como 50.072 `<mat-option>` dentro de un `mat-select`. Ahora es el mismo
   * `app-select-buscable` de pedidos, en modo servidor.
   */
  buscarCliente = (q: string) =>
    this.referencias.clientes(q).pipe(
      map(cs => cs.map(c => ({ id: c.id, texto: `${c.nombre} (${c.email})` }))));

  /** El selector devuelve el id elegido; recargar sus pedidos es lo de antes. */
  clienteElegido(id: number | null): void {
    this.form.clienteId = id;
    this.clienteCambiado();
  }
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
   * La página que devuelve el SERVIDOR.
   *
   * Antes llegaban los 179.851 tickets (78,98 MB) y `aplicarFiltros()`
   * recorría ese array con los cuatro criterios. **Los cuatro se movieron a
   * SQL**: sobre una página de 25, «cerrado» —que el orden por urgencia manda
   * al final de 179.851 filas— habría devuelto SIEMPRE cero sin dar un error, y
   * «sin asignar» habría dependido de qué cayera en la página visible.
   */
  readonly pag = new PaginaServidor<TicketRow>();

  /**
   * Opciones del selector de categoría.
   *
   * Salían de recorrer la bandeja entera (`[...new Set(data.map(...))]`), que
   * era el otro uso oculto del listado completo. Ahora salen de
   * `categorias-ref`, que es el catálogo de verdad y ya se pedía para el
   * formulario de alta: no depende de qué tickets estén cargados.
   */
  get categoriasEnBandeja(): string[] { return this._categorias; }
  private _categorias: string[] = [];

  /** Cambiar cualquier filtro vuelve a la primera página y RECUENTA. */
  aplicarFiltros(): void {
    this.pag.reiniciar();
    this.cargar();
  }

  ngOnInit(): void {
    this.cargar();
    this.soporte.categoriasRef().subscribe({
      next: c => {
        this.categoriasRef = c;
        this._categorias = c.map(x => x.nombre).sort();
      },
      error: () => {}
    });
    if (this.esGestion) {
      // Los clientes ya NO se descargan aquí: los busca `app-select-buscable`
      // contra el servidor cuando se escribe (antes eran 50.072 filas).
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

  /**
   * @param conTotal false al cambiar de página: el conjunto filtrado no cambió
   *                 y el conteo cuesta ~0,6 s sobre 179.851 tickets bajo RLS.
   */
  cargar(conTotal = true): void {
    this.loading = true;
    this.soporte.tickets({
      page: this.pag.pagina, size: this.pag.tam, conTotal,
      bandeja: this.esCliente ? undefined : this.filtroBandeja,
      estado: this.filtroEstado || undefined,
      categoria: this.filtroCategoria || undefined,
      prioridad: this.filtroPrioridad || undefined
    }).subscribe({
      next: pg => {
        this.pag.aplicar(pg);
        this.loading = false;
        // Cerrar el último ticket de la página la deja vacía: retrocede.
        if (this.pag.ajustarTrasBorrado()) { this.cargar(conTotal); }
      },
      error: () => this.loading = false
    });
  }

  /** Cambiar de página NO recuenta: el conjunto filtrado es el mismo. */
  alPaginar(e: PageEvent): void {
    this.pag.alPaginar(e);
    this.cargar(false);
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
