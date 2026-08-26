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
import { Subject, debounceTime, distinctUntilChanged, Subscription, of, switchMap } from 'rxjs';
import { CatalogoAdminService } from '../../../core/services/catalogo-admin.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import {
  ProductoAdmin, ProductoDetalleAdmin, MarcaAdmin, CategoriaAdmin, VarianteAdmin
} from '../../../core/models/operativo.model';
import {
  ProductoDialogComponent, ProductoDialogData, ProductoDialogResultado
} from './producto-dialog.component';
import {
  VarianteDialogComponent, VarianteDialogData, VarianteDialogResultado
} from './variante-dialog.component';

import { CampoTextoDirective } from '../../../core/validacion';

/**
 * Catálogo maestro — MÓDULO DE REFERENCIA del patrón de interfaz
 * (docs/PATRON_UI.md). Cumple las cinco reglas:
 *
 *  1. Se entra por la grilla, con búsqueda por texto (debounce) + dos filtros
 *     y paginación SERVER-SIDE: el catálogo real tiene ~1.200 productos y
 *     nunca se trae ni se renderiza completo.
 *  2. Sobre la fila seleccionada: Nuevo · Modificar · Eliminar · Ver.
 *  3. Cada diálogo declara su modo con el chip de `<app-modo-form>`.
 *  4. Los diálogos tienen dos botones: Aceptar y Cancelar.
 *  5. Eliminar pregunta antes, vía `ConfirmService`.
 *
 * «Eliminar» es una BAJA LÓGICA (PATCH .../activo con `false`), no un DELETE:
 * el producto sale de la tienda y su histórico de ventas se conserva. Se
 * revierte marcando «Activo» en Modo Actualizar, y el diálogo lo dice.
 */
