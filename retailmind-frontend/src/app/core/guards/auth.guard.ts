import { inject } from '@angular/core';
import { CanActivateFn, Router, RouterStateSnapshot } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Exige sesión y RECUERDA a dónde iba.
 *
 * Lo segundo dejó de ser un detalle cuando la tienda se abrió al público: un
 * enlace a `/shop/carrito` o a `/wishlist` lo puede abrir cualquiera, y sin el
 * `volver` esa persona inicia sesión y aterriza en `/inicio` — no en lo que
 * pidió—. El muro de sesión y la barra del visitante ya lo pasaban; este guard
 * era el único camino de entrada que lo perdía, y eso hacía que el sistema se
 * comportara de dos maneras distintas según por dónde se llegara.
 */
export const authGuard: CanActivateFn = (_ruta, estado: RouterStateSnapshot) => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  if (authService.isAuthenticated()) {
    return true;
  }
  router.navigate(['/login'], { queryParams: { volver: estado.url } });
  return false;
};
