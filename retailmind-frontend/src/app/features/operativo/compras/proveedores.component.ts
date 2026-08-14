import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule } from '@angular/material/paginator';
import { PaginaLocal } from '../../../core/services/pagina-local.util';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of } from 'rxjs';
import { ComprasService } from '../../../core/services/compras.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ProveedorFichaRow, ProductoProveedorRow, ProductoCompraRef
} from '../../../core/models/operativo.model';

/**
 * Ficha del proveedor + sección "Productos que ofrece" (OTD-COM-10, script 51):
 * costo pactado, plazo de entrega, cantidad mínima y proveedor preferido por
 * producto (solo UNO por variante; el backend desmarca al resto y el índice
 * único parcial es el backstop). Escriben COMPRAS/ADMIN; GERENTE lee.
 * La recepción de mercancía alimenta el costo automáticamente
 * (fn_upsert_producto_proveedor); aquí se gestionan los datos comerciales.
 */
@Component({
  selector: 'app-proveedores',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatAutocompleteModule, MatCheckboxModule,
    MatSnackBarModule, MatTooltipModule, MatPaginatorModule],
  templateUrl: './proveedores.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class ProveedoresComponent implements OnInit {

  proveedores: ProveedorFichaRow[] = [];
  loading = true;

  seleccionado: ProveedorFichaRow | null = null;
  productos: ProductoProveedorRow[] = [];
  /** Página del catálogo del proveedor (el conjunto llega a 6.107 filas). */
  readonly pag = new PaginaLocal<ProductoProveedorRow>();

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  // Buscador de producto en servidor (catálogo ~1.221 variantes: nunca completo)
  productosRef: ProductoCompraRef[] = [];
  productoBusqueda: string | ProductoCompraRef = '';
  private buscarProducto$ = new Subject<string>();

  columnasProveedor = ['proveedor', 'contacto', 'credito', 'productos', 'acciones'];
  columnasProducto = ['producto', 'costo', 'plazo', 'minimo', 'preferido', 'activo', 'acciones'];

  constructor(private compras: ComprasService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  /** Escribir el catálogo: COMPRAS/ADMIN (GERENTE solo lee). */
  get puedeEditar(): boolean {
    return this.auth.hasRole('ADMIN') || this.auth.hasRole('COMPRAS');
  }

  ngOnInit(): void {
    this.cargar();
    this.buscarProducto$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => q.trim().length >= 2 ? this.compras.productosRef(q.trim()) : of([]))
    ).subscribe({ next: p => this.productosRef = p, error: () => this.productosRef = [] });
  }

  private formVacio() {
    return { productoVarianteId: null as number | null, costo: null as number | null,
             tiempoEntregaDias: null as number | null, cantidadMinima: 1,
             esPreferido: false, codigoProveedor: '' };
  }

  nombreDe(p: ProveedorFichaRow): string {
    return p.nombre_comercial || p.razon_social;
  }

  cargar(): void {
    this.loading = true;
    this.compras.proveedores().subscribe({
      next: data => {
        this.proveedores = data;
        this.loading = false;
        if (this.seleccionado) {
          const s = data.find(p => p.id === this.seleccionado!.id);
          this.seleccionado = s || null;
        }
      },
      error: () => this.loading = false
    });
  }

  ver(p: ProveedorFichaRow): void {
    this.seleccionado = p;
    this.showForm = false;
    this.cargarProductos();
  }

  private cargarProductos(): void {
    if (!this.seleccionado) return;
    this.compras.productosDeProveedor(this.seleccionado.id).subscribe({
      next: data => { this.productos = data; this.pag.fijar(data); },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo cargar el catálogo del proveedor'),
        'Cerrar', { duration: 4000 })
    });
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.productoBusqueda = '';
    this.productosRef = [];
    this.showForm = true;
  }

  editar(pp: ProductoProveedorRow): void {
    this.editandoId = pp.id;
    this.form = {
      productoVarianteId: pp.producto_variante_id, costo: pp.costo,
      tiempoEntregaDias: pp.tiempo_entrega_dias, cantidadMinima: pp.cantidad_minima,
      esPreferido: pp.es_preferido, codigoProveedor: pp.codigo_proveedor || ''
    };
    this.productoBusqueda = `${pp.producto} (${pp.sku})`;
    this.showForm = true;
  }

  guardar(): void {
    if (!this.seleccionado) return;
    if (this.editandoId === null && !this.form.productoVarianteId) {
      this.snackBar.open('Busca y selecciona el producto a asociar', 'Cerrar', { duration: 3000 });
      return;
    }
    if (this.form.costo === null || this.form.costo < 0) {
      this.snackBar.open('El costo es requerido (cero o mayor)', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.compras.asociarProducto(this.seleccionado.id, this.form)
      : this.compras.editarProductoProveedor(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null
          ? 'Producto asociado al proveedor' : 'Condiciones actualizadas',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargarProductos();
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(pp: ProductoProveedorRow): void {
    this.compras.activarProductoProveedor(pp.id, !pp.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 });
        this.cargarProductos(); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }

  // ── Buscador de producto ─────────────────────────────────────────────

  buscarProducto(texto: string | ProductoCompraRef): void {
    if (typeof texto === 'string') {
      this.form.productoVarianteId = null;
      this.buscarProducto$.next(texto);
    }
  }

  productoElegido(p: ProductoCompraRef): void {
    this.form.productoVarianteId = p.id;
  }

  nombreProducto = (p: ProductoCompraRef | string | null): string =>
    typeof p === 'object' && p ? `${p.nombre} (${p.sku})` : (p || '');
}
