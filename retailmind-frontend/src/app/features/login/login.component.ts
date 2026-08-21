import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/services/auth.service';

import { CampoTextoDirective } from '../../core/validacion';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    RouterLink,
    CampoTextoDirective
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

  /**
   * A dónde volver tras entrar. Lo pone quien manda aquí desde la tienda (la
   * barra del visitante), y solo se acepta una ruta INTERNA: con una URL
   * absoluta esto sería un redirector abierto y bastaría enlazar
   * `/login?volver=https://…` para llevarse a la gente a otro sitio.
   */
  private volverA: string | null = null;

  constructor(
    private fb:          FormBuilder,
    private authService: AuthService,
    private router:      Router,
    ruta:                ActivatedRoute
  ) {
    const volver = ruta.snapshot.queryParamMap.get('volver');
    if (volver && volver.startsWith('/') && !volver.startsWith('//')) {
      this.volverA = volver;
    }
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

    // trim: un espacio pegado junto al email produce 401 aunque la clave sea correcta
    this.authService.login((username ?? '').trim(), password).subscribe({
      next: sesion => {
        this.loading = false;
        // Quien venía de la tienda vuelve a la tienda, pero SOLO si entró como
        // cliente: un gerente que teclea sus credenciales en el muro no tiene
        // nada que hacer en el carrito —cada botón de ahí le daría 403—, así
        // que aterriza en el sistema interno como cualquier otro día.
        if (this.volverA && sesion.rol === 'CLIENTE') {
          this.router.navigateByUrl(this.volverA);
          return;
        }
        // Todos los roles aterrizan en el dashboard de inicio; el propio
        // dashboard filtra las áreas visibles según el rol.
        this.router.navigate(['/inicio']);
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
