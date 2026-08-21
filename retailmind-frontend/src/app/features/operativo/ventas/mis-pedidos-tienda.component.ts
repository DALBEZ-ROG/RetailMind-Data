import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { VentasService } from '../../../core/services/ventas.service';
import { DevolucionesService } from '../../../core/services/devoluciones.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  CatalogoRef, DevolucionRow, DevolucionRma, ElegibilidadDevolucion,
  NovedadEnvioRow, PedidoVentaRow, PedidoVentaDetalle, SeguimientoRow
} from '../../../core/models/operativo.model';
import { CodigoLegiblePipe } from '../../../core/pipes/etiquetas.pipe';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

/**
 * CU-O-20: MIS PEDIDOS (rol CLIENTE). RLS (app.cliente_id) devuelve solo lo
 * suyo. Además del seguimiento, aquí NACE la devolución RMA: en un pedido
 * entregado (30 días de plazo) el cliente elige ítems + motivo; la solicitud
 * crea un ticket de soporte y el cliente sigue el estado y descarga la guía
 * de retorno (PDF) cuando soporte aprueba.
 */
@Component({
  selector: 'app-mis-pedidos-tienda',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, MatTableModule, MatIconModule,
    MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSnackBarModule, MatTooltipModule, MatPaginatorModule, CodigoLegiblePipe,
    CampoNumeroDirective, CampoTextoDirective
  ],
  templateUrl: './mis-pedidos-tienda.component.html',
  styleUrls: ['../operativo-shared.scss', '../../shop/shop-shared.scss',
              './mis-pedidos-tienda.component.scss']
})
export class MisPedidosTiendaComponent implements OnInit, OnDestroy {

