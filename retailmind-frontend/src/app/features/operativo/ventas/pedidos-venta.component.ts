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
import { VentasService } from '../../../core/services/ventas.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { NavPermissionsService } from '../../../core/navigation/nav-permissions.service';
import { SelectBuscableComponent, OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ClienteRef, BodegaRef, VarianteRef, PedidoVentaRow, PedidoVentaDetalle
} from '../../../core/models/operativo.model';

interface LineaPedido { varianteId: number | null; cantidad: number; }

@Component({
  selector: 'app-pedidos-venta',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule,
    MatSnackBarModule, MatTooltipModule, MatPaginatorModule, SelectBuscableComponent],
  templateUrl: './pedidos-venta.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PedidosVentaComponent implements OnInit {

  pedidos: PedidoVentaRow[] = [];
  clientes: ClienteRef[] = [];
  bodegas: BodegaRef[] = [];
  variantes: VarianteRef[] = [];
  clientesOpc: OpcionBuscable[] = [];
  variantesOpc: OpcionBuscable[] = [];
  loading = true;

  // Paginación client-side de la tabla de pedidos
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];

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

  columnas = ['numero', 'cliente', 'estado', 'fecha', 'total', 'acciones'];

  constructor(private ventas: VentasService, private referencias: ReferenciasService,
              private nav: NavPermissionsService, private snackBar: MatSnackBar) {}

  // La BD no concede bodega a grp_vendedor: no se dispara la petición (403).
  get puedeVerBodegas(): boolean { return this.nav.canDato('refBodegas'); }

  ngOnInit(): void {
    this.cargarPedidos();
    if (this.nav.canDato('refClientes')) {
      this.referencias.clientes().subscribe(c => {
        this.clientes = c;
        this.clientesOpc = c.map(x => ({ id: x.id, texto: `${x.nombre} (${x.email})` }));
      });
    }
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
  }

  cargarPedidos(): void {
    this.loading = true;
    this.ventas.pedidos().subscribe({
      next: data => { this.pedidos = data; this.pagina = 0; this.loading = false; },
      error: () => this.loading = false
    });
  }

  get pedidosPagina(): PedidoVentaRow[] {
    const inicio = this.pagina * this.tamPagina;
    return this.pedidos.slice(inicio, inicio + this.tamPagina);
  }

  alPaginar(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
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
        this.cargarPedidos();
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
      },
      error: () => this.snackBar.open('No se pudo cargar el pedido', 'Cerrar', { duration: 3000 })
    });
  }

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
