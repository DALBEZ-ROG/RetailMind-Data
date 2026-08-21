import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/services/auth.service';
import { mensajeError } from '../../core/services/api-error.util';
import { CampoTextoDirective } from '../../core/validacion';

/** Lo que el visitante intentaba hacer, para decírselo en el título. */
export interface SesionRequeridaData { motivo: string; }

/**
 * Qué pasó en la tarjeta:
 *  - `cliente`  entró un cliente → el llamador reintenta lo que iba a hacer;
 *  - `interno`  entró personal del negocio → se le llevó al sistema interno;
 *  - `registro` se fue a crear una cuenta;
 *  - `cancelado` cerró sin más.
 */
export type SesionRequeridaResultado = 'cliente' | 'interno' | 'registro' | 'cancelado';

/**
 * El muro de sesión de la tienda.
 *
 * Aparece cuando un visitante intenta hacer algo que exige cuenta —agregar al
 * carrito, guardar en la lista de deseos, pagar—. Deja MIRAR sin cuenta y pide
 * la cuenta en el momento en que hace falta, que es cuando el usuario ya quiere
 * algo; pedirla en la puerta espanta a quien solo venía a ver el catálogo.
 *
 * **Aquí solo entran CLIENTES, y no por una comprobación de pantalla.** El
 * `POST /api/auth/login` es el mismo para todo el mundo y no puede ser otro: el
 * personal del negocio también tiene que poder entrar. Lo que hace esta tarjeta
 * es MIRAR EL ROL de la sesión recién abierta y, si no es CLIENTE, cerrarse y
 * llevar a esa persona al sistema interno en vez de devolverla al carrito. No
 * es una barrera de seguridad —esa la ponen `SecurityConfig` y la RLS, que a un
 * GERENTE le niegan `/api/carrito` con o sin esta pantalla—: es no dejar a un
 * vendedor plantado en una tienda que su rol no puede usar.
 */
