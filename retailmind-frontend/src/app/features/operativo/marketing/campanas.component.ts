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
import { MarketingService } from '../../../core/services/marketing.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { CampanaRow } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-campanas',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './campanas.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CampanasComponent implements OnInit {

  campanas: CampanaRow[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  canales = ['email', 'redes', 'web', 'sms', 'mixto'];
  columnas = ['nombre', 'canal', 'presupuesto', 'vigencia', 'banners', 'estado', 'acciones'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void { this.cargar(); }

  private formVacio() {
    return { nombre: '', descripcion: '', canal: 'web',
             presupuesto: null as number | null, fechaInicio: '', fechaFin: '' };
  }

  cargar(): void {
    this.loading = true;
    this.marketing.campanas().subscribe({
      next: data => { this.campanas = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(c: CampanaRow): void {
    this.editandoId = c.id;
    this.form = {
      nombre: c.nombre, descripcion: c.descripcion || '', canal: c.canal,
      presupuesto: c.presupuesto,
      fechaInicio: (c.fecha_inicio || '').substring(0, 10),
      fechaFin: (c.fecha_fin || '').substring(0, 10)
    };
    this.showForm = true;
  }

  guardar(): void {
    if (!this.form.nombre) {
      this.snackBar.open('El nombre es requerido', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.marketing.crearCampana(this.form)
      : this.marketing.editarCampana(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'Campaña creada' : 'Campaña actualizada',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar la campaña'),
        'Cerrar', { duration: 4000 })
    });
  }

  cambiarEstado(c: CampanaRow, estado: string): void {
    this.marketing.estadoCampana(c.id, estado).subscribe({
      next: () => { this.snackBar.open(`Campaña ${estado}`, 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error al cambiar estado'),
        'Cerrar', { duration: 4000 })
    });
  }
}
