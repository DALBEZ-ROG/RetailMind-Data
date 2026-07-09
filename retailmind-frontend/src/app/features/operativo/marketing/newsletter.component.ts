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
import { MarketingService } from '../../../core/services/marketing.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { SuscriptorRow } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-newsletter',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './newsletter.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class NewsletterComponent implements OnInit {

  suscriptores: SuscriptorRow[] = [];
  loading = true;

  showForm = false;
  email = '';

  columnas = ['email', 'cliente', 'confirmado', 'suscripcion', 'baja', 'activo', 'acciones'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  get activos(): number { return this.suscriptores.filter(s => s.activo).length; }

  ngOnInit(): void { this.cargar(); }

  cargar(): void {
    this.loading = true;
    this.marketing.suscriptores().subscribe({
      next: data => { this.suscriptores = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  alta(): void {
    const email = this.email.trim();
    if (!email || !email.includes('@')) {
      this.snackBar.open('Ingresa un email válido', 'Cerrar', { duration: 3000 });
      return;
    }
    this.marketing.altaSuscriptor(email, null).subscribe({
      next: () => {
        this.snackBar.open('Suscriptor dado de alta', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.email = '';
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al dar de alta'), 'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(s: SuscriptorRow): void {
    this.marketing.activarSuscriptor(s.id, !s.activo).subscribe({
      next: () => {
        this.snackBar.open(s.activo ? 'Suscriptor dado de baja' : 'Suscriptor reactivado',
          'OK', { duration: 2000 });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }
}
