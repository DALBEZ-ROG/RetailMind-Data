import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-perfil',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatChipsModule,
    MatExpansionModule, MatProgressSpinnerModule,
    MatSnackBarModule, MatTooltipModule
  ],
  templateUrl: './perfil.component.html',
  styleUrl: './perfil.component.scss'
})
export class PerfilComponent implements OnInit {

  perfil: any = null;
  loading = true;
  queryMs = 0;

  emailForm!: FormGroup;
  passwordForm!: FormGroup;
  savingEmail = false;
  savingPassword = false;

  private readonly base = `${environment.apiUrl}/api/perfil`;

  constructor(
    private http: HttpClient,
    private authService: AuthService,
    private fb: FormBuilder,
    private snackBar: MatSnackBar,
    private router: Router
  ) {}

  ngOnInit(): void {
    const user = this.authService.getCurrentUser();
    if (!user) { this.router.navigate(['/login']); return; }

    this.emailForm = this.fb.group({
      email: ['', [Validators.required, Validators.email]]
    });

    this.passwordForm = this.fb.group({
      passwordActual: ['', Validators.required],
      passwordNuevo: ['', [Validators.required, Validators.minLength(6)]],
      confirmarPassword: ['', Validators.required]
    }, { validators: this.passwordsMatch });

    this.cargarPerfil(user.username);
  }

  private passwordsMatch(group: FormGroup) {
    const nuevo = group.get('passwordNuevo')?.value;
    const confirmar = group.get('confirmarPassword')?.value;
    const actual = group.get('passwordActual')?.value;
    if (nuevo && confirmar && nuevo !== confirmar) {
      return { noCoinciden: true };
    }
    if (nuevo && actual && nuevo === actual) {
      return { mismaPassword: true };
    }
    return null;
  }

  private cargarPerfil(username: string): void {
    const t0 = Date.now();
    this.http.get<any>(`${this.base}/${username}`).subscribe({
      next: (data) => {
        this.perfil = data;
        this.queryMs = Date.now() - t0;
        this.emailForm.patchValue({ email: data.email });
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Error al cargar el perfil', 'Cerrar', { duration: 3000 });
      }
    });
  }

  guardarEmail(): void {
    if (this.emailForm.invalid) return;
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.savingEmail = true;
    this.http.put(`${this.base}/${user.username}/email`, this.emailForm.value).subscribe({
      next: () => {
        this.perfil.email = this.emailForm.value.email;
        this.savingEmail = false;
        this.snackBar.open('Email actualizado ✓', 'OK', { duration: 2500, panelClass: ['snack-success'] });
      },
      error: (e: any) => {
        this.savingEmail = false;
        this.snackBar.open(e.error?.error || 'Error al actualizar email', 'Cerrar', { duration: 3000 });
      }
    });
  }

  cambiarPassword(): void {
    if (this.passwordForm.invalid) return;
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.savingPassword = true;
    const { passwordActual, passwordNuevo } = this.passwordForm.value;
    this.http.put(`${this.base}/${user.username}/password`, { passwordActual, passwordNuevo }).subscribe({
      next: () => {
        this.savingPassword = false;
        this.passwordForm.reset();
        this.snackBar.open('Contraseña actualizada ✓', 'OK', { duration: 2500, panelClass: ['snack-success'] });
      },
      error: (e: any) => {
        this.savingPassword = false;
        this.snackBar.open(e.error?.error || 'Error al cambiar contraseña', 'Cerrar', { duration: 3000 });
      }
    });
  }

  getAvatarColor(): string {
    return '#3f51b5';
  }

  getInitial(): string {
    return this.perfil?.username?.charAt(0)?.toUpperCase() || '?';
  }

  isAdmin(): boolean {
    return this.perfil?.rol === 'ADMIN';
  }
}