  pedidos: PedidoVentaRow[] = [];
  total = 0;
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];

  // ── Búsqueda y filtro, resueltos EN SERVIDOR ─────────────────────────────
  // `q` casa contra `pedido.numero` y `estado` contra la lista blanca del
  // backend. Se busca en servidor y no sobre la página cargada porque el
  // cliente con más pedidos tiene 748 y el tope por página son 200: filtrar
  // aquí encontraría el pedido solo si tuvo la suerte de estar en la página.
  busqueda = '';
  estadoFiltro = '';
  private readonly busqueda$ = new Subject<string>();
  private readonly subs = new Subscription();

  /** Los 11 estados del ciclo, en el orden en que ocurren. */
  readonly estados = [
    { codigo: 'pendiente', nombre: 'Pendiente' },
    { codigo: 'confirmado', nombre: 'Confirmado' },
    { codigo: 'pagado', nombre: 'Pagado' },
    { codigo: 'facturado', nombre: 'Facturado' },
    { codigo: 'en_preparacion', nombre: 'En preparación' },
    { codigo: 'preparado', nombre: 'Preparado' },
    { codigo: 'despachado', nombre: 'Despachado' },
    { codigo: 'entregado', nombre: 'Entregado' },
    { codigo: 'devuelto', nombre: 'Devuelto' },
    { codigo: 'no_entregado', nombre: 'No entregado' },
    { codigo: 'cancelado', nombre: 'Cancelado' }
  ];

  alPaginar(e: PageEvent): void {
    this.aplicar({ page: e.pageIndex || null, size: e.pageSize });
  }
  detalle: PedidoVentaDetalle | null = null;
  seguimiento: SeguimientoRow[] = [];
  novedades: NovedadEnvioRow[] = [];
  loading = true;

  // ── Devolución RMA del cliente ──
  devoluciones: DevolucionRow[] = [];
  devolucion: DevolucionRma | null = null;
  elegibilidad: ElegibilidadDevolucion | null = null;
  motivos: CatalogoRef[] = [];
  motivoCodigo: string | null = null;
  descripcionDevolucion = '';
  cantidades: Record<number, number> = {};
  solicitando = false;

  columnas = ['numero', 'fecha', 'estado', 'total', 'acciones'];
  columnasDevolucion = ['numero', 'pedido', 'motivo', 'estado', 'monto', 'acciones'];

  constructor(private ventas: VentasService, private rma: DevolucionesService,
              private snackBar: MatSnackBar,
              private router: Router, private route: ActivatedRoute) {}

  ngOnInit(): void {
    // El estado de la búsqueda vive en la URL. Eso es lo que permite que la
    // confirmación del checkout enlace aquí con `?q=PED-…` y el cliente caiga
    // directo en el pedido que acaba de hacer, en vez de buscarlo entre diez
    // años de historial.
    this.subs.add(this.route.queryParamMap.subscribe(p => {
      this.busqueda = p.get('q') || '';
      this.estadoFiltro = p.get('estado') || '';
      this.pagina = p.get('page') ? Number(p.get('page')) : 0;
      this.tamPagina = p.get('size') ? Number(p.get('size')) : 25;
      this.cargar();
    }));

    this.subs.add(this.busqueda$.pipe(debounceTime(400), distinctUntilChanged())
      .subscribe(q => this.aplicar({ q: q || null, page: null })));

    this.cargarDevoluciones();
  }

  ngOnDestroy(): void {
    this.busqueda$.complete();
    this.subs.unsubscribe();
  }

  /** Fusiona parámetros en la URL; `null` borra el parámetro. */
  private aplicar(cambios: Record<string, any>): void {
    this.router.navigate([], {
      relativeTo: this.route, queryParams: cambios, queryParamsHandling: 'merge'
    });
  }

  onBusquedaCambia(): void { this.busqueda$.next(this.busqueda.trim()); }
  filtrarEstado(): void { this.aplicar({ estado: this.estadoFiltro || null, page: null }); }
  quitarBusqueda(): void { this.aplicar({ q: null, page: null }); }
  quitarEstado(): void { this.aplicar({ estado: null, page: null }); }

  limpiarFiltros(): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: {} });
  }

  get hayFiltros(): boolean { return !!(this.busqueda || this.estadoFiltro); }

  get nombreEstado(): string {
    return this.estados.find(e => e.codigo === this.estadoFiltro)?.nombre || this.estadoFiltro;
  }

  get rangoDesde(): number { return this.total === 0 ? 0 : this.pagina * this.tamPagina + 1; }
  get rangoHasta(): number { return Math.min((this.pagina + 1) * this.tamPagina, this.total); }

  cargar(): void {
    this.loading = true;
    // El endpoint pagina en servidor y ordena por FECHA descendente (el `id`
    // no sirve: la carga masiva usó bandas de ids reservadas, ver
    // `VentasService.listarPedidos`).
    this.ventas.pedidos({
      page: this.pagina, size: this.tamPagina,
      q: this.busqueda.trim() || undefined,
      estado: this.estadoFiltro || undefined
    }).subscribe({
      next: pg => { this.pedidos = pg.items; this.total = pg.total; this.loading = false; },
      error: e => {
        this.loading = false;
        this.pedidos = [];
        this.snackBar.open(mensajeError(e, 'No se pudieron cargar tus pedidos'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  /** Icono y tono del estado, para pintarlo igual en toda la pantalla. */
  iconoEstado(estado: string): string {
    switch (estado) {
      case 'entregado': return 'task_alt';
      case 'despachado': return 'local_shipping';
      case 'preparado':
      case 'en_preparacion': return 'inventory_2';
      case 'facturado': return 'receipt_long';
      case 'pagado': return 'paid';
      case 'devuelto': return 'assignment_return';
      case 'cancelado':
      case 'no_entregado': return 'cancel';
      default: return 'schedule';
    }
  }

  tonoEstado(estado: string): 'ok' | 'warn' | 'error' | 'info' {
    if (['entregado', 'facturado', 'pagado'].includes(estado)) return 'ok';
    if (['cancelado', 'no_entregado'].includes(estado)) return 'error';
    if (['devuelto'].includes(estado)) return 'warn';
    return 'info';
  }

  /**
   * Devoluciones del cliente. El endpoint pasó a devolver el sobre paginado
   * (antes mandaba las 145.734 filas de la tabla; a un cliente RLS solo le
   * llegaban las suyas, pero la firma cambió igual). Se piden las 200 del tope,
   * y el rótulo muestra `totalDevoluciones` —el conteo REAL del servidor— para
   * que un recorte se vea en pantalla en vez de mentir por omisión: hoy el
   * cliente con más devoluciones tiene 58.
   */
  totalDevoluciones = 0;

  cargarDevoluciones(): void {
    this.rma.listar({ size: 200 }).subscribe(pg => {
      this.devoluciones = pg.items;
      this.totalDevoluciones = pg.total;
    });
  }

  verPedido(id: number): void {
    this.elegibilidad = null;
    this.ventas.pedido(id).subscribe({
      next: p => {
        this.detalle = p;
        this.seguimiento = [];
        this.novedades = [];
        // Seguimiento y novedades del envío del pedido (RLS: solo el suyo)
        if (p.envio) {
          this.ventas.seguimiento(p.envio.id).subscribe(s => this.seguimiento = s);
          this.ventas.novedadesPedido(p.id).subscribe(info => this.novedades = info.novedades);
        }
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar el pedido'), 'Cerrar', { duration: 4000 })
    });
  }

  // ── Novedades de envío (script 44): mensaje amable para el cliente ──

  private static readonly MOTIVOS_NOVEDAD: Record<string, string> = {
    cliente_ausente: 'no encontramos a nadie en la dirección',
    direccion_incorrecta: 'la dirección registrada es incorrecta',
    cliente_rechazo: 'el paquete fue rechazado en la entrega',
    zona_dificil_acceso: 'la zona de entrega es de difícil acceso',
    dano_en_transito: 'el paquete sufrió un daño en el trayecto'
  };

  mensajeNovedad(n: NovedadEnvioRow): string {
    const motivo = MisPedidosTiendaComponent.MOTIVOS_NOVEDAD[n.tipo] ?? n.tipo;
    if (n.estado === 'abierta') {
      return `Tu pedido no pudo entregarse: ${motivo}. Estamos gestionando la entrega.`;
    }
    if (n.accion === 'reprogramada') {
      return `Tu pedido no pudo entregarse (${motivo}). Se reprogramó un nuevo intento de entrega.`;
    }
    return `Tu pedido no pudo entregarse (${motivo}) y fue devuelto al almacén. `
      + 'Contáctanos por soporte para gestionar tu caso.';
  }

  /** PDF de la factura del pedido (el RLS de la BD lo limita a SUS facturas). */
  verFacturaPdf(): void {
    if (!this.detalle?.factura) return;
    this.ventas.facturaPdf(this.detalle.factura.id).subscribe({
      next: blob => this.abrirBlob(blob),
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }

  // ── Solicitar devolución (nace aquí el RMA) ──────────────────────────

  /** El botón aparece en estados devolvibles; el backend confirma plazo/cupos. */
  get puedeDevolver(): boolean {
    return !!this.detalle && ['entregado', 'devuelto', 'despachado'].includes(this.detalle.estado);
  }

  /** Reseñar exige compra: pedido pagado en adelante (espeja ESTADOS_COMPRA del backend). */
  get puedeResenar(): boolean {
    return !!this.detalle && ['pagado', 'facturado', 'en_preparacion', 'preparado',
      'despachado', 'entregado', 'devuelto'].includes(this.detalle.estado);
  }

  prepararDevolucion(): void {
    if (!this.detalle) return;
    this.rma.elegibilidad(this.detalle.id).subscribe({
      next: e => {
        this.elegibilidad = e;
        this.cantidades = {};
        e.items.forEach(it => this.cantidades[it.pedido_detalle_id] = 0);
        this.motivoCodigo = null;
        this.descripcionDevolucion = '';
        if (!this.motivos.length) {
          this.rma.motivos().subscribe(m => this.motivos = m);
        }
        if (!e.elegible) {
          this.snackBar.open('Este pedido ya no es elegible para devolución', 'Cerrar', { duration: 4000 });
        }
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo evaluar la devolución'), 'Cerrar', { duration: 4000 })
    });
  }

  solicitarDevolucion(): void {
    if (!this.elegibilidad || this.solicitando) return;
    if (!this.motivoCodigo) {
      this.snackBar.open('Selecciona el motivo de la devolución', 'Cerrar', { duration: 3000 });
      return;
    }
    const items = this.elegibilidad.items
      .filter(it => (this.cantidades[it.pedido_detalle_id] || 0) > 0)
      .map(it => ({ pedidoDetalleId: it.pedido_detalle_id,
                    cantidad: this.cantidades[it.pedido_detalle_id] }));
    if (!items.length) {
      this.snackBar.open('Indica al menos una cantidad a devolver', 'Cerrar', { duration: 3000 });
      return;
    }
    this.solicitando = true;
    this.rma.solicitar({
      pedidoId: this.elegibilidad.pedido_id, motivoCodigo: this.motivoCodigo,
      descripcion: this.descripcionDevolucion, items
    }).subscribe({
      next: d => {
        this.solicitando = false;
        this.elegibilidad = null;
        this.devolucion = d;
        this.snackBar.open(`Devolución ${d.numero} solicitada — soporte la revisará (ticket ${d.ticket_numero})`,
          'OK', { duration: 5000, panelClass: ['snack-success'] });
        this.cargarDevoluciones();
      },
      error: e => {
        this.solicitando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo solicitar la devolución'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  verDevolucion(id: number): void {
    this.rma.detalle(id).subscribe({
      next: d => this.devolucion = d,
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar la devolución'), 'Cerrar', { duration: 4000 })
    });
  }

  /** Guía de retorno en PDF (existe cuando soporte aprueba). */
  verGuiaPdf(): void {
    if (!this.devolucion) return;
    this.rma.guiaPdf(this.devolucion.id).subscribe({
      next: blob => this.abrirBlob(blob),
      error: () => this.snackBar.open('La guía se genera cuando soporte aprueba la devolución', 'Cerrar', { duration: 4000 })
    });
  }

  chipDevolucion(estado: string): string {
    if (['aprobada', 'reembolsada', 'cerrada', 'inspeccionada'].includes(estado)) return 'ok';
    if (estado === 'rechazada') return 'error';
    return 'warn';
  }

  private abrirBlob(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }
}
