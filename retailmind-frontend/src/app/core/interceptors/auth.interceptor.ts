import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError, retry, timer } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { MatSnackBar } from '@angular/material/snack-bar';

let isRefreshing = false;

export const authInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn) => {
  const authService = inject(AuthService);
  const snackBar    = inject(MatSnackBar);
  const token       = authService.getToken();

  // No agregar token a rutas de auth ni health
  if (req.url.includes('/api/auth/login') ||
      req.url.includes('/api/auth/refresh') ||
      req.url.includes('/api/health')) {
    return next(req);
  }

  let authReq = req;
  if (token) {
    authReq = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` }
    });
  }

  return next(authReq).pipe(
    // Reintentar GET fallidos hasta 2 veces con 1s de delay
    retry({
      count: req.method === 'GET' ? 2 : 0,
      delay: (error: HttpErrorResponse) => {
        if (error.status === 0 || error.status >= 500) {
          return timer(1000);
        }
        return throwError(() => error);
      }
    }),
    catchError((error: HttpErrorResponse) => {
      // 401 - intentar refresh
      if (error.status === 401 && !isRefreshing) {
        isRefreshing = true;
        return authService.refreshToken().pipe(
          switchMap(res => {
            isRefreshing = false;
            const newReq = req.clone({
              setHeaders: { Authorization: `Bearer ${res.token}` }
            });
            return next(newReq);
          }),
          catchError(refreshErr => {
            isRefreshing = false;
            authService.logout();
            return throwError(() => refreshErr);
          })
        );
      }

      // 500 - error del servidor
      if (error.status >= 500) {
        snackBar.open('Error del servidor. Intente nuevamente.', 'Cerrar', {
          duration: 4000, panelClass: 'snack-error'
        });
      }

      // 0 - sin conexion
      if (error.status === 0) {
        snackBar.open('Sin conexion con el servidor.', 'Cerrar', {
          duration: 5000, panelClass: 'snack-error'
        });
      }

      return throwError(() => error);
    })
  );
};
