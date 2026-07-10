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
import { MatExpansionModule } from '@angular/material/expansion';
import { SoporteService } from '../../../core/services/soporte.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { FaqRow, FaqActiva, CategoriaTicketRef } from '../../../core/models/operativo.model';

/**
 * FAQ con dos caras: gestión (ADMIN edita, GERENTE consulta la lista completa)
 * y centro de ayuda de solo lectura (CLIENTE/ANALISTA ven las FAQ activas).
 */
@Component({
  selector: 'app-faq',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatExpansionModule],
  templateUrl: './faq.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class FaqComponent implements OnInit {

  faqs: FaqRow[] = [];
  faqsActivas: FaqActiva[] = [];
  categoriasRef: CategoriaTicketRef[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  columnas = ['pregunta', 'categoria', 'orden', 'activo', 'acciones'];

  constructor(private soporte: SoporteService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }
  get esGestion(): boolean { return this.auth.hasRole('ADMIN') || this.auth.hasRole('GERENTE'); }

  ngOnInit(): void {
    this.cargar();
    if (this.esAdmin) {
      this.soporte.categoriasRef().subscribe({ next: c => this.categoriasRef = c, error: () => {} });
    }
  }

  private formVacio() {
    return { categoriaId: null as number | null, pregunta: '', respuesta: '',
             orden: 0 as number | null };
  }

  cargar(): void {
    this.loading = true;
    if (this.esGestion) {
      this.soporte.faqs().subscribe({
        next: data => { this.faqs = data; this.loading = false; },
        error: () => this.loading = false
      });
    } else {
      this.soporte.faqsActivas().subscribe({
        next: data => { this.faqsActivas = data; this.loading = false; },
        error: () => this.loading = false
      });
    }
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(f: FaqRow): void {
    this.editandoId = f.id;
    this.form = { categoriaId: f.categoria_ticket_id, pregunta: f.pregunta,
                  respuesta: f.respuesta, orden: f.orden };
    this.showForm = true;
  }

  guardar(): void {
    if (!this.form.pregunta.trim() || !this.form.respuesta.trim()) {
      this.snackBar.open('Pregunta y respuesta son requeridas', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.soporte.crearFaq(this.form)
      : this.soporte.editarFaq(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'FAQ creada' : 'FAQ actualizada',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar la FAQ'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(f: FaqRow): void {
    this.soporte.activarFaq(f.id, !f.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
