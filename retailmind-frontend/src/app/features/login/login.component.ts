import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatIconModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss'
})
export class LoginComponent {
  loginForm: FormGroup;
  loading      = false;
  hidePassword = true;
  shakeCard    = false;
  errorMessage = '';

  constructor(
    private fb:          FormBuilder,
    private authService: AuthService,
    private router:      Router
  ) {
    this.loginForm = this.fb.group({
      username: ['', [Validators.required, Validators.minLength(3)]],
      password: ['', [Validators.required, Validators.minLength(6)]]
    });
  }

  get f() { return this.loginForm.controls; }

  onSubmit(): void {
    if (this.loginForm.invalid) return;

    this.loading = true;
    this.errorMessage = '';
    const { username, password } = this.loginForm.value;

    this.authService.login(username, password).subscribe({
      next: () => {
        this.loading = false;
        const user = this.authService.getCurrentUser();
        if (user?.rol === 'ADMIN') {
          this.router.navigate(['/dashboard']);
        } else {
          this.router.navigate(['/shop']);
        }
      },
      error: () => {
        this.loading = false;
        this.errorMessage = 'Credenciales incorrectas. Intenta de nuevo.';
        this.triggerShake();
      }
    });
  }

  private triggerShake(): void {
    this.shakeCard = true;
    setTimeout(() => this.shakeCard = false, 600);
  }
}
