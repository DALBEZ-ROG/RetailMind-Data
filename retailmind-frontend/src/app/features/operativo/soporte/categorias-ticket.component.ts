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
import { SoporteService } from '../../../core/services/soporte.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { CategoriaTicketRow } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-categorias-ticket',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './categorias-ticket.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CategoriasTicketComponent implements OnInit {

  categorias: CategoriaTicketRow[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  columnas = ['nombre', 'uso', 'activo', 'acciones'];

  constructor(private soporte: SoporteService, private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.cargar(); }

  private formVacio() {
    return { nombre: '', descripcion: '' };
  }

  cargar(): void {
    this.loading = true;
    this.soporte.categorias().subscribe({
      next: data => { this.categorias = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(c: CategoriaTicketRow): void {
    this.editandoId = c.id;
    this.form = { nombre: c.nombre, descripcion: c.descripcion || '' };
    this.showForm = true;
  }

  guardar(): void {
    if (!this.form.nombre.trim()) {
      this.snackBar.open('El nombre es requerido', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.soporte.crearCategoria(this.form)
      : this.soporte.editarCategoria(this.editandoId, this.form);
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

  toggleActivo(c: CategoriaTicketRow): void {
    this.soporte.activarCategoria(c.id, !c.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