@Component({
  selector: 'app-sesion-requerida',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatIconModule,
    MatProgressSpinnerModule, CampoTextoDirective],
  template: `
    <div class="muro">
      <button class="muro-cerrar" (click)="cerrar()" aria-label="Cerrar">
        <mat-icon>close</mat-icon>
      </button>

      <div class="muro-cabecera">
        <img src="assets/ic_retailmind.png" alt="" width="44" height="48">
        <h2>Inicia sesión para continuar</h2>
        <p>Necesitas una cuenta {{ data.motivo }}.</p>
      </div>

      <form class="muro-form" (ngSubmit)="entrar()">
        <label class="muro-campo">
          <span>Correo</span>
          <div class="campo-caja">
            <mat-icon>mail_outline</mat-icon>
            <input appTexto="email" type="text" name="correo" [(ngModel)]="correo"
                   placeholder="tucorreo@ejemplo.com" autocomplete="username"
                   [disabled]="cargando">
          </div>
        </label>

        <label class="muro-campo">
          <span>Contraseña</span>
          <div class="campo-caja">
            <mat-icon>lock_outline</mat-icon>
            <input [type]="verClave ? 'text' : 'password'" name="clave" [(ngModel)]="clave"
                   placeholder="Tu contraseña" autocomplete="current-password"
                   [disabled]="cargando">
            <button type="button" class="ver-clave" (click)="verClave = !verClave"
                    [attr.aria-label]="verClave ? 'Ocultar contraseña' : 'Mostrar contraseña'">
              <mat-icon>{{ verClave ? 'visibility' : 'visibility_off' }}</mat-icon>
            </button>
          </div>
        </label>

        <p class="muro-error" *ngIf="error">
          <mat-icon>error_outline</mat-icon><span>{{ error }}</span>
        </p>

        <button type="submit" class="muro-entrar" [disabled]="cargando || !puedeEntrar">
          <mat-spinner *ngIf="cargando" diameter="20"></mat-spinner>
          <span *ngIf="!cargando">Iniciar sesión</span>
        </button>
      </form>

      <div class="muro-separador"><span>¿Eres nuevo en RetailMind?</span></div>

      <button class="muro-crear" (click)="irARegistro()" [disabled]="cargando">
        <mat-icon>person_add_alt</mat-icon>
        Crea tu cuenta
      </button>

      <p class="muro-pie">
        Puedes seguir mirando el catálogo sin cuenta. Solo hace falta para
        comprar, guardar productos y seguir tus pedidos.
      </p>
    </div>
  `,
  styles: [`
    :host { display: block; }

    .muro {
      position: relative;
      width: min(420px, 92vw);
      padding: 26px 30px 22px;
      background: var(--surface, #fff);
      border-radius: 18px;
      color: var(--text-primary, #14243d);
    }

    .muro-cerrar {
      position: absolute; top: 12px; right: 12px;
      display: grid; place-items: center;
      width: 32px; height: 32px;
      border: 0; border-radius: 50%;
      background: transparent; cursor: pointer;
      color: var(--text-secondary, #6b7a90);
      &:hover { background: rgba(127, 143, 166, 0.14); }
      mat-icon { font-size: 20px; width: 20px; height: 20px; }
    }

    .muro-cabecera {
      text-align: center;
      margin-bottom: 18px;
      h2 { margin: 10px 0 4px; font-size: 20px; font-weight: 700; letter-spacing: -0.2px; }
      p  { margin: 0; font-size: 13.5px; color: var(--text-secondary, #6b7a90); }
    }

    .muro-form { display: grid; gap: 13px; }

    .muro-campo {
      display: grid; gap: 6px;
      > span { font-size: 12.5px; font-weight: 600; color: var(--text-secondary, #6b7a90); }
    }

    .campo-caja {
      display: flex; align-items: center; gap: 9px;
      padding: 0 12px;
      background: var(--campo-fondo, rgba(127, 143, 166, 0.09));
      border: 1px solid transparent;
      border-radius: 11px;
      transition: border-color .15s, background .15s;

      &:focus-within {
        background: var(--campo-fondo-foco, #fff);
        border-color: var(--dubai-azul, #3d5afe);
      }
      mat-icon {
        flex: 0 0 auto;
        font-size: 19px; width: 19px; height: 19px;
        color: var(--text-secondary, #6b7a90);
      }
      input {
        flex: 1 1 auto; min-width: 0;
        padding: 11px 0;
        border: 0; background: transparent; outline: none;
        font: inherit; font-size: 14px;
        color: inherit;
      }
      .ver-clave {
        flex: 0 0 auto;
        display: grid; place-items: center;
        border: 0; background: transparent; cursor: pointer; padding: 2px;
        color: var(--text-secondary, #6b7a90);
      }
    }

    .muro-error {
      display: flex; align-items: center; gap: 7px;
      margin: 0; padding: 9px 11px;
      border-radius: 10px;
      font-size: 13px;
      color: #b3261e;
      background: rgba(240, 97, 109, 0.12);
      mat-icon { font-size: 18px; width: 18px; height: 18px; }
    }

    .muro-entrar {
      display: flex; align-items: center; justify-content: center; gap: 8px;
      margin-top: 3px; padding: 12px;
      border: 0; border-radius: 11px;
      font: inherit; font-size: 14px; font-weight: 700; letter-spacing: .3px;
      color: #fff;
      background: var(--dubai-azul, #3d5afe);
      cursor: pointer;
      transition: filter .15s;
      &:hover:not(:disabled) { filter: brightness(1.08); }
      &:disabled { opacity: .55; cursor: not-allowed; }
    }

    .muro-separador {
      display: flex; align-items: center; gap: 12px;
      margin: 20px 0 14px;
      color: var(--text-secondary, #6b7a90);
      font-size: 12.5px;
      &::before, &::after {
        content: ''; flex: 1 1 auto; height: 1px;
        background: rgba(127, 143, 166, 0.28);
      }
    }

    .muro-crear {
      display: flex; align-items: center; justify-content: center; gap: 8px;
      width: 100%; padding: 11px;
      border: 1.5px solid var(--dubai-azul, #3d5afe);
      border-radius: 11px;
      background: transparent;
      font: inherit; font-size: 14px; font-weight: 700;
      color: var(--dubai-azul, #3d5afe);
      cursor: pointer;
      transition: background .15s;
      &:hover:not(:disabled) { background: rgba(61, 90, 254, 0.09); }
      mat-icon { font-size: 19px; width: 19px; height: 19px; }
    }

    .muro-pie {
      margin: 16px 0 0;
      font-size: 12px; line-height: 1.5; text-align: center;
      color: var(--text-secondary, #6b7a90);
    }
  `]
})
export class SesionRequeridaDialog {

  correo = '';
  clave = '';
  verClave = false;
  cargando = false;
  error: string | null = null;

  constructor(private readonly dialogRef: MatDialogRef<SesionRequeridaDialog, SesionRequeridaResultado>,
              private readonly auth: AuthService,
              private readonly router: Router,
              @Inject(MAT_DIALOG_DATA) public data: SesionRequeridaData) {}

  get puedeEntrar(): boolean {
    return this.correo.trim().length > 3 && this.clave.length > 0;
  }

  entrar(): void {
    if (!this.puedeEntrar || this.cargando) { return; }
    this.cargando = true;
    this.error = null;
    this.auth.login(this.correo.trim(), this.clave).subscribe({
      next: sesion => {
        this.cargando = false;
        if (sesion.rol === 'CLIENTE') {
          this.dialogRef.close('cliente');
          return;
        }
        // Personal del negocio. La sesión es válida y se respeta —no se cierra—,
        // pero su sitio es el back-office: devolverlo al carrito sería dejarlo
        // frente a una pantalla en la que cada botón le va a dar 403.
        this.dialogRef.close('interno');
        this.router.navigate(['/inicio']);
      },
      error: e => {
        this.cargando = false;
        this.error = mensajeError(e, 'No pudimos iniciar tu sesión. Revisa el correo y la contraseña.');
      }
    });
  }

  irARegistro(): void {
    this.dialogRef.close('registro');
    this.router.navigate(['/registro'], {
      // Se lleva a dónde volver: quien iba a comprar debe acabar donde estaba y
      // no en una pantalla de inicio que no había pedido.
      queryParams: { volver: this.router.url }
    });
  }

  cerrar(): void { this.dialogRef.close('cancelado'); }
}