@Component({
  selector: 'app-productos-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatPaginatorModule, MatDialogModule, AccionesRegistroComponent,
    CampoTextoDirective
  ],
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

  /** Fila marcada en la grilla: sobre ella actúan Modificar / Eliminar / Ver. */
  filaSeleccionada: ProductoAdmin | null = null;
  /** Detalle cargado del producto seleccionado (incluye sus variantes). */
  seleccionado: ProductoDetalleAdmin | null = null;
  /** Fila marcada en la grilla de variantes, con su propia barra de acciones. */
  varianteSeleccionada: VarianteAdmin | null = null;

  columnas = ['nombre', 'marca', 'proveedores', 'variantes', 'publicado', 'activo'];
  readonly columnasVariante = ['sku', 'atributos', 'proveedor', 'precio', 'costo', 'peso', 'estado'];

  /**
   * Primer proveedor de la lista y cuántos quedan detrás.
   *
   * La grilla enseña UNO y anuncia el resto en vez de pintar la lista entera:
   * hay productos surtidos por dos o tres proveedores y el nombre de un
   * mayorista ecuatoriano ocupa media columna, así que la fila se rompería.
   * El detalle de la variante dice cuál es el de cada SKU.
   */
  proveedorPrincipal(p: ProductoAdmin): string {
    const lista = (p.proveedores || '').split(', ').filter(x => x);
    if (!lista.length) { return '—'; }
    return lista.length === 1 ? lista[0] : `${lista[0]} +${lista.length - 1}`;
  }

  constructor(private catalogo: CatalogoAdminService, private snackBar: MatSnackBar,
              private dialog: MatDialog, private confirmar: ConfirmService) {}

  ngOnInit(): void {
    this.busquedaSub = this.busqueda$
      .pipe(debounceTime(300), distinctUntilChanged())
      .subscribe(() => { this.page = 0; this.cargarProductos(); });
    this.cargarProductos();
    this.catalogo.marcas().subscribe(m => this.marcas = m);
    this.catalogo.categorias().subscribe(c => this.categorias = c);
  }

  ngOnDestroy(): void { this.busquedaSub?.unsubscribe(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargarProductos(): void {
    this.loading = true;
    this.catalogo.buscarProductos(this.busqueda, this.filtroMarcaId,
        this.filtroCategoriaId, this.page, this.size).subscribe({
      next: pagina => {
        this.productos = pagina.items;
        this.total = pagina.total;
        this.loading = false;
        this.resincronizarSeleccion();
      },
      error: () => this.loading = false
    });
  }

  /**
   * Tras recargar, la fila seleccionada es un objeto viejo: se vuelve a
   * apuntar al de la página nueva, o se suelta si ya no está en ella (otro
   * filtro, otra página). Sin esto, «Eliminar» actuaría sobre datos rancios.
   */
  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    const vigente = this.productos.find(p => p.id === this.filaSeleccionada!.id);
    this.filaSeleccionada = vigente ?? null;
    if (!vigente) { this.seleccionado = null; this.varianteSeleccionada = null; }
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

  // ── Regla 2: selección + las cuatro opciones sobre el producto ───────

  /** Un clic en la fila la selecciona y trae su detalle (las variantes). */
  seleccionarFila(p: ProductoAdmin): void {
    this.filaSeleccionada = p;
    this.varianteSeleccionada = null;
    this.cargarDetalle(p.id);
  }

  private cargarDetalle(id: number): void {
    this.catalogo.producto(id).subscribe({
      next: p => this.seleccionado = p,
      error: () => this.snackBar.open('No se pudo cargar el producto', 'Cerrar', { duration: 3000 })
    });
  }

  cerrarDetalle(): void {
    this.seleccionado = null;
    this.filaSeleccionada = null;
    this.varianteSeleccionada = null;
  }

  nuevoProducto(): void {
    this.abrirDialogoProducto('nuevo');
  }

  modificarProducto(): void {
    this.abrirDialogoProducto('actualizar');
  }

  verProducto(): void {
    this.abrirDialogoProducto('consulta');
  }

  /**
   * Un solo camino para los tres modos: se carga el detalle completo antes de
   * abrir para que ningún campo llegue vacío, y el modo decide el resto.
   */
  private abrirDialogoProducto(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    if (modo === 'nuevo') { this.abrirProducto(modo, undefined); return; }
    if (!this.filaSeleccionada) return;
    const id = this.filaSeleccionada.id;
    this.catalogo.producto(id).subscribe({
      next: p => this.abrirProducto(modo, p),
      error: () => this.snackBar.open('No se pudo cargar el producto', 'Cerrar', { duration: 3000 })
    });
  }

  private abrirProducto(modo: 'nuevo' | 'actualizar' | 'consulta',
                        producto?: ProductoDetalleAdmin): void {
    const data: ProductoDialogData = {
      marcas: this.marcas, categorias: this.categorias, producto, modo
    };
    this.dialog.open(ProductoDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: ProductoDialogResultado | undefined) => {
        if (!res) return;                       // Cancelar, Esc o Modo Consulta
        if (modo === 'nuevo') this.crearProducto(res);
        else this.guardarProducto(producto!, res);
      });
  }

  private crearProducto(res: ProductoDialogResultado): void {
    const { activo, ...body } = res;            // 'activo' no es del alta
    this.catalogo.crearProducto(body).subscribe({
      next: creado => {
        this.snackBar.open('Producto creado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargarProductos();
        this.cargarDetalle(creado.id);
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear producto'), 'Cerrar', { duration: 4000 })
    });
  }

  /**
   * Los datos del producto y su estado activo viven en endpoints distintos
   * (PUT del cuerpo, PATCH del activo): se envía el cuerpo y, solo si el
   * usuario cambió la casilla, se encadena la baja/alta lógica.
   */
  private guardarProducto(original: ProductoDetalleAdmin, res: ProductoDialogResultado): void {
    const { categoriaIds, activo, ...cambios } = res;
    this.catalogo.editarProducto(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.catalogo.activarProducto(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Producto actualizado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargarProductos();
        if (this.seleccionado?.id === original.id) this.cargarDetalle(original.id);
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar el producto'), 'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarProducto(): void {
    const p = this.filaSeleccionada;
    if (!p || !p.activo) return;
    this.confirmar.eliminacion(
      `el producto «${p.nombre}»`,
      'El producto y sus variantes dejarán de mostrarse en la tienda. No se borra ' +
      'nada: su histórico de ventas se conserva y puedes restaurarlo marcando ' +
      '«Activo» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.catalogo.activarProducto(p.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Producto «${p.nombre}» eliminado`, 'OK', { duration: 3000 });
          this.cargarProductos();
          if (this.seleccionado?.id === p.id) this.cargarDetalle(p.id);
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar el producto'), 'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoProductoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'El producto ya está eliminado (inactivo). Restáuralo desde Modificar.'
      : '';
  }

  // ── Variantes: la misma barra de acciones sobre su propia grilla ─────

  seleccionarVariante(v: VarianteAdmin): void { this.varianteSeleccionada = v; }

  nuevaVariante(): void { this.abrirVariante('nuevo'); }
  modificarVariante(): void { this.abrirVariante('actualizar'); }
  verVariante(): void { this.abrirVariante('consulta'); }

  private abrirVariante(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    if (!this.seleccionado) return;
    const productoId = this.seleccionado.id;
    const variante = modo === 'nuevo' ? undefined : this.varianteSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !variante) return;

    const data: VarianteDialogData = {
      productoNombre: this.seleccionado.nombre, variante, modo
    };
    this.dialog.open(VarianteDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: VarianteDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crearVariante(productoId, res);
        else this.guardarVariante(productoId, variante!, res);
      });
  }

  private crearVariante(productoId: number, res: VarianteDialogResultado): void {
    const { activo, ...body } = res;
    this.catalogo.crearVariante(productoId, body).subscribe({
      next: () => {
        this.snackBar.open('Variante creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargarDetalle(productoId);
        this.cargarProductos();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear variante'), 'Cerrar', { duration: 4000 })
    });
  }

  private guardarVariante(productoId: number, original: VarianteAdmin,
                          res: VarianteDialogResultado): void {
    this.catalogo.editarVariante(original.id,
        { sku: res.sku, precio: res.precio, costo: res.costo, pesoKg: res.pesoKg }).pipe(
      switchMap(() => res.activo === original.activo
        ? of(null)
        : this.catalogo.activarVariante(original.id, res.activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Variante actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.refrescarVariantes(productoId);
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la variante'), 'Cerrar', { duration: 4000 })
    });
  }

  eliminarVariante(): void {
    const v = this.varianteSeleccionada;
    const productoId = this.seleccionado?.id;
    if (!v || !v.activo || productoId == null) return;
    this.confirmar.eliminacion(
      `la variante «${v.sku}»`,
      'La variante dejará de venderse en la tienda. No se borra nada: su stock, ' +
      'su kardex y sus ventas se conservan, y puedes restaurarla marcando ' +
      '«Activa» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.catalogo.activarVariante(v.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Variante «${v.sku}» eliminada`, 'OK', { duration: 3000 });
          this.refrescarVariantes(productoId);
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar la variante'), 'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoVarianteNoEliminable(): string {
    return this.varianteSeleccionada && !this.varianteSeleccionada.activo
      ? 'La variante ya está eliminada (inactiva). Restáurala desde Modificar.'
      : '';
  }

  /** Recarga el detalle manteniendo apuntada la variante que estaba marcada. */
  private refrescarVariantes(productoId: number): void {
    const marcadaId = this.varianteSeleccionada?.id ?? null;
    this.catalogo.producto(productoId).subscribe({
      next: p => {
        this.seleccionado = p;
        this.varianteSeleccionada = marcadaId === null
          ? null
          : p.variantes.find(v => v.id === marcadaId) ?? null;
      },
      error: () => this.snackBar.open('No se pudo cargar el producto', 'Cerrar', { duration: 3000 })
    });
  }
}
