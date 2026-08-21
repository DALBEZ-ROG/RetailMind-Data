import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LoginRequest, LoginResponse, AuthUser } from '../models/auth.model';

const TOKEN_KEY   = 'rm_token';
const REFRESH_KEY = 'rm_refresh_token';
const USER_KEY    = 'rm_user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly base = `${environment.apiUrl}/api/auth`;

  constructor(private http: HttpClient, private router: Router) {}

  login(username: string, password: string): Observable<LoginResponse> {
    const body: LoginRequest = { username, password };
    return this.http.post<LoginResponse>(`${this.base}/login`, body).pipe(
      tap(res => this.guardarSesion(res))
    );
  }

  /**
   * Guarda una sesion ya emitida por el backend.
   *
   * Existe aparte del `login` porque el alta publica de cliente devuelve la
   * sesion en la MISMA respuesta del registro —para no obligar a entrar justo
   * despues de crear la cuenta— y necesita escribirla exactamente igual. Si
   * cada camino guardara sus claves por su cuenta, bastaria con que uno se
   * olvidara del refresco para que esa sesion caducara sin renovarse.
   */
  guardarSesion(res: LoginResponse): void {
    localStorage.setItem(TOKEN_KEY, res.token);
    localStorage.setItem(REFRESH_KEY, res.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify({
      username: res.username,
      nombre:   res.nombre,
      rol:      res.rol
    }));
  }

  logout(): void {
    this.limpiarSesion();
    this.router.navigate(['/login']);
  }

  /**
   * Borra la sesión SIN navegar.
   *
   * Se separa de `logout` porque hay un caso en el que cerrar sesión no
   * significa irse: alguien del personal que abre `/registro` y decide crear
   * una cuenta de cliente tiene que soltar la suya y QUEDARSE donde está. Con
   * `logout` acabaría en el login, que es justo la pantalla de la que venía.
   */
  limpiarSesion(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
  }

  refreshToken(): Observable<LoginResponse> {
    const refreshToken = localStorage.getItem(REFRESH_KEY) || '';
    return this.http.post<LoginResponse>(`${this.base}/refresh`, { refreshToken }).pipe(
      tap(res => {
        localStorage.setItem(TOKEN_KEY, res.token);
      })
    );
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  isAuthenticated(): boolean {
    return !!this.getToken();
  }

  getCurrentUser(): AuthUser | null {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  }

  hasRole(rol: string): boolean {
    const user = this.getCurrentUser();
    return user?.rol === rol;
  }
}
