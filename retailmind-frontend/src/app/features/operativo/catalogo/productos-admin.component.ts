import { Component, OnDestroy, OnInit } from '@angular/core';
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
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Subject, debounceTime, distinctUntilChanged, Subscription } from 'rxjs';
import { CatalogoAdminService } from '../../../core/services/catalogo-admin.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ProductoAdmin, ProductoDetalleAdmin, MarcaAdmin, CategoriaAdmin, VarianteAdmin
} from '../../../core/models/operativo.model';
import { ProductoDialogComponent, ProductoDialogData } from './producto-dialog.component';
import { VarianteDialogComponent, VarianteDialogData } from './variante-dialog.component';

/**
 * Catálogo maestro con paginación SERVER-SIDE (el catálogo real tiene ~1.200
 * productos: nunca se trae ni renderiza completo) y búsqueda por nombre /
 * SKU / marca / categoría. Alta y edición en diálogos estilo Dubai.
 */
@Component({
  selector: 'app-productos-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatPaginatorModule, MatDialogModule],
  templateUrl: './productos-admin.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class ProductosAdminComponent implements OnInit, OnDestroy {

  productos: ProductoAdmin[] = [];
  total = 0;
  page = 0;
  size = 25;
  readonly pageSizes = [25, 50, 100];

  marcas: MarcaAdmin[] = [];
  categorias: CategoriaAdmin[] = [];
  loading = true;

  busqueda = '';
  filtroMarcaId: number | null = null;
  filtroCategoriaId: number | null = null;
  private readonly busqueda$ = new Subject<string>();
  private busquedaSub?: Subscription;

  seleccionado: ProductoDetalleAdmin | null = null;

  columnas = ['nombre', 'marca', 'variantes', 'publicado', 'activo', 'acciones'];

  constructor(private catalogo: CatalogoAdminService, private snackBar: MatSnackBar,
              private dialog: MatDialog) {}

  ngOnInit(): void {
    this.busquedaSub = this.busqueda$
      .pipe(debounceTime(300), distinctUntilChanged())
      .subscribe(() => { this.page = 0; this.cargarProductos(); });
    this.cargarProductos();
    this.catalogo.marcas().subscribe(m => this.marcas = m);
    this.catalogo.categorias().subscribe(c => this.categorias = c);
  }

  ngOnDestroy(): void { this.busquedaSub?.unsubscribe(); }

  cargarProductos(): void {
    this.loading = true;
    this.catalogo.buscarProductos(this.busqueda, this.filtroMarcaId,
        this.filtroCategoriaId, this.page, this.size).subscribe({
      next: pagina => {
        this.productos = pagina.items;
        this.total = pagina.total;
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  alEscribirBusqueda(texto: string): void { this.busqueda$.next(texto); }

  alFiltrar(): void { this.page = 0; this.cargarProductos(); }

  limpiarFiltros(): void {
    this.busqueda = '';
    this.filtroMarcaId = null;
    this.filtroCategoriaId = null;
    this.alFiltrar();
  }

  alPaginar(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.cargarProductos();
  }

  // ── Producto (diálogos) ──────────────────────────────────────────────

  nuevoProducto(): void {
    const data: ProductoDialogData = { marcas: this.marcas, categorias: this.categorias };
    this.dialog.open(ProductoDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe(body => {
        if (!body) return;
        this.catalogo.crearProducto(body).subscribe({
          next: res => {
            this.snackBar.open('Producto creado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
            this.cargarProductos();
            this.verDetalle(res.id);
          },
          error: e => this.snackBar.open(mensajeError(e, 'Error al crear producto'), 'Cerrar', { duration: 4000 })
        });
      });
  }

  editarProducto(id: number): void {
    // Se carga el detalle para que el diálogo abra con TODO precargado.
    this.catalogo.producto(id).subscribe({
      next: p => {
        const data: ProductoDialogData = { marcas: this.marcas, categorias: this.categorias, producto: p };
        this.dialog.open(ProductoDialogComponent, { data, panelClass: 'dubai-dialog' })
          .afterClosed().subscribe(body => {
            if (!body) return;
            const { categoriaIds, ...cambios } = body;
            this.catalogo.editarProducto(id, cambios).subscribe({
              next: () => {
                this.snackBar.open('Producto actualizado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
                this.cargarProductos();
                if (this.seleccionado?.id === id) this.verDetalle(id);
              },
              error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar el producto'), 'Cerrar', { duration: 4000 })
            });
          });
      },
      error: () => this.snackBar.open('No se pudo cargar el producto', 'Cerrar', { duration: 3000 })
    });
  }

  verDetalle(id: number): void {
    this.catalogo.producto(id).subscribe({
      next: p => this.seleccionado = p,
      error: () => this.snackBar.open('No se pudo cargar el producto', 'Cerrar', { duration: 3000 })
    });
  }

  toggleActivo(p: ProductoAdmin): void {
    this.catalogo.activarProducto(p.id, !p.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargarProductos(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }

  // ── Variantes (diálogos) ─────────────────────────────────────────────

  nuevaVariante(): void {
    if (!this.seleccionado) return;
    const productoId = this.seleccionado.id;
    const data: VarianteDialogData = { productoNombre: this.seleccionado.nombre };
    this.dialog.open(VarianteDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe(body => {
        if (!body) return;
        this.catalogo.crearVariante(productoId, body).subscribe({
          next: () => {
            this.snackBar.open('Variante creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
            this.verDetalle(productoId);
            this.cargarProductos();
          },
          error: e => this.snackBar.open(mensajeError(e, 'Error al crear variante'), 'Cerrar', { duration: 4000 })
        });
      });
  }

  editarVariante(v: VarianteAdmin): void {
    if (!this.seleccionado) return;
    const productoId = this.seleccionado.id;
    const data: VarianteDialogData = { productoNombre: this.seleccionado.nombre, variante: v };
    this.dialog.open(VarianteDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe(body => {
        if (!body) return;
        this.catalogo.editarVariante(v.id, { sku: body.sku, precio: body.precio, costo: body.costo }).subscribe({
          next: () => {
            this.snackBar.open('Variante actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
            this.verDetalle(productoId);
          },
          error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la variante'), 'Cerrar', { duration: 4000 })
        });
      });
  }

  toggleVariante(v: VarianteAdmin): void {
    if (!this.seleccionado) return;
    const productoId = this.seleccionado.id;
    this.catalogo.activarVariante(v.id, !v.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.verDetalle(productoId); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
