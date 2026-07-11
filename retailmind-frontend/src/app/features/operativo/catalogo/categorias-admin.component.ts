import { Component, OnInit } from '@angular/core';
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
import { CatalogoAdminService } from '../../../core/services/catalogo-admin.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { CategoriaAdmin } from '../../../core/models/operativo.model';

/** Gestión de categorías del catálogo: crear / editar / activar (patrón FAQ). */
@Component({
  selector: 'app-categorias-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './categorias-admin.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CategoriasAdminComponent implements OnInit {

  categorias: CategoriaAdmin[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  columnas = ['nombre', 'slug', 'padre', 'descripcion', 'activo', 'acciones'];

  constructor(private catalogo: CatalogoAdminService, private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.cargar(); }

  private formVacio() {
    return { nombre: '', slug: '', descripcion: '', padreId: null as number | null };
  }

  cargar(): void {
    this.loading = true;
    this.catalogo.categorias().subscribe({
      next: data => { this.categorias = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nombrePadre(c: CategoriaAdmin): string {
    if (c.categoria_padre_id == null) return '—';
    return this.categorias.find(x => x.id === c.categoria_padre_id)?.nombre || '—';
  }

  /** Posibles padres para el select (excluye la categoría en edición). */
  get padresPosibles(): CategoriaAdmin[] {
    return this.categorias.filter(c => c.id !== this.editandoId);
  }

  nueva(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(c: CategoriaAdmin): void {
    this.editandoId = c.id;
    // Precarga completa del registro: nada de campos vacíos.
    this.form = { nombre: c.nombre, slug: c.slug, descripcion: c.descripcion || '',
                  padreId: c.categoria_padre_id };
    this.showForm = true;
  }

  autoSlug(): void {
    if (this.editandoId !== null && this.form.slug) return;
    this.form.slug = this.form.nombre.toLowerCase().trim()
      .replace(/[áéíóúñ]/g, c => ({ 'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ñ': 'n' }[c] || c))
      .replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
  }

  guardar(): void {
    if (!this.form.nombre.trim() || !this.form.slug.trim()) {
      this.snackBar.open('Nombre y slug son requeridos', 'Cerrar', { duration: 3000 });
      return;
    }
    // El endpoint de edición no cambia el padre (solo nombre/slug/descripción).
    const peticion = this.editandoId === null
      ? this.catalogo.crearCategoria(this.form)
      : this.catalogo.editarCategoria(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'Categoría creada' : 'Categoría actualizada',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar la categoría'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(c: CategoriaAdmin): void {
    this.catalogo.activarCategoria(c.id, !c.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
