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
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { Router } from '@angular/router';
import { map, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { VentasService } from '../../../core/services/ventas.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { NavPermissionsService } from '../../../core/navigation/nav-permissions.service';
import { AuthService } from '../../../core/services/auth.service';
import { SelectBuscableComponent, OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  BodegaRef, VarianteRef, CatalogoRef, PedidoVentaRow, PedidoVentaDetalle
} from '../../../core/models/operativo.model';
import { CodigoLegiblePipe } from '../../../core/pipes/etiquetas.pipe';

interface LineaPedido { varianteId: number | null; cantidad: number; }

@Component({
  selector: 'app-pedidos-venta',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule,
    MatSnackBarModule, MatTooltipModule, MatPaginatorModule, SelectBuscableComponent, CodigoLegiblePipe],
  templateUrl: './pedidos-venta.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PedidosVentaComponent implements OnInit {

  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  variantesOpc: OpcionBuscable[] = [];

  /**
   * Buscador de cliente EN SERVIDOR (tope 50 filas por consulta).
   *
   * Antes se descargaban los 50.072 clientes (4,03 MB) al abrir la pantalla y
   * `SelectBuscable` filtraba ese array. Se pasa como función al componente,
   * que la llama con debounce; hay que ligarla con arrow porque el `this` que
   * necesita es el de la pantalla, no el del selector.
   */
  buscarCliente = (q: string) =>
    this.referencias.clientes(q).pipe(
      map(cs => cs.map(c => ({ id: c.id, texto: `${c.nombre} (${c.email})` }))));
  loading = true;

  // Paginación server-side de la tabla de pedidos
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];

  /**
   * Filtros del listado, resueltos EN EL SERVIDOR.
   *
   * Sin ellos esta pantalla era inservible para el trabajo del día: la carga
   * masiva escribió ids explícitos hasta 2.100.119.001, mientras que
   * `pedido_id_seq` sigue por los 4.000 largos, así que un pedido recién
   * creado NO cae en la primera página del `ORDER BY p.id DESC` — cae en la
   * ÚLTIMA, a unas 120.000 páginas de distancia. Buscar por número es la única
   * forma de volver a encontrarlo.
   *
   * El filtrado nunca se hace aquí sobre la página recibida: eso miraría 25
   * filas de 2.999.993 y devolvería «no hay nada» sin dar ningún error.
   */
  q = '';
  filtroEstado = '';
  filtroCanal = '';
  private busqueda$ = new Subject<string>();

  readonly estados = ['pendiente', 'confirmado', 'pagado', 'facturado', 'en_preparacion',
    'preparado', 'despachado', 'entregado', 'devuelto', 'no_entregado', 'cancelado'];
  readonly canales = ['web', 'tienda', 'telefono'];

  get hayFiltros(): boolean {
    return !!(this.q.trim() || this.filtroEstado || this.filtroCanal);
  }

  showForm = false;
  clienteId: number | null = null;
  bodegaId: number | null = null;
  canal = 'tienda';
  lineas: LineaPedido[] = [{ varianteId: null, cantidad: 1 }];

  pedidoCreado: PedidoVentaDetalle | null = null;
  detallePedido: PedidoVentaDetalle | null = null;
  procesando = false;

  nuevaNota = '';
  notaVisibleCliente = false;

  // Cobro del pedido (compuerta: pago -> factura -> despacho -> entrega)
  metodosPago: CatalogoRef[] = [];
  cobrando = false;
  mostrarCobro = false;
  metodoPagoId: number | null = null;
  montoCobro: number | null = null;
  referenciaCobro = '';

  columnas = ['numero', 'cliente', 'origen', 'estado', 'fecha', 'total', 'acciones'];

  constructor(private ventas: VentasService, private referencias: ReferenciasService,
              private nav: NavPermissionsService, private auth: AuthService,
              private router: Router, private snackBar: MatSnackBar) {}

  // La BD no concede bodega a grp_vendedor: no se dispara la petición (403).
  get puedeVerBodegas(): boolean { return this.nav.canDato('refBodegas'); }

  // ── Acciones del proceso según rol (espejan SecurityConfig + GRANTs) ────
  private get rol(): string { return this.auth.getCurrentUser()?.rol ?? ''; }
  get puedeCobrar(): boolean { return ['ADMIN', 'VENDEDOR'].includes(this.rol); }
  get puedeFacturar(): boolean { return ['ADMIN', 'VENDEDOR'].includes(this.rol); }
  get puedeIrADespachos(): boolean { return ['ADMIN', 'GERENTE'].includes(this.rol); }
  get puedeEntregar(): boolean { return this.rol === 'ADMIN'; }

  // ── Estado del pedido seleccionado → qué acción sigue ───────────────────
  // Un pedido ONLINE (canal web) se paga en el checkout de la tienda y nace
  // pagado: nunca se le "registra pago" manualmente (el backend lo refuerza).
  get esOnline(): boolean { return this.detallePedido?.canal === 'web'; }
  get esCobrable(): boolean {
    return !!this.detallePedido && !this.esOnline
      && ['pendiente', 'confirmado'].includes(this.detallePedido.estado);
  }
  // La factura de un pedido ONLINE se emite AUTOMÁTICAMENTE al pagar el
  // checkout (script 39): "Emitir factura" solo aplica a internos pagados.
  get esFacturable(): boolean {
    return !!this.detallePedido && !this.detallePedido.factura
      && !this.esOnline && this.detallePedido.estado === 'pagado';
  }
  // El despacho exige la PREPARACIÓN de bodega (facturado → en_preparacion →
  // preparado); aquí solo se informa en qué eslabón va el pedido.
  get esDespachable(): boolean {
    return !!this.detallePedido && !!this.detallePedido.factura
      && this.detallePedido.estado === 'preparado';
  }
  get enPreparacion(): boolean {
    return !!this.detallePedido
      && ['facturado', 'en_preparacion'].includes(this.detallePedido.estado);
  }
  get esEntregable(): boolean {
    return this.detallePedido?.estado === 'despachado';
  }
  get esDevolvible(): boolean {
    return this.detallePedido?.estado === 'entregado';
  }

  ngOnInit(): void {
    this.cargarPedidos();
    // El texto se consulta con debounce; estado y canal disparan al instante.
    this.busqueda$.pipe(debounceTime(350), distinctUntilChanged())
      .subscribe(() => this.aplicarFiltros());
    if (this.puedeVerBodegas) {
      this.referencias.bodegas().subscribe(b => this.bodegas = b);
    }
    if (this.nav.canDato('refVariantes')) {
      this.referencias.variantes().subscribe(v => {
        this.variantes = v;
        this.variantesOpc = v.map(x =>
          ({ id: x.id, texto: `${x.sku} — ${x.producto} ($${Number(x.precio).toFixed(2)})` }));
      });
    }
    if (this.puedeCobrar && this.nav.canDato('refMetodosPago')) {
      this.referencias.metodosPago().subscribe(m => this.metodosPago = m);
    }
  }

  /**
   * Pide UNA página al servidor.
   *
   * Antes se traía los 2.999.993 pedidos y recortaba en el navegador; eso no
   * era lento, tumbaba el backend con OutOfMemoryError. Ahora el recorte es del
   * servidor y `total` viene del conteo real, así que el paginador sigue
   * sabiendo cuántas páginas hay.
   */
  cargarPedidos(conTotal = true): void {
    this.loading = true;
    this.ventas.pedidos({
      page: this.pagina, size: this.tamPagina, conTotal,
      q: this.q.trim() || undefined,
      estado: this.filtroEstado || undefined,
      canal: this.filtroCanal || undefined
    }).subscribe({
      next: pg => {
        this.pedidosPagina = pg.items;
        // total = -1 significa «no se recontó»: se conserva el que ya había,
        // y con él su carácter de exacto o de mínimo.
        if (pg.total >= 0) {
          this.total = pg.total;
          this.totalEsMinimo = !!pg.totalEsMinimo;
        }
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  /** La página visible, tal cual la devolvió el servidor. */
  pedidosPagina: PedidoVentaRow[] = [];

  /** Conteo del conjunto, no de la página: lo usa el paginador. */
  total = 0;

  /**
   * `total` es un MÍNIMO: contar los 2.999.993 pedidos bajo RLS cuesta 4,3 s
   * porque `esta_en_horario()` se evalúa por fila, así que el servidor corta el
   * conteo en su tope. La pantalla lo DICE («más de N») en vez de dar por
   * exacta una cifra que no lo es.
   */
  totalEsMinimo = false;

  /** Lo que se pinta junto al título. */
  get etiquetaTotal(): string {
    const n = this.total.toLocaleString('es-EC');
    return this.totalEsMinimo ? `más de ${n}` : n;
  }

  /**
   * Cambiar de página NO recuenta: el conjunto es el mismo y el conteo sobre
   * `pedido` cuesta segundos por RLS. El total se recalcula al entrar y tras
   * cualquier acción que cree o cambie un pedido.
   */
  alPaginar(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
    this.cargarPedidos(false);
  }

  /** Cada tecla del buscador: la consulta la lanza el debounce. */
  alBuscar(): void { this.busqueda$.next(this.q); }

  /**
   * Cambiar un filtro cambia el CONJUNTO, así que vuelve a la primera página y
   * SÍ recuenta: el total anterior era el de otro conjunto.
   */
  aplicarFiltros(): void {
    this.pagina = 0;
    this.cargarPedidos();
  }

  limpiarFiltros(): void {
    this.q = '';
    this.filtroEstado = '';
    this.filtroCanal = '';
    this.aplicarFiltros();
  }

  agregarLinea(): void { this.lineas.push({ varianteId: null, cantidad: 1 }); }
  quitarLinea(i: number): void { this.lineas.splice(i, 1); }

  precioDe(varianteId: number | null): number {
    const v = this.variantes.find(x => x.id === varianteId);
    return v ? Number(v.precio) : 0;
  }

  get totalEstimado(): number {
    return this.lineas.reduce((acc, l) =>
      acc + (l.cantidad || 0) * this.precioDe(l.varianteId), 0) * 1.15;
  }

  crearPedido(): void {
    const items = this.lineas
      .filter(l => l.varianteId != null && l.cantidad > 0)
      .map(l => ({ varianteId: l.varianteId!, cantidad: l.cantidad }));
    if (!this.clienteId || !this.bodegaId || !items.length) {
      this.snackBar.open('Cliente, bodega y al menos un producto son requeridos', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.ventas.crearPedido({
      clienteId: this.clienteId, bodegaId: this.bodegaId, canal: this.canal, items
    }).subscribe({
      next: pedido => {
        this.procesando = false;
        this.pedidoCreado = pedido;
        this.snackBar.open(`Pedido ${pedido.numero} creado — total ${pedido.total}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.clienteId = null; this.lineas = [{ varianteId: null, cantidad: 1 }];
        // Se FILTRA al pedido recién creado y se abre su detalle. Sin esto el
        // pedido nace invisible: su id sale de la secuencia (4.341) y el
        // listado ordena por id descendente sobre los 2.999.993 sembrados con
        // ids explícitos de hasta 2.100.119.001, así que aparecería en la
        // última página. Aquí es donde está el botón «Registrar pago».
        this.q = pedido.numero;
        this.filtroEstado = ''; this.filtroCanal = '';
        this.aplicarFiltros();
        this.verPedido(pedido.id);
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo crear el pedido'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  verPedido(id: number): void {
    this.ventas.pedido(id).subscribe({
      next: p => {
        this.detallePedido = p;
        this.nuevaNota = '';
        this.notaVisibleCliente = false;
        this.mostrarCobro = false;
        this.metodoPagoId = null;
        this.montoCobro = p.saldo_pendiente != null ? Number(p.saldo_pendiente) : null;
        this.referenciaCobro = '';
      },
      error: () => this.snackBar.open('No se pudo cargar el pedido', 'Cerrar', { duration: 3000 })
    });
  }

  // ── Acciones encadenadas del proceso ─────────────────────────────────────

  cobrar(): void {
    if (!this.detallePedido || !this.metodoPagoId) {
      this.snackBar.open('Selecciona el método de pago', 'Cerrar', { duration: 3000 });
      return;
    }
    this.cobrando = true;
    this.ventas.registrarPago(this.detallePedido.id, {
      metodoPagoId: this.metodoPagoId, monto: this.montoCobro,
      referencia: this.referenciaCobro
    }).subscribe({
      next: r => {
        this.cobrando = false;
        this.snackBar.open(r.saldoPendiente > 0
          ? `Abono registrado — saldo pendiente ${r.saldoPendiente}`
          : 'Pago registrado — pedido PAGADO', 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.verPedido(this.detallePedido!.id);
        this.cargarPedidos();
      },
      error: e => {
        this.cobrando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar el pago'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  facturar(): void {
    if (!this.detallePedido) return;
    this.procesando = true;
    this.ventas.emitirFactura(this.detallePedido.id).subscribe({
      next: f => {
        this.procesando = false;
        this.snackBar.open(`Factura ${f.numero} emitida`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.verPedido(this.detallePedido!.id);
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo emitir la factura'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  entregar(): void {
    if (!this.detallePedido) return;
    this.procesando = true;
    this.ventas.entregar(this.detallePedido.id).subscribe({
      next: p => {
        this.procesando = false;
        this.detallePedido = p;
        this.snackBar.open(`Pedido ${p.numero} marcado como ENTREGADO`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.cargarPedidos();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar la entrega'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  verFacturaPdf(): void {
    if (!this.detallePedido?.factura) return;
    this.ventas.facturaPdf(this.detallePedido.factura.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }

  irADespachos(): void { this.router.navigate(['/operativo/ventas/despachos']); }
  irADevoluciones(): void { this.router.navigate(['/operativo/ventas/devoluciones']); }

  agregarNota(): void {
    if (!this.detallePedido || !this.nuevaNota.trim()) return;
    this.ventas.crearNota(this.detallePedido.id, this.nuevaNota, this.notaVisibleCliente).subscribe({
      next: () => {
        this.snackBar.open('Nota agregada', 'OK', { duration: 2000, panelClass: ['snack-success'] });
        this.verPedido(this.detallePedido!.id);
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo agregar la nota'),
        'Cerrar', { duration: 4000 })
    });
  }
}
