import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CatalogoAdminService } from '../../../core/services/catalogo-admin.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { MarcaAdmin } from '../../../core/models/operativo.model';

/** Gestión de marcas del catálogo: crear / editar / activar (patrón FAQ). */
@Component({
  selector: 'app-marcas-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './marcas-admin.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class MarcasAdminComponent implements OnInit {

  marcas: MarcaAdmin[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  columnas = ['nombre', 'slug', 'descripcion', 'activo', 'acciones'];

  constructor(private catalogo: CatalogoAdminService, private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.cargar(); }

  private formVacio() {
    return { nombre: '', slug: '', descripcion: '' };
  }

  cargar(): void {
    this.loading = true;
    this.catalogo.marcas().subscribe({
      next: data => { this.marcas = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nueva(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(m: MarcaAdmin): void {
    this.editandoId = m.id;
    // Precarga completa del registro: nada de campos vacíos.
    this.form = { nombre: m.nombre, slug: m.slug, descripcion: m.descripcion || '' };
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
    const peticion = this.editandoId === null
      ? this.catalogo.crearMarca(this.form)
      : this.catalogo.editarMarca(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'Marca creada' : 'Marca actualizada',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar la marca'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(m: MarcaAdmin): void {
    this.catalogo.activarMarca(m.id, !m.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
