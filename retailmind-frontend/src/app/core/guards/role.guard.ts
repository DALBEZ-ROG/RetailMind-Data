import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Factoría de guards por rol: roleGuard(['ADMIN','COMPRAS']) devuelve un
 * CanActivateFn que solo deja pasar a esos roles. Los rechazados van a /shop
 * (ruta segura para cualquier usuario autenticado).
 */
export const roleGuard = (rolesPermitidos: string[]): CanActivateFn => () => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  const user = authService.getCurrentUser();
  if (user && rolesPermitidos.includes(user.rol)) {
    return true;
  }
  router.navigate(['/shop']);
  return false;
};

/** Compatibilidad: guard existente usado por las rutas admin/analytics. */
export const adminGuard: CanActivateFn = roleGuard(['ADMIN']);
