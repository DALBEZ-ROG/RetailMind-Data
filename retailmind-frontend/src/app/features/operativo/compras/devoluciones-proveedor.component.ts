import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { PaginaServidor } from '../../../core/services/pagina-servidor.util';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ComprasService } from '../../../core/services/compras.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { ProveedorRef } from '../../../core/models/operativo.model';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

/**
 * Devolución a proveedor (script 45) — espejo del RMA hacia el proveedor.
 * BODEGA identifica el defectuoso (inspección RMA + rechazo en recepción +
 * marcado posterior); COMPRAS agrupa por proveedor, envía y registra la
 * resolución (nota de crédito simulada o reposición que reingresa stock).
 * El backend + la BD son la fuente de verdad de cada compuerta.
 */
@Component({
  selector: 'app-devoluciones-proveedor',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatCheckboxModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatTooltipModule, MatSnackBarModule, MatPaginatorModule,
    CampoNumeroDirective, CampoTextoDirective
  ],
  templateUrl: './devoluciones-proveedor.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class DevolucionesProveedorComponent implements OnInit {

  readonly estadosItem = ['pendiente', 'en_devolucion', 'resuelto'];
  readonly estadosDev = ['registrada', 'enviada', 'resuelta', 'cerrada'];

  /**
   * La página que devuelve el SERVIDOR. El pool trae 27.831 ítems defectuosos
   * (12,66 MB) y cada fila lleva su propia casilla, o sea un componente por
   * fila. El filtro por `estado` ya viajaba al endpoint, así que no había
   * criterio que mudar a SQL.
   *
   * `seleccion` sobrevive al cambio de página a propósito: COMPRAS puede
   * agrupar ítems de varias páginas en una misma devolución, y el backend
   * valida cada id que recibe.
   */
  readonly pag = new PaginaServidor<any>();
  filtroItem: string | null = 'pendiente';
  seleccion = new Set<number>();

  devoluciones: any[] = [];
  filtroDev: string | null = null;
  detalle: any | null = null;

  proveedores: ProveedorRef[] = [];
  proveedorId: number | null = null;
  observacion = '';

  // Marcado posterior (BODEGA): la unidad ya estaba en stock y SALE al marcarse
  detallesRecepcion: any[] = [];
  recepcionDetalleId: number | null = null;
  cantidadDefectuosa = 1;
  notaDefectuoso = '';

  // Asignación de proveedor a ítems RMA sin rastro (COMPRAS)
  asignaciones: Record<number, number | null> = {};

  nota = '';
  tipoResolucion: 'nota_credito' | 'reposicion' = 'nota_credito';
  procesando = false;
  loading = false;

  columnasItems = ['sel', 'sku', 'producto', 'cantidad', 'origen', 'bodega', 'proveedor', 'estado', 'autor'];
  columnasDev = ['numero', 'proveedor', 'items', 'estado', 'resolucion', 'fecha', 'acciones'];

  constructor(private compras: ComprasService, private referencias: ReferenciasService,
              private auth: AuthService, private snackBar: MatSnackBar) {}

  // Acciones visibles por rol (espeja SecurityConfig; el backend decide)
  get esAdmin(): boolean   { return this.auth.hasRole('ADMIN'); }
  get esCompras(): boolean { return this.esAdmin || this.auth.hasRole('COMPRAS'); }
  get esBodega(): boolean  { return this.esAdmin || this.auth.hasRole('BODEGA'); }

  ngOnInit(): void {
    this.cargarItems();
    this.cargarDevoluciones();
    this.referencias.proveedores().subscribe(p => this.proveedores = p);
    if (this.esBodega) {
      this.compras.recepcionDetallesRef().subscribe(d =>
        this.detallesRecepcion = d.filter(x => x.marcable > 0));
    }
  }

  /** Cambiar de filtro vuelve a la primera página y RECUENTA. */
  filtrarItems(): void {
    this.pag.reiniciar();
    this.cargarItems();
  }

  /**
   * @param conTotal false SOLO al cambiar de página. Se aprovecha para
   *                 distinguir los dos casos: una recarga de verdad (alta,
   *                 transición, cambio de filtro) limpia la selección como
   *                 hacía antes; pasar de página la CONSERVA, que es lo que
   *                 permite agrupar ítems de varias páginas.
   */
  cargarItems(conTotal = true): void {
    if (conTotal) { this.seleccion.clear(); }
    this.loading = true;
    this.compras.itemsDefectuosos({
      estado: this.filtroItem, page: this.pag.pagina, size: this.pag.tam, conTotal
    }).subscribe({
      next: pg => {
        this.pag.aplicar(pg);
        this.loading = false;
        if (this.pag.ajustarTrasBorrado()) { this.cargarItems(conTotal); }
      },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudieron cargar los ítems defectuosos'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  /** Cambiar de página NO recuenta ni pierde la selección acumulada. */
  alPaginarItems(e: PageEvent): void {
    this.pag.alPaginar(e);
    this.cargarItems(false);
  }

  cargarDevoluciones(): void {
    this.compras.devolucionesProveedor(this.filtroDev).subscribe({
      next: d => this.devoluciones = d,
      error: e => this.snackBar.open(mensajeError(e, 'No se pudieron cargar las devoluciones'), 'Cerrar', { duration: 4000 })
    });
  }

  // ── Selección para agrupar (COMPRAS) ────────────────────────────────────
  seleccionable(it: any): boolean {
    return this.esCompras && it.estado === 'pendiente'
      && (this.proveedorId == null || it.proveedor_id == null || it.proveedor_id === this.proveedorId);
  }

  toggle(it: any): void {
    if (this.seleccion.has(it.id)) { this.seleccion.delete(it.id); return; }
    this.seleccion.add(it.id);
    // El primer ítem con proveedor fija el proveedor del grupo
    if (this.proveedorId == null && it.proveedor_id != null) this.proveedorId = it.proveedor_id;
  }

  crear(): void {
    if (!this.proveedorId) {
      this.snackBar.open('Elige el proveedor de la devolución (los ítems sin proveedor quedarán asignados a él)', 'Cerrar', { duration: 4500 });
      return;
    }
    if (!this.seleccion.size) {
      this.snackBar.open('Selecciona al menos un ítem defectuoso pendiente', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.compras.crearDevolucionProveedor({
      proveedorId: this.proveedorId, itemIds: [...this.seleccion], observacion: this.observacion
    }).subscribe({
      next: d => {
        this.procesando = false;
        this.snackBar.open(`Devolución ${d.numero} registrada`, 'OK', { duration: 3500, panelClass: ['snack-success'] });
        this.observacion = ''; this.proveedorId = null;
        this.detalle = d;
        this.cargarItems(); this.cargarDevoluciones();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo crear la devolución a proveedor'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  // ── Marcado posterior a la recepción (BODEGA) ───────────────────────────
  marcarDefectuoso(): void {
    if (!this.recepcionDetalleId) {
      this.snackBar.open('Elige la línea de recepción con unidades defectuosas', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.compras.marcarDefectuoso(this.recepcionDetalleId,
      { cantidad: this.cantidadDefectuosa, nota: this.notaDefectuoso }).subscribe({
      next: r => {
        this.procesando = false;
        this.snackBar.open(`${r.cantidad} unidad/es de ${r.sku} marcadas defectuosas (salieron del stock vendible)`, 'OK',
          { duration: 4500, panelClass: ['snack-success'] });
        this.recepcionDetalleId = null; this.cantidadDefectuosa = 1; this.notaDefectuoso = '';
        this.cargarItems();
        this.compras.recepcionDetallesRef().subscribe(d =>
          this.detallesRecepcion = d.filter(x => x.marcable > 0));
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo marcar el defectuoso'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  asignarProveedor(it: any): void {
    const provId = this.asignaciones[it.id];
    if (!provId) return;
    this.compras.asignarProveedorItem(it.id, provId).subscribe({
      next: () => {
        this.snackBar.open('Proveedor asignado al ítem', 'OK', { duration: 3000, panelClass: ['snack-success'] });
        this.cargarItems();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo asignar el proveedor'), 'Cerrar', { duration: 4500 })
    });
  }

  // ── Detalle y transiciones (COMPRAS) ────────────────────────────────────
  ver(id: number): void {
    this.compras.devolucionProveedor(id).subscribe({
      next: d => this.detalle = d,
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar el detalle'), 'Cerrar', { duration: 4000 })
    });
  }

  enviar(): void {
    if (!this.detalle) return;
    this.transicion(this.compras.enviarDevolucionProveedor(this.detalle.id, this.nota),
      'Devolución enviada al proveedor (sin movimiento de stock)');
  }

  resolver(): void {
    if (!this.detalle) return;
    this.transicion(this.compras.resolverDevolucionProveedor(this.detalle.id,
      { tipoResolucion: this.tipoResolucion, nota: this.nota }),
      this.tipoResolucion === 'reposicion'
        ? 'Reposición registrada: stock apto reingresado con kardex'
        : 'Nota de crédito registrada (sin movimiento de stock)');
  }

  cerrar(): void {
    if (!this.detalle) return;
    this.transicion(this.compras.cerrarDevolucionProveedor(this.detalle.id, this.nota),
      'Devolución a proveedor cerrada');
  }

  private transicion(obs: any, mensajeOk: string): void {
    this.procesando = true;
    obs.subscribe({
      next: (d: any) => {
        this.procesando = false; this.nota = '';
        this.detalle = d;
        this.snackBar.open(mensajeOk, 'OK', { duration: 4000, panelClass: ['snack-success'] });
        this.cargarItems(); this.cargarDevoluciones();
      },
      error: (e: any) => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo completar la acción'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  chipEstado(estado: string): 'ok' | 'warn' | 'error' | '' {
    switch (estado) {
      case 'resuelto': case 'resuelta': case 'cerrada': return 'ok';
      case 'pendiente': case 'registrada': return 'warn';
      case 'en_devolucion': case 'enviada': return '';
      default: return '';
    }
  }
}
