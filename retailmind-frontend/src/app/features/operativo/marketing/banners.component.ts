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
import { BannerRow, CampanaRow } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-banners',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './banners.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class BannersComponent implements OnInit {

  banners: BannerRow[] = [];
  campanas: CampanaRow[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  posiciones = ['home_principal', 'home_secundario', 'categoria', 'checkout'];
  columnas = ['titulo', 'posicion', 'orden', 'campana', 'vigencia', 'activo', 'acciones'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void {
    this.cargar();
    this.marketing.campanas().subscribe(c => this.campanas = c);
  }

  private formVacio() {
    return { titulo: '', imagenUrl: '', urlDestino: '', posicion: 'home_principal',
             orden: 0, campanaId: null as number | null, fechaInicio: '', fechaFin: '' };
  }

  cargar(): void {
    this.loading = true;
    this.marketing.banners().subscribe({
      next: data => { this.banners = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(b: BannerRow): void {
    this.editandoId = b.id;
    this.form = {
      titulo: b.titulo, imagenUrl: b.imagen_url, urlDestino: b.url_destino || '',
      posicion: b.posicion, orden: b.orden, campanaId: b.campana_id,
      fechaInicio: (b.fecha_inicio || '').substring(0, 16),
      fechaFin: (b.fecha_fin || '').substring(0, 16)
    };
    this.showForm = true;
  }

  guardar(): void {
    if (!this.form.titulo || !this.form.imagenUrl) {
      this.snackBar.open('Título y URL de imagen son requeridos', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.marketing.crearBanner(this.form)
      : this.marketing.editarBanner(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'Banner creado' : 'Banner actualizado',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar el banner'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(b: BannerRow): void {
    this.marketing.activarBanner(b.id, !b.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
