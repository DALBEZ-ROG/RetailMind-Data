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
import { CatalogoAdminService } from '../../../core/services/catalogo-admin.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ProductoAdmin, ProductoDetalleAdmin, MarcaAdmin, CategoriaAdmin
} from '../../../core/models/operativo.model';

@Component({
  selector: 'app-productos-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule,
    MatSnackBarModule, MatTooltipModule],
  templateUrl: './productos-admin.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class ProductosAdminComponent implements OnInit {

  productos: ProductoAdmin[] = [];
  marcas: MarcaAdmin[] = [];
  categorias: CategoriaAdmin[] = [];
  loading = true;

  showForm = false;
  nuevo = { nombre: '', slug: '', marcaId: null as number | null,
            descripcionCorta: '', descripcion: '', publicado: true,
            categoriaIds: [] as number[] };

  seleccionado: ProductoDetalleAdmin | null = null;
  showVarianteForm = false;
  nuevaVariante = { sku: '', precio: 0, costo: 0, codigoBarras: '', esPredeterminada: false };

  columnas = ['id', 'nombre', 'marca', 'variantes', 'publicado', 'activo', 'acciones'];

  constructor(private catalogo: CatalogoAdminService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.cargarProductos();
    this.catalogo.marcas().subscribe(m => this.marcas = m);
    this.catalogo.categorias().subscribe(c => this.categorias = c);
  }

  cargarProductos(): void {
    this.loading = true;
    this.catalogo.productos().subscribe({
      next: data => { this.productos = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  autoSlug(): void {
    this.nuevo.slug = this.nuevo.nombre.toLowerCase().trim()
      .replace(/[Ã¡Ã©Ã­Ã³ÃºÃ±]/g, c => ({ 'Ã¡':'a','Ã©':'e','Ã­':'i','Ã³':'o','Ãº':'u','Ã±':'n' }[c] || c))
      .replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
  }

  crearProducto(): void {
    if (!this.nuevo.nombre || !this.nuevo.slug) {
      this.snackBar.open('Nombre y slug son requeridos', 'Cerrar', { duration: 3000 });
      return;
    }
    this.catalogo.crearProducto(this.nuevo).subscribe({
      next: res => {
        this.snackBar.open(`Producto creado (id ${res.id})`, 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.nuevo = { nombre: '', slug: '', marcaId: null, descripcionCorta: '',
                       descripcion: '', publicado: true, categoriaIds: [] };
        this.cargarProductos();
        this.verDetalle(res.id);
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear producto'), 'Cerrar', { duration: 4000 })
    });
  }

  verDetalle(id: number): void {
    this.catalogo.producto(id).subscribe({
      next: p => { this.seleccionado = p; this.showVarianteForm = false; },
      error: () => this.snackBar.open('No se pudo cargar el producto', 'Cerrar', { duration: 3000 })
    });
  }

  crearVariante(): void {
    if (!this.seleccionado) return;
    if (!this.nuevaVariante.sku || this.nuevaVariante.precio <= 0) {
      this.snackBar.open('SKU y precio (> 0) son requeridos', 'Cerrar', { duration: 3000 });
      return;
    }
    this.catalogo.crearVariante(this.seleccionado.id, this.nuevaVariante).subscribe({
      next: res => {
        this.snackBar.open(`Variante creada (id ${res.id})`, 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.nuevaVariante = { sku: '', precio: 0, costo: 0, codigoBarras: '', esPredeterminada: false };
        this.showVarianteForm = false;
        this.verDetalle(this.seleccionado!.id);
        this.cargarProductos();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear variante'), 'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(p: ProductoAdmin): void {
    this.catalogo.activarProducto(p.id, !p.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargarProductos(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
